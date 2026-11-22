package app.absentmotion.ai

import app.absentmotion.editor.EditorState
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.model.content
import app.absentmotion.model.effects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.math.roundToInt

class AiAssistantResult(val pesan: String, val hasil: AiResult?, val galat: String? = null)

class AiConversation {
    internal val contents = JSONArray()
    internal var owner: Any? = null

    fun reset() {
        while (contents.length() > 0) contents.remove(0)
        owner = null
    }

    fun countFor(editor: Any): Int = if (owner === editor) requestCount() else 0

    fun requestCount(): Int {
        var count = 0
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            if (item.optString("role") != "user") continue
            val text = item.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "").orEmpty()
            if (text.startsWith("KEADAAN PROYEK:")) count++
        }
        return count
    }
}

object AiGemini {

    val conversation = AiConversation()

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun cancelActive() {
        activeConnection?.disconnect()
    }

    private const val MAX_LAYERS = 200
    private const val MAX_ROUNDS = 4
    private const val MAX_INFO_CHARS = 14000
    private const val MAX_HISTORY = 24
    private const val MAX_HISTORY_CHARS = 150000
    private val LOOKUPS = setOf("info_efek", "cari_efek", "info_bentuk")

    private class GeminiException(message: String, val soalBerpikir: Boolean = false) : Exception(message)

    private class Reply(val text: String, val content: JSONObject)

    // ---------------------------------------------------------------- keadaan proyek

    fun projectState(editor: EditorState): String {
        val scene = editor.scene
        val layers = JSONArray()
        for (layer in scene.layers.take(MAX_LAYERS)) {
            val obj = JSONObject()
            obj.put("id", layer.id)
            obj.put("nama", layer.label)
            obj.put("tipe", tipeLayer(layer.kind))
            obj.put("awal", layer.startTime)
            obj.put("durasi", layer.endTime - layer.startTime)
            layer.node["parent"]?.toLongOrNull()?.let { obj.put("induk_id", it) }
            addValues(obj, layer.at(editor.timeMs, scene.fps))
            if (layer.hidden) obj.put("sembunyi", true)
            if (layer.locked) obj.put("kunci", true)
            if (layer.kind == "text") obj.put("isi", layer.content.take(200))
            layers.put(obj)
        }
        val project = JSONObject()
        project.put("lebar", scene.width)
        project.put("tinggi", scene.height)
        project.put("fps", scene.fps)
        project.put("durasi", scene.totalTime)
        val state = JSONObject()
        state.put("proyek", project)
        state.put("waktu_sekarang", editor.timeMs)
        editor.selectedId?.let { state.put("terpilih", it) }
        state.put("layer", layers)
        return state.toString()
    }

    private fun round1(value: Float): Double = (value * 10f).roundToInt() / 10.0

    private fun addValues(obj: JSONObject, now: app.absentmotion.model.Layer) {
        val location = now.location
        obj.put("posisi_x", round1(location[0]))
        obj.put("posisi_y", round1(location[1]))
        val scale = now.scale
        if (scale[0] == scale[1]) {
            obj.put("skala", round1(scale[0] * 100f))
        } else {
            obj.put("skala_x", round1(scale[0] * 100f))
            obj.put("skala_y", round1(scale[1] * 100f))
        }
        if (now.rotation != 0f) obj.put("rotasi", round1(now.rotation))
        obj.put("opasitas", round1(now.opacity * 100f))
        if (now.fillType != "none") obj.put("warna", String.format("#%06x", now.fillColor and 0xFFFFFF))
        if (now.kind == "shape") {
            val size = now.prop("size")
            if (size != null && size.size >= 2) obj.put("ukuran", JSONArray().put(round1(size[0])).put(round1(size[1])))
        }
        val effects = now.effects.mapNotNull { it["id"]?.removePrefix("absent.") }
        if (effects.isNotEmpty()) obj.put("efek", JSONArray(effects))
    }

    private fun tipeLayer(kind: String): String = when (kind) {
        "text" -> "teks"
        "shape" -> "bentuk"
        "embedScene" -> "grup"
        else -> kind
    }

    // ---------------------------------------------------------------- putaran kerja

    suspend fun run(
        editor: EditorState,
        apiKey: String,
        model: String,
        thinking: String,
        request: String,
        onProgress: (String) -> Unit,
    ): AiAssistantResult {
        if (conversation.owner !== editor) {
            conversation.reset()
            conversation.owner = editor
        }
        val contents = conversation.contents
        trimHistory(contents)
        contents.put(userContent("KEADAAN PROYEK:\n" + projectState(editor) + "\n\nPERMINTAAN PENGGUNA:\n" + request))
        val result = try {
            runRounds(editor, apiKey, model, thinking, contents, onProgress)
        } catch (e: CancellationException) {
            dropDanglingUser(contents)
            throw e
        }
        if (result.galat != null) dropDanglingUser(contents)
        return result
    }

