package com.echo1.manbandpro

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.content.pm.ActivityInfo
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
    fun show(v: View) { for (x in listOf<View>(grid, rollBox, tlBox, mixBox, projBox, edBox)) x.visibility = if (x === v) View.VISIBLE else View.GONE
        for ((view, b) in tabMap) b.setActive(view === v)
        if (v === projBox) refreshProjects()
        toolBarRef.visibility = if (v === tlBox || v === rollBox) View.VISIBLE else View.GONE
        if (v !== edBox) edStop() }
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

    // Make a short landscape phone screen behave like a ~400dp-tall screen so the whole UI fits.
    override fun attachBaseContext(base: Context) {
        val c = Configuration(base.resources.configuration)
        val m = base.resources.displayMetrics
        val target = (minOf(m.widthPixels, m.heightPixels) / 400f * 160f).toInt()
        if (c.densityDpi > target) c.densityDpi = target
        super.attachBaseContext(base.createConfigurationContext(c))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestedOrientation = orients[orientPref()]
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        store = ProjectStore(this)
        val last = getSharedPreferences("echo", 0).getString("last", null)
        proj = (last?.takeIf { it in store.listProjects() } ?: store.listProjects().firstOrNull())?.let { store.open(it).first } ?: store.create("My Beat")
        load()
        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply { text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            background = bg(0xFF4A4A4F.toInt(), dp(7).toFloat()); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = dp(36); setPadding(dp(12), 0, dp(12), 0); setOnClickListener { f(this) } }
        grid = Grid(this); roll = Roll(this); keys = Keys(this); tl = TL(this); headers = Headers(this); mixer = Mixer(this); leds = Leds(this)
        mixBox = HorizontalScrollView(this).apply { visibility = View.GONE; addView(mixer, FrameLayout.LayoutParams(-2, -1)) }
        projList = LinearLayout(this).gap().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        projBox = ScrollView(this).apply { visibility = View.GONE; addView(projList) }
        edTitle = TextView(this).apply { text = "Sample Editor: tap Source"; setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(8), dp(4), 0, dp(4)) }
        wave = Wave(this); edKnob = Knob(this, -24f, 24f, 0f, "GAIN", "dB") { edGainDb = it }
        val opsRow = HorizontalScrollView(this).apply { addView(LinearLayout(this@MainActivity).gap().apply { setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(mkBtn("Source") { edChoose() }); addView(mkBtn("\u25B6 Play") { edPlay() }); addView(mkBtn("\u25A0") { edStop() })
            addView(mkBtn("Trim") { edApply { d, a, b -> d.copyOfRange(a, b) } })
            addView(mkBtn("Cut") { edApply { d, a, b -> d.copyOfRange(0, a) + d.copyOfRange(b, d.size) } })
            addView(mkBtn("Reverse") { edApply { d, a, b -> val o = d.copyOf(); for (i in a until b) o[i] = d[b - 1 - (i - a)]; o } })
            addView(mkBtn("Normalize") { edApply { d, a, b -> var pk = 1; for (i in a until b) pk = maxOf(pk, Math.abs(d[i].toInt())); val k = 31000f / pk
                val o = d.copyOf(); for (i in a until b) o[i] = (d[i] * k).toInt().coerceIn(-32768, 32767).toShort(); o } })
            addView(mkBtn("Fade In") { edApply { d, a, b -> val o = d.copyOf(); for (i in a until b) o[i] = (d[i] * ((i - a) / (b - a).toFloat())).toInt().toShort(); o } })
            addView(mkBtn("Fade Out") { edApply { d, a, b -> val o = d.copyOf(); for (i in a until b) o[i] = (d[i] * ((b - 1 - i) / (b - a).toFloat())).toInt().toShort(); o } })
            addView(mkBtn("Apply Gain") { val k = 10f.pow(edGainDb / 20f); edApply { d, a, b -> val o = d.copyOf(); for (i in a until b) o[i] = (d[i] * k).toInt().coerceIn(-32768, 32767).toShort(); o }
                edGainDb = 0f; edKnob.value = 0f; edKnob.invalidate() })
            addView(mkBtn("Undo") { if (edUndo.isNotEmpty()) { edData = edUndo.removeAt(edUndo.size - 1); wave.selA = 0; wave.selB = 0; wave.invalidate() } })
            addView(mkBtn("Save") { edSave() }) }) }
        val edLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(edTitle); addView(wave, LinearLayout.LayoutParams(-1, 0, 1f)); addView(opsRow) }
        edBox = LinearLayout(this).apply { visibility = View.GONE; addView(edLeft, LinearLayout.LayoutParams(0, -1, 1f)); addView(edKnob, LinearLayout.LayoutParams(dp(170), -1)) }
        tlBox = LinearLayout(this).apply { visibility = View.GONE
            addView(headers, LinearLayout.LayoutParams(dp(230), -1))
            addView(HorizontalScrollView(this@MainActivity).apply { addView(tl, FrameLayout.LayoutParams(-2, -1)) }, LinearLayout.LayoutParams(0, -1, 1f)) }
        val scroll = HorizontalScrollView(this).apply { addView(roll, FrameLayout.LayoutParams(-2, -1)) }
        rollBox = LinearLayout(this).apply { visibility = View.GONE
            addView(keys, LinearLayout.LayoutParams(120, -1)); addView(scroll, LinearLayout.LayoutParams(0, -1, 1f)) }
        status = TextView(this).apply { setTextColor(0xFF22DD33.toInt()); textSize = 13f; setPadding(dp(10), 0, dp(10), 0) }
        bpmText = TextView(this).apply { text = "%.1f BPM".format(bpm); setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(6), 0, dp(6), 0) }
        val playAction: () -> Unit = { if (playing) { stopEngine(); recording = false } else startEngine() }
        playBtn = ib(0, cG, "Play / Stop") { playAction() }
        val stopBtn = ib(1, cW, "Stop") { stopEngine(); recording = false }
        recBtn = ib(2, cR, "Record") { if (recording) { recording = false; stopEngine() } else startRec() }
        fun bpmBtn(t: String, d: Int) = btn(t) { bpm = (bpm + d).coerceIn(40.0, 240.0); bpmText.text = "%.1f BPM".format(bpm); save() }
        val loopBtn = btn("1 - $loopBars") { loopBars = loopBars % 18 + 1; it.text = "1 - $loopBars"; roll.requestLayout(); tl.requestLayout(); tl.invalidate(); save() }
        loopBtnRef = loopBtn
        val loopSw = Switch(this).apply { isChecked = loopOn; setOnCheckedChangeListener { _, c -> loopOn = c } }
        val title = SpannableString("Echo 1 ManBand Pro").apply { setSpan(ForegroundColorSpan(0xFFFFA52A.toInt()), 15, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val topBar = LinearLayout(this).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)); setBackgroundColor(0xFF2F2F33.toInt())
            addView(ImageView(this@MainActivity).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(ib(3, cO, "Undo") { undo() }); addView(ib(4, cO, "Redo") { redo() }); addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(playBtn); addView(stopBtn); addView(recBtn)
            addView(LinearLayout(this@MainActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF2B2B2E.toInt(), dp(14).toFloat())
                addView(bpmBtn("\u2212", -1)); addView(bpmText); addView(bpmBtn("+", 1))
                addView(TextView(this@MainActivity).apply { text = "4/4"; setTextColor(Color.LTGRAY); setPadding(dp(8), 0, dp(10), 0) }) })
            addView(LinearLayout(this@MainActivity).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF2B2B2E.toInt(), dp(14).toFloat()); setPadding(dp(10), 0, dp(4), 0)
                addView(TextView(this@MainActivity).apply { text = "Loop:"; setTextColor(Color.WHITE) }); addView(loopSw); addView(loopBtn) })
            addView(ib(5, cW, "Settings") { AlertDialog.Builder(this@MainActivity).setTitle("Settings")
                .setItems(arrayOf("Metronome: " + (if (metroOn) "On" else "Off"), "Count-in before recording: $countBars bar(s)", "Screen orientation: " + orientName())) { _, w -> if (w == 0) metroOn = !metroOn else if (w == 1) countBars = (countBars + 1) % 3 else cycleOrientation(); save() }.show() }) }
        val tabRow = LinearLayout(this).gap().apply { setPadding(dp(8), dp(4), dp(8), dp(4))
            fun tab(id: Int, label: String, v: View) { val b = ib(id, cW, label, null, label) { show(v) }; tabMap[v] = b; addView(b) }
            tab(14, "Timeline", tlBox); tab(12, "Sequencer", grid); tab(13, "Piano Roll", rollBox); tab(15, "Mixer", mixBox); tab(16, "Editor", edBox); tab(17, "Projects", projBox) }
        val lenIco = ib(-1, cB, "Note length (steps)", "$noteLen") { }
        lenIco.setOnClickListener { noteLen = when (noteLen) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }; lenIco.setText("$noteLen") }
        val toolBar = HorizontalScrollView(this).apply { setBackgroundColor(0xFF2F2F33.toInt())
            addView(LinearLayout(this@MainActivity).gap().apply { setPadding(dp(8), dp(4), dp(8), dp(4))
                addView(ib(8, cG, "Add track") { addTrack() }); addView(ib(6, cW, "Split at marker") { splitSelected() }); addView(ib(7, cW, "Delete selected") { deleteSelected() })
                addView(lenIco)
                addView(ib(11, cB, "Octave down") { basePitch = maxOf(24, basePitch - 12); keys.invalidate(); roll.invalidate() })
                addView(ib(10, cB, "Octave up") { basePitch = minOf(72, basePitch + 12); keys.invalidate(); roll.invalidate() }) }) }
        toolBarRef = toolBar
        val swingText = TextView(this).apply { text = "Swing ${(swing * 100).toInt()}%"; setTextColor(Color.WHITE); setPadding(dp(8), 0, dp(8), 0) }
        swingRef = swingText
        fun swingBtn(t: String, d: Int) = btn(t) { swing = (Math.round(swing * 100) + d).coerceIn(0, 50) / 100f; swingText.text = "Swing ${(swing * 100).toInt()}%"; save() }
        val bottom = LinearLayout(this).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(0xFF2F2F33.toInt())
            addView(ib(17, cO, "Projects") { show(projBox) })
            addView(TextView(this@MainActivity).apply { text = "${proj.name}.emb"; setTextColor(Color.WHITE); background = bg(0xFF4A4A4F.toInt(), dp(7).toFloat()); setPadding(dp(10), dp(6), dp(10), dp(6)) }.also { chip = it })
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ib(18, cW, "Back to start") { startStep = 0; tl.invalidate() }); addView(ib(0, cG, "Play / Stop") { playAction() }); addView(ib(1, cW, "Stop") { stopEngine(); recording = false })
            addView(ib(19, cW, "Next bar") { startStep = minOf((startStep / 16 + 1) * 16, loopBars * 16 - 4); tl.invalidate() })
            addView(LinearLayout(this@MainActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF2B2B2E.toInt(), dp(10).toFloat())
                addView(swingText); addView(swingBtn("\u2212", -5)); addView(swingBtn("+", 5)) })
            addView(leds, LinearLayout.LayoutParams(dp(90), dp(32)))
            addView(ib(15, cW, "Mixer") { show(mixBox) }) }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF232326.toInt())
            addView(hs(topBar)); addView(hs(tabRow))
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f)); addView(rollBox, LinearLayout.LayoutParams(-1, 0, 1f)); addView(tlBox, LinearLayout.LayoutParams(-1, 0, 1f)); addView(mixBox, LinearLayout.LayoutParams(-1, 0, 1f)); addView(projBox, LinearLayout.LayoutParams(-1, 0, 1f)); addView(edBox, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(toolBar); addView(hs(bottom)) })
        show(tlBox)
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
            runOnUiThread { status.text = "\u2713 Saved $t" } }
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
        val outN = (n.toLong() * 44100 / rate).toInt()
        return ShortArray(outN) { k -> i16(o + 8 + (k.toLong() * rate / 44100).toInt().coerceAtMost(n - 1) * 2 * ch).toShort() }
    }

    fun startEngine() {
        playing = true
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sr = 44100
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 35280), AudioTrack.MODE_STREAM)
            at.play(); val pos = IntArray(7) { -1 }; var step = startStep; val voices = ArrayList<V>(); val rvs = ArrayList<RV>(); var bufArr = ShortArray(8192)
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
                if (bufArr.size < spf * 2) bufArr = ShortArray(spf * 2); val buf = bufArr; var pkL = 0f; var pkR = 0f
                for (i in 0 until spf) {
                    var sq = 0; var kq = 0; var oL = 0f; var oR = 0f
                    for (j in 0 until nT) tsum[j] = 0f
                    for (r in 0 until 7) { val d = samples[r]; if (d != null && pos[r] >= 0) { sq += d[pos[r]++]; if (pos[r] >= d.size) pos[r] = -1 } }
                    if (ms != null) { var vi = 0; while (vi < voices.size) { val v = voices[vi]; val k = v.p.toInt()
                        if (k >= ms.size - 1 || v.left <= 0) { voices.removeAt(vi); continue }
                        kq += (ms[k] * 0.7).toInt(); v.p += v.rate; v.left--; vi++ } }
                    if (rvs.isNotEmpty()) { var ri = 0; while (ri < rvs.size) { val v = rvs[ri]
                        if (v.pos >= v.d.size || v.left <= 0) { rvs.removeAt(ri); continue }
                        if (v.ti >= 0) tsum[v.ti] += v.d[v.pos] * 0.7f; v.pos++; v.left--; ri++ } }
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
    val tabMap = LinkedHashMap<View, Ico>()
    val cO = 0xFFFFA020.toInt(); val cG = 0xFF3FD04E.toInt(); val cW = 0xFFE8E8E8.toInt(); val cB = 0xFF4DA3FF.toInt(); val cR = 0xFFFF3B4E.toInt()
    val tcols = intArrayOf(0xFF6AA0E0.toInt(), 0xFFD1A62A.toInt(), 0xFF3FA845.toInt(), 0xFF9B59D0.toInt(), 0xFFE05A5A.toInt(), 0xFFE08A3A.toInt(), 0xFF3FBFBF.toInt(), 0xFFD05AA0.toInt())
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
    lateinit var playBtn: View
    lateinit var recBtn: Ico
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
        recording = true; recBtn.setAlt(true)
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
            runOnUiThread { recBtn.setAlt(false); playBtn.contentDescription = if (playing) "Stop" else "Play"; tl.invalidate() }
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
        background = bg(0xFF505055.toInt(), dp(12).toFloat()); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = dp(40); setPadding(dp(12), 0, dp(12), 0); setOnClickListener { f(this) } }

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
            val row = LinearLayout(this).gap().apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = bg(0xFF2B2B2E.toInt(), dp(14).toFloat()); setPadding(dp(14), dp(10), dp(10), dp(10)) }
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

    var edData: ShortArray? = null
    var edName = ""
    var edGainDb = 0f
    val edUndo = ArrayList<ShortArray>()
    var edTrack: AudioTrack? = null
    lateinit var edBox: LinearLayout
    lateinit var edTitle: TextView
    lateinit var wave: Wave
    lateinit var edKnob: Knob
    lateinit var toolBarRef: View

    // selection (or whole sample when nothing is selected) as [start, end)
    fun edSel(): IntArray {
        val d = edData ?: return intArrayOf(0, 0)
        val a = minOf(wave.selA, wave.selB).coerceIn(0, d.size); val b = maxOf(wave.selA, wave.selB).coerceIn(0, d.size)
        return if (b - a > 100) intArrayOf(a, b) else intArrayOf(0, d.size)
    }

    fun edApply(op: (ShortArray, Int, Int) -> ShortArray) {
        val d = edData ?: return; val s = edSel()
        val out = op(d, s[0], s[1])
        if (out.isEmpty()) { toast("That would leave nothing"); return }
        edUndo.add(d); if (edUndo.size > 20) edUndo.removeAt(0)
        edData = out; wave.selA = 0; wave.selB = 0; wave.invalidate()
    }

    fun edChoose() {
        val list = proj.samples.map { it.path }
        if (list.isEmpty()) { toast("Import a sample first (tap a sequencer row name)"); return }
        AlertDialog.Builder(this).setTitle("Choose sample").setItems(list.toTypedArray()) { _, i ->
            try { edData = decodeWav(store.resolve(proj, list[i])); edName = list[i].substringAfterLast('/').substringBeforeLast('.')
                edUndo.clear(); wave.selA = 0; wave.selB = 0; edTitle.text = edName; wave.invalidate() }
            catch (e: Exception) { toast("Cannot read that file: ${e.message}") } }.show()
    }

    fun edStop() { edTrack?.let { try { it.stop() } catch (e: Exception) { }; it.release() }; edTrack = null }

    fun edPlay() {
        edStop(); val d = edData ?: return; val s = edSel(); val g = 10f.pow(edGainDb / 20f)
        val seg = ShortArray(s[1] - s[0]) { i -> (d[s[0] + i] * g).toInt().coerceIn(-32768, 32767).toShort() }
        if (seg.isEmpty()) return
        val at = AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, seg.size * 2, AudioTrack.MODE_STATIC)
        at.write(seg, 0, seg.size); at.play(); edTrack = at
    }

    // Writes the edit to "Edited Samples/" (the original is untouched), then offers to use it on a sequencer row.
    fun edSave() {
        val d = edData ?: return; val n = d.size * 2
        val body = ByteArray(n); val bb = java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN); for (v in d) bb.putShort(v)
        val wav = wavHeader(n) + body
        io.execute {
            try {
                val ref = store.saveEdited(proj, wav, "$edName edit.wav"); cache[ref.path] = d; save()
                runOnUiThread { AlertDialog.Builder(this).setTitle("Saved: ${ref.path}")
                    .setItems(Array(8) { i -> if (i < 7) "Use for ${names[i]} row" else "Don't assign" }) { _, i ->
                        if (i < 7) { pushUndo(); samples[i] = d; paths[i] = ref.path; grid.invalidate(); save() } }.show() }
            } catch (e: Exception) { runOnUiThread { toast("Save failed: ${e.message}") } }
        }
    }

    val orients = intArrayOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
    // phones default to landscape, tablets to auto-rotate
    fun orientPref() = getSharedPreferences("echo", 0).getInt("orient", if (resources.configuration.smallestScreenWidthDp < 600) 1 else 0)
    fun orientName() = arrayOf("Auto", "Landscape", "Portrait")[orientPref()]
    fun cycleOrientation() { val n = (orientPref() + 1) % 3; getSharedPreferences("echo", 0).edit().putInt("orient", n).apply(); requestedOrientation = orients[n] }
    // bars scroll sideways instead of clipping on narrow screens; weights still fill wide screens
    fun hs(v: View) = HorizontalScrollView(this).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(v) }

    fun ib(id: Int, tint: Int, tip: String, text: String? = null, tabLabel: String? = null, f: () -> Unit): Ico {
        val v = Ico(this, id, tint, text, tabLabel)
        v.contentDescription = tip
        v.setOnClickListener { f() }
        v.setOnLongClickListener { Toast.makeText(this, tip, Toast.LENGTH_SHORT).show(); true }
        return v
    }

    fun spf() = (44100 * 60 / bpm / 4).toInt()

    fun loadTracks() {
        proj.sections.optJSONArray("tracks")?.let { a -> for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val t = Track(o.getString("name"), tcols[i % 8], o.optDouble("vol", 0.8).toFloat(), o.optBoolean("mute"), o.optBoolean("solo"))
            t.pan = o.optDouble("pan", 0.0).toFloat(); o.optJSONObject("fx")?.let { t.fx.load(it) }; val rs = o.getJSONArray("regions")
            for (j in 0 until rs.length()) { val r = rs.getJSONArray(j); val path = r.getString(3)
                try { cache[path] = decodeWav(store.resolve(proj, path)) } catch (e: Exception) { }
                t.regions.add(Region(r.getInt(0), r.getInt(1), path, r.getInt(2))) }
            tracks.add(t) } }
        proj.sections.optJSONArray("metro")?.let { metroOn = it.getBoolean(0); countBars = it.getInt(1) }
        proj.sections.optJSONObject("mixer")?.let { m -> masterVol = m.optDouble("master", 0.8).toFloat(); m.optJSONObject("seqFx")?.let { seqCh.fx.load(it) }; m.optJSONObject("keysFx")?.let { keysCh.fx.load(it) }
            for ((c, k) in listOf(seqCh to "seq", keysCh to "keys")) m.optJSONArray(k)?.let { a -> c.vol = a.getDouble(0).toFloat(); c.pan = a.getDouble(1).toFloat(); c.mute = a.getBoolean(2); c.solo = a.getBoolean(3) } }
        if (tracks.isEmpty()) for (i in 1..4) tracks.add(Track("Audio $i", tcols[(i - 1) % 8]))
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

    fun addTrack() { pushUndo(); tracks.add(Track("Audio ${tracks.size + 1}", tcols[tracks.size % 8])); headers.invalidate(); tl.invalidate(); save() }

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
        var zone = 0; var zi = -1; var moved = false; var y0 = 0f; var pan0 = 0f
        fun rowH() = (height - RH) / maxOf(4, tracks.size)
        fun setVol(x: Float) {
            val t = tracks.getOrNull(zi) ?: return; val sx0 = dp(116).toFloat(); val sx1 = width - dp(44).toFloat()
            t.vol = ((x - sx0) / (sx1 - sx0) * 1.25f).coerceIn(0f, 1.25f); invalidate()
        }
        override fun onDraw(cv: Canvas) {
            val rh = rowH(); val w = width.toFloat(); val cr = dp(4).toFloat()
            p.style = Paint.Style.FILL; p.color = 0xFF3A3A3E.toInt(); cv.drawRect(0f, 0f, w, RH, p)
            for ((i, t) in tracks.withIndex()) {
                val y = RH + i * rh
                p.color = if (i % 2 == 0) 0xFF48484C.toInt() else 0xFF414145.toInt(); cv.drawRect(0f, y, w, y + rh - 1, p)
                p.color = t.color; cv.drawRect(0f, y, dp(20).toFloat(), y + rh - 1, p)
                p.color = Color.WHITE; p.textSize = dp(11).toFloat(); p.textAlign = Paint.Align.CENTER; cv.drawText("${i + 1}", dp(10).toFloat(), y + rh / 2 + dp(4), p)
                p.textAlign = Paint.Align.LEFT; p.textSize = dp(13).toFloat(); p.color = 0xFFEDEDED.toInt(); cv.drawText(t.name, dp(28).toFloat(), y + rh * 0.38f, p)
                val cy = y + rh * 0.74f; val bh = dp(10).toFloat()
                fun box(x0: Int, label: String, on: Boolean, onCol: Int) {
                    p.color = if (on) onCol else 0xFF5A5A5F.toInt(); cv.drawRoundRect(dp(x0).toFloat(), cy - bh, dp(x0 + 24).toFloat(), cy + bh, cr, cr, p)
                    p.color = if (on) Color.BLACK else 0xFFDDDDDD.toInt(); p.textSize = dp(10).toFloat(); p.textAlign = Paint.Align.CENTER
                    cv.drawText(label, dp(x0 + 12).toFloat(), cy + dp(4), p); p.textAlign = Paint.Align.LEFT
                }
                box(28, "M", t.mute, 0xFFFFB020.toInt()); box(56, "S", t.solo, 0xFFFFE04A.toInt()); box(84, "R", armTrack == i, 0xFFFF3B4E.toInt())
                val sx0 = dp(116).toFloat(); val sx1 = w - dp(44); val kx = sx0 + (sx1 - sx0) * (t.vol / 1.25f)
                p.color = 0xFF2A2A2D.toInt(); cv.drawRoundRect(sx0, cy - dp(3), sx1, cy + dp(3), cr, cr, p)
                p.color = 0xFF3FD04E.toInt(); cv.drawRoundRect(sx0, cy - dp(3), kx, cy + dp(3), cr, cr, p)
                p.color = 0xFFD8D8D8.toInt(); cv.drawCircle(kx, cy, dp(7).toFloat(), p)
                val px = w - dp(22); p.color = 0xFF2A2A2D.toInt(); cv.drawCircle(px, cy, dp(11).toFloat(), p)
                val a = Math.toRadians((270 + t.pan * 135).toDouble()); p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE
                cv.drawLine(px, cy, px + (Math.cos(a) * dp(9)).toFloat(), cy + (Math.sin(a) * dp(9)).toFloat(), p); p.style = Paint.Style.FILL
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val rh = rowH()
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (e.y < RH) return true
                    zi = ((e.y - RH) / rh).toInt(); if (zi >= tracks.size) { zi = -1; return true }
                    val ly = e.y - RH - zi * rh; val x = e.x; moved = false; y0 = e.y; pan0 = tracks[zi].pan
                    zone = when { ly < rh * 0.5f -> 1; x < dp(28) -> 0; x < dp(52) -> 2; x < dp(80) -> 3; x < dp(108) -> 4; x > width - dp(44) -> 6; x >= dp(112) -> 5; else -> 0 }
                    if (zone == 5) setVol(x)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (zi < 0) return true
                    if (Math.abs(e.y - y0) > dp(4)) moved = true
                    if (zone == 5) setVol(e.x)
                    if (zone == 6) { tracks[zi].pan = (pan0 + (y0 - e.y) / dp(60)).coerceIn(-1f, 1f); invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    if (zi < 0) return true
                    val t = tracks[zi]
                    when (zone) { 1 -> if (!moved) { pendingTrack = zi; tlPicker.launch(arrayOf("audio/*")) }; 2 -> t.mute = !t.mute; 3 -> t.solo = !t.solo; 4 -> armTrack = zi }
                    invalidate(); tl.invalidate(); mixer.invalidate(); save(); zi = -1
                }
            }
            return true
        }
    }

    inner class TL(c: Context) : View(c) {
        @Volatile var playStep = -1
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val cw = dp(9).toFloat()
        var drag: Region? = null; var grab = 0f; var moved = false; var mode = 0; var o0 = 0; var l0 = 0; var f0 = 0
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension((loopBars * 16 * cw).toInt(), MeasureSpec.getSize(h)) }
        override fun onDraw(cv: Canvas) {
            val rh = (height - RH) / maxOf(4, tracks.size); val w = width.toFloat(); val hh = height.toFloat(); val spfv = spf()
            p.style = Paint.Style.FILL; p.color = 0xFF2B2B2E.toInt(); cv.drawRect(0f, 0f, w, hh, p)
            for (i in tracks.indices) { p.color = if (i % 2 == 0) 0xFF333336.toInt() else 0xFF2D2D30.toInt(); cv.drawRect(0f, RH + i * rh, w, RH + (i + 1) * rh - 1, p) }
            p.color = 0xFF3A3A3E.toInt(); cv.drawRect(0f, 0f, w, RH, p)
            p.textSize = dp(12).toFloat()
            for (st in 0..loopBars * 16 step 4) { val bar = st % 16 == 0
                p.color = if (bar) 0xFF8A8A90.toInt() else 0xFF404044.toInt(); cv.drawRect(st * cw, if (bar) 0f else RH, st * cw + 1, hh, p)
                if (bar) { p.color = Color.WHITE; cv.drawText("${st / 16 + 1}", st * cw + dp(4), RH - dp(8), p) } }
            for ((i, t) in tracks.withIndex()) for (g in t.regions) {
                val y = RH + i * rh; val l = g.start * cw + 1; val r = (g.start + g.len) * cw - 1; val top = y + 3; val bot = y + rh - 4
                p.color = if (t.mute) 0xFF5A5A60.toInt() else t.color; cv.drawRoundRect(l, top, r, bot, 5f, 5f, p)
                p.color = 0x33000000; cv.drawRect(l, top, r, top + dp(12), p)
                cv.save(); cv.clipRect(l, top, r, bot)
                p.color = Color.WHITE; p.textSize = dp(10).toFloat(); cv.drawText(g.path.substringAfterLast('/').substringBeforeLast('.'), l + dp(4), top + dp(10), p)
                val d = cache[g.path]
                if (d != null) {
                    val mid = (top + dp(12) + bot) / 2; val half = (bot - top - dp(12)) / 2
                    p.color = 0xCCFFFFFF.toInt(); p.strokeWidth = 1f
                    var x = l
                    while (x < r) {
                        val f0 = g.off + ((x - l) / cw * spfv).toInt(); val f1 = minOf(g.off + ((x + 2 - l) / cw * spfv).toInt(), d.size)
                        if (f0 >= d.size) break
                        var mn = 0; var mx = 0; val stp = maxOf(1, (f1 - f0) / 12); var k = f0
                        while (k < f1) { val v = d[k].toInt(); if (v < mn) mn = v; if (v > mx) mx = v; k += stp }
                        cv.drawLine(x, mid - mx / 32768f * half, x, mid - mn / 32768f * half + 1, p); x += 2
                    }
                }
                cv.restore()
                if (g === selected) { p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE; cv.drawRoundRect(l, top, r, bot, 5f, 5f, p); p.style = Paint.Style.FILL
                    cv.drawRect(l + 2, y + rh * 0.3f, l + 7, y + rh * 0.7f, p); cv.drawRect(r - 7, y + rh * 0.3f, r - 2, y + rh * 0.7f, p) }
            }
            val sx = startStep * cw; p.color = 0x66FFFFFF; cv.drawRect(sx, 0f, sx + 1, hh, p); p.color = Color.WHITE
            val tri = Path(); tri.moveTo(sx - dp(5), 0f); tri.lineTo(sx + dp(5), 0f); tri.lineTo(sx, dp(9).toFloat()); tri.close(); cv.drawPath(tri, p)
            if (playStep >= 0) { p.color = Color.WHITE; cv.drawRect(playStep * cw, 0f, playStep * cw + 2, hh, p) }
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
                p.color = if (i % 2 == 0) 0xFF3A3A3E.toInt() else 0xFF2B2B2E.toInt(); cv.drawRect(x, 0f, x + sw - 2, hh, p)
                p.textSize = 26f; p.color = Color.WHITE; cv.drawText(if (master) "Master" else cs[i].name, x + 12, 40f, p)
                if (!master && cs[i].fx.isOn()) { p.color = 0xFF22DD33.toInt(); cv.drawText("FX", x + sw - 52, 40f, p) }
                val vol = if (master) masterVol else cs[i].vol
                if (!master) { p.color = 0xFF3A4156.toInt(); cv.drawRect(x + 20, 82f, x + sw - 22, 94f, p)
                    p.color = 0xFFFFB020.toInt(); cv.drawCircle(x + 20 + (cs[i].pan + 1) / 2 * (sw - 42), 88f, 16f, p) }
                p.color = 0xFF505055.toInt(); cv.drawRect(x + sw / 2 - 6, ft, x + sw / 2 + 6, fb, p)
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

    inner class Wave(c: Context) : View(c) {
        var selA = 0; var selB = 0; var drag = 0
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun fx(f: Int): Float { val d = edData ?: return 0f; return f * width.toFloat() / d.size }
        override fun onDraw(cv: Canvas) {
            p.style = Paint.Style.FILL; p.color = 0xFF2F2F33.toInt(); cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            val d = edData
            if (d == null || d.isEmpty()) { p.color = Color.GRAY; p.textSize = dp(14).toFloat(); cv.drawText("Tap Source to choose a sample", dp(12).toFloat(), height / 2f, p); return }
            val mid = height / 2f; p.color = 0xFF505055.toInt(); cv.drawRect(0f, mid - 1, width.toFloat(), mid + 1, p)
            p.color = 0xFF18C8F0.toInt(); p.strokeWidth = 1.5f
            for (x in 0 until width) {
                val i0 = (x.toLong() * d.size / width).toInt(); val i1 = maxOf(i0 + 1, ((x + 1).toLong() * d.size / width).toInt()).coerceAtMost(d.size)
                var mn = 32767; var mx = -32768; val st = maxOf(1, (i1 - i0) / 32); var i = i0
                while (i < i1) { val v = d[i].toInt(); if (v < mn) mn = v; if (v > mx) mx = v; i += st }
                if (mx >= mn) cv.drawLine(x.toFloat(), mid - mx / 32768f * mid, x.toFloat(), mid - mn / 32768f * mid, p)
            }
            if (selB != selA) { val a = fx(minOf(selA, selB)); val b = fx(maxOf(selA, selB))
                p.color = 0x3318C8F0; cv.drawRect(a, 0f, b, height.toFloat(), p); p.color = Color.WHITE; cv.drawRect(a, 0f, a + 3, height.toFloat(), p); cv.drawRect(b - 3, 0f, b, height.toFloat(), p) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val d = edData ?: return true
            val f = (e.x.coerceIn(0f, width - 1f) * d.size / width).toInt()
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { drag = if (selB != selA && Math.abs(e.x - fx(selA)) < dp(24)) 1 else if (selB != selA && Math.abs(e.x - fx(selB)) < dp(24)) 2 else 3
                    if (drag == 3) { selA = f; selB = f } }
                MotionEvent.ACTION_MOVE -> { if (drag == 1) selA = f else selB = f }
                MotionEvent.ACTION_UP -> { if (selA > selB) { val t = selA; selA = selB; selB = t }; drag = 0 }
            }
            invalidate(); return true
        }
    }

    // Rotary knob: glowing arc from centre (blue below, orange above), tick ring, brushed-metal cap. Drag up/down to turn, double-tap to reset.
    inner class Knob(c: Context, val lo: Float, val hi: Float, val def: Float, val label: String, val unit: String, val onChange: (Float) -> Unit) : View(c) {
        var value = def
        val p = Paint(Paint.ANTI_ALIAS_FLAG); var y0 = 0f; var v0 = 0f
        val gd = android.view.GestureDetector(c, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { value = def; onChange(value); invalidate(); return true } })
        fun ang(v: Float) = 135f + 270f * (v - lo) / (hi - lo)
        override fun onTouchEvent(e: MotionEvent): Boolean {
            gd.onTouchEvent(e)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { y0 = e.y; v0 = value; parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> { value = (v0 + (y0 - e.y) / dp(260) * (hi - lo)).coerceIn(lo, hi); onChange(value); invalidate() }
            }
            return true
        }
        override fun onDraw(cv: Canvas) {
            val txt = dp(34).toFloat(); val cx = width / 2f; val cy = (height - txt) / 2f; val R = minOf(width / 2f, cy) - dp(2)
            val zero = if (lo < 0f && hi > 0f) 0f else lo
            p.style = Paint.Style.FILL; p.shader = null; p.textAlign = Paint.Align.CENTER; p.textSize = R * 0.11f
            for (k in 0..16) { val v = lo + k * (hi - lo) / 16f; val a = Math.toRadians(ang(v).toDouble()); val major = k % 2 == 0
                val lit = if (value >= zero) v in zero..value else v in value..zero
                val base = if (v > zero) 0xFFFFA020.toInt() else if (v < zero) 0xFF3AA0FF.toInt() else Color.WHITE
                p.color = if (lit) base else (base and 0x00FFFFFF) or 0x66000000
                p.style = Paint.Style.STROKE; p.strokeWidth = if (major) R * 0.035f else R * 0.02f; p.strokeCap = Paint.Cap.ROUND
                val r1 = if (major) R * 0.66f else R * 0.70f; val r2 = R * 0.78f
                cv.drawLine(cx + (Math.cos(a) * r1).toFloat(), cy + (Math.sin(a) * r1).toFloat(), cx + (Math.cos(a) * r2).toFloat(), cy + (Math.sin(a) * r2).toFloat(), p)
                if (major) { p.style = Paint.Style.FILL; p.color = 0xFFB0B6C2.toInt()
                    cv.drawText(if (v > 0) "+${v.toInt()}" else "${v.toInt()}", cx + (Math.cos(a) * R * 0.92).toFloat(), cy + (Math.sin(a) * R * 0.92).toFloat() + R * 0.04f, p) } }
            val ra = R * 0.58f; val oval = RectF(cx - ra, cy - ra, cx + ra, cy + ra)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = R * 0.03f; p.color = 0xFF1A2230.toInt(); cv.drawArc(oval, 135f, 270f, false, p)
            val glow = if (value >= zero) 0xFFFFA020.toInt() else 0xFF3AA0FF.toInt(); val sweep = ang(value) - ang(zero)
            p.strokeWidth = R * 0.08f; p.color = (glow and 0x00FFFFFF) or 0x33000000; cv.drawArc(oval, ang(zero), sweep, false, p)
            p.strokeWidth = R * 0.03f; p.color = glow; cv.drawArc(oval, ang(zero), sweep, false, p)
            p.style = Paint.Style.FILL; p.color = 0xFF0C0D0F.toInt(); cv.drawCircle(cx, cy, R * 0.54f, p)
            p.shader = RadialGradient(cx - R * 0.12f, cy - R * 0.18f, R * 0.5f, intArrayOf(0xFF4A4E55.toInt(), 0xFF15171A.toInt()), null, Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, R * 0.47f, p)
            p.shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF, 0x2AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.2f, 0.5f, 0.75f, 1f))
            cv.drawCircle(cx, cy, R * 0.47f, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF6A6E75.toInt(); cv.drawCircle(cx, cy, R * 0.47f, p)
            val a = Math.toRadians(ang(value).toDouble()); p.color = Color.WHITE; p.strokeWidth = R * 0.045f
            cv.drawLine(cx + (Math.cos(a) * R * 0.2).toFloat(), cy + (Math.sin(a) * R * 0.2).toFloat(), cx + (Math.cos(a) * R * 0.4).toFloat(), cy + (Math.sin(a) * R * 0.4).toFloat(), p)
            p.style = Paint.Style.FILL; p.color = 0xFFB0B6C2.toInt(); p.textSize = dp(15).toFloat(); cv.drawText(label, cx, cy + R + dp(14), p)
            p.color = Color.GRAY; p.textSize = dp(11).toFloat(); cv.drawText("%+.1f %s".format(value, unit), cx, cy + R + dp(29), p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    // Glossy tile button with a glyph drawn in code (no image files). Tabs show their label only while active.
    inner class Ico(c: Context, val id: Int, val tint: Int, var text: String?, val tabLabel: String?) : View(c) {
        var active = false; var alt = false
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun setActive(a: Boolean) { active = a; requestLayout(); invalidate() }
        fun setAlt(a: Boolean) { alt = a; invalidate() }
        fun setText(t: String) { text = t; invalidate() }
        override fun onMeasure(w: Int, h: Int) {
            p.textSize = dp(12).toFloat()
            val extra = if (tabLabel != null && active) p.measureText(tabLabel) + dp(8) else 0f
            setMeasuredDimension((dp(38) + extra).toInt(), dp(38))
        }
        fun tri(cv: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val q = Path(); q.moveTo(x1, y1); q.lineTo(x2, y2); q.lineTo(x3, y3); q.close(); cv.drawPath(q, p)
        }
        override fun onDraw(cv: Canvas) {
            val rr = dp(8).toFloat(); val s = dp(38).toFloat()
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), if (active) intArrayOf(0xFF4F93F0.toInt(), 0xFF2A62B8.toInt()) else intArrayOf(0xFF5A5A60.toInt(), 0xFF333337.toInt()), null, Shader.TileMode.CLAMP)
            cv.drawRoundRect(1f, 1f, width - 1f, height - 1f, rr, rr, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x33FFFFFF; cv.drawRoundRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, rr, rr, p)
            val cx = s / 2; val cy = height / 2f; val r = s * 0.28f
            p.color = if (active) Color.WHITE else tint; p.strokeWidth = r * 0.22f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            fun fill() { p.style = Paint.Style.FILL }; fun line() { p.style = Paint.Style.STROKE }
            if (id == -1) { fill(); p.textSize = r * 1.3f; p.textAlign = Paint.Align.CENTER; cv.drawText(text ?: "", cx, cy + r * 0.45f, p); p.textAlign = Paint.Align.LEFT }
            else when (id) {
                0 -> { fill(); tri(cv, cx - r * 0.5f, cy - r, cx - r * 0.5f, cy + r, cx + r, cy) }
                1 -> { fill(); cv.drawRoundRect(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, 4f, 4f, p) }
                2 -> { fill(); if (alt) cv.drawRoundRect(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, 4f, 4f, p) else cv.drawCircle(cx, cy, r * 0.9f, p) }
                3, 4 -> { cv.save(); if (id == 4) cv.scale(-1f, 1f, cx, cy)
                    line(); cv.drawArc(RectF(cx - r, cy - r * 0.6f, cx + r, cy + r * 1.2f), 180f, 200f, false, p)
                    fill(); tri(cv, cx - r * 1.4f, cy + r * 0.3f, cx - r * 0.6f, cy + r * 0.3f, cx - r, cy - r * 0.4f); cv.restore() }
                5 -> { line(); cv.drawCircle(cx, cy, r * 0.5f, p); for (k in 0..7) { val a = Math.PI / 4 * k
                    cv.drawLine(cx + (Math.cos(a) * r * 0.75f).toFloat(), cy + (Math.sin(a) * r * 0.75f).toFloat(), cx + (Math.cos(a) * r * 1.05f).toFloat(), cy + (Math.sin(a) * r * 1.05f).toFloat(), p) } }
                6 -> { line(); cv.drawLine(cx - r, cy - r, cx + r * 0.5f, cy + r * 0.6f, p); cv.drawLine(cx + r, cy - r, cx - r * 0.5f, cy + r * 0.6f, p)
                    cv.drawCircle(cx - r * 0.55f, cy + r * 0.75f, r * 0.3f, p); cv.drawCircle(cx + r * 0.55f, cy + r * 0.75f, r * 0.3f, p) }
                7 -> { line(); cv.drawLine(cx - r, cy - r * 0.55f, cx + r, cy - r * 0.55f, p); cv.drawLine(cx - r * 0.3f, cy - r * 0.9f, cx + r * 0.3f, cy - r * 0.9f, p)
                    cv.drawRect(cx - r * 0.7f, cy - r * 0.55f, cx + r * 0.7f, cy + r, p) }
                8 -> { line(); cv.drawLine(cx - r, cy, cx + r, cy, p); cv.drawLine(cx, cy - r, cx, cy + r, p) }
                9 -> { fill(); cv.drawCircle(cx - r * 0.35f, cy + r * 0.6f, r * 0.45f, p); line(); cv.drawLine(cx + r * 0.05f, cy + r * 0.55f, cx + r * 0.05f, cy - r, p); cv.drawLine(cx + r * 0.05f, cy - r, cx + r * 0.85f, cy - r * 0.3f, p) }
                10 -> { fill(); tri(cv, cx - r, cy + r * 0.2f, cx + r, cy + r * 0.2f, cx, cy - r); cv.drawRect(cx - r * 0.3f, cy + r * 0.2f, cx + r * 0.3f, cy + r, p) }
                11 -> { fill(); tri(cv, cx - r, cy - r * 0.2f, cx + r, cy - r * 0.2f, cx, cy + r); cv.drawRect(cx - r * 0.3f, cy - r, cx + r * 0.3f, cy - r * 0.2f, p) }
                12 -> { fill(); for (i in -1..1) for (j in -1..1) cv.drawRoundRect(cx + i * r * 0.75f - r * 0.27f, cy + j * r * 0.75f - r * 0.27f, cx + i * r * 0.75f + r * 0.27f, cy + j * r * 0.75f + r * 0.27f, 2f, 2f, p) }
                13 -> { line(); cv.drawRect(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f, p); for (k in -1..1) cv.drawLine(cx + k * r * 0.66f, cy - r * 0.8f, cx + k * r * 0.66f, cy + r * 0.8f, p)
                    fill(); for (k in intArrayOf(-1, 1)) cv.drawRect(cx + k * r * 0.33f - r * 0.18f, cy - r * 0.8f, cx + k * r * 0.33f + r * 0.18f, cy + r * 0.1f, p) }
                14 -> { fill(); cv.drawRoundRect(cx - r, cy - r * 0.9f, cx + r * 0.3f, cy - r * 0.4f, 3f, 3f, p); cv.drawRoundRect(cx - r * 0.5f, cy - r * 0.25f, cx + r, cy + r * 0.25f, 3f, 3f, p); cv.drawRoundRect(cx - r, cy + r * 0.4f, cx + r * 0.6f, cy + r * 0.9f, 3f, 3f, p) }
                15 -> { line(); for (k in -1..1) cv.drawLine(cx + k * r * 0.75f, cy - r, cx + k * r * 0.75f, cy + r, p)
                    fill(); cv.drawCircle(cx - r * 0.75f, cy - r * 0.3f, r * 0.3f, p); cv.drawCircle(cx, cy + r * 0.4f, r * 0.3f, p); cv.drawCircle(cx + r * 0.75f, cy - r * 0.1f, r * 0.3f, p) }
                16 -> { line(); val hs = floatArrayOf(0.3f, 0.7f, 0.4f, 1f, 0.5f, 0.8f, 0.3f); for (k in 0..6) cv.drawLine(cx + (k - 3) * r * 0.33f, cy - r * hs[k], cx + (k - 3) * r * 0.33f, cy + r * hs[k], p) }
                17 -> { fill(); cv.drawRoundRect(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.8f, 4f, 4f, p); cv.drawRect(cx - r, cy - r * 0.85f, cx - r * 0.1f, cy - r * 0.5f, p) }
                18 -> { fill(); cv.drawRect(cx - r, cy - r * 0.8f, cx - r * 0.6f, cy + r * 0.8f, p); tri(cv, cx + r, cy - r * 0.8f, cx + r, cy + r * 0.8f, cx - r * 0.4f, cy) }
                19 -> { fill(); cv.drawRect(cx + r * 0.6f, cy - r * 0.8f, cx + r, cy + r * 0.8f, p); tri(cv, cx - r, cy - r * 0.8f, cx - r, cy + r * 0.8f, cx + r * 0.4f, cy) }
            }
            if (tabLabel != null && active) { fill(); p.color = Color.WHITE; p.textSize = dp(12).toFloat(); cv.drawText(tabLabel, s - dp(2), cy + dp(4), p) }
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
        val p = Paint(Paint.ANTI_ALIAS_FLAG); var lw = dp(224).toFloat(); val top = dp(44).toFloat()
        override fun onDraw(cv: Canvas) {
            lw = minOf(dp(224).toFloat(), width * 0.34f); val rh = (height - top) / 7f; val cw = (width - lw) / 16f; val rad = dp(6).toFloat(); val m = dp(3).toFloat(); val iz = minOf(dp(36).toFloat(), rh * 0.8f); val tz = minOf(dp(15).toFloat(), rh * 0.42f); val bz = minOf(dp(34).toFloat(), rh * 0.8f)
            val anyS = rowSolo.any { it }
            p.style = Paint.Style.FILL
            p.textSize = dp(14).toFloat(); p.color = Color.WHITE
            for (k in 0 until 4) cv.drawText("${k + 1}", lw + k * 4 * cw + dp(6), top - dp(16), p)
            p.color = 0xFF22DD33.toInt(); cv.drawRect(lw, top - dp(8), width.toFloat(), top - dp(5), p)
            cv.drawRect(lw, top - dp(20), lw + dp(5), top - dp(5), p); cv.drawRect(width - dp(5).toFloat(), top - dp(20), width.toFloat(), top - dp(5), p)
            for (r in 0 until 7) { val y = top + r * rh
                p.color = (colors[r] and 0x00FFFFFF) or 0x18000000; cv.drawRect(lw, y, width.toFloat(), y + rh, p)
                p.color = 0xFF2B2B2E.toInt(); cv.drawRect(0f, y, lw - dp(4), y + rh, p)
                p.color = colors[r]; cv.drawRoundRect(dp(8).toFloat(), y + (rh - iz) / 2, dp(8) + iz, y + (rh + iz) / 2, dp(6).toFloat(), dp(6).toFloat(), p)
                p.color = Color.WHITE; p.textSize = iz * 0.5f; p.textAlign = Paint.Align.CENTER; cv.drawText(names[r].take(1), dp(8) + iz / 2, y + rh / 2 + iz * 0.18f, p); p.textAlign = Paint.Align.LEFT
                p.textSize = tz; if (lw >= dp(200)) cv.drawText(names[r], dp(16) + iz, y + rh / 2 + tz * 0.35f, p)
                if (samples[r] == null && lw >= dp(200)) { p.color = Color.GRAY; cv.drawText("+", dp(16) + iz + p.measureText(names[r]) + dp(6), y + rh / 2 + tz * 0.35f, p) }
                val by = y + (rh - bz) / 2; val bh = by + bz
                p.color = if (rowSolo[r]) 0xFF22DD33.toInt() else 0xFF505055.toInt(); cv.drawRoundRect(lw - dp(88), by, lw - dp(48), bh, dp(8).toFloat(), dp(8).toFloat(), p)
                p.color = if (rowMute[r]) 0xFFFFB020.toInt() else 0xFF505055.toInt(); cv.drawRoundRect(lw - dp(44), by, lw - dp(8), bh, dp(8).toFloat(), dp(8).toFloat(), p)
                p.color = Color.WHITE; p.textSize = tz; p.textAlign = Paint.Align.CENTER
                cv.drawText("S", lw - dp(68), by + bz / 2 + tz * 0.35f, p); cv.drawText("M", lw - dp(26), by + bz / 2 + tz * 0.35f, p); p.textAlign = Paint.Align.LEFT
                for (st in 0 until 16) {
                    p.color = if (pattern[r][st]) (if (rowMute[r] || (anyS && !rowSolo[r])) 0xFF555B6A.toInt() else colors[r]) else if (st / 4 % 2 == 0) 0xFF1A1F2B.toInt() else 0xFF151A25.toInt()
                    cv.drawRoundRect(lw + st * cw + m, y + m, lw + (st + 1) * cw - m, y + rh - m, rad, rad, p) } }
            if (playStep >= 0) { p.color = Color.WHITE; val px = lw + playStep * cw + cw / 2; cv.drawRect(px - 2, top - dp(20), px + 2, height.toFloat(), p) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            lw = minOf(dp(224).toFloat(), width * 0.34f)
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
