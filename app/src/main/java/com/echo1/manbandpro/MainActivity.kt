package com.echo1.manbandpro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.echo1.manbandpro.storage.*
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    val names = listOf("Kick", "Snare", "Hat", "Perc", "Bass", "Chord", "Melody")
    val colors = intArrayOf(0xFFFF3B4E.toInt(), 0xFF1E90FF.toInt(), 0xFF22DD33.toInt(), 0xFFFF8A1E.toInt(),
        0xFF8A3CFF.toInt(), 0xFF18D8F0.toInt(), 0xFFFFD21E.toInt())
    val pattern = Array(7) { BooleanArray(16) }
    val samples = arrayOfNulls<ShortArray>(7)
    val paths = arrayOfNulls<String>(7)
    class Region(var start: Int, var len: Int, val path: String, var off: Int)
    open class Ch(var name: String, var vol: Float = 0.8f, var pan: Float = 0f, var mute: Boolean = false, var solo: Boolean = false) { val fx = Fx() }
    class Track(name: String, val color: Int, vol: Float = 0.8f, mute: Boolean = false, solo: Boolean = false) : Ch(name, vol, 0f, mute, solo) {
        val regions = java.util.concurrent.CopyOnWriteArrayList<Region>() }
    class RV(val d: ShortArray, var pos: Int, var left: Int, val t: Track) { var ti = -1 }
    val seqCh = Ch("Sequencer"); val keysCh = Ch("Piano Roll")
    @Volatile var masterVol = 0.8f
    @Volatile var meterL = 0f
    @Volatile var meterR = 0f
    lateinit var mixer: Mixer
    lateinit var mixBox: HorizontalScrollView
    fun show(v: View) { for (x in listOf<View>(grid, rollBox, tlBox, mixBox, projBox)) x.visibility = if (x === v) View.VISIBLE else View.GONE
        for ((view, b) in tabMap) b.background = bg(if (view === v) 0xFF1E6BFF.toInt() else 0xFF171C27.toInt(), dp(14).toFloat())
        if (v === projBox) refreshProjects() }
    fun active(mute: Boolean, solo: Boolean, any: Boolean) = !mute && (!any || solo)
    fun lGain(vol: Float, pan: Float) = (vol * Math.cos((pan + 1) * Math.PI / 4) * 1.414).toFloat()
    fun rGain(vol: Float, pan: Float) = (vol * Math.sin((pan + 1) * Math.PI / 4) * 1.414).toFloat()
    val tracks = java.util.concurrent.CopyOnWriteArrayList<Track>()
    val cache = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()
    var selected: Region? = null
    @Volatile var startStep = 0
    var pendingTrack = 0
    val RH = 48f
    lateinit var tl: TL
    lateinit var headers: Headers
    lateinit var tlBox: LinearLayout
    val tlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importRegion(pendingTrack, it) } }
    class Note(val step: Int, val len: Int, val pitch: Int)
    class V(var p: Double, val rate: Double, var left: Int)
    val notes = java.util.concurrent.CopyOnWriteArrayList<Note>()
    @Volatile var loopBars = 8
    @Volatile var noteLen = 2
    var basePitch = 48
    lateinit var roll: Roll
    lateinit var keys: Keys
    lateinit var rollBox: LinearLayout
    @Volatile var bpm = 120.0
    @Volatile var playing = false
    lateinit var store: ProjectStore
    lateinit var proj: EmbProject
    lateinit var grid: Grid
    lateinit var status: TextView
    lateinit var bpmText: TextView
    var pendingRow = 0
    val io = Executors.newSingleThreadExecutor()
    val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importSample(pendingRow, it) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = ProjectStore(this)
        val last = getSharedPreferences("echo", 0).getString("last", null)
        proj = (last?.takeIf { it in store.listProjects() } ?: store.listProjects().firstOrNull())?.let { store.open(it).first } ?: store.create("My Beat")
        load()
        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply { text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            background = bg(0xFF1B2130.toInt(), dp(12).toFloat()); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = dp(40); setPadding(dp(14), 0, dp(14), 0); setOnClickListener { f(this) } }
        grid = Grid(this); roll = Roll(this); keys = Keys(this); tl = TL(this); headers = Headers(this); mixer = Mixer(this); leds = Leds(this)
        mixBox = HorizontalScrollView(this).apply { visibility = View.GONE; addView(mixer, FrameLayout.LayoutParams(-2, -1)) }
        projList = LinearLayout(this).gap().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        projBox = ScrollView(this).apply { visibility = View.GONE; addView(projList) }
        tlBox = LinearLayout(this).apply { visibility = View.GONE
            addView(headers, LinearLayout.LayoutParams(400, -1))
            addView(HorizontalScrollView(this@MainActivity).apply { addView(tl, FrameLayout.LayoutParams(-2, -1)) }, LinearLayout.LayoutParams(0, -1, 1f)) }
        val scroll = HorizontalScrollView(this).apply { addView(roll, FrameLayout.LayoutParams(-2, -1)) }
        rollBox = LinearLayout(this).apply { visibility = View.GONE
            addView(keys, LinearLayout.LayoutParams(120, -1)); addView(scroll, LinearLayout.LayoutParams(0, -1, 1f)) }
        status = TextView(this).apply { setTextColor(0xFF22DD33.toInt()); textSize = 13f; setPadding(dp(10), 0, dp(10), 0) }
        bpmText = TextView(this).apply { text = "%.1f BPM".format(bpm); setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(6), 0, dp(6), 0) }
        val playAction: (Button) -> Unit = { if (playing) { stopEngine(); recording = false } else startEngine() }
        playBtn = btn("\u25B6", playAction).also { it.setTextColor(0xFF22DD33.toInt()) }
        val stopBtn = btn("\u25A0") { stopEngine(); recording = false }
        recBtn = btn("\u25CF") { if (recording) { recording = false; stopEngine() } else startRec() }.also { it.setTextColor(0xFFFF3B4E.toInt()) }
        fun bpmBtn(t: String, d: Int) = btn(t) { bpm = (bpm + d).coerceIn(40.0, 240.0); bpmText.text = "%.1f BPM".format(bpm); save() }
        val loopBtn = btn("1 - $loopBars") { loopBars = loopBars % 18 + 1; it.text = "1 - $loopBars"; roll.requestLayout(); tl.requestLayout(); tl.invalidate(); save() }
        loopBtnRef = loopBtn
        val loopSw = Switch(this).apply { isChecked = loopOn; setOnCheckedChangeListener { _, c -> loopOn = c } }
        val title = SpannableString("Echo 1 ManBand Pro").apply { setSpan(ForegroundColorSpan(0xFFFFA52A.toInt()), 15, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val topBar = LinearLayout(this).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)); setBackgroundColor(0xFF0F131B.toInt())
            addView(ImageView(this@MainActivity).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(TextView(this@MainActivity).apply { text = title; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC); setPadding(dp(10), 0, dp(10), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(playBtn); addView(stopBtn); addView(recBtn)
            addView(LinearLayout(this@MainActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF141923.toInt(), dp(14).toFloat())
                addView(bpmBtn("\u2212", -1)); addView(bpmText); addView(bpmBtn("+", 1))
                addView(TextView(this@MainActivity).apply { text = "4/4"; setTextColor(Color.LTGRAY); setPadding(dp(8), 0, dp(10), 0) }) })
            addView(LinearLayout(this@MainActivity).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF141923.toInt(), dp(14).toFloat()); setPadding(dp(10), 0, dp(4), 0)
                addView(TextView(this@MainActivity).apply { text = "Loop:"; setTextColor(Color.WHITE) }); addView(loopSw); addView(loopBtn) })
            addView(btn("\u2699") { AlertDialog.Builder(this@MainActivity).setTitle("Settings")
                .setItems(arrayOf("Metronome: " + (if (metroOn) "On" else "Off"), "Count-in before recording: $countBars bar(s)")) { _, w -> if (w == 0) metroOn = !metroOn else countBars = (countBars + 1) % 3; save() }.show() }) }
        val tabRow = LinearLayout(this).gap().apply { setPadding(dp(8), dp(4), dp(8), dp(4))
            fun tab(label: String, v: View?) { val b = btn(label) { if (v != null) show(v) else Toast.makeText(this@MainActivity, "Projects screen is the next thing to build", Toast.LENGTH_SHORT).show() }
                if (v != null) tabMap[v] = b; addView(b, LinearLayout.LayoutParams(0, dp(46), 1f)) }
            tab("\u25A6  Sequencer", grid); tab("\u266B  Piano Roll", rollBox); tab("\u2630  Timeline", tlBox); tab("\u2261  Mixer", mixBox); tab("\u25A4  Projects", projBox) }
        val toolBar = HorizontalScrollView(this).apply { setBackgroundColor(0xFF0F131B.toInt())
            addView(LinearLayout(this@MainActivity).gap().apply { setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(btn("Undo") { undo() }); addView(btn("Redo") { redo() }); addView(btn("+ Track") { addTrack() }); addView(btn("Split") { splitSelected() }); addView(btn("Del") { deleteSelected() })
                addView(btn("Len: $noteLen") { noteLen = when (noteLen) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }; it.text = "Len: $noteLen" })
                addView(btn("Oct -") { basePitch = maxOf(24, basePitch - 12); keys.invalidate(); roll.invalidate() })
                addView(btn("Oct +") { basePitch = minOf(72, basePitch + 12); keys.invalidate(); roll.invalidate() }) }) }
        val swingText = TextView(this).apply { text = "Swing ${(swing * 100).toInt()}%"; setTextColor(Color.WHITE); setPadding(dp(8), 0, dp(8), 0) }
        swingRef = swingText
        fun swingBtn(t: String, d: Int) = btn(t) { swing = (Math.round(swing * 100) + d).coerceIn(0, 50) / 100f; swingText.text = "Swing ${(swing * 100).toInt()}%"; save() }
        val bottom = LinearLayout(this).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(0xFF121723.toInt())
            addView(btn("\u25A4 Projects") { show(projBox) })
            addView(TextView(this@MainActivity).apply { text = "${proj.name}.emb"; setTextColor(Color.WHITE); background = bg(0xFF1B2130.toInt(), dp(12).toFloat()); setPadding(dp(14), dp(10), dp(14), dp(10)) }.also { chip = it })
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("\u23EE") { startStep = 0; tl.invalidate() }); addView(btn("\u25B6", playAction).also { it.setTextColor(0xFF22DD33.toInt()) }); addView(btn("\u25A0") { stopEngine(); recording = false })
            addView(btn("\u23ED") { startStep = minOf((startStep / 16 + 1) * 16, loopBars * 16 - 4); tl.invalidate() })
            addView(LinearLayout(this@MainActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF141923.toInt(), dp(14).toFloat())
                addView(swingText); addView(swingBtn("\u2212", -5)); addView(swingBtn("+", 5)) })
            addView(leds, LinearLayout.LayoutParams(dp(120), dp(36)))
            addView(btn("\u2261") { show(mixBox) }) }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0E14.toInt())
            addView(topBar); addView(tabRow)
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f)); addView(rollBox, LinearLayout.LayoutParams(-1, 0, 1f)); addView(tlBox, LinearLayout.LayoutParams(-1, 0, 1f)); addView(mixBox, LinearLayout.LayoutParams(-1, 0, 1f)); addView(projBox, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(toolBar); addView(bottom) })
        show(grid)
        val miss = store.missingSamples(proj)
        if (miss.isNotEmpty()) Toast.makeText(this, "Missing samples: " + miss.joinToString { it.path } + " (tap the row name to replace)", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { stopEngine(); super.onDestroy() }

    fun load() {
        bpm = proj.bpm; swing = proj.swing; loopBars = proj.loopEndBar.coerceIn(1, 18); loadTracks()
        proj.sections.optString("rowMS").split(",").let { q -> if (q.size == 2) for (r in 0 until 7) { rowMute[r] = q[0].getOrNull(r) == '1'; rowSolo[r] = q[1].getOrNull(r) == '1' } }
        proj.sections.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) { val n = a.getJSONArray(i); notes.add(Note(n.getInt(0), n.getInt(1), n.getInt(2))) } }
        proj.sections.optString("pattern").split(",").forEachIndexed { r, s -> if (r < 7) s.forEachIndexed { i, c -> if (i < 16) pattern[r][i] = c == '1' } }
        proj.sections.optJSONArray("rowSamples")?.let { a -> for (r in 0 until minOf(7, a.length())) {
            val p = a.getString(r); if (p.isNotEmpty()) { paths[r] = p; val f = store.resolve(proj, p); if (f.exists()) samples[r] = decodeWav(f) } } }
    }

    fun syncSections() {
        proj.bpm = bpm; proj.loopEndBar = loopBars; proj.swing = swing; saveTracks()
        proj.sections.put("rowMS", rowMute.joinToString("") { if (it) "1" else "0" } + "," + rowSolo.joinToString("") { if (it) "1" else "0" })
        proj.sections.put("notes", JSONArray(notes.map { JSONArray(listOf(it.step, it.len, it.pitch)) }))
        proj.sections.put("pattern", pattern.joinToString(",") { row -> row.joinToString("") { if (it) "1" else "0" } })
        proj.sections.put("rowSamples", JSONArray(paths.map { it ?: "" }))
    }

    fun save() {
        syncSections()
        io.execute { try { store.autosave(proj) } catch (e: Exception) { }
            val t = java.text.SimpleDateFormat("h:mm a").format(java.util.Date())
            runOnUiThread { status.text = "  \u2713 Auto Saved $t" } }
    }

    fun importSample(row: Int, uri: Uri) = io.execute {
        try {
            val name = (uri.lastPathSegment ?: "sample.wav").substringAfterLast('/').let { if (it.contains('.')) it else "$it.wav" }
            val ref = contentResolver.openInputStream(uri)!!.use { store.importSample(proj, it, name) }
            pushUndo(); samples[row] = decodeWav(store.resolve(proj, ref.path)); paths[row] = ref.path
            runOnUiThread { grid.invalidate() }; save()
        } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Import failed: ${e.message} (use 16-bit PCM WAV)", Toast.LENGTH_LONG).show() } }
    }

    // 16-bit PCM WAV -> mono 44.1kHz
    fun decodeWav(f: File): ShortArray {
        val b = f.readBytes()
        fun i16(o: Int) = (b[o].toInt() and 255) or (b[o + 1].toInt() shl 8)
        fun i32(o: Int) = i16(o) or (i16(o + 2) shl 16)
        require(String(b, 0, 4) == "RIFF" && i16(34) == 16) { "not 16-bit PCM WAV" }
        val ch = i16(22); val rate = i32(24)
        var o = 12; while (String(b, o, 4) != "data") o += 8 + i32(o + 4)
        val n = minOf(i32(o + 4), b.size - o - 8) / (2 * ch)
        val mono = ShortArray(n) { k -> i16(o + 8 + k * 2 * ch).toShort() }
        val outN = (n.toLong() * 44100 / rate).toInt()
        return ShortArray(outN) { k -> mono[(k.toLong() * rate / 44100).toInt().coerceAtMost(n - 1)] }
    }

    fun startEngine() {
        playing = true
        Thread {
            val sr = 44100
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192), AudioTrack.MODE_STREAM)
            at.play(); val pos = IntArray(7) { -1 }; var step = startStep; val voices = ArrayList<V>(); val rvs = ArrayList<RV>()
            while (playing) {
                val spf0 = sr * 60 / bpm / 4
                val spf = (if (step % 2 == 0) spf0 * (1 + swing * 0.66) else spf0 * (1 - swing * 0.66)).toInt()
                if (countSteps > 0) {
                    val cb = ShortArray(spf * 2)
                    if (countSteps % 4 == 0) { val c = if (countSteps % 16 == 0) clickHi else clickLo; for (i in 0 until minOf(spf, c.size)) { cb[2 * i] = c[i]; cb[2 * i + 1] = c[i] } }
                    at.write(cb, 0, cb.size); countSteps--; continue
                }
                if (step >= loopBars * 16) { if (loopOn) step = 0 else { playing = false; continue } }
                val ds = step % 16
                val anyRowSolo = rowSolo.any { it }
                for (r in 0 until 7) if (pattern[r][ds] && samples[r] != null && !rowMute[r] && (!anyRowSolo || rowSolo[r])) pos[r] = 0
                val ms = samples[6]
                if (ms != null) for (n in notes) if (n.step == step) voices.add(V(0.0, 2.0.pow((n.pitch - 60) / 12.0), n.len * spf))
                val anySolo = tracks.any { it.solo } || seqCh.solo || keysCh.solo
                val sOn = active(seqCh.mute, seqCh.solo, anySolo); val sGl = if (sOn) lGain(seqCh.vol, seqCh.pan) else 0f; val sGr = if (sOn) rGain(seqCh.vol, seqCh.pan) else 0f
                val kOn = active(keysCh.mute, keysCh.solo, anySolo); val kGl = if (kOn) lGain(keysCh.vol, keysCh.pan) else 0f; val kGr = if (kOn) rGain(keysCh.vol, keysCh.pan) else 0f
                val mv = masterVol; val clickOn = metroOn && step % 4 == 0
                for (t in tracks) if (!t.mute && (!anySolo || t.solo)) for (g in t.regions) if (g.start == step) cache[g.path]?.let { rvs.add(RV(it, g.off, g.len * spf, t)) }
                val tl2 = tracks.toList(); val nT = tl2.size
                val tGl = FloatArray(nT); val tGr = FloatArray(nT); val tsum = FloatArray(nT)
                for (j in 0 until nT) { val tt = tl2[j]; val on = active(tt.mute, tt.solo, anySolo)
                    tGl[j] = if (on) lGain(tt.vol, tt.pan) else 0f; tGr[j] = if (on) rGain(tt.vol, tt.pan) else 0f; tt.fx.update() }
                seqCh.fx.update(); keysCh.fx.update()
                for (v in rvs) v.ti = tl2.indexOf(v.t)
                grid.playStep = ds; roll.playStep = step; grid.postInvalidate(); roll.postInvalidate(); tl.playStep = step; tl.postInvalidate()
                val buf = ShortArray(spf * 2); var pkL = 0f; var pkR = 0f
                for (i in 0 until spf) {
                    var sq = 0; var kq = 0; var oL = 0f; var oR = 0f
                    for (j in 0 until nT) tsum[j] = 0f
                    for (r in 0 until 7) { val d = samples[r]; if (d != null && pos[r] >= 0) { sq += d[pos[r]++]; if (pos[r] >= d.size) pos[r] = -1 } }
                    if (ms != null) { val iter = voices.iterator(); while (iter.hasNext()) { val v = iter.next(); val k = v.p.toInt()
                        if (k >= ms.size - 1 || v.left <= 0) { iter.remove(); continue }
                        kq += (ms[k] * 0.7).toInt(); v.p += v.rate; v.left-- } }
                    if (rvs.isNotEmpty()) { val it2 = rvs.iterator(); while (it2.hasNext()) { val v = it2.next()
                        if (v.pos >= v.d.size || v.left <= 0) { it2.remove(); continue }
                        if (v.ti >= 0) tsum[v.ti] += v.d[v.pos] * 0.7f; v.pos++; v.left-- } }
                    val ys = seqCh.fx.process(sq.toFloat()); val yk = keysCh.fx.process(kq.toFloat())
                    oL += ys * sGl + yk * kGl; oR += ys * sGr + yk * kGr
                    for (j in 0 until nT) { val y = tl2[j].fx.process(tsum[j]); oL += y * tGl[j]; oR += y * tGr[j] }
                    var fl = oL * 0.9f * mv; var fr = oR * 0.9f * mv
                    if (clickOn && i < 1500) { val c = (if (step % 16 == 0) clickHi else clickLo)[i] * 0.5f; fl += c; fr += c }
                    buf[2 * i] = fl.toInt().coerceIn(-32768, 32767).toShort(); buf[2 * i + 1] = fr.toInt().coerceIn(-32768, 32767).toShort()
                    pkL = maxOf(pkL, Math.abs(fl)); pkR = maxOf(pkR, Math.abs(fr))
                }
                at.write(buf, 0, spf * 2); meterL = pkL / 32768f; meterR = pkR / 32768f; mixer.postInvalidate(); leds.postInvalidate(); step++
            }
            at.stop(); at.release(); grid.playStep = -1; roll.playStep = -1; grid.postInvalidate(); roll.postInvalidate(); tl.playStep = -1; tl.postInvalidate(); meterL = 0f; meterR = 0f; mixer.postInvalidate(); leds.postInvalidate()
        }.start()
    }
    fun stopEngine() { playing = false; countSteps = 0 }

    fun showFx(c: Ch) {
        val f = c.fx
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        fun sl(label: String, lo: Float, hi: Float, cur: Float, fmt: String, set: (Float) -> Unit) {
            val tv = TextView(this).apply { text = label + ": " + String.format(fmt, cur) }
            val sb = SeekBar(this)
            sb.max = 100; sb.progress = ((cur - lo) / (hi - lo) * 100).toInt().coerceIn(0, 100)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, user: Boolean) { val v = lo + pr / 100f * (hi - lo); set(v); tv.text = label + ": " + String.format(fmt, v) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { save() }
            })
            box.addView(tv); box.addView(sb)
        }
        sl("EQ low dB", -12f, 12f, f.eqLow, "%.1f") { f.eqLow = it }
        sl("EQ mid dB", -12f, 12f, f.eqMid, "%.1f") { f.eqMid = it }
        sl("EQ high dB", -12f, 12f, f.eqHigh, "%.1f") { f.eqHigh = it }
        sl("Compressor threshold dB (0 = off)", -40f, 0f, if (f.compOn) f.compThresh else 0f, "%.0f") { if (it > -2f) f.compOn = false else { f.compOn = true; f.compThresh = it } }
        sl("Compressor ratio", 1.5f, 10f, f.compRatio, "%.1f") { f.compRatio = it }
        sl("Delay mix", 0f, 1f, f.delayMix, "%.2f") { f.delayMix = it }
        sl("Delay time ms", 50f, 900f, f.delayMs, "%.0f") { f.delayMs = it }
        sl("Delay feedback", 0f, 0.85f, f.delayFb, "%.2f") { f.delayFb = it }
        sl("Reverb mix", 0f, 1f, f.reverbMix, "%.2f") { f.reverbMix = it }
        sl("Reverb size", 0f, 1f, f.reverbSize, "%.2f") { f.reverbSize = it }
        AlertDialog.Builder(this).setTitle("FX: " + c.name).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).setNeutralButton("Reset") { _, _ -> f.reset(); save() }.show()
    }

    @Volatile var recording = false
    @Volatile var loopOn = true
    @Volatile var swing = 0f
    val rowMute = BooleanArray(7)
    val rowSolo = BooleanArray(7)
    val tabMap = LinkedHashMap<View, Button>()
    lateinit var leds: Leds
    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun bg(color: Int, r: Float = 24f) = GradientDrawable().apply { setColor(color); cornerRadius = r }
    fun LinearLayout.gap(): LinearLayout { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(dp(8), dp(8)) }; return this }
    @Volatile var metroOn = false
    @Volatile var countSteps = 0
    var countBars = 1
    fun makeClick(f: Double) = ShortArray(1500) { i -> (Math.sin(2 * Math.PI * f * i / 44100) * Math.exp(-i / 300.0) * 12000).toInt().toShort() }
    val clickHi = makeClick(1600.0)
    val clickLo = makeClick(1000.0)
    var armTrack = 0
    lateinit var playBtn: Button
    lateinit var recBtn: Button
    val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec() else Toast.makeText(this, "Microphone permission is needed to record", Toast.LENGTH_LONG).show() }

    fun wavHeader(n: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + n); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(44100); b.putInt(88200); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(n); return b.array()
    }

    // Records the mic onto the armed track (red R in the Timeline header) at the green marker, while the project plays.
    fun startRec() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micPerm.launch(Manifest.permission.RECORD_AUDIO); return }
        if (recording) return
        val ti = armTrack.coerceIn(0, tracks.size - 1); val at = startStep / 4 * 4
        val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Toast.makeText(this, "Could not open the microphone", Toast.LENGTH_LONG).show(); return }
        recording = true; recBtn.text = "\u25A0"
        if (!playing) { countSteps = countBars * 16; startEngine(); playBtn.contentDescription = "Stop" }
        Thread {
            val raw = File(store.dir(proj.name), "Cache/rec.raw"); var total = 0
            try {
                while (recording && countSteps > 0) Thread.sleep(5)
                rec.startRecording(); val b = ByteArray(4096)
                raw.outputStream().use { o -> while (recording) { val n = rec.read(b, 0, b.size); if (n > 0) { o.write(b, 0, n); total += n } } }
                rec.stop()
            } finally { rec.release() }
            try {
                if (total > 8820) {
                    val tmp = File(store.dir(proj.name), "Cache/rec.wav")
                    tmp.outputStream().use { o -> o.write(wavHeader(total)); raw.inputStream().use { it.copyTo(o) } }
                    val n = proj.samples.count { it.path.startsWith("Samples/Rec") } + 1
                    val ref = tmp.inputStream().use { store.importSample(proj, it, "Rec $n.wav") }
                    val d = decodeWav(store.resolve(proj, ref.path)); cache[ref.path] = d
                    pushUndo(); tracks[ti].regions.add(Region(at, maxOf(1, Math.ceil(d.size.toDouble() / spf()).toInt()), ref.path, 0))
                    tmp.delete(); save()
                }
                raw.delete()
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show() } }
            runOnUiThread { recBtn.text = "\u25CF"; playBtn.contentDescription = if (playing) "Stop" else "Play"; tl.invalidate() }
        }.start()
    }

    val undoStack = ArrayList<String>()
    val redoStack = ArrayList<String>()

    @Synchronized fun pushUndo() {
        syncSections(); undoStack.add(proj.sections.toString())
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun applyState(js: String) {
        val o = JSONObject(js)
        proj.sections.keys().asSequence().toList().forEach { proj.sections.remove(it) }
        o.keys().forEach { proj.sections.put(it, o.get(it)) }
        notes.clear(); tracks.clear(); selected = null
        for (row in pattern) row.fill(false)
        samples.fill(null); paths.fill(null)
        load()
        grid.invalidate(); roll.requestLayout(); roll.invalidate(); keys.invalidate(); headers.invalidate()
        tl.requestLayout(); tl.invalidate(); mixer.requestLayout(); mixer.invalidate()
        save()
    }

    @Synchronized fun undo() {
        if (undoStack.isEmpty()) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return }
        syncSections(); redoStack.add(proj.sections.toString()); applyState(undoStack.removeAt(undoStack.size - 1))
    }

    @Synchronized fun redo() {
        if (redoStack.isEmpty()) { Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show(); return }
        syncSections(); undoStack.add(proj.sections.toString()); applyState(redoStack.removeAt(redoStack.size - 1))
    }

    lateinit var projBox: ScrollView
    lateinit var projList: LinearLayout
    lateinit var chip: TextView
    lateinit var loopBtnRef: Button
    lateinit var swingRef: TextView

    fun toast(m: String?) = Toast.makeText(this, m ?: "Error", Toast.LENGTH_LONG).show()

    fun mkBtn(t: String, f: (Button) -> Unit) = Button(this).apply { text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        background = bg(0xFF2A3245.toInt(), dp(12).toFloat()); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = dp(40); setPadding(dp(12), 0, dp(12), 0); setOnClickListener { f(this) } }

    fun askName(title: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> val n = et.text.toString().trim(); if (n.isNotEmpty()) ok(n) }.setNegativeButton("Cancel", null).show()
    }

    fun saveNow() { if (store.dir(proj.name).exists()) { syncSections(); store.save(proj) } }

    fun openProject(name: String, saveFirst: Boolean = true) {
        stopEngine(); recording = false
        if (saveFirst) saveNow()
        val (p, missing) = try { store.open(name) } catch (e: Exception) { toast("Could not open: ${e.message}"); return }
        proj = p
        getSharedPreferences("echo", 0).edit().putString("last", name).apply()
        notes.clear(); tracks.clear(); selected = null; for (row in pattern) row.fill(false)
        samples.fill(null); paths.fill(null); cache.clear(); rowMute.fill(false); rowSolo.fill(false)
        for (c in listOf(seqCh, keysCh)) { c.vol = 0.8f; c.pan = 0f; c.mute = false; c.solo = false; c.fx.reset() }
        masterVol = 0.8f; undoStack.clear(); redoStack.clear(); startStep = 0
        load()
        chip.text = "${proj.name}.emb"; loopBtnRef.text = "1 - $loopBars"; swingRef.text = "Swing ${(swing * 100).toInt()}%"; bpmText.text = "%.1f BPM".format(bpm)
        grid.invalidate(); roll.requestLayout(); roll.invalidate(); keys.invalidate(); headers.invalidate()
        tl.requestLayout(); tl.invalidate(); mixer.requestLayout(); mixer.invalidate()
        refreshProjects()
        if (missing.isNotEmpty()) AlertDialog.Builder(this).setTitle("Missing samples")
            .setMessage(missing.joinToString("\n") { it.path } + "\n\nThese files are not in the project's Samples folder. Tap a sequencer row name or a timeline track name to import a replacement.")
            .setPositiveButton("OK", null).show()
    }

    fun refreshProjects() {
        projList.removeAllViews()
        val head = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "Projects"; setTextColor(Color.WHITE); textSize = 20f }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(mkBtn("+ New Project") { askName("New project", "My Beat") { n -> try { saveNow(); store.create(n); openProject(n, false) } catch (e: Exception) { toast(e.message) } } })
        projList.addView(head)
        for (n in store.listProjects()) {
            val cur = n == proj.name
            val row = LinearLayout(this).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF141923.toInt(), dp(14).toFloat()); setPadding(dp(14), dp(10), dp(10), dp(10)) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply { text = n + if (cur) "   (open)" else ""; setTextColor(if (cur) 0xFF22DD33.toInt() else Color.WHITE); textSize = 17f })
            info.addView(TextView(this).apply { text = "$n.emb  \u2022  " + store.assetSummary(n); setTextColor(Color.GRAY); textSize = 12f })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(mkBtn("Open") { openProject(n); show(grid) })
            row.addView(mkBtn("Rename") { askName("Rename project", n) { nn -> try { if (cur) saveNow(); store.rename(n, nn); if (cur) openProject(nn, false) else refreshProjects() } catch (e: Exception) { toast(e.message) } } })
            row.addView(mkBtn("Duplicate") { askName("Duplicate as", "$n copy") { nn -> try { if (cur) saveNow(); store.duplicate(n, nn); refreshProjects() } catch (e: Exception) { toast(e.message) } } })
            row.addView(mkBtn("Recover") { AlertDialog.Builder(this).setTitle("Recover \"$n\"")
                .setMessage("Restore the newest autosave backup? This replaces the current project file with that backup.")
                .setPositiveButton("Recover") { _, _ -> val rp = store.recover(n); if (rp == null) toast("No autosave backup found") else { store.save(rp); openProject(n, false) } }
                .setNegativeButton("Cancel", null).show() })
            row.addView(mkBtn("Delete") { AlertDialog.Builder(this).setTitle("Delete \"$n\"?")
                .setMessage("This permanently deletes the folder \"$n\" (${store.assetSummary(n)}) with its .emb file, samples, presets and autosaves. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> store.delete(n)
                    if (cur) { if (store.listProjects().isEmpty()) store.create("My Beat"); openProject(store.listProjects().first(), false) } else refreshProjects() }
                .setNegativeButton("Cancel", null).show() })
            projList.addView(row)
        }
    }

    fun spf() = (44100 * 60 / bpm / 4).toInt()

    fun loadTracks() {
        proj.sections.optJSONArray("tracks")?.let { a -> for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val t = Track(o.getString("name"), colors[i % 7], o.optDouble("vol", 0.8).toFloat(), o.optBoolean("mute"), o.optBoolean("solo"))
            t.pan = o.optDouble("pan", 0.0).toFloat(); o.optJSONObject("fx")?.let { t.fx.load(it) }; val rs = o.getJSONArray("regions")
            for (j in 0 until rs.length()) { val r = rs.getJSONArray(j); val path = r.getString(3)
                try { cache[path] = decodeWav(store.resolve(proj, path)) } catch (e: Exception) { }
                t.regions.add(Region(r.getInt(0), r.getInt(1), path, r.getInt(2))) }
            tracks.add(t) } }
        proj.sections.optJSONArray("metro")?.let { metroOn = it.getBoolean(0); countBars = it.getInt(1) }
        proj.sections.optJSONObject("mixer")?.let { m -> masterVol = m.optDouble("master", 0.8).toFloat(); m.optJSONObject("seqFx")?.let { seqCh.fx.load(it) }; m.optJSONObject("keysFx")?.let { keysCh.fx.load(it) }
            for ((c, k) in listOf(seqCh to "seq", keysCh to "keys")) m.optJSONArray(k)?.let { a -> c.vol = a.getDouble(0).toFloat(); c.pan = a.getDouble(1).toFloat(); c.mute = a.getBoolean(2); c.solo = a.getBoolean(3) } }
        if (tracks.isEmpty()) for (i in 1..4) tracks.add(Track("Audio $i", colors[(i - 1) % 7]))
    }

    fun saveTracks() {
        proj.masterVolume = masterVol; proj.sections.put("metro", JSONArray(listOf(metroOn, countBars)))
        proj.sections.put("mixer", JSONObject().put("master", masterVol.toDouble())
            .put("seq", JSONArray(listOf(seqCh.vol.toDouble(), seqCh.pan.toDouble(), seqCh.mute, seqCh.solo)))
            .put("keys", JSONArray(listOf(keysCh.vol.toDouble(), keysCh.pan.toDouble(), keysCh.mute, keysCh.solo)))
            .put("seqFx", seqCh.fx.toJson()).put("keysFx", keysCh.fx.toJson()))
        proj.sections.put("tracks", JSONArray(tracks.map { t -> JSONObject().put("name", t.name).put("vol", t.vol.toDouble()).put("pan", t.pan.toDouble()).put("fx", t.fx.toJson())
            .put("mute", t.mute).put("solo", t.solo)
            .put("regions", JSONArray(t.regions.map { g -> JSONArray(listOf(g.start, g.len, g.off)).put(g.path) })) }))
    }

    fun addTrack() { pushUndo(); tracks.add(Track("Audio ${tracks.size + 1}", colors[tracks.size % 7])); headers.invalidate(); tl.invalidate(); save() }

    fun importRegion(ti: Int, uri: Uri) = io.execute {
        try {
            val name = (uri.lastPathSegment ?: "sample.wav").substringAfterLast('/').let { if (it.contains('.')) it else "$it.wav" }
            val ref = contentResolver.openInputStream(uri)!!.use { store.importSample(proj, it, name) }
            val d = decodeWav(store.resolve(proj, ref.path)); cache[ref.path] = d
            val len = maxOf(1, Math.ceil(d.size.toDouble() / spf()).toInt())
            pushUndo(); tracks[ti].regions.add(Region(startStep / 4 * 4, len, ref.path, 0))
            runOnUiThread { tl.invalidate() }; save()
        } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Import failed: ${e.message} (use 16-bit PCM WAV)", Toast.LENGTH_LONG).show() } }
    }

    fun splitSelected() {
        val g = selected ?: return
        val at = if (playing) tl.playStep else startStep
        if (at <= g.start || at >= g.start + g.len) { Toast.makeText(this, "Tap the ruler to put the green marker inside the selected region", Toast.LENGTH_LONG).show(); return }
        pushUndo(); val t = tracks.first { g in it.regions }; val first = at - g.start
        t.regions.remove(g)
        t.regions.add(Region(g.start, first, g.path, g.off))
        val b = Region(at, g.len - first, g.path, g.off + first * spf()); t.regions.add(b)
        selected = b; tl.invalidate(); save()
    }

    fun deleteSelected() { val g = selected ?: return; pushUndo(); tracks.forEach { it.regions.remove(g) }; selected = null; tl.invalidate(); save() }

    inner class Headers(c: Context) : View(c) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val rh = (height - RH) / maxOf(4, tracks.size); p.textSize = 30f
            for ((i, t) in tracks.withIndex()) { val y = RH + i * rh
                p.color = 0xFF1B2130.toInt(); cv.drawRect(0f, y, width.toFloat(), y + rh - 1, p)
                p.color = t.color; cv.drawRect(0f, y, 10f, y + rh - 1, p)
                p.color = Color.WHITE; cv.drawText(t.name, 24f, y + rh / 2 + 10, p)
                p.color = if (t.mute) 0xFFFFB020.toInt() else 0xFF3A4156.toInt(); cv.drawRect(width - 140f, y + rh / 2 - 28, width - 76f, y + rh / 2 + 28, p)
                p.color = if (t.solo) 0xFF22DD33.toInt() else 0xFF3A4156.toInt(); cv.drawRect(width - 66f, y + rh / 2 - 28, width - 2f, y + rh / 2 + 28, p)
                p.color = if (armTrack == i) 0xFFFF3B4E.toInt() else 0xFF3A4156.toInt(); cv.drawRect(width - 212f, y + rh / 2 - 28, width - 148f, y + rh / 2 + 28, p)
                p.color = Color.WHITE; cv.drawText("R", width - 192f, y + rh / 2 + 10, p); cv.drawText("M", width - 120f, y + rh / 2 + 10, p); cv.drawText("S", width - 46f, y + rh / 2 + 10, p) }
            p.textSize = 22f; p.color = Color.GRAY; cv.drawText("tap name = add audio", 12f, 30f, p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP && e.y > RH) {
                val ti = ((e.y - RH) / ((height - RH) / maxOf(4, tracks.size))).toInt(); val t = tracks.getOrNull(ti) ?: return true
                when { e.x > width - 70 -> t.solo = !t.solo; e.x > width - 144 -> t.mute = !t.mute; e.x > width - 218 -> armTrack = ti
                    else -> { pendingTrack = ti; tlPicker.launch(arrayOf("audio/*")) } }
                invalidate(); tl.invalidate(); save()
            }
            return true
        }
    }

    inner class TL(c: Context) : View(c) {
        @Volatile var playStep = -1
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val cw = 14f
        var drag: Region? = null; var grab = 0f; var moved = false; var mode = 0; var o0 = 0; var l0 = 0; var f0 = 0
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension((loopBars * 16 * cw).toInt(), MeasureSpec.getSize(h)) }
        override fun onDraw(cv: Canvas) {
            val rh = (height - RH) / maxOf(4, tracks.size); val w = width.toFloat(); val hh = height.toFloat()
            p.style = Paint.Style.FILL; p.color = 0xFF0F131B.toInt(); cv.drawRect(0f, 0f, w, hh, p)
            for (i in tracks.indices) { p.color = if (i % 2 == 0) 0xFF171C27.toInt() else 0xFF131822.toInt(); cv.drawRect(0f, RH + i * rh, w, RH + (i + 1) * rh - 1, p) }
            p.textSize = 24f
            for (s in 0..loopBars * 16 step 4) { val bar = s % 16 == 0
                p.color = if (bar) 0xFF8090B0.toInt() else 0xFF2A3245.toInt(); cv.drawRect(s * cw, if (bar) 0f else RH, s * cw + 2, hh, p)
                if (bar) { p.color = Color.WHITE; cv.drawText("${s / 16 + 1}", s * cw + 6, 32f, p) } }
            for ((i, t) in tracks.withIndex()) for (g in t.regions) { val y = RH + i * rh
                val l = g.start * cw + 1; val r = (g.start + g.len) * cw - 1
                p.color = if (t.mute) 0xFF444B5A.toInt() else t.color; cv.drawRoundRect(l, y + 6, r, y + rh - 6, 8f, 8f, p)
                if (g === selected) { p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.WHITE; cv.drawRoundRect(l, y + 6, r, y + rh - 6, 8f, 8f, p); p.style = Paint.Style.FILL
                    cv.drawRect(l + 2, y + 20, l + 8, y + rh - 20, p); cv.drawRect(r - 8, y + 20, r - 2, y + rh - 20, p) }
                cv.save(); cv.clipRect(l, y, r, y + rh); p.color = Color.BLACK; cv.drawText(g.path.substringAfterLast('/'), l + 8, y + rh / 2 + 8, p); cv.restore() }
            p.color = 0xFF22DD33.toInt(); cv.drawRect(startStep * cw, 0f, startStep * cw + 4, hh, p)
            if (playStep >= 0) { p.color = Color.WHITE; cv.drawRect(playStep * cw, 0f, playStep * cw + 3, hh, p) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val rh = (height - RH) / maxOf(4, tracks.size)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (e.y < RH) { startStep = (e.x / cw).toInt() / 4 * 4; invalidate(); return true }
                    val ti = ((e.y - RH) / rh).toInt(); val st = (e.x / cw).toInt()
                    val hit = tracks.getOrNull(ti)?.regions?.firstOrNull { st >= it.start && st < it.start + it.len }
                    selected = hit; drag = hit; moved = false
                    if (hit != null) { grab = e.x / cw - hit.start; o0 = hit.start; l0 = hit.len; f0 = hit.off
                        val ez = minOf(24f, hit.len * cw * 0.3f)
                        mode = if (e.x - hit.start * cw < ez) 1 else if ((hit.start + hit.len) * cw - e.x < ez) 2 else 0
                        parent.requestDisallowInterceptTouchEvent(true) }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> drag?.let { g ->
                    if (!moved) { moved = true; pushUndo() }
                    val sp = spf()
                    when (mode) {
                        0 -> g.start = (Math.round((e.x / cw - grab) / 4f) * 4).coerceAtLeast(0)
                        1 -> { val ns = Math.round(e.x / cw).coerceIn(maxOf(0, o0 - f0 / sp), o0 + l0 - 1)
                            g.start = ns; g.len = l0 - (ns - o0); g.off = f0 + (ns - o0) * sp }
                        else -> { val maxLen = Math.ceil(((cache[g.path]?.size ?: 0) - f0).toDouble() / sp).toInt().coerceAtLeast(1)
                            g.len = (Math.round(e.x / cw) - o0).coerceIn(1, maxLen) }
                    }
                    invalidate() }
                MotionEvent.ACTION_UP -> { if (drag != null) save(); drag = null }
            }
            return true
        }
    }

    inner class Mixer(c: Context) : View(c) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val sw = 170f
        var zi = 0; var zone = 0; var downX = 0f
        fun chans(): List<Ch> = listOf<Ch>(seqCh, keysCh) + tracks
        fun fTop() = 150f
        fun fBot() = height - 130f
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(((chans().size + 1) * sw).toInt(), MeasureSpec.getSize(h)) }
        override fun onDraw(cv: Canvas) {
            val cs = chans(); val hh = height.toFloat(); val ft = fTop(); val fb = fBot()
            for (i in 0..cs.size) {
                val x = i * sw; val master = i == cs.size
                p.style = Paint.Style.FILL
                p.color = if (i % 2 == 0) 0xFF171C27.toInt() else 0xFF131822.toInt(); cv.drawRect(x, 0f, x + sw - 2, hh, p)
                p.textSize = 26f; p.color = Color.WHITE; cv.drawText(if (master) "Master" else cs[i].name, x + 12, 40f, p)
                if (!master && cs[i].fx.isOn()) { p.color = 0xFF22DD33.toInt(); cv.drawText("FX", x + sw - 52, 40f, p) }
                val vol = if (master) masterVol else cs[i].vol
                if (!master) { p.color = 0xFF3A4156.toInt(); cv.drawRect(x + 20, 82f, x + sw - 22, 94f, p)
                    p.color = 0xFFFFB020.toInt(); cv.drawCircle(x + 20 + (cs[i].pan + 1) / 2 * (sw - 42), 88f, 16f, p) }
                p.color = 0xFF2A3245.toInt(); cv.drawRect(x + sw / 2 - 6, ft, x + sw / 2 + 6, fb, p)
                val y = fb - vol / 1.25f * (fb - ft)
                p.color = 0xFF18C8F0.toInt(); cv.drawRoundRect(x + sw / 2 - 34, y - 16, x + sw / 2 + 34, y + 16, 8f, 8f, p)
                p.textSize = 22f; p.color = Color.LTGRAY; cv.drawText("${Math.round(vol * 100)}%", x + 12, fb + 36, p)
                if (master) { for ((k, m) in listOf(meterL, meterR).withIndex()) { val lv = m.coerceIn(0f, 1f)
                    p.color = if (lv > 0.9f) 0xFFFF3B4E.toInt() else if (lv > 0.7f) 0xFFFFD21E.toInt() else 0xFF22DD33.toInt()
                    val bx = x + 14 + k * (sw - 48); cv.drawRect(bx, fb - lv * (fb - ft), bx + 20, fb, p) } }
                else { p.color = if (cs[i].mute) 0xFFFFB020.toInt() else 0xFF3A4156.toInt(); cv.drawRect(x + 14, hh - 70, x + 78, hh - 14, p)
                    p.color = if (cs[i].solo) 0xFF22DD33.toInt() else 0xFF3A4156.toInt(); cv.drawRect(x + 90, hh - 70, x + 154, hh - 14, p)
                    p.textSize = 28f; p.color = Color.WHITE; cv.drawText("M", x + 36, hh - 30, p); cv.drawText("S", x + 112, hh - 30, p) }
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val cs = chans()
            if (e.action == MotionEvent.ACTION_DOWN) {
                downX = e.x; zi = (e.x / sw).toInt().coerceIn(0, cs.size); val lx = e.x - zi * sw
                zone = when { e.y > height - 80 -> 3; e.y > fTop() - 30 -> 2; e.y in 60f..120f -> 1; else -> 0 }
                if (zone == 3 && zi < cs.size) { val c = cs[zi]; if (lx < 84) c.mute = !c.mute else c.solo = !c.solo; invalidate(); save() }
                if (zone == 1 || zone == 2) parent.requestDisallowInterceptTouchEvent(true)
            }
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                val lx = e.x - zi * sw
                if (zone == 1 && zi < cs.size) { var v = ((lx - 20) / (sw - 42) * 2 - 1).coerceIn(-1f, 1f); if (Math.abs(v) < 0.08f) v = 0f; cs[zi].pan = v }
                if (zone == 2) { val v = ((fBot() - e.y) / (fBot() - fTop()) * 1.25f).coerceIn(0f, 1.25f); if (zi < cs.size) cs[zi].vol = v else masterVol = v }
                invalidate()
            }
            if (e.action == MotionEvent.ACTION_UP && (zone == 1 || zone == 2)) save()
            if (e.action == MotionEvent.ACTION_UP && zone == 0 && zi < cs.size && Math.abs(e.x - downX) < 20f) showFx(cs[zi])
            return true
        }
    }

    inner class Roll(c: Context) : View(c) {
        @Volatile var playStep = -1
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val cw = 56f
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension((loopBars * 16 * cw).toInt(), MeasureSpec.getSize(h)) }
        override fun onDraw(cv: Canvas) {
            val rh = height / 24f
            for (r in 0 until 24) { val pitch = basePitch + 23 - r
                p.color = if (pitch % 12 in setOf(1, 3, 6, 8, 10)) 0xFF10141C.toInt() else 0xFF1A2030.toInt()
                cv.drawRect(0f, r * rh, width.toFloat(), (r + 1) * rh - 1, p) }
            for (s in 0..loopBars * 16) { p.color = if (s % 16 == 0) 0xFF8090B0.toInt() else if (s % 4 == 0) 0xFF3A4560.toInt() else 0xFF252C3C.toInt()
                cv.drawRect(s * cw, 0f, s * cw + 2, height.toFloat(), p) }
            p.color = 0xFF18C8F0.toInt()
            for (n in notes) { val r = basePitch + 23 - n.pitch
                if (r in 0..23) cv.drawRoundRect(n.step * cw + 3, r * rh + 2, (n.step + n.len) * cw - 3, (r + 1) * rh - 2, 8f, 8f, p) }
            if (playStep >= 0) { p.color = Color.WHITE; cv.drawRect(playStep * cw, 0f, playStep * cw + 3, height.toFloat(), p) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                val step = (e.x / cw).toInt(); val pitch = basePitch + 23 - (e.y / (height / 24f)).toInt().coerceIn(0, 23)
                val hit = notes.firstOrNull { it.pitch == pitch && step >= it.step && step < it.step + it.len }
                pushUndo(); if (hit != null) notes.remove(hit)
                else if (step < loopBars * 16) notes.add(Note(step, minOf(noteLen, loopBars * 16 - step), pitch))
                invalidate(); save()
            }
            return true
        }
    }

    inner class Keys(c: Context) : View(c) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val rh = height / 24f; p.textSize = 26f
            for (r in 0 until 24) { val pitch = basePitch + 23 - r
                p.color = if (pitch % 12 in setOf(1, 3, 6, 8, 10)) Color.BLACK else 0xFFE8E8F0.toInt()
                cv.drawRect(0f, r * rh, width.toFloat(), (r + 1) * rh - 1, p)
                if (pitch % 12 == 0) { p.color = Color.DKGRAY; cv.drawText("C${pitch / 12 - 1}", 8f, r * rh + rh - 8, p) } }
        }
    }

    inner class Leds(c: Context) : View(c) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val n = 12; val w = width / n.toFloat(); val h = height / 2f
            for (row in 0..1) { val lv = if (row == 0) meterL else meterR
                for (k in 0 until n) { val on = lv * n > k
                    p.color = if (!on) 0xFF1A2030.toInt() else if (k >= 10) 0xFFFF3B4E.toInt() else if (k >= 8) 0xFFFFD21E.toInt() else 0xFF22DD33.toInt()
                    cv.drawRoundRect(k * w + 2, row * h + 2, (k + 1) * w - 2, (row + 1) * h - 2, 3f, 3f, p) } }
        }
    }

    inner class Grid(c: Context) : View(c) {
        @Volatile var playStep = -1
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val lw = dp(224).toFloat(); val top = dp(44).toFloat()
        override fun onDraw(cv: Canvas) {
            val rh = (height - top) / 7f; val cw = (width - lw) / 16f; val rad = dp(6).toFloat(); val m = dp(3).toFloat()
            val anyS = rowSolo.any { it }
            p.style = Paint.Style.FILL
            p.textSize = dp(14).toFloat(); p.color = Color.WHITE
            for (k in 0 until 4) cv.drawText("${k + 1}", lw + k * 4 * cw + dp(6), top - dp(16), p)
            p.color = 0xFF22DD33.toInt(); cv.drawRect(lw, top - dp(8), width.toFloat(), top - dp(5), p)
            cv.drawRect(lw, top - dp(20), lw + dp(5), top - dp(5), p); cv.drawRect(width - dp(5).toFloat(), top - dp(20), width.toFloat(), top - dp(5), p)
            for (r in 0 until 7) { val y = top + r * rh
                p.color = (colors[r] and 0x00FFFFFF) or 0x18000000; cv.drawRect(lw, y, width.toFloat(), y + rh, p)
                p.color = 0xFF141923.toInt(); cv.drawRect(0f, y, lw - dp(4), y + rh, p)
                p.color = colors[r]; cv.drawRoundRect(dp(8).toFloat(), y + (rh - dp(36)) / 2, dp(44).toFloat(), y + (rh + dp(36)) / 2, dp(8).toFloat(), dp(8).toFloat(), p)
                p.color = Color.WHITE; p.textSize = dp(18).toFloat(); cv.drawText(names[r].take(1), dp(20).toFloat(), y + rh / 2 + dp(6), p)
                p.textSize = dp(15).toFloat(); cv.drawText(names[r], dp(52).toFloat(), y + rh / 2 + dp(5), p)
                if (samples[r] == null) { p.color = Color.GRAY; cv.drawText("+", dp(52) + p.measureText(names[r]) + dp(6), y + rh / 2 + dp(5), p) }
                val by = y + (rh - dp(34)) / 2; val bh = by + dp(34)
                p.color = if (rowSolo[r]) 0xFF22DD33.toInt() else 0xFF2A3245.toInt(); cv.drawRoundRect(lw - dp(88), by, lw - dp(48), bh, dp(8).toFloat(), dp(8).toFloat(), p)
                p.color = if (rowMute[r]) 0xFFFFB020.toInt() else 0xFF2A3245.toInt(); cv.drawRoundRect(lw - dp(44), by, lw - dp(8), bh, dp(8).toFloat(), dp(8).toFloat(), p)
                p.color = Color.WHITE; p.textSize = dp(15).toFloat()
                cv.drawText("S", lw - dp(74), by + dp(23), p); cv.drawText("M", lw - dp(32), by + dp(23), p)
                for (st in 0 until 16) {
                    p.color = if (pattern[r][st]) (if (rowMute[r] || (anyS && !rowSolo[r])) 0xFF555B6A.toInt() else colors[r]) else if (st / 4 % 2 == 0) 0xFF1A1F2B.toInt() else 0xFF151A25.toInt()
                    cv.drawRoundRect(lw + st * cw + m, y + m, lw + (st + 1) * cw - m, y + rh - m, rad, rad, p) } }
            if (playStep >= 0) { p.color = Color.WHITE; val px = lw + playStep * cw + cw / 2; cv.drawRect(px - 2, top - dp(20), px + 2, height.toFloat(), p) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN || e.y < top) return true
            val r = ((e.y - top) / ((height - top) / 7f)).toInt().coerceIn(0, 6)
            if (e.x < lw) {
                if (e.x > lw - dp(44)) { pushUndo(); rowMute[r] = !rowMute[r]; invalidate(); save() }
                else if (e.x > lw - dp(88)) { pushUndo(); rowSolo[r] = !rowSolo[r]; invalidate(); save() }
                else { pendingRow = r; picker.launch(arrayOf("audio/*")) }
            } else { val st = ((e.x - lw) / ((width - lw) / 16f)).toInt().coerceIn(0, 15); pushUndo(); pattern[r][st] = !pattern[r][st]; invalidate(); save() }
            return true
        }
    }
}