    private fun trimHistory(contents: JSONArray) {
        while (contents.length() > 2 && (contents.length() > MAX_HISTORY || contents.toString().length > MAX_HISTORY_CHARS)) {
            contents.remove(0)
        }
        while (contents.length() > 0 && contents.optJSONObject(0)?.optString("role") != "user") contents.remove(0)
    }

    private fun dropDanglingUser(contents: JSONArray) {
        val last = contents.length() - 1
        if (last >= 0 && contents.optJSONObject(last)?.optString("role") == "user") contents.remove(last)
    }

    private suspend fun runRounds(
        editor: EditorState,
        apiKey: String,
        model: String,
        thinking: String,
        contents: JSONArray,
        onProgress: (String) -> Unit,
    ): AiAssistantResult {
        val applied = ArrayList<String>()
        val skipped = ArrayList<Pair<String, String>>()
        var message = ""

        for (round in 1..MAX_ROUNDS) {
            onProgress(if (round == 1) "Berpikir..." else "Putaran $round dari $MAX_ROUNDS...")
            val reply = try {
                withContext(Dispatchers.IO) { callModel(model, apiKey, contents, thinking) }
            } catch (e: GeminiException) {
                return partial(message, applied, skipped, e.message ?: "Gemini gagal menjawab")
            } catch (e: IOException) {
                return partial(message, applied, skipped, "Tidak bisa menghubungi Gemini: " + (e.message ?: "periksa koneksi internet"))
            }
            currentCoroutineContext().ensureActive()
            contents.put(reply.content)

            val root = parseRoot(reply.text)
                ?: return partial(message, applied, skipped, "Jawaban Gemini tidak bisa dibaca")
            val pesan = root.optString("pesan", "")
            if (pesan.isNotBlank()) message = pesan

            val lookups = ArrayList<JSONObject>()
            val edits = ArrayList<JSONObject>()
            val items = root.optJSONArray("aksi")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val original = items.optJSONObject(i)
                    if (original == null) {
                        skipped += "aksi[$i]" to "Elemen aksi bukan objek JSON"
                        continue
                    }
                    val item = normalize(original)
                    if (item.optString("aksi", "").lowercase() in LOOKUPS) lookups += item else edits += item
                }
            }

