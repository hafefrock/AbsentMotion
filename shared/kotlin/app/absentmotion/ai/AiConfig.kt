package app.absentmotion.ai

import android.content.Context

object AiConfig {

    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    private fun prefs(context: Context) = context.getSharedPreferences("ai_gemini", Context.MODE_PRIVATE)

    fun apiKey(context: Context): String = prefs(context).getString("api_key", "").orEmpty()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString("api_key", key.trim()).apply()
    }

    fun models(context: Context): List<String> {
        val saved = prefs(context).getString("models", "").orEmpty().split('\n').filter { it.isNotBlank() }
        return saved.ifEmpty { listOf(DEFAULT_MODEL) }
    }

    fun model(context: Context): String {
        val list = models(context)
        val selected = prefs(context).getString("model", "").orEmpty()
        return if (selected in list) selected else list.first()
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString("model", cleanModel(model)).apply()
    }

    fun addModel(context: Context, name: String): String {
        val clean = cleanModel(name)
        if (clean.isBlank()) return ""
        val list = models(context)
        if (clean !in list) saveModels(context, list + clean)
        return clean
    }

    fun removeModel(context: Context, name: String) {
        val rest = models(context) - name
        saveModels(context, rest.ifEmpty { listOf(DEFAULT_MODEL) })
    }

    private fun saveModels(context: Context, list: List<String>) {
        prefs(context).edit().putString("models", list.joinToString("\n")).apply()
    }

    val thinkingChoices: List<Pair<String, String>> = listOf(
        "" to "Otomatis",
        "LOW" to "Rendah",
        "MEDIUM" to "Sedang",
        "HIGH" to "Tinggi",
    )

    fun thinking(context: Context): String {
        val prefs = prefs(context)
        return if (prefs.contains("thinking")) prefs.getString("thinking", "").orEmpty() else "MEDIUM"
    }

    fun setThinking(context: Context, level: String) {
        prefs(context).edit().putString("thinking", level).apply()
    }

    fun thinkingLabel(level: String): String = thinkingChoices.firstOrNull { it.first == level }?.second ?: "Otomatis"

    fun vision(context: Context): Boolean = prefs(context).getBoolean("vision", true)

    fun setVision(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("vision", on).apply()
    }

    fun cleanModel(model: String): String =
        model.trim().removePrefix("models/").filter { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }
}
