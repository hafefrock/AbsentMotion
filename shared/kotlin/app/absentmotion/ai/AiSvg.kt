package app.absentmotion.ai

import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addSvg
import app.absentmotion.editor.ungroupSelected
import app.absentmotion.model.Scene
import app.absentmotion.model.importSvg
import org.json.JSONArray
import org.json.JSONObject

/** Aksi menggambar dengan SVG dan memasang titik putar di sendi. */
internal object AiSvg {

    val NAMES = setOf("tambah_svg", "atur_sendi")

    private const val MAX_SVG_CHARS = 120000
    private val viewBox = Regex("viewBox\\s*=\\s*[\"']\\s*([-+0-9.eE]+)[\\s,]+([-+0-9.eE]+)[\\s,]+([-+0-9.eE]+)[\\s,]+([-+0-9.eE]+)\\s*[\"']")
    private val rootTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val sizeAttribute = Regex("\\s(width|height)\\s*=")

    /** Pemetaan koordinat SVG ke kanvas untuk SVG terakhir yang diimpor. */
    private class Space(val minX: Float, val minY: Float, val w: Float, val h: Float, val scale: Float, val canvasW: Float, val canvasH: Float)

    private var space: Space? = null

    fun run(editor: EditorState, item: JSONObject): AiExtra.Outcome = when (val name = item.optString("aksi", "").lowercase()) {
        "tambah_svg" -> tambahSvg(editor, item)
        "atur_sendi" -> aturSendi(editor, item)
        else -> throw IllegalArgumentException("Aksi '$name' tidak dikenal")
    }

    // ---------------------------------------------------------------- tambah_svg

    private fun tambahSvg(editor: EditorState, item: JSONObject): AiExtra.Outcome {
        val svg = item.optString("svg", "").trim()
        if (svg.isEmpty()) throw IllegalArgumentException("Field 'svg' (kode SVG lengkap) tidak ada")
        if (svg.length > MAX_SVG_CHARS) throw IllegalArgumentException("SVG terlalu besar (maksimal $MAX_SVG_CHARS karakter)")
        val name = item.optString("nama", "").trim().ifEmpty { "Gambar" }
        val scene = editor.scene
        val start = (number(item, "awal")?.toInt() ?: editor.timeMs).coerceAtLeast(0)
        val duration = (number(item, "durasi")?.toInt() ?: (editor.settings.defaultLayerDuration * 1000)).coerceAtLeast(100)

        val result = try {
            importSvg(svg, name, scene, start, start + duration)
        } catch (e: Exception) {
            throw IllegalArgumentException("SVG tidak bisa dibaca: " + (e.message ?: "format tidak valid"))
        }
        val layer = result.layer ?: throw IllegalArgumentException("SVG kosong: tidak ada bentuk yang bisa diimpor")

        val before = scene.layers.map { it.id }.toSet()
        editor.addSvg(layer)
        val mapped = spaceOf(svg, scene)
        space = mapped

        var summary = "Menambah gambar SVG '$name' (${result.shapes} bentuk)"
        var created: Long? = layer.id
        if (flag(item, "bongkar", true) && layer.kind == "embedScene") {
            editor.selectedId = layer.id
            if (editor.ungroupSelected()) {
                val parts = editor.scene.layers.filter { it.id !in before }
                created = null
                summary += ", dibongkar menjadi ${parts.size} layer: " +
                    parts.take(40).joinToString(", ") { "${it.label.ifEmpty { "tanpa nama" }} (id=${it.id})" }
            }
        } else {
            summary += " id=${layer.id}"
        }
        if (result.skipped.isNotEmpty()) summary += ". ${result.skipped.size} elemen tidak didukung dan dilewati"
        if (mapped == null) summary += ". Koordinat ruang 'svg' tidak tersedia (pakai viewBox tanpa width/height)"
        return AiExtra.Outcome(summary, created)
    }

    private fun spaceOf(svg: String, scene: Scene): Space? {
        val tag = rootTag.find(svg)?.value.orEmpty()
        if (sizeAttribute.containsMatchIn(tag)) return null
        val match = viewBox.find(svg) ?: return null
        val v = match.groupValues.drop(1).map { it.toFloatOrNull() ?: return null }
        if (v[2] <= 0f || v[3] <= 0f) return null
        val canvasW = scene.width.toFloat()
        val canvasH = scene.height.toFloat()
        val scale = minOf(canvasW * 0.8f / v[2], canvasH * 0.8f / v[3])
        return Space(v[0], v[1], v[2], v[3], scale, canvasW, canvasH)
    }

    // ---------------------------------------------------------------- atur_sendi

    private fun aturSendi(editor: EditorState, item: JSONObject): AiExtra.Outcome {
        val id = idOf(item)
        val layer = editor.scene.layer(id) ?: throw IllegalArgumentException("Layer dengan id=$id tidak ditemukan")
        val x = number(item, "x") ?: throw IllegalArgumentException("Field 'x' harus berupa angka")
        val y = number(item, "y") ?: throw IllegalArgumentException("Field 'y' harus berupa angka")
        val jointX: Double
        val jointY: Double
        when (item.optString("ruang", "kanvas").trim().lowercase()) {
            "kanvas", "canvas" -> {
                jointX = x
                jointY = y
            }
            "svg" -> {
                val s = space ?: throw IllegalArgumentException("Ruang 'svg' belum tersedia: panggil tambah_svg dengan viewBox lebih dulu")
                jointX = s.canvasW / 2.0 + (x - s.minX - s.w / 2.0) * s.scale
                jointY = s.canvasH / 2.0 + (y - s.minY - s.h / 2.0) * s.scale
            }
            else -> throw IllegalArgumentException("Field 'ruang' harus 'svg' atau 'kanvas'")
        }

        // Titik poros bekerja seperti anchor: poros ditempatkan di posisi layer. Agar gambar tidak bergeser,
        // posisi baru = sendi, dan poros baru = sendi - (posisi lama - poros lama).
        val now = layer.at(editor.timeMs, editor.scene.fps)
        val location = now.location
        val pivot = now.pivot
        val pivotX = jointX - (location[0] - pivot[0])
        val pivotY = jointY - (location[1] - pivot[1])

        val actions = JSONArray()
        for ((property, value) in listOf("poros_x" to pivotX, "poros_y" to pivotY, "posisi_x" to jointX, "posisi_y" to jointY)) {
            actions.put(JSONObject().put("aksi", "atur_nilai").put("id", id).put("properti", property).put("nilai", round2(value)))
        }
        val parsed = AiActionExecutor.parseResponse(JSONObject().put("aksi", actions).toString())
        val outcome = AiActionExecutor.execute(editor, parsed.aksi)
        if (outcome.dilewati.isNotEmpty()) throw IllegalArgumentException(outcome.dilewati.joinToString("; ") { it.second })
        return AiExtra.Outcome("Memasang titik putar layer id=$id di sendi (${round2(jointX)}, ${round2(jointY)}) tanpa menggeser gambar")
    }

    // ---------------------------------------------------------------- pembantu

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun idOf(item: JSONObject): Long {
        val raw: Any? = item.opt("id")
        val id = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return id ?: throw IllegalArgumentException("Field 'id' harus berupa angka id layer")
    }

    private fun number(item: JSONObject, key: String): Double? {
        val raw: Any? = item.opt(key)
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun flag(item: JSONObject, key: String, default: Boolean): Boolean {
        val raw: Any? = item.opt(key)
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "ya", "1" -> true
                "false", "tidak", "0" -> false
                else -> default
            }
            else -> default
        }
    }
}
