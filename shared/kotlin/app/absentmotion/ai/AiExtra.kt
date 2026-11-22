package app.absentmotion.ai

import app.absentmotion.editor.EditorState
import app.absentmotion.editor.canClip
import app.absentmotion.editor.copyKeyframes
import app.absentmotion.editor.extractAudio
import app.absentmotion.editor.flip
import app.absentmotion.editor.freezeFrame
import app.absentmotion.editor.groupLayers
import app.absentmotion.editor.pasteKeyframes
import app.absentmotion.editor.reverseRemap
import app.absentmotion.editor.setGain
import app.absentmotion.editor.setVideoSpeed
import app.absentmotion.editor.toggleBookmark
import app.absentmotion.editor.toggleClippingMask
import app.absentmotion.editor.turnOnRemap
import app.absentmotion.editor.ungroupSelected
import app.absentmotion.model.Layer
import org.json.JSONObject

internal object AiExtra {

    val NAMES = setOf(
        "pilih", "kelompokkan", "bongkar_grup", "topeng_pemotong", "balik",
        "atur_volume", "ekstrak_audio", "atur_kecepatan", "bekukan_frame", "putar_mundur",
        "bookmark", "salin_keyframe", "tempel_keyframe",
    )

    class Outcome(val ringkasan: String, val dibuat: Long? = null)

    fun run(editor: EditorState, item: JSONObject): Outcome {
        return when (val name = item.optString("aksi", "").lowercase()) {
            "pilih" -> pilih(editor, item)
            "kelompokkan" -> kelompokkan(editor, item)
            "bongkar_grup" -> bongkarGrup(editor, item)
            "topeng_pemotong" -> topengPemotong(editor, item)
            "balik" -> balik(editor, item)
            "atur_volume" -> aturVolume(editor, item)
            "ekstrak_audio" -> ekstrakAudio(editor, item)
            "atur_kecepatan" -> aturKecepatan(editor, item)
            "bekukan_frame" -> bekukanFrame(editor, item)
            "putar_mundur" -> putarMundur(editor, item)
            "bookmark" -> bookmark(editor, item)
            "salin_keyframe" -> salinKeyframe(editor, item)
            "tempel_keyframe" -> tempelKeyframe(editor, item)
            else -> throw IllegalArgumentException("Aksi '$name' tidak dikenal")
        }
    }

    // ---------------------------------------------------------------- pembantu

    private fun idOf(item: JSONObject, key: String = "id"): Long {
        val id = when (val raw = item.opt(key)) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return id ?: throw IllegalArgumentException("Field '$key' harus berupa angka id layer")
    }

    private fun layerOf(editor: EditorState, id: Long): Layer =
        editor.scene.layer(id) ?: throw IllegalArgumentException("Layer dengan id=$id tidak ditemukan")

    private fun isVideo(layer: Layer): Boolean = layer.mediaFill?.video == true

    private fun requireVideo(layer: Layer) {
        if (!isVideo(layer)) throw IllegalArgumentException("Layer id=${layer.id} bukan layer video")
    }

    private fun number(item: JSONObject, key: String): Double? = when (val raw = item.opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.trim().removeSuffix("%").toDoubleOrNull()
        else -> null
    }

