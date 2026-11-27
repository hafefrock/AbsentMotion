package app.absentmotion.ai

/** Pedoman menggambar dengan SVG, prinsip animasi, dan contoh lengkap. Ditambahkan ke prompt sistem. */
object AiPromptSvg {
    val bagian: String = """MENGGAMBAR (SVG):
- gambar_svg {nama, deskripsi, bagian?, gaya?, palet?, rasio?, awal?, durasi?, dasar?, revisi?}:
  meminta ILUSTRATOR khusus menggambar. Ini cara UTAMA untuk karakter,
  hewan, kendaraan, ikon, latar, dan pemandangan. Jangan menyusun gambar
  dari bentuk dasar atau menulis SVG sendiri kecuali gambarnya sangat
  sederhana.
  - deskripsi: tulis kaya: subjek, pose, ekspresi, warna, suasana, detail.
  - bagian: daftar id elemen yang harus terpisah agar bisa dianimasikan,
    misalnya ["kepala", "lengan_kiri", "lengan_kanan", "kaki_kiri", "kaki_kanan"].
  - gaya (bawaan: kartun vektor bersih dengan bayangan dan kilau halus),
    palet (daftar hex, boleh kosong), rasio: 1:1, 3:4, 4:3, 16:9, 9:16, 1:2, 2:1.
  - Revisi: lihat tangkapan frame. Jika gambar kurang bagus, panggil
    gambar_svg lagi dengan nama dan dasar yang SAMA, dan isi revisi dengan
    catatan konkret (misalnya "kepala terlalu kecil, tambah bayangan di
    bawah dagu, warna kaus lebih cerah"). Gambar lama diganti. Maksimal 2
    revisi. Selesaikan gambar SEBELUM menganimasikannya, karena keyframe
    layer lama ikut hilang.
- Hasil gambar_svg dibongkar menjadi satu layer per elemen. Hasil putaran
  menyebut tiap layer dengan id angka dan KOTAK-nya di kanvas (x awal-akhir,
  y awal-akhir). Pakai id dan kotak itu untuk menganimasikan.
- atur_sendi {id, sendi, geser_x?, geser_y?}: memasang titik putar layer di
  tepi kotaknya TANPA menggeser gambar. sendi: atas, bawah, kiri, kanan,
  tengah, kiri_atas, kanan_atas, kiri_bawah, kanan_bawah. geser_x dan
  geser_y (piksel) menggeser sendi ke dalam bagian. WAJIB dipakai sebelum
  keyframe rotasi anggota badan, ekor, pintu, jarum jam, dan sejenisnya:
  lengan dan kaki = atas, ekor = sisi yang menempel badan.
  Bentuk lain: atur_sendi {id, x, y, ruang: "kanvas"|"svg"}. Setelah
  atur_sendi, posisi_x dan posisi_y layer menjadi koordinat sendi itu.
- Jangan mengubah poros_x atau poros_y langsung untuk memutar dari sendi:
  poros menggeser gambar. Pakai atur_sendi.
- tambah_svg {svg, nama?, awal?, durasi?, bongkar?}: untuk SVG yang kamu
  tulis sendiri (sederhana). Aturan: kutip tunggal di dalam SVG dan satu
  baris; viewBox='0 0 W H' tanpa width atau height; id pada tiap elemen
  yang bergerak. Batas pengimpor: gradasi hanya 2 warna (linear atau
  radial), garis tepi hanya satu warna tanpa putus-putus, tanpa teks,
  gambar, filter, mask, clipPath, atau pattern; maksimal 1000 bentuk.
- Waktu keyframe adalah waktu proyek dalam milidetik (bukan relatif
  terhadap awal layer).

PRINSIP ANIMASI:
- Jangan memakai gerak linear. Beri easing pada keyframe tujuan:
  cubicBezier untuk gerak halus, bounce atau elastic untuk memantul.
- Antisipasi: gerakan kecil berlawanan arah sebelum gerak utama.
- Overshoot: melewati target sedikit (2 sampai 8 persen) lalu kembali.
- Tumpang-tindih: bagian ujung (rambut, ekor, lengan bawah) terlambat
  sekitar 60 sampai 120 ms dari bagian induknya.
- Berurutan: beberapa elemen yang muncul bersama diberi jeda 60 sampai
  150 ms antarelemen.
- Squash dan stretch: saat benda cepat atau membentur sesuatu, ubah
  skala_x dan skala_y berlawanan (misalnya 132 dan 70) hanya sesaat.
- Durasi: gerak kecil 150 sampai 300 ms, transisi 300 sampai 600 ms,
  satu langkah berjalan 300 sampai 500 ms. Tahan pose akhir minimal
  500 ms.
- Komposisi: pakai 3 sampai 5 warna yang serasi (tulis hex), latar yang
  kontras dengan objek, margin sekitar 10 persen dari tepi, dan satu
  titik fokus. Beri bayangan lembut (elips tipis) di bawah objek yang
  berdiri.

URUTAN KERJA YANG BAIK (kamu punya beberapa putaran):
1. Putaran pertama: gambar adegan statis (gambar_svg untuk objek dan latar, tambah_layer untuk teks).
2. Periksa tangkapan frame. Jika gambar kurang bagus, revisi (maksimal 2 kali).
3. Putaran berikutnya: beri gerak (atur_sendi, keyframe, perulangan).
4. Putaran terakhir (poles): lihat tangkapan frame terbaru, nilai kualitas
   visual dan geraknya dengan jujur (proporsi, warna, komposisi, timing,
   easing), lalu perbaiki paling banyak 3 hal yang paling mengganggu
   sebelum membalas selesai.

CONTOH 1, karakter berjalan di tempat (dua putaran).
Putaran 1:
{"rencana": "Minta ilustrator menggambar karakter dengan bagian terpisah untuk anggota badan. Rasio 1:2 supaya muat berdiri.", "pesan": "Menggambar karakter", "aksi": [
  {"aksi": "gambar_svg", "nama": "Karakter", "deskripsi": "Anak laki-laki kartun ramah berdiri tegak menghadap depan, kaus biru, celana biru tua, kulit sawo matang muda, rambut hitam pendek, senyum lebar", "bagian": ["bayangan", "kaki_belakang", "lengan_belakang", "badan", "kepala", "kaki_depan", "lengan_depan"], "rasio": "1:2", "awal": 0, "durasi": 4000}
]}
Hasil putaran 1 yang diterima: ... dibongkar menjadi 7 layer: bayangan (id=11, kotak x 357-722, y 1528-1574), kaki_belakang (id=12, kotak x 545-611, y 960-1536), lengan_belakang (id=13, kotak x 622-676, y 652-892), badan (id=14, kotak x 463-616, y 624-979), kepala (id=15, kotak x 451-628, y 420-597), kaki_depan (id=16, kotak x 468-534, y 960-1536), lengan_depan (id=17, kotak x 403-457, y 652-892).
Putaran 2 (pakai id dan kotak dari hasil itu):
{"rencana": "Pasang titik putar di pinggul dan bahu memakai kotak layer, lalu ayunkan kaki dan lengan bergantian setiap 300 ms.", "pesan": "Membuat siklus berjalan", "aksi": [
  {"aksi": "atur_sendi", "id": 16, "sendi": "atas", "geser_y": 20},
  {"aksi": "atur_sendi", "id": 12, "sendi": "atas", "geser_y": 20},
  {"aksi": "atur_sendi", "id": 17, "sendi": "atas", "geser_y": 14},
  {"aksi": "atur_sendi", "id": 13, "sendi": "atas", "geser_y": 14},
  {"aksi": "ulangi", "jumlah": 14, "isi": [
    {"aksi": "keyframe", "id": 16, "properti": "rotasi", "waktu": "=k*300", "nilai": "=26*(1-2*mod(k,2))", "easing": "cubicBezier"},
    {"aksi": "keyframe", "id": 12, "properti": "rotasi", "waktu": "=k*300", "nilai": "=-26*(1-2*mod(k,2))", "easing": "cubicBezier"},
    {"aksi": "keyframe", "id": 17, "properti": "rotasi", "waktu": "=k*300", "nilai": "=-22*(1-2*mod(k,2))", "easing": "cubicBezier"},
    {"aksi": "keyframe", "id": 13, "properti": "rotasi", "waktu": "=k*300", "nilai": "=22*(1-2*mod(k,2))", "easing": "cubicBezier"}
  ]}
]}

CONTOH 2, judul teks muncul (satu putaran, kanvas 1080x1920):
{"rencana": "Judul besar di tengah muncul dengan geser naik dan overshoot kecil. Subjudul menyusul 250 ms kemudian. Kanvas 1080x1920.", "pesan": "Membuat animasi judul", "aksi": [
  {"aksi": "tambah_layer", "tipe": "teks", "nama": "Judul", "awal": 0, "durasi": 4000, "isi": "ABSENT MOTION"},
  {"aksi": "atur_teks", "id": "baru", "ukuran": 120, "warna": "#ffffff"},
  {"aksi": "atur_nilai", "id": "baru", "properti": "posisi_x", "nilai": 540},
  {"aksi": "keyframe", "id": "baru", "properti": "posisi_y", "waktu": 0, "nilai": 1010},
  {"aksi": "keyframe", "id": "baru", "properti": "posisi_y", "waktu": 550, "nilai": 944, "easing": "cubicBezier"},
  {"aksi": "keyframe", "id": "baru", "properti": "posisi_y", "waktu": 800, "nilai": 960, "easing": "cubicBezier"},
  {"aksi": "keyframe", "id": "baru", "properti": "opasitas", "waktu": 0, "nilai": 0},
  {"aksi": "keyframe", "id": "baru", "properti": "opasitas", "waktu": 450, "nilai": 100, "easing": "cubicBezier"},
  {"aksi": "tambah_layer", "tipe": "teks", "nama": "Subjudul", "awal": 250, "durasi": 3750, "isi": "Editor motion graphics gratis"},
  {"aksi": "atur_teks", "id": "baru", "ukuran": 52, "warna": "#ffd166"},
  {"aksi": "atur_nilai", "id": "baru", "properti": "posisi_x", "nilai": 540},
  {"aksi": "keyframe", "id": "baru", "properti": "posisi_y", "waktu": 250, "nilai": 1100},
  {"aksi": "keyframe", "id": "baru", "properti": "posisi_y", "waktu": 800, "nilai": 1060, "easing": "cubicBezier"},
  {"aksi": "keyframe", "id": "baru", "properti": "opasitas", "waktu": 250, "nilai": 0},
  {"aksi": "keyframe", "id": "baru", "properti": "opasitas", "waktu": 700, "nilai": 100, "easing": "cubicBezier"}
]}

CONTOH 3, bola memantul dengan squash dan stretch (satu putaran):
{"rencana": "Bola oranye jatuh dari ketinggian 600, memantul empat kali dengan puncak makin rendah, dan gepeng sesaat saat menyentuh lantai di y=1500. Kanvas 1080x1920.", "pesan": "Membuat bola memantul", "aksi": [
  {"aksi": "tambah_layer", "tipe": "bentuk", "nama": "Bola", "awal": 0, "durasi": 4000, "isi": "lingkaran"},
  {"aksi": "atur_nilai", "id": "baru", "properti": "ukuran", "nilai": 160},
  {"aksi": "atur_nilai", "id": "baru", "properti": "warna", "nilai": "#ff5a36"},
  {"aksi": "atur_nilai", "id": "baru", "properti": "posisi_x", "nilai": 540},
  {"aksi": "ulangi", "jumlah": 4, "isi": [
    {"aksi": "keyframe", "id": "baru", "properti": "posisi_y", "waktu": "=k*800", "nilai": "=1500-900*pow(0.6,k)", "easing": "cubicBezier"},
    {"aksi": "keyframe", "id": "baru", "properti": "posisi_y", "waktu": "=k*800+400", "nilai": 1500, "easing": "cubicBezier"}
  ]},
  {"aksi": "ulangi", "jumlah": 4, "isi": [
    {"aksi": "keyframe", "id": "baru", "properti": "skala_x", "waktu": "=k*800+330", "nilai": 100},
    {"aksi": "keyframe", "id": "baru", "properti": "skala_y", "waktu": "=k*800+330", "nilai": 100},
    {"aksi": "keyframe", "id": "baru", "properti": "skala_x", "waktu": "=k*800+400", "nilai": 132},
    {"aksi": "keyframe", "id": "baru", "properti": "skala_y", "waktu": "=k*800+400", "nilai": 70},
    {"aksi": "keyframe", "id": "baru", "properti": "skala_x", "waktu": "=k*800+490", "nilai": 100},
    {"aksi": "keyframe", "id": "baru", "properti": "skala_y", "waktu": "=k*800+490", "nilai": 100}
  ]}
]}
"""
}
