package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addAudio
import app.absentmotion.editor.addCamera
import app.absentmotion.editor.addEmptyGroup
import app.absentmotion.editor.addImage
import app.absentmotion.editor.addLight
import app.absentmotion.editor.addShape
import app.absentmotion.editor.addText
import app.absentmotion.editor.addVideo
import app.absentmotion.editor.chain
import app.absentmotion.editor.changeSettings
import app.absentmotion.editor.deleteSelected
import app.absentmotion.editor.duplicateSelected
import app.absentmotion.editor.renameLayer
import app.absentmotion.editor.setParent
import app.absentmotion.editor.split
import app.absentmotion.editor.toggleHidden
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.fonts.FontName
import app.absentmotion.model.CameraFov
import app.absentmotion.model.Easing
import app.absentmotion.model.EasingCatalog
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.model.ShapeDef
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.blend
import app.absentmotion.model.borders
import app.absentmotion.model.canAnimate
import app.absentmotion.model.canSplitAt
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.formatColor
import app.absentmotion.model.formatFloats
import app.absentmotion.model.formatNumber
import app.absentmotion.model.keyframes
import app.absentmotion.model.parseColor
import app.absentmotion.model.removeEffect
import app.absentmotion.model.shadow
import app.absentmotion.model.withBlend
import app.absentmotion.model.withBorderAdded
import app.absentmotion.model.withBorderDirection
import app.absentmotion.model.withBorderRemoved
import app.absentmotion.model.withContent
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.withFont
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withShadowEnabled
import app.absentmotion.model.withTextAlign
import app.absentmotion.model.withTextSize
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

data class AiResult(
    val diterapkan: List<String>,
    val dilewati: List<Pair<String, String>>,
)

fun execute(editor: EditorState, aksi: List<AiActionExecutor.AiAction>): AiResult =
    AiActionExecutor.execute(editor, aksi)

object AiActionExecutor {

    sealed interface AiAction {
        val namaAksi: String

        data class TambahLayer(
            val tipe: String,
            val nama: String? = null,
            val awal: Int? = null,
            val durasi: Int? = null,
            val isi: String? = null,
        ) : AiAction { override val namaAksi = "tambah_layer" }

        data class HapusLayer(val id: Long) : AiAction { override val namaAksi = "hapus_layer" }
        data class DuplikatLayer(val id: Long) : AiAction { override val namaAksi = "duplikat_layer" }
        data class UbahNama(val id: Long, val nama: String) : AiAction { override val namaAksi = "ubah_nama" }
        data class AturWaktu(val id: Long, val awal: Int?, val durasi: Int?) : AiAction { override val namaAksi = "atur_waktu" }
        data class PotongLayer(val id: Long, val waktu: Int) : AiAction { override val namaAksi = "potong_layer" }
        data class AturInduk(val id: Long, val indukId: Long?) : AiAction { override val namaAksi = "atur_induk" }
        data class AturUrutan(val id: Long, val posisi: Any?) : AiAction { override val namaAksi = "atur_urutan" }
        data class Sembunyikan(val id: Long, val nilai: Boolean) : AiAction { override val namaAksi = "sembunyikan" }
        data class Kunci(val id: Long, val nilai: Boolean) : AiAction { override val namaAksi = "kunci" }
        data class AturNilai(val id: Long, val properti: String, val nilai: Any?) : AiAction { override val namaAksi = "atur_nilai" }
        data class Keyframe(val id: Long, val properti: String, val waktu: Int, val nilai: Any?, val easing: String? = null) : AiAction {
            override val namaAksi = "keyframe"
        }
        data class HapusKeyframe(val id: Long, val properti: String, val waktu: Int) : AiAction {
            override val namaAksi = "hapus_keyframe"
        }
        data class TambahEfek(val id: Long, val efek: String, val parameter: Map<String, Any?> = emptyMap()) : AiAction {
            override val namaAksi = "tambah_efek"
        }
        data class UbahEfek(val id: Long, val efek: String, val parameter: Map<String, Any?>) : AiAction {
            override val namaAksi = "ubah_efek"
        }
        data class KeyframeEfek(
            val id: Long,
            val efek: String,
            val namaParameter: String,
            val waktu: Int,
            val nilai: Any?,
            val easing: String? = null,
        ) : AiAction { override val namaAksi = "keyframe_efek" }
        data class HapusEfek(val id: Long, val efek: String) : AiAction { override val namaAksi = "hapus_efek" }
        data class AturTeks(
            val id: Long,
            val isi: String? = null,
            val font: String? = null,
            val ukuran: Float? = null,
            val warna: String? = null,
            val rata: String? = null,
        ) : AiAction { override val namaAksi = "atur_teks" }
        data class AturGaya(
            val id: Long,
            val isiWarna: String? = null,
            val garis: Any? = null,
            val bayangan: Any? = null,
            val modeCampur: String? = null,
        ) : AiAction { override val namaAksi = "atur_gaya" }
        data class AturProyek(
            val rasio: String? = null,
            val resolusi: Any? = null,
            val fps: Int? = null,
            val latar: String? = null,
        ) : AiAction { override val namaAksi = "atur_proyek" }
        data class TidakValid(override val namaAksi: String, val alasan: String) : AiAction
    }

    data class ParsedResponse(val pesan: String, val aksi: List<AiAction>)

