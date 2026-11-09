package com.echo1.manbandpro.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

// ---------- Data model (stored in the .emb file; no audio inside) ----------

const val EMB_FORMAT_VERSION = 1
const val MAX_BARS = 18

data class SampleRef(
    val path: String,          // project-relative, e.g. "Samples/Kick.wav"
    val sha256: String,
    val editedPath: String? = null  // e.g. "Edited Samples/Kick_trim.wav"
)

data class EmbProject(
    var name: String,
    var created: Long = System.currentTimeMillis(),
    var modified: Long = created,
    var bpm: Double = 120.0,
    var beatsPerBar: Int = 4,
    var beatUnit: Int = 4,
    var masterVolume: Float = 0.8f,
    var maxBars: Int = MAX_BARS,
    var loopStartBar: Int = 1,
    var loopEndBar: Int = 8,
    var swing: Float = 0f,
    val samples: MutableList<SampleRef> = mutableListOf(),
    // Sections below are free-form JSON so each editor can own its schema
    // without changing the container: drumChannels, drumPatterns, midiTracks,
    // pianoRollNotes, chords, basslines, samplers, sampleMappings, mixer
    // (volume/pan/mute/solo), midiEditInfo, settings.
    val sections: JSONObject = JSONObject()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", EMB_FORMAT_VERSION)
        put("name", name); put("created", created); put("modified", modified)
        put("bpm", bpm); put("timeSig", JSONArray(listOf(beatsPerBar, beatUnit)))
        put("masterVolume", masterVolume.toDouble()); put("maxBars", maxBars)
        put("loop", JSONArray(listOf(loopStartBar, loopEndBar)))
        put("swing", swing.toDouble())
        put("samples", JSONArray(samples.map {
            JSONObject().put("path", it.path).put("sha256", it.sha256)
                .put("edited", it.editedPath ?: JSONObject.NULL)
        }))
        put("sections", sections)
    }

    companion object {
        fun fromJson(j: JSONObject): EmbProject {
            require(j.getInt("version") <= EMB_FORMAT_VERSION) { "Project made by a newer app version" }
            val ts = j.getJSONArray("timeSig"); val lp = j.getJSONArray("loop")
            val sm = j.getJSONArray("samples")
            return EmbProject(
                name = j.getString("name"), created = j.getLong("created"),
                modified = j.getLong("modified"), bpm = j.getDouble("bpm"),
                beatsPerBar = ts.getInt(0), beatUnit = ts.getInt(1),
                masterVolume = j.getDouble("masterVolume").toFloat(),
                maxBars = j.getInt("maxBars"),
                loopStartBar = lp.getInt(0), loopEndBar = lp.getInt(1),
                swing = j.optDouble("swing", 0.0).toFloat(),
                samples = MutableList(sm.length()) { i ->
                    val s = sm.getJSONObject(i)
                    SampleRef(s.getString("path"), s.getString("sha256"),
                        if (s.isNull("edited")) null else s.getString("edited"))
                },
                sections = j.optJSONObject("sections") ?: JSONObject()
            )
        }
    }
}

// ---------- Storage manager ----------

class ProjectStore(context: Context) {

    // App-private internal storage: files/Echo1ManBandPro/Projects/
    // No permissions needed, works on API 24+.
    private val root = File(context.filesDir, "Echo1ManBandPro/Projects").apply { mkdirs() }

    private val subDirs = listOf("Samples", "Edited Samples", "Presets", "Autosave", "Cache")

    fun dir(name: String) = File(root, name)
    fun embFile(name: String) = File(dir(name), "$name.emb")

    fun listProjects(): List<String> =
        root.listFiles { f -> f.isDirectory && embFile(f.name).exists() }
            ?.map { it.name }?.sorted() ?: emptyList()

    // New Project: folder + subfolders created automatically.
    fun create(name: String): EmbProject {
        val clean = sanitize(name)
        require(!dir(clean).exists()) { "A project named \"$clean\" already exists" }
        subDirs.forEach { File(dir(clean), it).mkdirs() }
        return EmbProject(name = clean).also { save(it) }
    }

    // Save: write temp file, fsync, atomic rename. Never touches the audio thread.
    @Synchronized
    fun save(p: EmbProject) {
        p.modified = System.currentTimeMillis()
        atomicWrite(embFile(p.name), p.toJson().toString().toByteArray(Charsets.UTF_8))
    }