    private fun flag(item: JSONObject, key: String, default: Boolean): Boolean = when (val raw = item.opt(key)) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> when (raw.trim().lowercase()) {
            "true", "ya", "aktif", "on", "1" -> true
            "false", "tidak", "mati", "off", "0" -> false
            else -> default
        }
        else -> default
    }

    private fun <T> atTime(editor: EditorState, ms: Int?, block: () -> T): T {
        val previous = editor.frame
        if (ms != null) editor.frame = editor.scene.frameAt(ms.coerceAtLeast(0)).coerceAtLeast(0)
        try {
            return block()
        } finally {
            editor.frame = previous
        }
    }

    // ---------------------------------------------------------------- seleksi dan susunan

    private fun pilih(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        layerOf(editor, id)
        editor.selectedId = id
        return Outcome("Memilih layer id=$id")
    }

    private fun kelompokkan(editor: EditorState, item: JSONObject): Outcome {
        val array = item.optJSONArray("ids") ?: throw IllegalArgumentException("Field 'ids' (daftar id layer) tidak ada")
        val ids = LinkedHashSet<Long>()
        for (i in 0 until array.length()) {
            val id = (array.opt(i) as? Number)?.toLong() ?: throw IllegalArgumentException("ids[$i] bukan angka id layer")
            layerOf(editor, id)
            ids += id
        }
        if (ids.isEmpty()) throw IllegalArgumentException("Daftar 'ids' kosong")
        editor.groupLayers(ids)
        val groupId = editor.selectedId
        if (groupId == null || groupId in ids || editor.scene.layer(groupId) == null) {
            throw IllegalArgumentException("Gagal mengelompokkan layer")
        }
        val name = item.optString("nama", "").trim()
        if (name.isNotEmpty()) {
            AiActionExecutor.execute(editor, listOf(AiActionExecutor.AiAction.UbahNama(groupId, name)))
        }
        return Outcome("Mengelompokkan ${ids.size} layer menjadi grup id=$groupId", groupId)
    }

    private fun bongkarGrup(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        layerOf(editor, id)
        editor.selectedId = id
        if (!editor.ungroupSelected()) throw IllegalArgumentException("Layer id=$id bukan grup")
        return Outcome("Membongkar grup id=$id")
    }

    private fun topengPemotong(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        layerOf(editor, id)
        val wanted = flag(item, "nilai", true)
        editor.selectedId = id
        if (!editor.canClip) throw IllegalArgumentException("Layer paling bawah tidak bisa memakai topeng pemotong")
        val current = editor.selected?.node?.get("clippingMask") == "true"
        if (current != wanted) editor.toggleClippingMask()
        return Outcome(if (wanted) "Mengaktifkan topeng pemotong layer id=$id" else "Mematikan topeng pemotong layer id=$id")
    }

    private fun balik(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        layerOf(editor, id)
        val axis = item.optString("sumbu", "horizontal").trim().lowercase()
        val horizontal = when (axis) {
            "horizontal", "h", "x", "kiri_kanan" -> true
            "vertikal", "v", "y", "atas_bawah" -> false
            else -> throw IllegalArgumentException("Sumbu harus 'horizontal' atau 'vertikal'")
        }
        editor.selectedId = id
        editor.flip(horizontal)
        return Outcome("Membalik layer id=$id secara ${if (horizontal) "horizontal" else "vertikal"}")
    }

    // ---------------------------------------------------------------- audio dan video

    private fun aturVolume(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        val layer = layerOf(editor, id)
        if (layer.kind != "audio" && !isVideo(layer)) throw IllegalArgumentException("Layer id=$id bukan audio atau video")
        val percent = number(item, "nilai") ?: throw IllegalArgumentException("Field 'nilai' (persen volume) harus angka")
        if (percent < 0.0) throw IllegalArgumentException("Volume tidak boleh negatif")
        editor.selectedId = id
        editor.setGain((percent / 100.0).toFloat())
        return Outcome("Mengatur volume layer id=$id ke ${percent.toInt()}%")
    }

    private fun ekstrakAudio(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        requireVideo(layerOf(editor, id))
        val before = editor.scene.layers.map { it.id }.toSet()
        editor.selectedId = id
        editor.extractAudio()
        val added = editor.scene.layers.firstOrNull { it.id !in before }?.id
            ?: throw IllegalArgumentException("Gagal mengekstrak audio (video tanpa suara?)")
        return Outcome("Mengekstrak audio video id=$id menjadi layer audio id=$added", added)
    }

    private fun aturKecepatan(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        val original = layerOf(editor, id)
        requireVideo(original)
        val speed = number(item, "nilai") ?: throw IllegalArgumentException("Field 'nilai' (kelipatan kecepatan) harus angka")
        if (speed < 0.05 || speed > 100.0) throw IllegalArgumentException("Kecepatan harus antara 0.05 dan 100")
        editor.selectedId = id
        editor.setVideoSpeed(speed.toFloat(), original)
        if (editor.scene.layer(id) == original) {
            throw IllegalArgumentException("Kecepatan tidak berubah (sudah sama, atau layer memakai remap waktu)")
        }
        return Outcome("Mengatur kecepatan video id=$id ke ${speed}x")
    }

    private fun bekukanFrame(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        val original = layerOf(editor, id)
        requireVideo(original)
        val at = number(item, "waktu")?.toInt()
        val durationMs = (number(item, "durasi") ?: 1000.0).coerceIn(100.0, 30000.0)
        editor.selectedId = id
        atTime(editor, at) { editor.freezeFrame((durationMs / 1000.0).toFloat()) }
        if (editor.scene.layer(id) == original) {
            throw IllegalArgumentException("Gagal membekukan frame (waktu di luar rentang layer?)")
        }
        return Outcome("Membekukan frame video id=$id selama ${durationMs.toInt()} ms")
    }

    private fun putarMundur(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        requireVideo(layerOf(editor, id))
        editor.selectedId = id
        if (editor.selected?.node?.child("timeRemap") == null) editor.turnOnRemap()
        editor.reverseRemap()
        return Outcome("Memutar mundur video id=$id")
    }

    // ---------------------------------------------------------------- timeline

    private fun bookmark(editor: EditorState, item: JSONObject): Outcome {
        val at = number(item, "waktu")?.toInt() ?: throw IllegalArgumentException("Field 'waktu' (ms) harus angka")
        atTime(editor, at) { editor.toggleBookmark() }
        return Outcome("Menandai atau melepas bookmark pada $at ms")
    }

    private fun salinKeyframe(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        layerOf(editor, id)
        val at = number(item, "waktu")?.toInt() ?: throw IllegalArgumentException("Field 'waktu' (ms) harus angka")
        editor.selectedId = id
        val copied = atTime(editor, at) { editor.copyKeyframes() }
        if (!copied) throw IllegalArgumentException("Tidak ada keyframe pada $at ms di layer id=$id")
        return Outcome("Menyalin keyframe layer id=$id pada $at ms")
    }

    private fun tempelKeyframe(editor: EditorState, item: JSONObject): Outcome {
        val id = idOf(item)
        layerOf(editor, id)
        val at = number(item, "waktu")?.toInt() ?: throw IllegalArgumentException("Field 'waktu' (ms) harus angka")
        editor.selectedId = id
        val pasted = atTime(editor, at) { editor.pasteKeyframes() }
        if (!pasted) throw IllegalArgumentException("Tidak ada keyframe tersalin, atau tidak bisa ditempel di layer id=$id")
        return Outcome("Menempel keyframe ke layer id=$id pada $at ms")
    }
}