    fun parseResponse(json: String): ParsedResponse {
        return try {
            val cleaned = stripCodeFence(json)
            if (cleaned.isEmpty()) return ParsedResponse("", emptyList())
            val array: JSONArray
            val pesan: String
            if (cleaned.startsWith("[")) {
                pesan = ""
                array = JSONArray(cleaned)
            } else {
                val root = JSONObject(cleaned)
                pesan = root.optString("pesan", "")
                array = root.optJSONArray("aksi") ?: JSONArray()
            }
            val list = ArrayList<AiAction>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj == null) {
                    list += AiAction.TidakValid("aksi[$i]", "Elemen aksi bukan objek JSON")
                } else {
                    list += parseActionObject(obj)
                }
            }
            ParsedResponse(pesan, list)
        } catch (_: Exception) {
            ParsedResponse("", emptyList())
        }
    }

    fun parse(json: String): List<AiAction> = parseResponse(json).aksi

    fun execute(editor: EditorState, aksi: List<AiAction>): AiResult =
        execute(editor, aksi, null)

    fun execute(editor: EditorState, aksi: List<AiAction>, catalog: EffectCatalog?): AiResult {
        val applied = ArrayList<String>()
        val skipped = ArrayList<Pair<String, String>>()
        val activeCatalog = catalog ?: editor.engine.effects.value

        try {
            editor.batchEdit {
                for (action in aksi) {
                    try {
                        val summary = executeOne(editor, action, activeCatalog)
                        if (summary != null) applied += summary
                    } catch (e: SkipActionException) {
                        skipped += action.namaAksi to e.alasan
                    } catch (e: Exception) {
                        skipped += action.namaAksi to (e.message?.takeIf { it.isNotBlank() } ?: "Kesalahan tidak terduga")
                    }
                }
            }
        } catch (e: Exception) {
            skipped += "batch" to (e.message?.takeIf { it.isNotBlank() } ?: "Gagal menjalankan batch aksi")
        }

        return AiResult(diterapkan = applied, dilewati = skipped)
    }

    private class SkipActionException(val alasan: String) : RuntimeException(alasan)

    private fun fail(reason: String): Nothing = throw SkipActionException(reason)

    private fun executeOne(editor: EditorState, action: AiAction, catalog: EffectCatalog): String? {
        return when (action) {
            is AiAction.TidakValid -> fail(action.alasan)
            is AiAction.TambahLayer -> execTambahLayer(editor, action)
            is AiAction.HapusLayer -> execHapusLayer(editor, action)
            is AiAction.DuplikatLayer -> execDuplikatLayer(editor, action)
            is AiAction.UbahNama -> execUbahNama(editor, action)
            is AiAction.AturWaktu -> execAturWaktu(editor, action)
            is AiAction.PotongLayer -> execPotongLayer(editor, action)
            is AiAction.AturInduk -> execAturInduk(editor, action)
            is AiAction.AturUrutan -> execAturUrutan(editor, action)
            is AiAction.Sembunyikan -> execSembunyikan(editor, action)
            is AiAction.Kunci -> fail("Aksi 'kunci' belum didukung karena model Layer belum memiliki properti kunci")
            is AiAction.AturNilai -> execAturNilai(editor, action)
            is AiAction.Keyframe -> execKeyframe(editor, action)
            is AiAction.HapusKeyframe -> execHapusKeyframe(editor, action)
            is AiAction.TambahEfek -> execTambahEfek(editor, action, catalog)
            is AiAction.UbahEfek -> execUbahEfek(editor, action, catalog)
            is AiAction.KeyframeEfek -> execKeyframeEfek(editor, action, catalog)
            is AiAction.HapusEfek -> execHapusEfek(editor, action, catalog)
            is AiAction.AturTeks -> execAturTeks(editor, action)
            is AiAction.AturGaya -> execAturGaya(editor, action)
            is AiAction.AturProyek -> execAturProyek(editor, action)
        }
    }

    // --- Layer lifecycle ---

    private fun execTambahLayer(editor: EditorState, action: AiAction.TambahLayer): String {
        val tipe = action.tipe.trim().lowercase(Locale.US)
        if (action.awal != null && action.awal < 0) fail("Waktu awal tidak boleh negatif")
        if (action.durasi != null && action.durasi <= 0) fail("Durasi harus lebih besar dari 0")

        val prevFrame = editor.frame
        if (action.awal != null) {
            editor.frame = editor.scene.frameAt(action.awal).coerceAtLeast(0)
        }
        try {
            when (tipe) {
                "bentuk", "shape" -> {
                    val def = resolveShape(action.isi)
                    editor.addShape(def)
                }
                "teks", "text" -> {
                    editor.addText()
                    val isi = action.isi
                    if (isi != null) {
                        editor.editSelected { it.withContent(isi) }
                    }
                }
                "kamera", "camera" -> editor.addCamera()
                "cahaya", "light" -> editor.addLight()
                "grup", "group" -> editor.addEmptyGroup()
                "gambar", "image", "video", "audio" -> {
                    val uriOrTitle = action.isi?.trim().orEmpty()
                    if (uriOrTitle.isEmpty()) fail("Menambah layer '$tipe' memerlukan isi URI media yang sudah terdaftar")
                    val media = editor.scene.media.firstOrNull { it.uri == uriOrTitle || it.title.equals(uriOrTitle, ignoreCase = true) }
                        ?: fail("Media '$uriOrTitle' tidak ditemukan di proyek")
                    when (tipe) {
                        "gambar", "image" -> editor.addImage(media)
                        "video" -> editor.addVideo(media)
                        else -> editor.addAudio(media)
                    }
                }
                else -> fail("Tipe layer '$tipe' tidak dikenal")
            }

            val createdId = editor.selectedId ?: fail("Layer baru gagal ditambahkan")
            val nama = action.nama?.trim()?.takeIf { it.isNotEmpty() }
            val startMs = action.awal ?: editor.scene.layer(createdId)?.startTime ?: 0
            val durMs = action.durasi
            if (nama != null || action.awal != null || durMs != null) {
                editor.edit { s ->
                    s.update(createdId) { layer ->
                        var updated = if (nama != null) layer.withLabel(nama) else layer
                        if (action.awal != null || durMs != null) {
                            val currentDur = (updated.endTime - updated.startTime + 1).coerceAtLeast(1)
                            val targetDur = durMs ?: currentDur
                            val endMs = s.endTimeFor(s.frameAt(startMs), targetDur)
                            updated = updated.withTimes(startMs, maxOf(startMs, endMs))
                        }
                        updated
                    }
                }
            }
            val finalLayer = editor.scene.layer(createdId)
            return "Menambah layer $tipe '${finalLayer?.label.orEmpty()}' (id=$createdId)"
        } finally {
            editor.frame = prevFrame
        }
    }

    private fun execHapusLayer(editor: EditorState, action: AiAction.HapusLayer): String {
        val layer = requireLayer(editor.scene, action.id)
        val label = layer.label
        editor.selectedId = layer.id
        editor.deleteSelected()
        return "Menghapus layer '$label' (id=${action.id})"
    }

    private fun execDuplikatLayer(editor: EditorState, action: AiAction.DuplikatLayer): String {
        val layer = requireLayer(editor.scene, action.id)
        editor.selectedId = layer.id
        editor.duplicateSelected()
        val copyId = editor.selectedId
        return "Menduplikasi layer '${layer.label}' (id=${action.id} -> id=$copyId)"
    }

    private fun execUbahNama(editor: EditorState, action: AiAction.UbahNama): String {
        requireLayer(editor.scene, action.id)
        val nama = action.nama.trim()
        if (nama.isEmpty()) fail("Nama layer baru tidak boleh kosong")
        editor.renameLayer(action.id, nama)
        return "Mengubah nama layer id=${action.id} menjadi '$nama'"
    }

    private fun execAturWaktu(editor: EditorState, action: AiAction.AturWaktu): String {
        val layer = requireLayer(editor.scene, action.id)
        if (action.awal == null && action.durasi == null) fail("Field 'awal' atau 'durasi' harus diisi")
        if (action.awal != null && action.awal < 0) fail("Waktu awal tidak boleh negatif")
        if (action.durasi != null && action.durasi <= 0) fail("Durasi harus lebih besar dari 0")
        val startMs = action.awal ?: layer.startTime
        val durMs = action.durasi ?: (layer.endTime - layer.startTime + 1).coerceAtLeast(1)
        val endMs = maxOf(startMs, editor.scene.endTimeFor(editor.scene.frameAt(startMs), durMs))
        editor.edit { s -> s.update(action.id) { it.withTimes(startMs, endMs) } }
        return "Mengatur waktu layer id=${action.id} ($startMs..$endMs ms)"
    }

    private fun execPotongLayer(editor: EditorState, action: AiAction.PotongLayer): String {
        val layer = requireLayer(editor.scene, action.id)
        if (action.waktu < 0) fail("Waktu potong tidak boleh negatif")
        val targetFrame = editor.scene.frameAt(action.waktu)
        if (!editor.scene.canSplitAt(layer, targetFrame)) {
            fail("Waktu potong ${action.waktu} ms tidak berada di dalam rentang aktif layer (${layer.startTime}..${layer.endTime} ms)")
        }
        val prevFrame = editor.frame
        editor.frame = targetFrame
        editor.selectedId = layer.id
        try {
            editor.split()
        } finally {
            editor.frame = prevFrame.coerceIn(0, maxOf(editor.scene.lastFrame, 0))
        }
        return "Memotong layer id=${action.id} pada ${action.waktu} ms"
    }

    private fun execAturInduk(editor: EditorState, action: AiAction.AturInduk): String {
        val layer = requireLayer(editor.scene, action.id)
        val parentId = action.indukId?.takeIf { it > 0L }
        if (parentId != null) {
            if (parentId == layer.id) fail("Layer tidak boleh menjadi induk bagi dirinya sendiri")
            val parentLayer = editor.scene.layer(parentId) ?: fail("Layer induk id=$parentId tidak ditemukan")
            if (editor.scene.chain(parentLayer).any { it.id == layer.id }) {
                fail("Penetapan induk id=$parentId akan membuat siklus induk")
            }
        }
        val prevSelected = editor.selectedId
        editor.selectedId = layer.id
        try {
            editor.setParent(parentId)
        } finally {
            if (prevSelected != null && editor.scene.layer(prevSelected) != null) editor.selectedId = prevSelected
        }
        return if (parentId == null) "Melepas induk layer id=${action.id}" else "Mengatur induk layer id=${action.id} ke id=$parentId"
    }

    private fun execAturUrutan(editor: EditorState, action: AiAction.AturUrutan): String {
        requireLayer(editor.scene, action.id)
        val list = editor.scene.layers
        val currentIdx = list.indexOfFirst { it.id == action.id }
        if (currentIdx < 0) fail("Layer id=${action.id} tidak ditemukan")
        val targetIdx = resolveOrderIndex(action.posisi, currentIdx, list.lastIndex)
            ?: fail("Posisi urutan tidak valid: '${action.posisi}'")
        editor.edit { s -> s.reorder(action.id, targetIdx) }
        return "Mengubah urutan layer id=${action.id} ke indeks $targetIdx"
    }

    private fun execSembunyikan(editor: EditorState, action: AiAction.Sembunyikan): String {
        val layer = requireLayer(editor.scene, action.id)
        if (layer.hidden != action.nilai) {
            editor.toggleHidden(action.id)
        }
        return if (action.nilai) "Menyembunyikan layer id=${action.id}" else "Menampilkan layer id=${action.id}"
    }

    // --- Animatable properties & keyframes ---

    private fun execAturNilai(editor: EditorState, action: AiAction.AturNilai): String {
        val layer = editor.scene.layerAt(action.id, editor.sampleMs)
            ?: fail("Layer dengan id=${action.id} tidak ditemukan")
        val prop = resolveProperty(action.properti) ?: fail("Properti '${action.properti}' tidak dikenal")
        val updated = applyPropertyValue(layer, prop, action.nilai)
        editor.edit { s -> s.update(action.id, editor.sampleMs) { updated } }
        return "Mengatur '${action.properti}' pada layer id=${action.id}"
    }

    private fun execKeyframe(editor: EditorState, action: AiAction.Keyframe): String {
        val base = requireLayer(editor.scene, action.id)
        val clampedMs = clampKeyframeTime(base, action.waktu)
        val prop = resolveProperty(action.properti) ?: fail("Properti '${action.properti}' tidak dikenal")
        val easing = parseEasing(action.easing) ?: fail("Jenis easing '${action.easing}' tidak dikenal")
        val atLayer = editor.scene.layerAt(action.id, clampedMs)
            ?: fail("Layer dengan id=${action.id} tidak ditemukan")
        val spec = buildPropertySpec(atLayer, prop, action.nilai)

        editor.edit { s ->
            s.update(action.id, clampedMs) { layer ->
                applyKeyframeToLayer(layer, spec.ref, spec.value, spec.type, spec.fallback, easing)
            }
        }
        return "Menambah keyframe '${action.properti}' pada layer id=${action.id} di $clampedMs ms"
    }

    private fun execHapusKeyframe(editor: EditorState, action: AiAction.HapusKeyframe): String {
        val base = requireLayer(editor.scene, action.id)
        val clampedMs = clampKeyframeTime(base, action.waktu)
        val prop = resolveProperty(action.properti) ?: fail("Properti '${action.properti}' tidak dikenal")
        val atLayer = editor.scene.layerAt(action.id, clampedMs)
            ?: fail("Layer dengan id=${action.id} tidak ditemukan")
        if (!atLayer.onKeyframe(prop.ref)) {
            fail("Tidak ada keyframe pada properti '${action.properti}' di $clampedMs ms")
        }
        editor.edit { s ->
            s.update(action.id, clampedMs) { it.withKeyframeToggled(prop.ref, prop.defaultValue) }
        }
        return "Menghapus keyframe '${action.properti}' pada layer id=${action.id} di $clampedMs ms"
    }

    // --- Effects ---

    private fun execTambahEfek(editor: EditorState, action: AiAction.TambahEfek, catalog: EffectCatalog): String {
        val layer = requireLayer(editor.scene, action.id)
        if (!layer.canAnimate) fail("Layer jenis '${layer.kind}' tidak mendukung efek visual")
        val def = findEffectDefinition(catalog, action.efek)
            ?: fail("Efek '${action.efek}' tidak ditemukan di katalog")
        val validated = validateEffectParams(def, action.parameter, requireExplicitChoice = true)
        var instance = def.instance()
        for ((param, formatted) in validated) {
            instance = instance.withEffectProperty(param.id, param.type, formatted, 0f, 0f..0f)
        }
        editor.edit { s -> s.update(action.id) { it.addEffect(instance) } }
        return "Menambah efek '${def.key}' pada layer id=${action.id}"
    }

    private fun execUbahEfek(editor: EditorState, action: AiAction.UbahEfek, catalog: EffectCatalog): String {
        val layer = editor.scene.layerAt(action.id, editor.sampleMs)
            ?: fail("Layer dengan id=${action.id} tidak ditemukan")
        if (action.parameter.isEmpty()) fail("Parameter efek tidak boleh kosong")
        val def = findEffectDefinition(catalog, action.efek)
            ?: fail("Efek '${action.efek}' tidak ditemukan di katalog")
        val idx = findInstalledEffectIndex(layer, def.id, action.efek)
        if (idx < 0) fail("Efek '${def.key}' belum terpasang pada layer id=${action.id}")
        val validated = validateEffectParams(def, action.parameter, requireExplicitChoice = true)
        editor.edit { s ->
            s.update(action.id, editor.sampleMs) { l ->
                l.withEffect(idx) { node ->
                    var next = node
                    for ((param, formatted) in validated) {
                        next = next.withEffectProperty(param.id, param.type, formatted, l.t, l.snap)
                    }
                    next
                }
            }
        }
        return "Mengubah efek '${def.key}' pada layer id=${action.id}"
    }

    private fun execKeyframeEfek(editor: EditorState, action: AiAction.KeyframeEfek, catalog: EffectCatalog): String {
        val base = requireLayer(editor.scene, action.id)
        val def = findEffectDefinition(catalog, action.efek)
            ?: fail("Efek '${action.efek}' tidak ditemukan di katalog")
        val idx = findInstalledEffectIndex(base, def.id, action.efek)
        if (idx < 0) fail("Efek '${def.key}' belum terpasang pada layer id=${action.id}")
        val param = findEffectParam(def, action.namaParameter)
            ?: fail("Parameter '${action.namaParameter}' tidak ada pada efek '${def.key}'")
        val clampedMs = clampKeyframeTime(base, action.waktu)
        val formatted = formatEffectParamValue(param, action.nilai)
        val easing = parseEasing(action.easing) ?: fail("Jenis easing '${action.easing}' tidak dikenal")
        val ref = PropRef.effect(idx, param.id)

        editor.edit { s ->
            s.update(action.id, clampedMs) { layer ->
                val ensured = if (layer.element(ref) != null) layer
                else layer.withEffect(idx) { it.withEffectProperty(param.id, param.type, param.default, layer.t, layer.snap) }
                applyKeyframeToLayer(ensured, ref, formatted, param.type, param.default, easing)
            }
        }
        return "Menambah keyframe efek '${def.key}.${param.id}' pada layer id=${action.id} di $clampedMs ms"
    }

    private fun execHapusEfek(editor: EditorState, action: AiAction.HapusEfek, catalog: EffectCatalog): String {
        val layer = requireLayer(editor.scene, action.id)
        val defId = findEffectDefinition(catalog, action.efek)?.id
        val idx = findInstalledEffectIndex(layer, defId, action.efek)
        if (idx < 0) fail("Efek '${action.efek}' tidak ditemukan pada layer id=${action.id}")
        editor.edit { s -> s.update(action.id) { it.removeEffect(idx) } }
        return "Menghapus efek '${action.efek}' dari layer id=${action.id}"
    }

    // --- Text, Style, Project ---

    private fun execAturTeks(editor: EditorState, action: AiAction.AturTeks): String {
        val layer = editor.scene.layerAt(action.id, editor.sampleMs)
            ?: fail("Layer dengan id=${action.id} tidak ditemukan")
        if (layer.kind != "text") fail("Layer id=${action.id} bukan layer teks")
        if (action.isi == null && action.font == null && action.ukuran == null && action.warna == null && action.rata == null) {
            fail("Minimal satu properti teks harus diisi")
        }
        val colorInt = action.warna?.let { parseColorArgb(it) ?: fail("Format warna teks tidak valid: '$it'") }
        val align = action.rata?.let { normalizeAlign(it) ?: fail("Perataan teks tidak dikenal: '$it'") }
        val size = action.ukuran?.let {
            if (!it.isFinite() || it <= 0f) fail("Ukuran teks harus lebih besar dari 0")
            it.coerceIn(1f, 500f)
        }
        val fontXml = action.font?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveFontXml(it) }

        editor.edit { s ->
            s.update(action.id, editor.sampleMs) { l ->
                var next = l
                action.isi?.let { next = next.withContent(it) }
                fontXml?.let { next = next.withFont(it) }
                size?.let { next = next.withTextSize(it) }
                colorInt?.let { next = next.withFill("color", it) }
                align?.let { next = next.withTextAlign(it) }
                next
            }
        }
        return "Mengatur teks layer id=${action.id}"
    }

    private fun execAturGaya(editor: EditorState, action: AiAction.AturGaya): String {
        requireLayer(editor.scene, action.id)
        if (action.isiWarna == null && action.garis == null && action.bayangan == null && action.modeCampur == null) {
            fail("Minimal satu properti gaya harus diisi")
        }
        val fillParsed = action.isiWarna?.let { raw ->
            val trimmed = raw.trim().lowercase(Locale.US)
            if (trimmed == "none" || trimmed == "hapus" || trimmed == "transparan") null to true
            else (parseColorArgb(raw) ?: fail("Format warna isi tidak valid: '$raw'")) to false
        }
        val blendId = action.modeCampur?.let { resolveBlendMode(it) ?: fail("Mode campur '$it' tidak dikenal") }

        editor.edit { s ->
            s.update(action.id, editor.sampleMs) { l ->
                var next = l
                if (fillParsed != null) {
                    val (color, none) = fillParsed
                    next = if (none || color == null) next.withFill("none") else next.withFill("color", color)
                }
                if (action.garis != null) {
                    next = applyBorderStyle(next, action.garis)
                }
                if (action.bayangan != null) {
                    next = applyShadowStyle(next, action.bayangan)
                }
                if (blendId != null) {
                    next = next.withBlend(blendId)
                }
                next
            }
        }
        return "Mengatur gaya layer id=${action.id}"
    }

    private fun execAturProyek(editor: EditorState, action: AiAction.AturProyek): String {
        val s = editor.scene
        if (action.rasio == null && action.resolusi == null && action.fps == null && action.latar == null) {
            fail("Minimal satu pengaturan proyek harus diisi")
        }
        var width = s.width
        var height = s.height

        if (action.resolusi != null) {
            val (w, h) = parseResolution(action.resolusi, width, height)
                ?: fail("Format resolusi tidak valid: '${action.resolusi}'")
            width = w
            height = h
        } else if (action.rasio != null) {
            val (w, h) = parseAspectRatio(action.rasio)
                ?: fail("Format rasio aspek tidak valid: '${action.rasio}'")
            width = w
            height = h
        }

        val fps = action.fps?.let {
            if (it !in 1..240) fail("FPS harus berada di antara 1 dan 240")
            it
        } ?: s.fps

        val bgColor = action.latar?.let {
            parseColorArgb(it) ?: fail("Format warna latar tidak valid: '$it'")
        } ?: s.bgColor

        editor.changeSettings(width, height, fps, bgColor)
        return "Mengatur proyek (${width}x$height, ${fps}fps)"
    }

    // --- Helpers: Lookup, Units, Properties, Keyframes ---

    private fun requireLayer(scene: Scene, id: Long): Layer =
        scene.layer(id) ?: fail("Layer dengan id=$id tidak ditemukan")

    private fun clampKeyframeTime(layer: Layer, waktuMs: Int): Int {
        if (waktuMs < 0) fail("Waktu keyframe tidak boleh negatif ($waktuMs ms)")
        val tolerance = 100
        if (waktuMs < layer.startTime - tolerance || waktuMs > layer.endTime + tolerance) {
            fail("Waktu $waktuMs ms di luar rentang layer (${layer.startTime}..${layer.endTime} ms)")
        }
        return waktuMs.coerceIn(layer.startTime, layer.endTime)
    }

    private enum class PropKey(val ref: PropRef, val type: String, val defaultValue: String) {
        POS_X(PropRef.Location, "vec3", "0,0,0"),
        POS_Y(PropRef.Location, "vec3", "0,0,0"),
        POS_Z(PropRef.Location, "vec3", "0,0,0"),
        POS(PropRef.Location, "vec3", "0,0,0"),
        PIVOT_X(PropRef.Pivot, "vec3", "0,0,0"),
        PIVOT_Y(PropRef.Pivot, "vec3", "0,0,0"),
        PIVOT(PropRef.Pivot, "vec3", "0,0,0"),
        SCALE_X(PropRef.Scale, "vec2", "1,1"),
        SCALE_Y(PropRef.Scale, "vec2", "1,1"),
        SCALE(PropRef.Scale, "vec2", "1,1"),
        OPACITY(PropRef.Opacity, "float", "1"),
        ROTATION(PropRef.Rotation, "float", "0"),
        SKEW_X(PropRef.Skew, "vec2", "0,0"),
        SKEW_Y(PropRef.Skew, "vec2", "0,0"),
        SKEW(PropRef.Skew, "vec2", "0,0"),
        TILT_X(PropRef.Tilt, "vec2", "0,0"),
        TILT_Y(PropRef.Tilt, "vec2", "0,0"),
        TILT(PropRef.Tilt, "vec2", "0,0"),
        FILL_COLOR(PropRef.FillColor, "color", "#ffffffff"),
        SIZE(PropRef.shape("size"), "vec2", "100,100"),
        CORNER_RADIUS(PropRef.shape("cornerRadius"), "float", "25"),
        FOV(CameraFov, "float", "60"),
    }

    private fun resolveProperty(raw: String): PropKey? {
        return when (raw.trim().lowercase(Locale.US)) {
            "posisi_x", "x", "position_x", "pos_x" -> PropKey.POS_X
            "posisi_y", "y", "position_y", "pos_y" -> PropKey.POS_Y
            "posisi_z", "z", "position_z", "pos_z" -> PropKey.POS_Z
            "posisi", "lokasi", "location", "position" -> PropKey.POS
            "poros_x", "pivot_x" -> PropKey.PIVOT_X
            "poros_y", "pivot_y" -> PropKey.PIVOT_Y
            "poros", "pivot" -> PropKey.PIVOT
            "skala_x", "scale_x" -> PropKey.SCALE_X
            "skala_y", "scale_y" -> PropKey.SCALE_Y
            "skala", "scale" -> PropKey.SCALE
            "opasitas", "opacity", "alpha" -> PropKey.OPACITY
            "rotasi", "rotation", "sudut", "angle" -> PropKey.ROTATION
            "miring_x", "skew_x" -> PropKey.SKEW_X
            "miring_y", "skew_y" -> PropKey.SKEW_Y
            "miring", "skew" -> PropKey.SKEW
            "tilt_x", "kemiringan_3d_x" -> PropKey.TILT_X
            "tilt_y", "kemiringan_3d_y" -> PropKey.TILT_Y
            "tilt", "kemiringan_3d" -> PropKey.TILT
            "warna", "isi_warna", "fillcolor", "fill_color", "color" -> PropKey.FILL_COLOR
            "ukuran", "size" -> PropKey.SIZE
            "radius_sudut", "cornerradius", "corner_radius" -> PropKey.CORNER_RADIUS
            "fov" -> PropKey.FOV
            else -> null
        }
    }

    private data class PropertySpec(val ref: PropRef, val value: String, val type: String, val fallback: String)

    private fun buildPropertySpec(layer: Layer, key: PropKey, raw: Any?): PropertySpec {
        val valueStr = when (key) {
            PropKey.POS_X -> {
                val x = asFloat(raw) ?: fail("Nilai posisi_x harus angka")
                val cur = layer.location
                formatFloats(floatArrayOf(x, cur[1], cur[2]))
            }
            PropKey.POS_Y -> {
                val y = asFloat(raw) ?: fail("Nilai posisi_y harus angka")
                val cur = layer.location
                formatFloats(floatArrayOf(cur[0], y, cur[2]))
            }
            PropKey.POS_Z -> {
                val z = asFloat(raw) ?: fail("Nilai posisi_z harus angka")
                val cur = layer.location
                formatFloats(floatArrayOf(cur[0], cur[1], z))
            }
            PropKey.POS -> {
                val nums = asFloatList(raw, 2, 3) ?: fail("Nilai posisi harus [x, y] atau [x, y, z]")
                val cur = layer.location
                formatFloats(floatArrayOf(nums[0], nums[1], nums.getOrElse(2) { cur[2] }))
            }
            PropKey.PIVOT_X -> {
                val x = asFloat(raw) ?: fail("Nilai poros_x harus angka")
                val cur = layer.pivot
                formatFloats(floatArrayOf(x, cur[1], cur[2]))
            }
            PropKey.PIVOT_Y -> {
                val y = asFloat(raw) ?: fail("Nilai poros_y harus angka")
                val cur = layer.pivot
                formatFloats(floatArrayOf(cur[0], y, cur[2]))
            }
            PropKey.PIVOT -> {
                val nums = asFloatList(raw, 2, 3) ?: fail("Nilai poros harus [x, y]")
                val cur = layer.pivot
                formatFloats(floatArrayOf(nums[0], nums[1], nums.getOrElse(2) { cur[2] }))
            }
            PropKey.SCALE_X -> {
                val sx = (asFloat(raw) ?: fail("Nilai skala_x harus angka")) / 100f
                val cur = layer.scale
                formatFloats(floatArrayOf(sx, cur[1]))
            }
            PropKey.SCALE_Y -> {
                val sy = (asFloat(raw) ?: fail("Nilai skala_y harus angka")) / 100f
                val cur = layer.scale
                formatFloats(floatArrayOf(cur[0], sy))
            }
            PropKey.SCALE -> {
                val single = asFloat(raw)
                if (single != null) {
                    val ratio = single / 100f
                    formatFloats(floatArrayOf(ratio, ratio))
                } else {
                    val pair = asFloatList(raw, 2, 2) ?: fail("Nilai skala (persen) harus angka atau [sx, sy]")
                    formatFloats(floatArrayOf(pair[0] / 100f, pair[1] / 100f))
                }
            }
            PropKey.OPACITY -> {
                val op = (asFloat(raw) ?: fail("Nilai opasitas (0..100) harus angka")).coerceIn(0f, 100f) / 100f
                formatFloats(floatArrayOf(op))
            }
            PropKey.ROTATION -> {
                val deg = asFloat(raw) ?: fail("Nilai rotasi (derajat) harus angka")
                formatFloats(floatArrayOf(-deg))
            }
            PropKey.SKEW_X -> {
                val xDeg = asFloat(raw) ?: fail("Nilai miring_x (derajat) harus angka")
                val cur = layer.skew
                formatFloats(floatArrayOf(Math.toRadians(xDeg.toDouble()).toFloat(), Math.toRadians(cur[1].toDouble()).toFloat()))
            }
            PropKey.SKEW_Y -> {
                val yDeg = asFloat(raw) ?: fail("Nilai miring_y (derajat) harus angka")
                val cur = layer.skew
                formatFloats(floatArrayOf(Math.toRadians(cur[0].toDouble()).toFloat(), Math.toRadians(yDeg.toDouble()).toFloat()))
            }
            PropKey.SKEW -> {
                val single = asFloat(raw)
                if (single != null) {
                    formatFloats(floatArrayOf(Math.toRadians(single.toDouble()).toFloat(), 0f))
                } else {
                    val pair = asFloatList(raw, 2, 2) ?: fail("Nilai miring (derajat) harus angka atau [x, y]")
                    formatFloats(floatArrayOf(Math.toRadians(pair[0].toDouble()).toFloat(), Math.toRadians(pair[1].toDouble()).toFloat()))
                }
            }
            PropKey.TILT_X -> {
                val x = asFloat(raw) ?: fail("Nilai tilt_x harus angka")
                val cur = layer.tilt
                formatFloats(floatArrayOf(x, cur[1]))
            }
            PropKey.TILT_Y -> {
                val y = asFloat(raw) ?: fail("Nilai tilt_y harus angka")
                val cur = layer.tilt
                formatFloats(floatArrayOf(cur[0], y))
            }
            PropKey.TILT -> {
                val pair = asFloatList(raw, 2, 2) ?: fail("Nilai tilt harus [x, y]")
                formatFloats(floatArrayOf(pair[0], pair[1]))
            }
            PropKey.FILL_COLOR -> {
                val color = parseColorArgb(raw) ?: fail("Format warna tidak valid: '$raw'")
                formatColor(color)
            }
            PropKey.SIZE -> {
                val single = asFloat(raw)
                if (single != null) formatFloats(floatArrayOf(single, single))
                else {
                    val pair = asFloatList(raw, 2, 2) ?: fail("Nilai ukuran harus angka atau [w, h]")
                    formatFloats(floatArrayOf(pair[0], pair[1]))
                }
            }
            PropKey.CORNER_RADIUS -> {
                val r = (asFloat(raw) ?: fail("Nilai radius_sudut harus angka")).coerceAtLeast(0f)
                formatFloats(floatArrayOf(r))
            }
            PropKey.FOV -> {
                val fov = (asFloat(raw) ?: fail("Nilai fov harus angka")).coerceIn(5f, 160f)
                formatFloats(floatArrayOf(fov))
            }
        }
        return PropertySpec(key.ref, valueStr, key.type, key.defaultValue)
    }

    private fun applyPropertyValue(layer: Layer, key: PropKey, raw: Any?): Layer {
        return when (key) {
            PropKey.POS_X, PropKey.POS_Y, PropKey.POS_Z, PropKey.POS -> {
                val spec = buildPropertySpec(layer, key, raw)
                val v = spec.value.split(',').map { it.toFloat() }
                layer.withTransform("location", v[0], v[1], v[2])
            }
            PropKey.PIVOT_X, PropKey.PIVOT_Y, PropKey.PIVOT -> {
                val spec = buildPropertySpec(layer, key, raw)
                val v = spec.value.split(',').map { it.toFloat() }
                layer.withTransform("pivot", v[0], v[1], v[2])
            }
            PropKey.SCALE_X, PropKey.SCALE_Y, PropKey.SCALE -> {
                val spec = buildPropertySpec(layer, key, raw)
                val v = spec.value.split(',').map { it.toFloat() }
                layer.withTransform("scale", v[0], v[1])
            }
            PropKey.OPACITY -> {
                val op = (asFloat(raw) ?: fail("Nilai opasitas (0..100) harus angka")).coerceIn(0f, 100f) / 100f
                layer.withTransform("opacity", op)
            }
            PropKey.ROTATION -> {
                val deg = asFloat(raw) ?: fail("Nilai rotasi (derajat) harus angka")
                layer.withRotation(deg)
            }
            PropKey.SKEW_X -> {
                val xDeg = asFloat(raw) ?: fail("Nilai miring_x (derajat) harus angka")
                layer.withSkew(xDeg, layer.skew[1])
            }
            PropKey.SKEW_Y -> {
                val yDeg = asFloat(raw) ?: fail("Nilai miring_y (derajat) harus angka")
                layer.withSkew(layer.skew[0], yDeg)
            }
            PropKey.SKEW -> {
                val single = asFloat(raw)
                if (single != null) layer.withSkew(single, 0f)
                else {
                    val pair = asFloatList(raw, 2, 2) ?: fail("Nilai miring (derajat) harus angka atau [x, y]")
                    layer.withSkew(pair[0], pair[1])
                }
            }
            PropKey.TILT_X -> layer.withTilt(asFloat(raw) ?: fail("Nilai tilt_x harus angka"), layer.tilt[1])
            PropKey.TILT_Y -> layer.withTilt(layer.tilt[0], asFloat(raw) ?: fail("Nilai tilt_y harus angka"))
            PropKey.TILT -> {
                val pair = asFloatList(raw, 2, 2) ?: fail("Nilai tilt harus [x, y]")
                layer.withTilt(pair[0], pair[1])
            }
            PropKey.FILL_COLOR -> {
                val color = parseColorArgb(raw) ?: fail("Format warna tidak valid: '$raw'")
                layer.withFill("color", color)
            }
            PropKey.SIZE, PropKey.CORNER_RADIUS -> {
                val spec = buildPropertySpec(layer, key, raw)
                layer.withProp(key.ref.name, spec.type, spec.value)
            }
            PropKey.FOV -> {
                val spec = buildPropertySpec(layer, key, raw)
                layer.withValue(key.ref, spec.value)
            }
        }
    }

    private fun applyKeyframeToLayer(
        layer: Layer,
        ref: PropRef,
        value: String,
        type: String,
        fallback: String,
        easing: Easing,
    ): Layer {
        val ensured = when {
            layer.element(ref) != null -> layer
            ref.kind == PropRef.Kind.Property -> layer.withProp(ref.name, type, fallback)
            else -> layer.withValue(ref, fallback)
        }
        val toggled = if (ensured.onKeyframe(ref)) ensured else ensured.withKeyframeToggled(ref, fallback)
        val withVal = toggled.withValue(ref, value)
        if (easing.isLinear && ref.kind != PropRef.Kind.Effect) {
            // Clear custom easing back to linear if explicitly requested
            val el = withVal.element(ref) ?: return withVal
            val idx = el.keyframes.indexOfFirst { it.t in withVal.snap }
            if (idx < 0 || el.keyframes[idx].e == null) return withVal
            val updatedKeys = el.keyframes.toMutableList().also { it[idx] = it[idx].copy(e = null) }
            return withVal.withElement(ref, el.withKeyframes(updatedKeys))
        }
        val el = withVal.element(ref) ?: return withVal
        val idx = el.keyframes.indexOfFirst { it.t in withVal.snap }
        if (idx < 0) return withVal
        val easingText = easing.takeUnless { it.isLinear }?.toString()
        val updatedKeys = el.keyframes.toMutableList().also { it[idx] = it[idx].copy(e = easingText) }
        return withVal.withElement(ref, el.withKeyframes(updatedKeys))
    }

    // --- Helpers: Easing ---

    private fun parseEasing(raw: String?): Easing? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Easing.Linear
        val parts = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val prefixes = parts.takeWhile { it.equals("local", true) || it.equals("reverse", true) }.map { it.lowercase(Locale.US) }
        val token = parts.getOrNull(prefixes.size) ?: return Easing.Linear
        val canonical = when (token.lowercase(Locale.US)) {
            "linear" -> "linear"
            "cubicbezier", "cubic-bezier", "cubic_bezier", "easeinout", "ease-in-out" -> "cubicBezier"
            "easein", "ease-in" -> return Easing("cubicBezier", listOf(0.42f, 0f, 1f, 1f), prefixes)
            "easeout", "ease-out" -> return Easing("cubicBezier", listOf(0f, 0f, 0.58f, 1f), prefixes)
            "bounce" -> "bounce"
            "elastic" -> "elastic"
            "cyclic" -> "cyclic"
            "random" -> "random"
            "step", "steps" -> "step"
            "elasticstep", "elastic-step", "elastic_step" -> "elasticStep"
            else -> return null
        }
        val rest = parts.drop(prefixes.size + 1)
        if (rest.isNotEmpty()) {
            val reconstructed = (prefixes + canonical + rest).joinToString(" ")
            val parsed = Easing.parse(reconstructed)
            if (parsed.type == canonical || canonical == "linear") return parsed
        }
        return when (canonical) {
            "linear" -> Easing("linear", emptyList(), prefixes)
            "cubicBezier" -> Easing("cubicBezier", listOf(0.42f, 0f, 0.58f, 1f), prefixes)
            else -> EasingCatalog.families.flatten().firstOrNull { it.easing.type == canonical }?.easing?.copy(prefixes = prefixes)
        }
    }

    // --- Helpers: Effects ---

    private fun findEffectDefinition(catalog: EffectCatalog, efek: String): EffectDefinition? {
        val key = efek.trim()
        if (key.isEmpty()) return null
        return catalog.definitions.firstOrNull {
            it.key.equals(key, ignoreCase = true) ||
                it.id.equals(key, ignoreCase = true) ||
                it.id.equals("absent.$key", ignoreCase = true)
        }
    }

    private fun findInstalledEffectIndex(layer: Layer, defId: String?, rawName: String): Int {
        val clean = rawName.trim()
        return layer.effects.indexOfFirst { node ->
            val id = node["id"] ?: ""
            (defId != null && id.equals(defId, ignoreCase = true)) ||
                id.equals(clean, ignoreCase = true) ||
                id.equals("absent.$clean", ignoreCase = true)
        }
    }

    private fun findEffectParam(def: EffectDefinition, name: String): EffectParam? {
        val trimmed = name.trim()
        return def.params.firstOrNull { it.id == trimmed }
            ?: def.params.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
    }

    private fun validateEffectParams(
        def: EffectDefinition,
        params: Map<String, Any?>,
        requireExplicitChoice: Boolean,
    ): List<Pair<EffectParam, String>> {
        val out = ArrayList<Pair<EffectParam, String>>(params.size)
        for ((name, rawVal) in params) {
            val param = findEffectParam(def, name)
                ?: fail("Parameter '$name' tidak ditemukan pada efek '${def.key}'")
            if (param.kind == "choice" && requireExplicitChoice && rawVal == null) continue
            out += param to formatEffectParamValue(param, rawVal)
        }
        return out
    }

    private fun formatEffectParamValue(param: EffectParam, raw: Any?): String {
        return when (param.kind) {
            "float", "angle" -> {
                val n = asFloat(raw) ?: fail("Parameter '${param.id}' memerlukan nilai angka")
                param.format(n.coerceIn(param.min, param.max))
            }
            "choice" -> {
                val idx = resolveChoiceIndex(param, raw)
                    ?: fail("Nilai pilihan tidak valid untuk '${param.id}': '$raw'")
                param.format(idx)
            }
            "switch" -> {
                val b = asBoolean(raw) ?: fail("Parameter '${param.id}' memerlukan nilai boolean")
                param.format(b)
            }
            "color" -> {
                val argb = parseColorArgb(raw) ?: fail("Format warna untuk '${param.id}' tidak valid: '$raw'")
                param.format(formatColor(argb))
            }
            "point" -> {
                val pair = asFloatList(raw, 2, 2) ?: fail("Parameter titik '${param.id}' memerlukan [x, y]")
                param.format(JSONArray(listOf(pair[0].toDouble(), pair[1].toDouble())))
            }
            "text" -> {
                val s = raw?.toString() ?: fail("Parameter teks '${param.id}' tidak boleh null")
                param.format(s)
            }
            else -> fail("Tipe parameter '${param.kind}' tidak didukung")
        }
    }

    private fun resolveChoiceIndex(param: EffectParam, raw: Any?): Int? {
        if (raw == null || param.options.isEmpty()) return null
        val num = asFloat(raw)?.roundToInt()
        if (num != null && num in param.options.indices) return num
        val text = raw.toString().trim()
        val idx = param.options.indexOfFirst { it.equals(text, ignoreCase = true) }
        return idx.takeIf { it in param.options.indices }
    }

    // --- Helpers: Shapes, Fonts, Styles, Colors, Order, Project ---

    private fun resolveShape(isi: String?): ShapeDef {
        val raw = isi?.trim().orEmpty()
        val targetId = when (raw.lowercase(Locale.US)) {
            "", "kotak", "persegi", "persegi panjang", "rect", "rectangle", ".rect" -> ".rect"
            "bulat", "lingkaran", "circle", ".circle" -> ".circle"
            "kotak membulat", "rounded", "roundrect", ".roundrect" -> ".roundrect"
            "segitiga", "triangle", ".triangle" -> ".triangle"
            "bintang", "star", ".star" -> ".star"
            "poligon", "segi banyak", "poly", "polygon", ".poly" -> ".poly"
            else -> raw
        }
        ShapeCatalog.byId(targetId)?.let { return it }
        ShapeCatalog.all.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.outline.equals(raw, ignoreCase = true) }?.let { return it }
        ShapeCatalog.byId(".rect")?.let { return it }
        return when (targetId) {
            ".circle" -> ShapeDef(".circle", "Circle", "color", listOf(XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "100,100"))), emptyList(), R.string.shape_name_circle)
            ".roundrect" -> ShapeDef(".roundrect", "Rounded Rectangle", "color", listOf(XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "100,100")), XmlNode("property", mapOf("name" to "cornerRadius", "type" to "float", "value" to "25"))), emptyList(), R.string.shape_name_rounded_rectangle)
            ".star" -> ShapeDef(".star", "Star", "color", emptyList(), emptyList(), R.string.shape_name_star)
            ".triangle" -> ShapeDef(".triangle", "Triangle", "color", emptyList(), emptyList(), R.string.shape_name_triangle)
            else -> ShapeDef(".rect", "Rectangle", "color", listOf(XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "100,100"))), emptyList(), R.string.shape_name_rectangle)
        }
    }

    private fun resolveFontXml(raw: String): String {
        if (raw.startsWith("googlefonts?") || raw.startsWith("imported?")) return FontName.parse(raw).xml
        return FontName("googlefonts", raw).xml
    }

    private fun normalizeAlign(raw: String): String? = when (raw.trim().lowercase(Locale.US)) {
        "kiri", "left" -> "left"
        "tengah", "center", "centre" -> "center"
        "kanan", "right" -> "right"
        else -> null
    }

    private fun resolveBlendMode(raw: String): String? {
        val k = raw.trim().lowercase(Locale.US).replace('_', '-')
        return when (k) {
            "normal", "biasa" -> "normal"
            "multiply", "kalikan" -> "multiply"
            "darken", "gelap" -> "darken"
            "darker-color" -> "darker-color"
            "color-burn" -> "color-burn"
            "linear-burn" -> "linear-burn"
            "screen", "layar" -> "screen"
            "lighten", "terang" -> "lighten"
            "lighter-color" -> "lighter-color"
            "color-dodge" -> "color-dodge"
            "linear-dodge", "add", "tambah" -> "linear-dodge"
            "overlay", "hamparan" -> "overlay"
            "soft-light" -> "soft-light"
            "hard-light" -> "hard-light"
            "soft-overlay" -> "soft-overlay"
            "vivid-light" -> "vivid-light"
            "linear-light" -> "linear-light"
            "pin-light" -> "pin-light"
            "diff", "difference", "selisih" -> "diff"
            "exclusion" -> "exclusion"
            "subtract", "kurang" -> "subtract"
            "divide", "bagi" -> "divide"
            "hue" -> "hue"
            "saturation" -> "saturation"
            "color", "warna" -> "color"
            "luminance" -> "luminance"
            "mask" -> "mask"
            "exclude" -> "exclude"
            else -> null
        }
    }

    private fun applyBorderStyle(layer: Layer, raw: Any): Layer {
        when (raw) {
            is Boolean -> {
                return if (raw) {
                    if (layer.borders.isEmpty()) layer.withBorderAdded() else layer
                } else {
                    var next = layer
                    while (next.borders.isNotEmpty()) next = next.withBorderRemoved(0)
                    next
                }
            }
            is Number -> {
                val size = raw.toFloat().coerceAtLeast(0f)
                var next = if (layer.borders.isEmpty()) layer.withBorderAdded() else layer
                return next.withValue(PropRef(PropRef.Kind.Child, "border[0]/size"), formatNumber(size))
            }
            is JSONObject -> {
                val enabled = if (raw.has("aktif")) asBoolean(raw.opt("aktif")) else if (raw.has("enabled")) asBoolean(raw.opt("enabled")) else true
                if (enabled == false) {
                    var next = layer
                    while (next.borders.isNotEmpty()) next = next.withBorderRemoved(0)
                    return next
                }
                var next = if (layer.borders.isEmpty()) layer.withBorderAdded() else layer
                val colorRaw = raw.opt("warna") ?: raw.opt("color")
                if (colorRaw != null && colorRaw != JSONObject.NULL) {
                    val argb = parseColorArgb(colorRaw) ?: fail("Format warna garis tidak valid: '$colorRaw'")
                    next = next.withValue(PropRef(PropRef.Kind.Child, "border[0]/color"), formatColor(argb))
                }
                val sizeRaw = raw.opt("ukuran") ?: raw.opt("size") ?: raw.opt("lebar")
                if (sizeRaw != null && sizeRaw != JSONObject.NULL) {
                    val size = (asFloat(sizeRaw) ?: fail("Ukuran garis harus angka")).coerceAtLeast(0f)
                    next = next.withValue(PropRef(PropRef.Kind.Child, "border[0]/size"), formatNumber(size))
                }
                val dirRaw = (raw.opt("arah") ?: raw.opt("direction"))?.toString()?.trim()?.lowercase(Locale.US)
                if (dirRaw in setOf("inside", "outside", "centered")) {
                    next = next.withBorderDirection(0, dirRaw!!)
                }
                return next
            }
            else -> fail("Format properti 'garis' tidak valid")
        }
    }

    private fun applyShadowStyle(layer: Layer, raw: Any): Layer {
        when (raw) {
            is Boolean -> return layer.withShadowEnabled(raw)
            is JSONObject -> {
                val enabled = if (raw.has("aktif")) asBoolean(raw.opt("aktif")) else if (raw.has("enabled")) asBoolean(raw.opt("enabled")) else true
                if (enabled == false) return layer.withShadowEnabled(false)
                var next = layer.withShadowEnabled(true)
                val colorRaw = raw.opt("warna") ?: raw.opt("color")
                if (colorRaw != null && colorRaw != JSONObject.NULL) {
                    val argb = parseColorArgb(colorRaw) ?: fail("Format warna bayangan tidak valid: '$colorRaw'")
                    next = next.withValue(PropRef(PropRef.Kind.Child, "shadow/color"), formatColor(argb))
                }
                val sizeRaw = raw.opt("ukuran") ?: raw.opt("size")
                if (sizeRaw != null && sizeRaw != JSONObject.NULL) {
                    val size = (asFloat(sizeRaw) ?: fail("Ukuran bayangan harus angka")).coerceAtLeast(0f)
                    next = next.withValue(PropRef(PropRef.Kind.Child, "shadow/size"), formatNumber(size))
                }
                val opacityRaw = raw.opt("opasitas") ?: raw.opt("opacity")
                if (opacityRaw != null && opacityRaw != JSONObject.NULL) {
                    val op = (asFloat(opacityRaw) ?: fail("Opasitas bayangan harus angka")).coerceIn(0f, 100f) / 100f
                    next = next.withValue(PropRef(PropRef.Kind.Child, "shadow/opacity"), formatNumber(op))
                }
                val xRaw = raw.opt("x")
                val yRaw = raw.opt("y")
                if (xRaw != null || yRaw != null) {
                    val cur = next.shadow
                    val sx = asFloat(xRaw) ?: cur.x
                    val sy = asFloat(yRaw) ?: cur.y
                    next = next.withValue(PropRef(PropRef.Kind.Child, "shadow/offset"), "${formatNumber(sx)},${formatNumber(sy)}")
                }
                return next
            }
            else -> fail("Format properti 'bayangan' tidak valid")
        }
    }

    private fun resolveOrderIndex(posisi: Any?, currentIdx: Int, maxIdx: Int): Int? {
        if (maxIdx < 0 || posisi == null || posisi == JSONObject.NULL) return null
        val num = asFloat(posisi)?.roundToInt()
        if (num != null) return num.coerceIn(0, maxIdx)
        return when (posisi.toString().trim().lowercase(Locale.US)) {
            "atas", "depan", "top", "front", "paling_atas" -> maxIdx
            "bawah", "belakang", "bottom", "back", "paling_bawah" -> 0
            "naik", "maju", "up", "forward" -> (currentIdx + 1).coerceIn(0, maxIdx)
            "turun", "mundur", "down", "backward" -> (currentIdx - 1).coerceIn(0, maxIdx)
            else -> null
        }
    }

    private fun parseResolution(raw: Any, curW: Int, curH: Int): Pair<Int, Int>? {
        if (raw is JSONObject) {
            val w = (raw.opt("width") ?: raw.opt("lebar"))?.let(::asFloat)?.roundToInt() ?: curW
            val h = (raw.opt("height") ?: raw.opt("tinggi"))?.let(::asFloat)?.roundToInt() ?: curH
            return if (w in 16..4320 && h in 16..4320) w to h else null
        }
        val text = raw.toString().trim().lowercase(Locale.US)
        when (text) {
            "720p", "hd" -> return if (curW >= curH) 1280 to 720 else 720 to 1280
            "1080p", "fhd", "fullhd" -> return if (curW >= curH) 1920 to 1080 else 1080 to 1920
            "4k", "uhd", "2160p" -> return if (curW >= curH) 3840 to 2160 else 2160 to 3840
        }
        val match = Regex("(\\d+)\\s*[xX*]\\s*(\\d+)").find(text) ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: return null
        val h = match.groupValues[2].toIntOrNull() ?: return null
        return if (w in 16..4320 && h in 16..4320) w to h else null
    }

    private fun parseAspectRatio(raw: String): Pair<Int, Int>? {
        val text = raw.trim().lowercase(Locale.US)
        return when (text) {
            "16:9" -> 1920 to 1080
            "9:16" -> 1080 to 1920
            "1:1" -> 1080 to 1080
            "4:5" -> 1080 to 1350
            "5:4" -> 1350 to 1080
            "4:3" -> 1440 to 1080
            "3:4" -> 1080 to 1440
            "21:9" -> 2520 to 1080
            else -> {
                val match = Regex("(\\d+)\\s*[:/]\\s*(\\d+)").find(text) ?: return null
                val a = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                val b = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                if (a >= b) ((1080f * a / b).roundToInt().coerceIn(16, 4320)) to 1080
                else 1080 to ((1080f * b / a).roundToInt().coerceIn(16, 4320))
            }
        }
    }

    private fun parseColorArgb(raw: Any?): Int? {
        val s = raw?.toString()?.trim()?.lowercase(Locale.US) ?: return null
        when (s) {
            "hitam", "black" -> return 0xFF000000.toInt()
            "putih", "white" -> return 0xFFFFFFFF.toInt()
            "merah", "red" -> return 0xFFFF0000.toInt()
            "hijau", "green" -> return 0xFF00FF00.toInt()
            "biru", "blue" -> return 0xFF0000FF.toInt()
            "kuning", "yellow" -> return 0xFFFFFF00.toInt()
            "transparan", "transparent" -> return 0x00000000
        }
        val hex = s.removePrefix("#")
        return when (hex.length) {
            6 -> parseColor("#ff$hex")
            8 -> parseColor("#$hex")
            else -> null
        }
    }

    // --- Helpers: JSON parsing (untrusted input) ---

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        val withoutStart = if (firstNewline >= 0) trimmed.substring(firstNewline + 1) else trimmed.removePrefix("```")
        return withoutStart.removeSuffix("```").trim()
    }

    private fun parseActionObject(obj: JSONObject): AiAction {
        val nama = obj.optString("aksi", "").trim()
        if (nama.isEmpty()) return AiAction.TidakValid("(kosong)", "Field 'aksi' tidak ada")
        return when (nama.lowercase(Locale.US)) {
            "tambah_layer" -> {
                val tipe = optNonBlankString(obj, "tipe")
                    ?: return AiAction.TidakValid(nama, "Field 'tipe' wajib diisi")
                AiAction.TambahLayer(
                    tipe = tipe,
                    nama = optNonBlankString(obj, "nama"),
                    awal = optInt(obj, "awal"),
                    durasi = optInt(obj, "durasi"),
                    isi = optStringNullable(obj, "isi"),
                )
            }
            "hapus_layer" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                AiAction.HapusLayer(id)
            }
            "duplikat_layer" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                AiAction.DuplikatLayer(id)
            }
            "ubah_nama" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val newName = optNonBlankString(obj, "nama") ?: return AiAction.TidakValid(nama, "Field 'nama' wajib diisi")
                AiAction.UbahNama(id, newName)
            }
            "atur_waktu" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                AiAction.AturWaktu(id, optInt(obj, "awal"), optInt(obj, "durasi"))
            }
            "potong_layer" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val waktu = optInt(obj, "waktu") ?: return AiAction.TidakValid(nama, "Field 'waktu' tidak valid")
                AiAction.PotongLayer(id, waktu)
            }
            "atur_induk" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val indukRaw = obj.opt("induk_id")
                val indukId = if (indukRaw == null || indukRaw == JSONObject.NULL || indukRaw.toString().equals("null", true) || indukRaw.toString().isBlank()) null
                else optLong(obj, "induk_id") ?: return AiAction.TidakValid(nama, "Field 'induk_id' tidak valid")
                AiAction.AturInduk(id, indukId)
            }
            "atur_urutan" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val pos = if (obj.has("posisi") && !obj.isNull("posisi")) obj.get("posisi") else return AiAction.TidakValid(nama, "Field 'posisi' wajib diisi")
                AiAction.AturUrutan(id, pos)
            }
            "sembunyikan" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val nilai = asBoolean(optAny(obj, "nilai")) ?: return AiAction.TidakValid(nama, "Field 'nilai' harus boolean")
                AiAction.Sembunyikan(id, nilai)
            }
            "kunci" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val nilai = asBoolean(optAny(obj, "nilai")) ?: return AiAction.TidakValid(nama, "Field 'nilai' harus boolean")
                AiAction.Kunci(id, nilai)
            }
            "atur_nilai" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val properti = optNonBlankString(obj, "properti") ?: return AiAction.TidakValid(nama, "Field 'properti' wajib diisi")
                val nilai = optAny(obj, "nilai") ?: return AiAction.TidakValid(nama, "Field 'nilai' wajib diisi")
                AiAction.AturNilai(id, properti, nilai)
            }
            "keyframe" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val properti = optNonBlankString(obj, "properti") ?: return AiAction.TidakValid(nama, "Field 'properti' wajib diisi")
                val waktu = optInt(obj, "waktu") ?: return AiAction.TidakValid(nama, "Field 'waktu' tidak valid")
                val nilai = optAny(obj, "nilai") ?: return AiAction.TidakValid(nama, "Field 'nilai' wajib diisi")
                AiAction.Keyframe(id, properti, waktu, nilai, optNonBlankString(obj, "easing"))
            }
            "hapus_keyframe" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val properti = optNonBlankString(obj, "properti") ?: return AiAction.TidakValid(nama, "Field 'properti' wajib diisi")
                val waktu = optInt(obj, "waktu") ?: return AiAction.TidakValid(nama, "Field 'waktu' tidak valid")
                AiAction.HapusKeyframe(id, properti, waktu)
            }
            "tambah_efek" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val efek = optNonBlankString(obj, "efek") ?: return AiAction.TidakValid(nama, "Field 'efek' wajib diisi")
                val params = jsonObjectToMap(obj.optJSONObject("parameter"))
                AiAction.TambahEfek(id, efek, params)
            }
            "ubah_efek" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val efek = optNonBlankString(obj, "efek") ?: return AiAction.TidakValid(nama, "Field 'efek' wajib diisi")
                val paramObj = obj.optJSONObject("parameter") ?: return AiAction.TidakValid(nama, "Field 'parameter' harus objek JSON")
                AiAction.UbahEfek(id, efek, jsonObjectToMap(paramObj))
            }
            "keyframe_efek" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val efek = optNonBlankString(obj, "efek") ?: return AiAction.TidakValid(nama, "Field 'efek' wajib diisi")
                val paramName = optNonBlankString(obj, "nama_parameter") ?: return AiAction.TidakValid(nama, "Field 'nama_parameter' wajib diisi")
                val waktu = optInt(obj, "waktu") ?: return AiAction.TidakValid(nama, "Field 'waktu' tidak valid")
                val nilai = optAny(obj, "nilai") ?: return AiAction.TidakValid(nama, "Field 'nilai' wajib diisi")
                AiAction.KeyframeEfek(id, efek, paramName, waktu, nilai, optNonBlankString(obj, "easing"))
            }
            "hapus_efek" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                val efek = optNonBlankString(obj, "efek") ?: return AiAction.TidakValid(nama, "Field 'efek' wajib diisi")
                AiAction.HapusEfek(id, efek)
            }
            "atur_teks" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                AiAction.AturTeks(
                    id = id,
                    isi = optStringNullable(obj, "isi"),
                    font = optNonBlankString(obj, "font"),
                    ukuran = asFloat(optAny(obj, "ukuran")),
                    warna = optNonBlankString(obj, "warna"),
                    rata = optNonBlankString(obj, "rata"),
                )
            }
            "atur_gaya" -> {
                val id = optLong(obj, "id") ?: return AiAction.TidakValid(nama, "Field 'id' tidak valid")
                AiAction.AturGaya(
                    id = id,
                    isiWarna = optNonBlankString(obj, "isi_warna"),
                    garis = optAny(obj, "garis"),
                    bayangan = optAny(obj, "bayangan"),
                    modeCampur = optNonBlankString(obj, "mode_campur"),
                )
            }
            "atur_proyek" -> AiAction.AturProyek(
                rasio = optNonBlankString(obj, "rasio"),
                resolusi = optAny(obj, "resolusi"),
                fps = optInt(obj, "fps"),
                latar = optNonBlankString(obj, "latar"),
            )
            else -> AiAction.TidakValid(nama, "Aksi '$nama' tidak dikenal")
        }
    }

    private fun jsonObjectToMap(obj: JSONObject?): Map<String, Any?> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) {
            val v = obj.opt(key)
            map[key] = if (v == JSONObject.NULL) null else v
        }
        return map
    }

    private fun optAny(obj: JSONObject, key: String): Any? =
        if (obj.has(key) && !obj.isNull(key)) obj.get(key) else null

    private fun optStringNullable(obj: JSONObject, key: String): String? =
        if (obj.has(key) && !obj.isNull(key)) obj.opt(key)?.toString() else null

    private fun optNonBlankString(obj: JSONObject, key: String): String? =
        optStringNullable(obj, key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun optLong(obj: JSONObject, key: String): Long? {
        val raw = optAny(obj, key) ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().trim().toLongOrNull()
        }
    }

    private fun optInt(obj: JSONObject, key: String): Int? = asFloat(optAny(obj, key))?.roundToInt()

    private fun asFloat(raw: Any?): Float? {
        if (raw == null || raw == JSONObject.NULL) return null
        return when (raw) {
            is Number -> raw.toFloat().takeIf { it.isFinite() }
            is String -> raw.trim().removeSuffix("%").removeSuffix("px").removeSuffix("°").finiteFloat()
            else -> null
        }
    }

    private fun asFloatList(raw: Any?, minCount: Int, maxCount: Int): List<Float>? {
        val list = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { asFloat(raw.opt(it)) }
            is Iterable<*> -> raw.mapNotNull { asFloat(it) }
            is String -> raw.split(',', ' ').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.let(::asFloat) }
            else -> return null
        }
        return if (list.size in minCount..maxCount) list else null
    }

    private fun asBoolean(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> when (raw.trim().lowercase(Locale.US)) {
            "true", "1", "ya", "yes", "on", "aktif" -> true
            "false", "0", "tidak", "no", "off", "nonaktif" -> false
            else -> null
        }
        else -> null
    }
}
