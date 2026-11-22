package app.absentmotion.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** Penyedia AI lain yang memakai format Chat Completions ala OpenAI (Groq, NVIDIA NIM, OpenRouter, dll). */
class AiRemote(val id: String, val label: String, val baseUrl: String, val apiKey: String, val model: String)

class AiPreset(val id: String, val label: String, val baseUrl: String, val defaultModel: String, val keyPage: String)

object AiRemoteConfig {

    const val GEMINI = "gemini"

    // Nama model bisa berubah. Semuanya bisa diganti dari Pengaturan.
    val presets: List<AiPreset> = listOf(
        AiPreset("groq", "Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "console.groq.com/keys"),
        AiPreset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "openai/gpt-oss-120b", "build.nvidia.com"),
        AiPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "nvidia/nemotron-3-super-120b-a12b:free", "openrouter.ai/keys"),
        AiPreset("custom", "Kustom (alamat sendiri)", "", "", ""),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("ai_remote", Context.MODE_PRIVATE)

    fun preset(id: String): AiPreset? = presets.firstOrNull { it.id == id }

    fun provider(context: Context): String {
        val id = prefs(context).getString("provider", GEMINI).orEmpty()
        return if (id == GEMINI || preset(id) != null) id else GEMINI
    }

    fun setProvider(context: Context, id: String) {
        prefs(context).edit().putString("provider", id).apply()
    }

    fun label(id: String): String = if (id == GEMINI) "Gemini (Google)" else preset(id)?.label ?: id

    fun apiKey(context: Context, id: String): String = prefs(context).getString("key_$id", "").orEmpty()

    fun setApiKey(context: Context, id: String, key: String) {
        prefs(context).edit().putString("key_$id", key.trim()).apply()
    }

    fun model(context: Context, id: String): String {
        val saved = prefs(context).getString("model_$id", "").orEmpty()
        return saved.ifBlank { preset(id)?.defaultModel.orEmpty() }
    }

    fun setModel(context: Context, id: String, model: String) {
        prefs(context).edit().putString("model_$id", model.trim()).apply()
    }

    fun baseUrl(context: Context, id: String): String {
        val saved = prefs(context).getString("base_$id", "").orEmpty()
        return saved.ifBlank { preset(id)?.baseUrl.orEmpty() }
    }

    fun setBaseUrl(context: Context, id: String, url: String) {
        prefs(context).edit().putString("base_$id", url.trim().trimEnd('/')).apply()
    }

    /** null berarti pakai Gemini. */
    fun active(context: Context): AiRemote? {
        val id = provider(context)
        val preset = preset(id) ?: return null
        return AiRemote(id, preset.label, baseUrl(context, id), apiKey(context, id), model(context, id))
    }
}

object AiOpenAi {

    class RemoteException(message: String) : Exception(message)

    private class Rejected(val code: Int, val detail: String, val retryAfter: String?) : Exception(detail)

    private const val FRAME_LABEL = "[FRAME] "
    private const val MAX_TOKENS = 8000

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    // Fitur yang ditolak oleh model tertentu. Dicatat supaya putaran berikutnya tidak mengulang kegagalan yang sama.
    private val dropped = HashMap<String, MutableSet<String>>()

    fun cancelActive() {
        activeConnection?.disconnect()
    }

    /** Dipanggil dari thread IO. Mengembalikan teks jawaban dan isi percakapan berformat Gemini (role model). */
    fun call(remote: AiRemote, contents: JSONArray, thinking: String): Pair<String, JSONObject> {
        if (remote.baseUrl.isBlank()) throw RemoteException("Alamat server ${remote.label} belum diisi")
        if (remote.model.isBlank()) throw RemoteException("Nama model ${remote.label} belum diisi")
        val key = remote.baseUrl + "|" + remote.model
        val off = synchronized(dropped) { dropped.getOrPut(key) { HashSet() } }
        for (attempt in 1..5) {
            val skip = synchronized(dropped) { HashSet(off) }
            try {
                val text = post(remote, contents, thinking, skip)
                return text to JSONObject().put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            } catch (e: Rejected) {
                val low = e.detail.lowercase()
                val retryable = e.code == 400 || e.code == 422
                val feature = when {
                    !retryable -> null
                    "format" !in skip && ("response_format" in low || "json_object" in low || "json mode" in low) -> "format"
                    "max" !in skip && ("max_tokens" in low || "max_completion_tokens" in low) -> "max"
                    "reasoning" !in skip && "reasoning" in low -> "reasoning"
                    "images" !in skip && hasImages(contents) -> "images"
                    else -> null
                }
                if (feature == null || attempt == 5) throw RemoteException(messageFor(remote, e))
                synchronized(dropped) { off += feature }
            }
        }
        throw RemoteException("${remote.label} tidak memberi jawaban")
    }

