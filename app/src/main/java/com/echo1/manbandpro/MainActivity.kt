package com.echo1.manbandpro

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.echo1.manbandpro.storage.*
import kotlin.math.pow
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    val names = listOf("Kick", "Snare", "Hat", "Perc", "Bass", "Chord", "Melody")
    val colors = intArrayOf(0xFFFF3B4E.toInt(), 0xFF1E90FF.toInt(), 0xFF22DD33.toInt(), 0xFFFF8A1E.toInt(),
        0xFF8A3CFF.toInt(), 0xFF18D8F0.toInt(), 0xFFFFD21E.toInt())
    val pattern = Array(7) { BooleanArray(16) }
    val samples = arrayOfNulls<ShortArray>(7)
    val paths = arrayOfNulls<String>(7)
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
        proj = store.listProjects().firstOrNull()?.let { store.open(it).first } ?: store.create("My Beat")
        load()
        val play = Button(this).apply { text = "Play"; setOnClickListener { if (playing) stopEngine() else startEngine(); text = if (playing) "Stop" else "Play" } }
        fun bpmBtn(t: String, d: Int) = Button(this).apply { text = t; setOnClickListener { bpm = (bpm + d).coerceIn(40.0, 240.0); bpmText.text = "%.1f BPM".format(bpm); save() } }
        bpmText = TextView(this).apply { text = "%.1f BPM".format(bpm); setTextColor(Color.WHITE); setPadding(24, 0, 24, 0) }
        status = TextView(this).apply { setTextColor(0xFF22DD33.toInt()); setPadding(24, 0, 0, 0) }
        val bar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL
            addView(play); addView(bpmBtn("-", -1)); addView(bpmText); addView(bpmBtn("+", 1))
            addView(TextView(context).apply { text = "  ${proj.name}.emb"; setTextColor(Color.LTGRAY) }); addView(status) }
        grid = Grid(this); roll = Roll(this); keys = Keys(this)
        val scroll = HorizontalScrollView(this).apply { addView(roll, FrameLayout.LayoutParams(-2, -1)) }
        rollBox = LinearLayout(this).apply { visibility = View.GONE
            addView(keys, LinearLayout.LayoutParams(120, -1)); addView(scroll, LinearLayout.LayoutParams(0, -1, 1f)) }
        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply { text = t; setOnClickListener { f(this) } }
        val tabs = LinearLayout(this).apply {
            addView(btn("Sequencer") { grid.visibility = View.VISIBLE; rollBox.visibility = View.GONE })
            addView(btn("Piano Roll") { grid.visibility = View.GONE; rollBox.visibility = View.VISIBLE })
            addView(btn("Bars: $loopBars") { loopBars = loopBars % 18 + 1; it.text = "Bars: $loopBars"; roll.requestLayout(); save() })
            addView(btn("Len: $noteLen") { noteLen = when (noteLen) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }; it.text = "Len: $noteLen" })
            addView(btn("Oct -") { basePitch = maxOf(24, basePitch - 12); keys.invalidate(); roll.invalidate() })
            addView(btn("Oct +") { basePitch = minOf(72, basePitch + 12); keys.invalidate(); roll.invalidate() }) }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0E14.toInt())
            addView(bar); addView(tabs)
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f)); addView(rollBox, LinearLayout.LayoutParams(-1, 0, 1f)) })
        val miss = store.missingSamples(proj)
        if (miss.isNotEmpty()) Toast.makeText(this, "Missing samples: " + miss.joinToString { it.path } + " (tap the row name to replace)", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { stopEngine(); super.onDestroy() }

    fun load() {
        bpm = proj.bpm; loopBars = proj.loopEndBar.coerceIn(1, 18)
        proj.sections.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) { val n = a.getJSONArray(i); notes.add(Note(n.getInt(0), n.getInt(1), n.getInt(2))) } }
        proj.sections.optString("pattern").split(",").forEachIndexed { r, s -> if (r < 7) s.forEachIndexed { i, c -> if (i < 16) pattern[r][i] = c == '1' } }
        proj.sections.optJSONArray("rowSamples")?.let { a -> for (r in 0 until minOf(7, a.length())) {
            val p = a.getString(r); if (p.isNotEmpty()) { paths[r] = p; val f = store.resolve(proj, p); if (f.exists()) samples[r] = decodeWav(f) } } }
    }

    fun save() {
        proj.bpm = bpm; proj.loopEndBar = loopBars
        proj.sections.put("notes", JSONArray(notes.map { JSONArray(listOf(it.step, it.len, it.pitch)) }))
        proj.sections.put("pattern", pattern.joinToString(",") { row -> row.joinToString("") { if (it) "1" else "0" } })
        proj.sections.put("rowSamples", JSONArray(paths.map { it ?: "" }))
        io.execute { store.autosave(proj)
            val t = java.text.SimpleDateFormat("h:mm a").format(java.util.Date())
            runOnUiThread { status.text = "  \u2713 Auto Saved $t" } }
    }

    fun importSample(row: Int, uri: Uri) = io.execute {
        try {
            val name = (uri.lastPathSegment ?: "sample.wav").substringAfterLast('/').let { if (it.contains('.')) it else "$it.wav" }
            val ref = contentResolver.openInputStream(uri)!!.use { store.importSample(proj, it, name) }
            samples[row] = decodeWav(store.resolve(proj, ref.path)); paths[row] = ref.path
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
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192), AudioTrack.MODE_STREAM)
            at.play(); val pos = IntArray(7) { -1 }; var step = 0; val voices = ArrayList<V>()
            while (playing) {
                val spf = (sr * 60 / bpm / 4).toInt()
                if (step >= loopBars * 16) step = 0
                val ds = step % 16
                for (r in 0 until 7) if (pattern[r][ds] && samples[r] != null) pos[r] = 0
                val ms = samples[6]
                if (ms != null) for (n in notes) if (n.step == step) voices.add(V(0.0, 2.0.pow((n.pitch - 60) / 12.0), n.len * spf))
                grid.playStep = ds; roll.playStep = step; grid.postInvalidate(); roll.postInvalidate()
                val buf = ShortArray(spf)
                for (i in 0 until spf) {
                    var s = 0
                    for (r in 0 until 7) { val d = samples[r]; if (d != null && pos[r] >= 0) { s += d[pos[r]++]; if (pos[r] >= d.size) pos[r] = -1 } }
                    if (ms != null) { val iter = voices.iterator(); while (iter.hasNext()) { val v = iter.next(); val k = v.p.toInt()
                        if (k >= ms.size - 1 || v.left <= 0) { iter.remove(); continue }
                        s += (ms[k] * 0.7).toInt(); v.p += v.rate; v.left-- } }
                    buf[i] = (s * 0.6).toInt().coerceIn(-32768, 32767).toShort()
                }
                at.write(buf, 0, spf); step++
            }
            at.stop(); at.release(); grid.playStep = -1; roll.playStep = -1; grid.postInvalidate(); roll.postInvalidate()
        }.start()
    }
    fun stopEngine() { playing = false }

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
                if (hit != null) notes.remove(hit)
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

    inner class Grid(c: Context) : View(c) {
        @Volatile var playStep = -1
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val lw = 220f
        override fun onDraw(cv: Canvas) {
            val rh = height / 7f; val cw = (width - lw) / 16f
            for (r in 0 until 7) {
                p.color = Color.WHITE; p.textSize = 36f
                cv.drawText(names[r] + if (samples[r] == null) " (+)" else "", 12f, r * rh + rh / 2 + 12, p)
                for (s in 0 until 16) {
                    p.color = if (pattern[r][s]) colors[r] else if (s / 4 % 2 == 0) 0xFF1A1F2B.toInt() else 0xFF141925.toInt()
                    cv.drawRoundRect(lw + s * cw + 4, r * rh + 4, lw + (s + 1) * cw - 4, (r + 1) * rh - 4, 10f, 10f, p)
                }
            }
            if (playStep >= 0) { p.color = Color.WHITE; cv.drawRect(lw + playStep * cw + cw / 2 - 2, 0f, lw + playStep * cw + cw / 2 + 2, height.toFloat(), p) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            val r = (e.y / (height / 7f)).toInt().coerceIn(0, 6)
            if (e.x < lw) { pendingRow = r; picker.launch(arrayOf("audio/*")) }
            else { val s = ((e.x - lw) / ((width - lw) / 16f)).toInt().coerceIn(0, 15); pattern[r][s] = !pattern[r][s]; invalidate(); save() }
            return true
        }
    }
}
