package app.absentmotion.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Ilustrator: mengubah permintaan gambar_svg menjadi SVG lewat satu panggilan model yang berfokus pada seni vektor. */
internal object AiArtist {

    private const val MAX_SVG_CHARS = 120000

    private val ratios = mapOf(
        "1:1" to (600 to 600),
        "3:4" to (600 to 800),
        "4:3" to (800 to 600),
        "16:9" to (800 to 450),
        "9:16" to (450 to 800),
        "1:2" to (400 to 800),
        "2:1" to (800 to 400),
    )

    private val rootTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** Mengganti tiap aksi gambar_svg dengan tambah_svg berisi SVG hasil ilustrator. Aksi lain dibiarkan. */
    suspend fun prepare(
        items: List<JSONObject>,
        skipped: MutableList<Pair<String, String>>,
        onProgress: (String) -> Unit,
        ask: suspend (String) -> String,
    ): List<JSONObject> {
        if (items.none { it.optString("aksi", "").lowercase() == "gambar_svg" }) return items
        val out = ArrayList<JSONObject>()
        for (item in items) {
            if (item.optString("aksi", "").lowercase() != "gambar_svg") {
                out += item
                continue
            }
            val name = item.optString("nama", "").trim().ifEmpty { "Gambar" }
            onProgress("Menggambar '$name'...")
            try {
                val svg = draw(item, ask)
                val action = JSONObject().put("aksi", "tambah_svg").put("svg", svg).put("nama", name)
                for (key in listOf("awal", "durasi", "bongkar")) {
                    val value = item.opt(key)
                    if (value != null) action.put(key, value)
                }
                val base = item.optString("dasar", "").trim()
                if (base.isNotEmpty()) action.put("ganti", base)
                out += action
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped += "gambar_svg" to (e.message?.takeIf { it.isNotBlank() } ?: "Ilustrator gagal membuat gambar")
            }
        }
        return out
    }

    private suspend fun draw(item: JSONObject, ask: suspend (String) -> String): String {
        val description = item.optString("deskripsi", "").trim()
        if (description.isEmpty()) throw IllegalArgumentException("gambar_svg butuh field 'deskripsi'")
        val ratio = item.optString("rasio", "1:1").trim()
        val size = ratios[ratio] ?: throw IllegalArgumentException("Field 'rasio' harus salah satu dari: " + ratios.keys.joinToString(", "))
        val style = item.optString("gaya", "").trim().ifEmpty { "kartun vektor bersih dengan bayangan dan kilau halus" }
        val palette = stringsOf(item.opt("palet"))
        val parts = stringsOf(item.opt("bagian"))
        val base = item.optString("dasar", "").trim()
        val notes = item.optString("revisi", "").trim()
        val previous = if (base.isNotEmpty()) AiSvg.previousSvg(base) else null

        val message = StringBuilder(PANDUAN)
        message.append("\nTUGAS GAMBAR\n")
        message.append("Subjek: ").append(description).append('\n')
        message.append("Gaya: ").append(style).append('\n')
        if (palette.isNotEmpty()) message.append("Palet (boleh disesuaikan): ").append(palette.joinToString(", ")).append('\n')
        message.append("Ukuran: viewBox=\"0 0 ").append(size.first).append(' ').append(size.second).append("\"\n")
        if (parts.isNotEmpty()) {
            message.append("Bagian yang HARUS menjadi elemen terpisah dengan id persis ini (untuk dianimasikan): ")
                .append(parts.joinToString(", ")).append('\n')
        }
        if (previous != null) {
            message.append("\nREVISI: perbaiki gambar di bawah sesuai catatan. Pertahankan id bagian yang sama dan keluarkan SVG LENGKAP yang baru.\n")
            message.append("Catatan revisi: ").append(notes.ifEmpty { "tingkatkan kualitas dan detailnya" }).append('\n')
            message.append("SVG sebelumnya:\n").append(previous).append('\n')
        } else if (notes.isNotEmpty()) {
            message.append("Catatan tambahan: ").append(notes).append('\n')
        }
        message.append("\nBalas HANYA {\"svg\": \"<svg ...>...</svg>\"}.")

        val svg = extractSvg(ask(message.toString()))
        return normalize(svg, size.first, size.second)
    }

