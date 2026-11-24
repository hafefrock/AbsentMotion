package app.absentmotion.ai

/** Pedoman menggambar dengan SVG, prinsip animasi, dan contoh lengkap. Ditambahkan ke prompt sistem. */
object AiPromptSvg {
    val bagian: String = """MENGGAMBAR DENGAN SVG:
- tambah_svg {svg, nama?, awal?, durasi?, bongkar?}: mengimpor kode SVG
  menjadi layer vektor. Pakai ini untuk gambar yang butuh bentuk bebas:
  karakter, hewan, kendaraan, ikon, pemandangan. Jangan menyusun gambar
  dari puluhan bentuk dasar.
- bongkar (bawaan true): grup hasil impor langsung dibongkar, sehingga
  tiap elemen SVG menjadi layer sendiri dengan nama yang sama seperti id
  elemennya. Id angka layer muncul di HASIL PUTARAN berikutnya; pakai id
  itu untuk animasi di putaran berikutnya.
- Aturan SVG:
  1. Pakai tanda kutip tunggal di dalam SVG supaya JSON tetap valid, dan
     tulis seluruh SVG dalam satu baris.
  2. Tulis viewBox='0 0 W H' dan JANGAN menulis width atau height.
  3. Hanya path, rect, circle, ellipse, line, polyline, polygon, serta
     linearGradient dan radialGradient di defs. Tanpa teks, gambar,
     filter, mask, clipPath, pattern, atau CSS. Teks dibuat dengan
     tambah_layer teks.
  4. Beri id pada TIAP elemen yang harus bergerak sendiri (id menjadi
     nama layer). Satu elemen = satu layer; jangan membungkus elemen
     yang akan digerakkan di dalam g.
  5. Elemen pertama berada paling bawah. Urutkan dari latar ke depan.
  6. Maksimal sekitar 40 elemen. Pakai bentuk organik (rx, lengkung) dan
     gradasi supaya tidak kaku.
- Pemetaan ke kanvas: gambar diskalakan s = min(0.8 x lebar / W,
  0.8 x tinggi / H) lalu dipusatkan. Titik SVG (x, y) jatuh di
  (lebar/2 + (x - W/2) x s, tinggi/2 + (y - H/2) x s).
- atur_sendi {id, x, y, ruang: "svg"|"kanvas"}: memasang titik putar
  layer di sendi TANPA menggeser gambar. WAJIB dipakai sebelum memberi
  keyframe rotasi pada anggota badan, ekor, pintu, jarum jam, dan
  sejenisnya. Tulis koordinat sendi dalam ruang "svg" (koordinat di SVG
  yang kamu tulis sendiri). Setelah atur_sendi, posisi_x dan posisi_y
  layer menjadi koordinat sendi itu.
- Jangan mengubah poros_x atau poros_y langsung untuk memutar dari
  sendi: poros menggeser gambar karena titik poros ditempatkan di posisi
  layer. Pakai atur_sendi.
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
1. Putaran pertama: bangun adegan statis (latar, objek, teks).
2. Putaran berikutnya: beri gerak (atur_sendi, keyframe, perulangan).
3. Putaran terakhir (poles): lihat tangkapan frame terbaru, nilai kualitas
   visual dan geraknya dengan jujur (proporsi, warna, komposisi, timing,
   easing), lalu perbaiki paling banyak 3 hal yang paling mengganggu
   sebelum membalas selesai.

CONTOH 1, karakter berjalan di tempat (dua putaran).
Putaran 1:
{"rencana": "Gambar karakter dengan SVG: kepala, badan, dua lengan, dua kaki, dan bayangan. Tiap bagian yang bergerak diberi id sendiri.", "pesan": "Menggambar karakter", "aksi": [
  {"aksi": "tambah_svg", "nama": "Karakter", "awal": 0, "durasi": 4000, "svg": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 800'><ellipse id='bayangan' cx='200' cy='708' rx='95' ry='12' fill='#000000' opacity='0.18'/><rect id='kaki_belakang' x='203' y='400' width='34' height='300' rx='17' fill='#1f2a44'/><rect id='lengan_belakang' x='243' y='240' width='28' height='125' rx='14' fill='#e8b48a'/><rect id='badan' x='160' y='225' width='80' height='185' rx='28' fill='#3b6fd4'/><circle id='kepala' cx='200' cy='165' r='46' fill='#f2c49b'/><rect id='kaki_depan' x='163' y='400' width='34' height='300' rx='17' fill='#2b3a5e'/><rect id='lengan_depan' x='129' y='240' width='28' height='125' rx='14' fill='#f2c49b'/></svg>"}
]}
Hasil putaran 1 yang diterima: ... dibongkar menjadi 7 layer: bayangan
(id=11), kaki_belakang (id=12), lengan_belakang (id=13), badan (id=14),
kepala (id=15), kaki_depan (id=16), lengan_depan (id=17).
Putaran 2 (pakai id dari hasil itu; sendi ditulis dalam koordinat SVG):
{"rencana": "Pasang titik putar di pinggul dan bahu, lalu ayunkan kaki dan lengan bergantian setiap 300 ms.", "pesan": "Membuat siklus berjalan", "aksi": [
  {"aksi": "atur_sendi", "id": 16, "x": 180, "y": 410, "ruang": "svg"},
  {"aksi": "atur_sendi", "id": 12, "x": 220, "y": 410, "ruang": "svg"},
  {"aksi": "atur_sendi", "id": 17, "x": 143, "y": 252, "ruang": "svg"},
  {"aksi": "atur_sendi", "id": 13, "x": 257, "y": 252, "ruang": "svg"},
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