    fun open(name: String): Pair<EmbProject, List<SampleRef>> {
        val f = embFile(name)
        val p = try {
            EmbProject.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            // Main file damaged: fall back to the newest autosave.
            recover(name) ?: throw e
        }
        return p to missingSamples(p)   // UI shows warning + locate/replace; never auto-substitute
    }

    // ---- Autosave: call from a background thread (debounced after edits) ----
    private val maxBackups = 5

    @Synchronized
    fun autosave(p: EmbProject) {
        save(p)
        val dirA = File(dir(p.name), "Autosave").apply { mkdirs() }
        atomicWrite(File(dirA, "autosave_${System.currentTimeMillis()}.emb"),
            p.toJson().toString().toByteArray())
        dirA.listFiles()?.sortedByDescending { it.name }?.drop(maxBackups)?.forEach { it.delete() }
    }

    fun recover(name: String): EmbProject? =
        File(dir(name), "Autosave").listFiles()?.sortedByDescending { it.name }
            ?.firstNotNullOfOrNull { f ->
                runCatching { EmbProject.fromJson(JSONObject(f.readText())) }.getOrNull()
            }

    // ---- Samples ----
    // Copies into Samples/ once; identical content (same checksum) is not copied again.
    fun importSample(p: EmbProject, source: java.io.InputStream, fileName: String): SampleRef {
        val tmp = File(dir(p.name), "Cache/import.tmp")
        tmp.outputStream().use { source.copyTo(it) }
        val sum = sha256(tmp)
        p.samples.firstOrNull { it.sha256 == sum }?.let { tmp.delete(); return it }
        val target = uniqueFile(File(dir(p.name), "Samples"), sanitize(fileName))
        tmp.renameTo(target)
        return SampleRef("Samples/${target.name}", sum).also { p.samples.add(it) }
    }

    fun resolve(p: EmbProject, rel: String) = File(dir(p.name), rel)

    fun missingSamples(p: EmbProject) = p.samples.filter { !resolve(p, it.path).exists() }

    // Cache is always safe to delete.
    fun clearCache(name: String) { File(dir(name), "Cache").listFiles()?.forEach { it.deleteRecursively() } }

    // ---- Rename / Duplicate / Delete ----
    fun rename(old: String, newName: String): EmbProject {
        val clean = sanitize(newName)
        require(!dir(clean).exists()) { "A project named \"$clean\" already exists" }
        val (p, _) = open(old)
        // References are project-relative, so renaming the folder keeps them valid.
        File(dir(old), "$old.emb").renameTo(File(dir(old), "$clean.emb"))
        check(dir(old).renameTo(dir(clean))) { "Rename failed" }
        p.name = clean
        save(p)
        open(clean)  // verify it opens
        return p
    }

    fun duplicate(name: String, newName: String): EmbProject {
        val clean = sanitize(newName)
        require(!dir(clean).exists()) { "A project named \"$clean\" already exists" }
        val src = dir(name); val dst = dir(clean)
        src.copyRecursively(dst)  // own copy of assets: edits can never leak between projects
        File(dst, "$name.emb").delete()
        File(dst, "Cache").listFiles()?.forEach { it.deleteRecursively() }
        val (p, _) = open(name)
        p.name = clean; p.created = System.currentTimeMillis()
        save(p)
        return p
    }

    // UI must show project name + asset summary and get confirmation BEFORE calling this.
    fun assetSummary(name: String): String {
        val files = dir(name).walkTopDown().filter { it.isFile }.toList()
        return "${files.size} files, ${files.sumOf { it.length() } / 1024} KB"
    }
    fun delete(name: String) { dir(name).deleteRecursively() }

    // Edited audio goes to "Edited Samples/"; the original in "Samples/" is never touched.
    fun saveEdited(p: EmbProject, wav: ByteArray, name: String): SampleRef {
        val f = uniqueFile(File(dir(p.name), "Edited Samples").apply { mkdirs() }, sanitize(name))
        atomicWrite(f, wav)
        return SampleRef("Edited Samples/${f.name}", sha256(f)).also { p.samples.add(it) }
    }

    // ---- Helpers ----
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {           // rename over existing is atomic on Android's ext4/f2fs
            target.delete(); check(tmp.renameTo(target)) { "Atomic save failed" }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(8192); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name); var i = 1
        while (f.exists()) { f = File(dir, "${name.substringBeforeLast('.')}_$i.${name.substringAfterLast('.', "wav")}"); i++ }
        return f
    }

    private fun sanitize(n: String) =
        n.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifEmpty { "Untitled" }
}