    private fun stringsOf(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it).trim() }.filter { it.isNotEmpty() }
        is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun extractSvg(reply: String): String {
        val text = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val fromJson = try {
            findSvg(JSONObject(text))
        } catch (_: Exception) {
            null
        }
        val svg = fromJson ?: run {
            val start = text.indexOf("<svg")
            val end = text.lastIndexOf("</svg>")
            if (start < 0 || end < start) {
                null
            } else {
                text.substring(start, end + 6).replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/")
            }
        }
        if (svg == null || !svg.contains("<svg") || !svg.contains("</svg>")) {
            throw IllegalArgumentException("Ilustrator tidak mengembalikan SVG yang valid")
        }
        if (svg.length > MAX_SVG_CHARS) throw IllegalArgumentException("SVG dari ilustrator terlalu besar")
        return svg
    }

    private fun findSvg(obj: JSONObject): String? {
        val direct = obj.optString("svg", "")
        if (direct.contains("<svg")) return direct
        val actions = obj.optJSONArray("aksi") ?: return null
        for (i in 0 until actions.length()) {
            val nested = actions.optJSONObject(i)?.optString("svg", "").orEmpty()
            if (nested.contains("<svg")) return nested
        }
        return null
    }

    /** Memastikan xmlns dan viewBox ada, dan membuang width/height di tag akar. */
    private fun normalize(svg: String, width: Int, height: Int): String {
        val tag = rootTag.find(svg) ?: throw IllegalArgumentException("Tag svg tidak ditemukan")
        var fixed = tag.value
            .replace(Regex("\\s(width|height)\\s*=\\s*(\"[^\"]*\"|'[^']*')"), "")
        if (!fixed.contains("xmlns")) fixed = fixed.replaceFirst("<svg", "<svg xmlns='http://www.w3.org/2000/svg'")
        if (!fixed.contains("viewBox")) fixed = fixed.replaceFirst("<svg", "<svg viewBox='0 0 $width $height'")
        return svg.replaceFirst(tag.value, fixed)
    }

    private const val PANDUAN = """Kamu adalah ILUSTRATOR VEKTOR profesional. Tugasmu: menggambar SATU SVG yang indah dan rapi untuk diimpor ke editor animasi.

BATAS PENGIMPOR (wajib dipatuhi):
- Elemen yang boleh: path, rect, circle, ellipse, line, polyline, polygon, g, defs, use. Dilarang: teks, image, filter, mask, clipPath, pattern, marker.
- Gradasi: linearGradient atau radialGradient dengan TEPAT 2 stop (stop-color dan stop-opacity boleh). Garis tepi (stroke) hanya satu warna, tanpa gradasi dan tanpa putus-putus.
- Pakai tanda kutip tunggal di semua atribut. Tulis viewBox="0 0 W H" (sesuai ukuran yang diminta) dan JANGAN menulis width atau height.
- Jangan memakai atribut transform pada bagian yang akan bergerak; tulis koordinat langsung.
- Maksimal 1000 bentuk. Targetkan 25 sampai 60 bentuk yang bermakna.

TEKNIK BERLAPIS (dari bawah ke atas):
1. Bayangan lantai: elips tipis hitam, opacity 0.12 sampai 0.2.
2. Siluet dasar dengan gradasi 2 warna (lebih terang di atas, lebih gelap di bawah).
3. Bayangan bentuk: path gelap sewarna yang mengikuti tepi bawah siluet, memakai gradasi dari transparan ke gelap (opacity akhir 0.3 sampai 0.55).
4. Detail permukaan (belang, bercak, lipatan) dengan warna lebih gelap dari dasar.
5. Kilau: elips atau path putih dengan radialGradient (putih opacity 0.5 menuju 0) di sisi yang menghadap cahaya, yaitu kiri atas.
6. Detail akhir: mata, mulut, garis kecil dengan stroke-linecap round.
- Karena tidak ada clipPath, bayangan dan kilau harus mengikuti batas siluet dan tidak boleh keluar dari tepinya.

KUALITAS:
- Kurva halus: pakai perintah C, S, atau Q dengan titik kontrol yang masuk akal. Hindari sudut tajam yang tidak disengaja. Tutup path dengan Z.
- Simetri: untuk bagian kiri dan kanan, hitung koordinat cerminnya (x baru = lebar - x) supaya benar-benar seimbang.
- Karakter lucu (chibi): kepala besar (sekitar 45 persen dari tinggi), mata besar dengan kilau putih, pipi merah muda tipis. Hewan: badan oval, telinga dan ekor punya id sendiri.
- Warna: 5 sampai 7 warna serasi: dasar, bayangan (lebih gelap, sedikit bergeser ke ungu atau biru), terang, aksen, dan warna garis (versi gelap dari warna dasar, bukan hitam murni). Sumber cahaya selalu dari kiri atas.
- Komposisi: objek utama menempati 70 sampai 85 persen kanvas dengan margin, dan berdiri di atas bayangan lantai.
- Latar atau pemandangan: susun lapisan dari jauh ke dekat (langit, gunung jauh, gunung dekat, bukit, objek depan). Makin jauh makin pucat dan kebiruan.
- Beri id deskriptif huruf kecil dengan garis bawah pada TIAP elemen yang mungkin bergerak (kepala, ekor, telinga_kiri, mata_kanan, lengan_kiri, dan seterusnya). Elemen yang tidak bergerak boleh digabung.
- Elemen pertama berada paling bawah.

CEK SEBELUM MENJAWAB: siluet jelas? bayangan dan kilau ada? warna serasi? semua id yang diminta ada? tidak ada elemen terlarang? semua atribut memakai kutip tunggal?

KELUARAN: balas HANYA objek JSON {"svg": "<svg ...>...</svg>"} dengan SVG dalam satu baris dan kutip tunggal. Jangan menulis aksi, rencana, atau penjelasan.

CONTOH 1 (hewan, viewBox 0 0 600 600):
<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 600 600'><defs><linearGradient id='bulu' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#ffbd6e'/><stop offset='1' stop-color='#e8892f'/></linearGradient><linearGradient id='bayang' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#d9822b' stop-opacity='0'/><stop offset='1' stop-color='#b86a1e' stop-opacity='0.55'/></linearGradient><radialGradient id='kilau' cx='0.5' cy='0.5' r='0.5'><stop offset='0' stop-color='#ffffff' stop-opacity='0.55'/><stop offset='1' stop-color='#ffffff' stop-opacity='0'/></radialGradient></defs><ellipse id='bayangan' cx='300' cy='552' rx='175' ry='20' fill='#000000' opacity='0.14'/><path id='ekor' d='M430 480 C545 485 575 385 530 330 C512 308 480 322 495 345 C520 385 498 432 425 432 Z' fill='url(#bulu)'/><path id='ekor_belang' d='M512 345 C528 365 530 395 520 418 C540 400 548 366 530 338 Z' fill='#c9731f' opacity='0.7'/><ellipse id='badan' cx='300' cy='445' rx='142' ry='112' fill='url(#bulu)'/><path id='badan_bayangan' d='M165 470 C170 540 235 560 300 560 C365 560 430 540 435 470 C400 520 200 520 165 470 Z' fill='url(#bayang)'/><ellipse id='perut' cx='300' cy='478' rx='84' ry='66' fill='#fff1de'/><ellipse id='kaki_kiri' cx='238' cy='540' rx='42' ry='25' fill='#ffd29b'/><ellipse id='kaki_kanan' cx='362' cy='540' rx='42' ry='25' fill='#ffd29b'/><path id='jari_kiri' d='M222 546 L222 556 M238 548 L238 558 M254 546 L254 556' stroke='#c9731f' stroke-width='3' stroke-linecap='round' fill='none'/><path id='jari_kanan' d='M346 546 L346 556 M362 548 L362 558 M378 546 L378 556' stroke='#c9731f' stroke-width='3' stroke-linecap='round' fill='none'/><path id='telinga_kiri' d='M182 232 C170 160 192 112 212 98 C250 118 280 150 292 190 Z' fill='url(#bulu)'/><path id='telinga_kiri_dalam' d='M200 205 C196 160 208 132 218 124 C240 138 256 160 264 186 Z' fill='#ffb3b8'/><path id='telinga_kanan' d='M418 232 C430 160 408 112 388 98 C350 118 320 150 308 190 Z' fill='url(#bulu)'/><path id='telinga_kanan_dalam' d='M400 205 C404 160 392 132 382 124 C360 138 344 160 336 186 Z' fill='#ffb3b8'/><ellipse id='kepala' cx='300' cy='275' rx='152' ry='130' fill='url(#bulu)'/><ellipse id='kilau_kepala' cx='248' cy='214' rx='62' ry='34' fill='url(#kilau)'/><path id='belang_dahi' d='M300 150 L300 192 M268 156 L276 190 M332 156 L324 190' stroke='#c9731f' stroke-width='7' stroke-linecap='round' fill='none'/><ellipse id='pipi_kiri' cx='208' cy='312' rx='34' ry='21' fill='#ff8f9b' opacity='0.55'/><ellipse id='pipi_kanan' cx='392' cy='312' rx='34' ry='21' fill='#ff8f9b' opacity='0.55'/><ellipse id='mata_kiri' cx='244' cy='266' rx='29' ry='35' fill='#ffffff'/><ellipse id='pupil_kiri' cx='248' cy='272' rx='18' ry='24' fill='#2b2d42'/><circle id='kilau_mata_kiri' cx='255' cy='261' r='8' fill='#ffffff'/><ellipse id='mata_kanan' cx='356' cy='266' rx='29' ry='35' fill='#ffffff'/><ellipse id='pupil_kanan' cx='352' cy='272' rx='18' ry='24' fill='#2b2d42'/><circle id='kilau_mata_kanan' cx='359' cy='261' r='8' fill='#ffffff'/><path id='hidung' d='M284 306 Q300 296 316 306 Q300 324 284 306 Z' fill='#ff7f8e'/><path id='mulut' d='M300 322 L300 336 M300 336 Q284 352 266 341 M300 336 Q316 352 334 341' stroke='#8a4b2a' stroke-width='4' stroke-linecap='round' stroke-linejoin='round' fill='none'/><path id='kumis_kiri' d='M232 322 L150 308 M232 332 L146 336 M236 342 L156 362' stroke='#8a4b2a' stroke-width='3' stroke-linecap='round' fill='none'/><path id='kumis_kanan' d='M368 322 L450 308 M368 332 L454 336 M364 342 L444 362' stroke='#8a4b2a' stroke-width='3' stroke-linecap='round' fill='none'/></svg>

CONTOH 2 (pemandangan, viewBox 0 0 800 450):
<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 800 450'><defs><linearGradient id='langit' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#3b6fd4'/><stop offset='1' stop-color='#ffc58a'/></linearGradient><radialGradient id='cahaya' cx='0.5' cy='0.5' r='0.5'><stop offset='0' stop-color='#fff3b0' stop-opacity='0.9'/><stop offset='1' stop-color='#fff3b0' stop-opacity='0'/></radialGradient><linearGradient id='gunungA' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#8e7cc3'/><stop offset='1' stop-color='#cdb4db'/></linearGradient><linearGradient id='gunungB' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#5b4b8a'/><stop offset='1' stop-color='#8b78b8'/></linearGradient><linearGradient id='bukitA' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#6fbf73'/><stop offset='1' stop-color='#3f8f56'/></linearGradient><linearGradient id='bukitB' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#3f8f56'/><stop offset='1' stop-color='#245f3c'/></linearGradient></defs><rect id='langit' x='0' y='0' width='800' height='450' fill='url(#langit)'/><circle id='cahaya_matahari' cx='560' cy='190' r='170' fill='url(#cahaya)'/><circle id='matahari' cx='560' cy='190' r='46' fill='#fff3b0'/><path id='awan_1' d='M90 110 C70 110 62 90 82 84 C84 62 116 58 128 76 C150 62 178 78 172 100 C196 100 196 122 172 122 L100 122 C84 122 78 114 90 110 Z' fill='#ffffff' opacity='0.85'/><path id='awan_2' d='M330 70 C316 70 310 56 324 52 C326 36 350 34 358 46 C374 38 394 50 390 64 C406 64 406 80 390 80 L338 80 C326 80 322 74 330 70 Z' fill='#ffffff' opacity='0.7'/><path id='gunung_jauh' d='M0 300 L110 190 L190 250 L300 150 L420 270 L520 200 L640 280 L740 210 L800 250 L800 330 L0 330 Z' fill='url(#gunungA)'/><path id='salju_jauh' d='M300 150 L268 182 L292 176 L304 192 L318 174 L338 184 Z' fill='#ffffff' opacity='0.85'/><path id='gunung_dekat' d='M0 340 L150 230 L260 310 L380 220 L520 330 L650 250 L800 330 L800 380 L0 380 Z' fill='url(#gunungB)'/><path id='bukit_belakang' d='M0 360 C150 310 300 330 450 350 C600 370 700 330 800 340 L800 450 L0 450 Z' fill='url(#bukitA)'/><path id='bukit_depan' d='M0 410 C140 370 300 390 460 410 C620 430 720 390 800 400 L800 450 L0 450 Z' fill='url(#bukitB)'/><rect id='batang_pohon' x='158' y='352' width='14' height='58' rx='5' fill='#6b4226'/><circle id='daun_bawah' cx='165' cy='344' r='34' fill='#2f7d4a'/><circle id='daun_tengah' cx='165' cy='318' r='27' fill='#3f9a5c'/><circle id='daun_atas' cx='165' cy='296' r='19' fill='#56b373'/><ellipse id='bayangan_pohon' cx='172' cy='412' rx='36' ry='7' fill='#000000' opacity='0.18'/></svg>
"""
}