    fun test(remote: AiRemote): String {
        val contents = JSONArray().put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Tes koneksi. Balas dengan JSON: {\"pesan\": \"ok\", \"aksi\": []}"))),
        )
        return try {
            call(remote, contents, "")
            "✓ Terhubung ke ${remote.label} (${remote.model})"
        } catch (e: RemoteException) {
            "✗ " + (e.message ?: "${remote.label} gagal menjawab")
        } catch (e: IOException) {
            "✗ Tidak bisa menghubungi ${remote.label}: " + (e.message ?: "periksa koneksi internet")
        }
    }

    // ---------------------------------------------------------------- bentuk permintaan

    private fun hasImages(contents: JSONArray): Boolean {
        for (i in 0 until contents.length()) {
            val parts = contents.optJSONObject(i)?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                if (parts.optJSONObject(j)?.has("inlineData") == true) return true
            }
        }
        return false
    }

    private fun textBlock(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)

    private fun buildMessages(contents: JSONArray, images: Boolean): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", AiPrompt.sistem))
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            val role = if (item.optString("role") == "model") "assistant" else "user"
            val parts = item.optJSONArray("parts") ?: continue
            val plain = StringBuilder()
            val blocks = JSONArray()
            var withImage = false
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val inline = part.optJSONObject("inlineData")
                if (inline != null) {
                    if (images && role == "user") {
                        val url = "data:" + inline.optString("mimeType", "image/png") + ";base64," + inline.optString("data", "")
                        blocks.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                        withImage = true
                    }
                    continue
                }
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isEmpty()) continue
                if (text.startsWith(FRAME_LABEL)) {
                    if (images) blocks.put(textBlock(text))
                    continue
                }
                if (plain.isNotEmpty()) plain.append('\n')
                plain.append(text)
                blocks.put(textBlock(text))
            }
            if (!withImage && plain.isEmpty()) continue
            val message = JSONObject().put("role", role)
            if (withImage) message.put("content", blocks) else message.put("content", plain.toString())
            messages.put(message)
        }
        return messages
    }

    private fun post(remote: AiRemote, contents: JSONArray, thinking: String, skip: Set<String>): String {
        val body = JSONObject()
            .put("model", remote.model)
            .put("messages", buildMessages(contents, "images" !in skip))
            .put("stream", false)
        if ("format" !in skip) body.put("response_format", JSONObject().put("type", "json_object"))
        if ("max" !in skip) body.put("max_tokens", MAX_TOKENS)
        val effort = thinking.lowercase()
        if ("reasoning" !in skip && effort in setOf("low", "medium", "high") &&
            remote.baseUrl.contains("groq.com") && remote.model.contains("gpt-oss")
        ) {
            body.put("reasoning_effort", effort)
        }

        val url = java.net.URI(remote.baseUrl.trimEnd('/') + "/chat/completions").toURL()
        val connection = url.openConnection() as HttpURLConnection
        activeConnection = connection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 180000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer " + remote.apiKey)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Rejected(code, errorDetail(text), connection.getHeaderField("Retry-After"))
            return extractReply(remote, text)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------- bentuk jawaban

    private fun errorDetail(body: String): String {
        try {
            val root = JSONObject(body)
            val obj = root.optJSONObject("error")
            if (obj != null) return obj.optString("message", "").ifBlank { body.take(300) }
            val flat = root.optString("error", "")
            if (flat.isNotBlank()) return flat
            val msg = root.optString("message", "")
            if (msg.isNotBlank()) return msg
            val detail = root.optString("detail", "")
            if (detail.isNotBlank()) return detail
        } catch (_: Exception) {
        }
        return body.take(300)
    }

    private fun messageFor(remote: AiRemote, e: Rejected): String {
        val label = remote.label
        return when (e.code) {
            401 -> "API key $label tidak valid. Periksa kembali kuncinya."
            403 -> "Akses ke $label ditolak: ${e.detail}"
            404 -> "Model atau alamat $label tidak ditemukan. Periksa nama model di Pengaturan. ${e.detail}"
            413 -> "Permintaan terlalu besar untuk $label. Matikan \"Lihat hasil\" atau pakai model lain. ${e.detail}"
            429 -> "Kuota $label habis atau terlalu sering dipakai." +
                (e.retryAfter?.let { " Coba lagi dalam $it detik." } ?: " Coba lagi sebentar lagi.") +
                (if (e.detail.isNotBlank()) " (${e.detail.take(160)})" else "")
            else -> "$label error ${e.code}: ${e.detail}"
        }
    }

    private fun extractReply(remote: AiRemote, body: String): String {
        val choice = try {
            JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
        } catch (_: Exception) {
            null
        } ?: throw RemoteException("Jawaban ${remote.label} kosong atau tidak bisa dibaca")
        val message = choice.optJSONObject("message")
        val raw = message?.opt("content")
        var text = when (raw) {
            is String -> raw
            is JSONArray -> buildString {
                for (i in 0 until raw.length()) append(raw.optJSONObject(i)?.optString("text", "").orEmpty())
            }
            else -> ""
        }
        text = cleanJson(text.replace(Regex("(?s)<think>.*?</think>"), ""))
        if (text.isBlank()) {
            val why = choice.optString("finish_reason", "")
            throw RemoteException(
                if (why == "length") "Jawaban ${remote.label} terpotong karena terlalu panjang. Coba permintaan yang lebih kecil."
                else "Jawaban ${remote.label} kosong",
            )
        }
        return text
    }

    /** Buang pagar kode dan teks di luar JSON supaya parser aplikasi bisa membacanya. */
    private fun cleanJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (text.startsWith("{") || text.startsWith("[")) return text
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        return if (a >= 0 && b > a) text.substring(a, b + 1) else text
    }
}
