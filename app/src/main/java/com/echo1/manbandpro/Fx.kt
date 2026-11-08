package com.echo1.manbandpro

import org.json.JSONObject
import kotlin.math.*

class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    fun process(x: Float): Float {
        val xd = x.toDouble(); val y = b0 * xd + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = xd; y2 = y1; y1 = y; return y.toFloat()
    }
    // kind: 0 = low shelf, 1 = peak, 2 = high shelf (RBJ cookbook)
    fun set(kind: Int, f: Double, db: Double, q: Double) {
        val A = 10.0.pow(db / 40); val w = 2 * PI * f / 44100.0; val cw = cos(w); val al = sin(w) / (2 * q); val sA = 2 * sqrt(A) * al
        val nb0: Double; val nb1: Double; val nb2: Double; val a0: Double; val na1: Double; val na2: Double
        when (kind) {
            0 -> { nb0 = A * ((A + 1) - (A - 1) * cw + sA); nb1 = 2 * A * ((A - 1) - (A + 1) * cw); nb2 = A * ((A + 1) - (A - 1) * cw - sA)
                a0 = (A + 1) + (A - 1) * cw + sA; na1 = -2 * ((A - 1) + (A + 1) * cw); na2 = (A + 1) + (A - 1) * cw - sA }
            1 -> { nb0 = 1 + al * A; nb1 = -2 * cw; nb2 = 1 - al * A; a0 = 1 + al / A; na1 = -2 * cw; na2 = 1 - al / A }
            else -> { nb0 = A * ((A + 1) + (A - 1) * cw + sA); nb1 = -2 * A * ((A - 1) + (A + 1) * cw); nb2 = A * ((A + 1) + (A - 1) * cw - sA)
                a0 = (A + 1) - (A - 1) * cw + sA; na1 = 2 * ((A - 1) - (A + 1) * cw); na2 = (A + 1) - (A - 1) * cw - sA }
        }
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
    }
}

/** Per-channel insert chain: EQ (3 band) -> compressor -> delay -> reverb. Mono, runs on the audio thread. */
class Fx {
    @Volatile var eqLow = 0f; @Volatile var eqMid = 0f; @Volatile var eqHigh = 0f
    @Volatile var compOn = false; @Volatile var compThresh = -18f; @Volatile var compRatio = 4f
    @Volatile var delayMix = 0f; @Volatile var delayMs = 375f; @Volatile var delayFb = 0.35f
    @Volatile var reverbMix = 0f; @Volatile var reverbSize = 0.6f

    private val lo = Biquad(); private val mid = Biquad(); private val hi = Biquad()
    private var cL = 99f; private var cM = 99f; private var cH = 99f
    private var on = false; private var eqOn = false
    private var pComp = false; private var pTh = -18f; private var pRa = 4f; private var mk = 1f; private var env = 0f
    private val dbuf = FloatArray(44100); private var dp = 0; private var pDN = 1; private var pDMix = 0f; private var pDFb = 0f
    private val combs = arrayOf(FloatArray(1116), FloatArray(1188), FloatArray(1277), FloatArray(1356))
    private val aps = arrayOf(FloatArray(556), FloatArray(441))
    private val ci = IntArray(4); private val ai = IntArray(2); private val cs = FloatArray(4)
    private var pRMix = 0f; private var pRFb = 0.8f

    fun isOn() = eqLow != 0f || eqMid != 0f || eqHigh != 0f || compOn || delayMix > 0f || reverbMix > 0f

    /** Call once per audio block (not per sample). */
    fun update() {
        if (eqLow != cL) { cL = eqLow; lo.set(0, 200.0, cL.toDouble(), 0.7) }
        if (eqMid != cM) { cM = eqMid; mid.set(1, 1000.0, cM.toDouble(), 0.8) }
        if (eqHigh != cH) { cH = eqHigh; hi.set(2, 4000.0, cH.toDouble(), 0.7) }
        eqOn = cL != 0f || cM != 0f || cH != 0f
        pComp = compOn; pTh = compThresh; pRa = compRatio
        mk = 10f.pow(abs(pTh) * (1 - 1 / pRa) * 0.5f / 20f)
        pDMix = delayMix; pDN = (delayMs * 44.1f).toInt().coerceIn(1, dbuf.size - 1); pDFb = delayFb
        pRMix = reverbMix; pRFb = 0.7f + reverbSize * 0.23f
        on = eqOn || pComp || pDMix > 0f || pRMix > 0f
    }

    fun process(x0: Float): Float {
        if (!on) return x0
        var x = x0
        if (eqOn) x = hi.process(mid.process(lo.process(x)))
        if (pComp) {
            val l = abs(x) / 32768f
            env += (l - env) * (if (l > env) 0.0045f else 0.0002f)
            val over = 20f * log10(env + 1e-6f) - pTh
            if (over > 0f) x *= 10f.pow(-over * (1 - 1 / pRa) / 20f)
            x *= mk
        }
        if (pDMix > 0f) {
            val d = dbuf[(dp - pDN + dbuf.size) % dbuf.size]
            dbuf[dp] = x + d * pDFb; dp = (dp + 1) % dbuf.size; x += d * pDMix
        }
        if (pRMix > 0f) {
            val inp = x * 0.015f; var sum = 0f
            for (i in 0 until 4) { val b = combs[i]; val o = b[ci[i]]; cs[i] = o * 0.8f + cs[i] * 0.2f
                b[ci[i]] = inp + cs[i] * pRFb; ci[i] = (ci[i] + 1) % b.size; sum += o }
            for (i in 0 until 2) { val b = aps[i]; val bo = b[ai[i]]; val o = -sum + bo
                b[ai[i]] = sum + bo * 0.5f; ai[i] = (ai[i] + 1) % b.size; sum = o }
            x += sum * 3f * pRMix
        }
        return x
    }

    fun reset() { eqLow = 0f; eqMid = 0f; eqHigh = 0f; compOn = false; delayMix = 0f; reverbMix = 0f }

    fun toJson() = JSONObject().put("lo", eqLow.toDouble()).put("mid", eqMid.toDouble()).put("hi", eqHigh.toDouble())
        .put("cOn", compOn).put("cTh", compThresh.toDouble()).put("cRa", compRatio.toDouble())
        .put("dMix", delayMix.toDouble()).put("dMs", delayMs.toDouble()).put("dFb", delayFb.toDouble())
        .put("rMix", reverbMix.toDouble()).put("rSize", reverbSize.toDouble())

    fun load(o: JSONObject) {
        eqLow = o.optDouble("lo", 0.0).toFloat(); eqMid = o.optDouble("mid", 0.0).toFloat(); eqHigh = o.optDouble("hi", 0.0).toFloat()
        compOn = o.optBoolean("cOn"); compThresh = o.optDouble("cTh", -18.0).toFloat(); compRatio = o.optDouble("cRa", 4.0).toFloat()
        delayMix = o.optDouble("dMix", 0.0).toFloat(); delayMs = o.optDouble("dMs", 375.0).toFloat(); delayFb = o.optDouble("dFb", 0.35).toFloat()
        reverbMix = o.optDouble("rMix", 0.0).toFloat(); reverbSize = o.optDouble("rSize", 0.6).toFloat()
    }
}
