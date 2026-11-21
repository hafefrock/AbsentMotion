package app.absentmotion.ai

import app.absentmotion.editor.EditorState
import app.absentmotion.model.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

class AiAssistantResult(val pesan: String, val hasil: AiResult?, val galat: String? = null)

object AiGemini {

    private const val MAX_LAYERS = 200

    private class GeminiException(message: String) : Exception(message)

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

    private fun tipeLayer(kind: String): String = when (kind) {
        "text" -> "teks"
        "shape" -> "bentuk"
        "embedScene" -> "grup"
        else -> kind
    }

    suspend fun run(editor: EditorState, apiKey: String, model: String, request: String): AiAssistantResult {
        val userText = "KEADAAN PROYEK:\n" + projectState(editor) + "\n\nPERMINTAAN PENGGUNA:\n" + request
        val raw = try {
            withContext(Dispatchers.IO) { callModel(model, apiKey, userText) }
        } catch (e: GeminiException) {
            return AiAssistantResult("", null, e.message ?: "Gemini gagal menjawab")
        } catch (e: IOException) {
            return AiAssistantResult("", null, "Tidak bisa menghubungi Gemini: " + (e.message ?: "periksa koneksi internet"))
        }
        val clean = cleanJson(raw)
        val root = try {
            if (clean.startsWith("[")) JSONObject().put("aksi", JSONArray(clean)) else JSONObject(clean)
        } catch (_: Exception) {
            return AiAssistantResult("", null, "Jawaban Gemini tidak bisa dibaca")
        }
        val pesan = root.optString("pesan", "")
        val items = root.optJSONArray("aksi")
        if (items == null || items.length() == 0) {
            return if (pesan.isBlank()) AiAssistantResult("", null, "Gemini tidak memberi aksi apa pun") else AiAssistantResult(pesan, null)
        }

        val applied = ArrayList<String>()
        val skipped = ArrayList<Pair<String, String>>()
        val created = ArrayList<Long>()
        editor.batchEdit {
            for (i in 0 until items.length()) {
                val original = items.optJSONObject(i)
                if (original == null) {
                    skipped += "aksi[$i]" to "Elemen aksi bukan objek JSON"
                    continue
                }
                val item = normalize(original)
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
        return AiAssistantResult(pesan, AiResult(applied, skipped))
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

    suspend fun test(apiKey: String, model: String): String {
        return try {
            withContext(Dispatchers.IO) { callModel(model, apiKey, "Tes koneksi. Balas dengan pesan singkat dan aksi kosong.") }
            "✓ Terhubung ke $model"
        } catch (e: GeminiException) {
            "✗ " + (e.message ?: "Gemini gagal menjawab")
        } catch (e: IOException) {
            "✗ Tidak bisa menghubungi Gemini: " + (e.message ?: "periksa koneksi internet")
        }
    }

    private fun cleanJson(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("```")) return text
        return text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun callModel(model: String, apiKey: String, userText: String): String {
        val systemPart = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AiPrompt.sistem)))
        val userPart = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))
        val config = JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2)
        val body = JSONObject()
            .put("systemInstruction", systemPart)
            .put("contents", JSONArray().put(userPart))
            .put("generationConfig", config)

        val url = java.net.URI("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw errorFor(code, text)
            return extractText(text)
        } finally {
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
        return GeminiException(message)
    }

    private fun extractText(body: String): String {
        val parts = try {
            JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        } catch (_: Exception) {
            null
        } ?: throw GeminiException("Jawaban Gemini kosong atau diblokir")
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (!part.optBoolean("thought", false)) out.append(part.optString("text", ""))
        }
        if (out.isBlank()) throw GeminiException("Jawaban Gemini kosong")
        return out.toString()
    }
}