            val roundApplied = ArrayList<String>()
            val roundSkipped = ArrayList<Pair<String, String>>()
            val created = ArrayList<Long>()
            if (edits.isNotEmpty()) {
                onProgress("Menjalankan ${edits.size} aksi...")
                runEdits(editor, edits, created, roundApplied, roundSkipped)
                applied += roundApplied
                skipped += roundSkipped
            }
            val info = if (lookups.isNotEmpty()) answerLookups(editor, lookups) else ""
            val done = root.optBoolean("selesai", false)
            if (lookups.isEmpty() && (edits.isEmpty() || done) || round == MAX_ROUNDS) break
            contents.put(userContent(feedback(editor, round, roundApplied, roundSkipped, created, info)))
        }

        if (applied.isEmpty() && skipped.isEmpty()) {
            return if (message.isBlank()) AiAssistantResult("", null, "Gemini tidak memberi aksi apa pun") else AiAssistantResult(message, null)
        }
        return AiAssistantResult(message, AiResult(applied, skipped))
    }

    private fun partial(message: String, applied: List<String>, skipped: List<Pair<String, String>>, galat: String): AiAssistantResult {
        val result = if (applied.isEmpty() && skipped.isEmpty()) null else AiResult(applied.toList(), skipped.toList())
        return AiAssistantResult(message, result, galat)
    }

    private fun runEdits(
        editor: EditorState,
        edits: List<JSONObject>,
        created: MutableList<Long>,
        applied: MutableList<String>,
        skipped: MutableList<Pair<String, String>>,
    ) {
        val items = AiLoop.expandAll(edits, skipped)
        editor.batchEdit {
            for (item in items) {
                val problem = resolveRefs(item, created)
                if (problem != null) {
                    skipped += item.optString("aksi", "(kosong)") to problem
                    continue
                }
                val single = JSONObject().put("aksi", JSONArray().put(item)).toString()
                val result = AiActionExecutor.execute(editor, AiActionExecutor.parseResponse(single).aksi)
                applied += result.diterapkan
                skipped += result.dilewati
                if (item.optString("aksi", "").lowercase() == "tambah_layer" && result.diterapkan.isNotEmpty()) {
                    editor.selectedId?.let { created += it }
                }
            }
        }
    }

    private fun feedback(
        editor: EditorState,
        round: Int,
        applied: List<String>,
        skipped: List<Pair<String, String>>,
        created: List<Long>,
        info: String,
    ): String {
        val out = StringBuilder("HASIL PUTARAN $round:\n")
        if (applied.isEmpty() && skipped.isEmpty() && info.isBlank()) out.append("(tidak ada aksi yang dijalankan)\n")
        for (line in applied.take(40)) out.append("✓ ").append(line).append('\n')
        for ((name, reason) in skipped.take(40)) out.append("✗ ").append(name).append(": ").append(reason).append('\n')
        if (created.isNotEmpty()) {
            out.append("Layer baru: ")
            out.append(created.mapIndexed { i, id -> "baru${i + 1} = id $id" }.joinToString(", "))
            out.append('\n')
        }
        if (info.isNotBlank()) out.append("\nINFO DARI APLIKASI:\n").append(info)
        out.append("\nKEADAAN PROYEK TERBARU:\n").append(projectState(editor))
        out.append("\n\nLanjutkan jika masih ada yang perlu dikerjakan atau diperbaiki (pakai id layer asli, bukan 'baru'). ")
        out.append("Jika sudah sesuai permintaan awal, balas {\"selesai\": true, \"pesan\": \"ringkasan\", \"aksi\": []}.")
        return out.toString()
    }

    // ---------------------------------------------------------------- tanya aplikasi

    private fun answerLookups(editor: EditorState, lookups: List<JSONObject>): String {
        val catalog = editor.engine.effects.value
        val out = StringBuilder()
        for (item in lookups) {
            when (item.optString("aksi", "").lowercase()) {
                "info_efek" -> {
                    val names = namesOf(item.opt("efek"))
                    if (names.isEmpty()) out.append("info_efek: field 'efek' kosong\n")
                    for (name in names.take(8)) {
                        val def = catalog.definitions.firstOrNull {
                            it.key.equals(name, ignoreCase = true) || it.id.equals(name, ignoreCase = true) || it.id.equals("absent.$name", ignoreCase = true)
                        }
                        if (def == null) {
                            out.append("info_efek '$name': tidak ada. Pakai cari_efek.\n")
                            continue
                        }
                        out.append("EFEK ${def.key} | ${def.name} | ${def.category} | ${def.description.take(160)}\n")
                        for (param in def.params) out.append(describe(param)).append('\n')
                    }
                }
                "cari_efek" -> {
                    val query = item.optString("kata", "").trim()
                    val found = catalog.search(query).filter { it.available }.distinctBy { it.key }.take(15)
                    out.append("HASIL cari_efek '$query':\n")
                    if (found.isEmpty()) out.append("(tidak ada yang cocok, coba kata lain)\n")
                    for (def in found) out.append("${def.key} | ${def.name} | ${def.category} | ${def.description.take(100)}\n")
                }
                "info_bentuk" -> {
                    val query = item.optString("kata", "").trim().lowercase()
                    val shapes = ShapeCatalog.all.filter { query.isEmpty() || it.name.lowercase().contains(query) || it.id.lowercase().contains(query) }.take(60)
                    out.append("BENTUK (pakai nilai 'nama' di isi tambah_layer):\n")
                    if (shapes.isEmpty()) out.append("(tidak ada yang cocok)\n")
                    for (shape in shapes) out.append("${shape.name} | ${shape.id}\n")
                }
            }
            if (out.length > MAX_INFO_CHARS) {
                out.setLength(MAX_INFO_CHARS)
                out.append("\n(info dipotong, minta lebih sedikit efek sekaligus)\n")
                break
            }
        }
        return out.toString()
    }

    private fun namesOf(value: Any?): List<String> {
        val names = when (value) {
            is String -> listOf(value.trim())
            is JSONArray -> (0 until value.length()).map { value.optString(it).trim() }
            else -> emptyList()
        }
        return names.filter { it.isNotEmpty() }
    }

    private fun describe(param: EffectParam): String {
        val range = when (param.kind) {
            "choice" -> "pilihan " + param.options.mapIndexed { i, option -> "$i=$option" }.joinToString(", ")
            "point" -> "titik [x, y]"
            "color" -> "warna hex"
            "switch" -> "true/false"
            "text" -> "teks"
            else -> {
                val low = if (param.min <= -1e30f) "-∞" else number(param.min)
                val high = if (param.max >= 1e30f) "∞" else number(param.max)
                "$low sampai $high"
            }
        }
        return "  ${param.id} | ${param.kind} | $range | bawaan ${param.default}"
    }

    private fun number(value: Float): String = if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    // ---------------------------------------------------------------- bentuk JSON

    private fun userContent(text: String): JSONObject =
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun parseRoot(text: String): JSONObject? {
        val clean = cleanJson(text)
        return try {
            if (clean.startsWith("[")) JSONObject().put("aksi", JSONArray(clean)) else JSONObject(clean)
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanJson(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("```")) return text
        return text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun normalize(item: JSONObject): JSONObject {
        if (item.optString("aksi", "").isNotBlank()) return item
        val keys = item.keys().asSequence().toList()
        if (keys.size == 1) {
            val inner = item.optJSONObject(keys[0])
            if (inner != null) {
                val flat = JSONObject(inner.toString())
                flat.put("aksi", keys[0])
                return flat
            }
        }
        for (alt in listOf("action", "nama_aksi", "perintah", "tipe_aksi")) {
            val value = item.optString(alt, "")
            if (value.isNotBlank()) {
                item.put("aksi", value)
                return item
            }
        }
        return item
    }

    private fun resolveRefs(item: JSONObject, created: List<Long>): String? {
        for (key in listOf("id", "induk_id")) {
            val text = (item.opt(key) as? String)?.trim()?.lowercase() ?: continue
            if (!text.startsWith("baru")) continue
            val n = text.removePrefix("baru").toIntOrNull()
            val id = (if (n == null) created.lastOrNull() else created.getOrNull(n - 1))
                ?: return "Referensi '$text' tidak ada: belum ada layer baru yang berhasil dibuat"
            item.put(key, id)
        }
        return null
    }

    // ---------------------------------------------------------------- jaringan

    suspend fun test(apiKey: String, model: String): String {
        val contents = JSONArray().put(userContent("Tes koneksi. Balas dengan pesan singkat dan aksi kosong."))
        return try {
            withContext(Dispatchers.IO) { callModel(model, apiKey, contents, "") }
            "✓ Terhubung ke $model"
        } catch (e: GeminiException) {
            "✗ " + (e.message ?: "Gemini gagal menjawab")
        } catch (e: IOException) {
            "✗ Tidak bisa menghubungi Gemini: " + (e.message ?: "periksa koneksi internet")
        }
    }

    private fun callModel(model: String, apiKey: String, contents: JSONArray, thinking: String): Reply =
        try {
            post(model, apiKey, contents, thinking)
        } catch (e: GeminiException) {
            if (thinking.isNotBlank() && e.soalBerpikir) post(model, apiKey, contents, "") else throw e
        }

    private fun post(model: String, apiKey: String, contents: JSONArray, thinking: String): Reply {
        val systemPart = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AiPrompt.sistem)))
        val config = JSONObject().put("responseMimeType", "application/json")
        if (thinking.isNotBlank()) config.put("thinkingConfig", JSONObject().put("thinkingLevel", thinking))
        val body = JSONObject()
            .put("systemInstruction", systemPart)
            .put("contents", contents)
            .put("generationConfig", config)

        val url = java.net.URI("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").toURL()
        val connection = url.openConnection() as HttpURLConnection
        activeConnection = connection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 180000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw errorFor(code, text)
            return extractReply(text)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun errorFor(code: Int, body: String): GeminiException {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        val message = when (code) {
            400, 403 -> if (detail.contains("API key", ignoreCase = true)) "API key Gemini tidak valid. Periksa kembali kuncinya." else "Permintaan ditolak ($code): $detail"
            404 -> "Model Gemini tidak ditemukan. Pilih model lain di Pengaturan. $detail"
            429 -> "Kuota Gemini habis atau terlalu sering dipakai. Coba lagi sebentar lagi."
            else -> "Gemini error $code: $detail"
        }
        return GeminiException(message, soalBerpikir = code == 400 && detail.contains("thinking", ignoreCase = true))
    }

    private fun extractReply(body: String): Reply {
        val content = try {
            JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
        } catch (_: Exception) {
            null
        } ?: throw GeminiException("Jawaban Gemini kosong atau diblokir")
        val parts = content.optJSONArray("parts") ?: throw GeminiException("Jawaban Gemini kosong")
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (!part.optBoolean("thought", false)) out.append(part.optString("text", ""))
        }
        if (out.isBlank()) throw GeminiException("Jawaban Gemini kosong")
        if (!content.has("role")) content.put("role", "model")
        return Reply(out.toString(), content)
    }
}
