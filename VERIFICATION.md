# Pemeriksaan aktual RecallDesk

Tanggal: 5 November 2026.

Lingkungan: Linux, OpenJDK 17.0.20, Python 3.12.14. Tidak ada display server; interaksi Swing dijalankan melalui EDT dan dirender dalam mode headless.

| Pemeriksaan | Hasil aktual |
| --- | --- |
| `java Build.java test` | Kompilasi source/test dengan target Java 17, UTF-8, `-Xlint:all -Werror` berhasil; 37 tes lulus, tidak ada tes dilewati |
| Penjadwalan | Semua transisi kotak, batas due tepat, reset lupa 10 menit, dan 10.000 langkah review dengan waktu maju diuji |
| Codec | Roundtrip Unicode/multiline; data terpotong, bit flip, versi/header salah, panjang tidak cocok, trailing data dan UTF-8 tidak valid ditolak |
| Penyimpanan | CRUD/review bertahan setelah reopen; gagal menulis backup mempertahankan snapshot memori dan byte utama; snapshot service lama ditolak melalui revisi |
| Backup | Generasi sebelumnya diverifikasi; file utama rusak/hilang tidak diubah menjadi koleksi kosong; backup rusak tidak menimpa file utama |
| Lock | Store kedua dalam JVM dan proses anak ditolak selama lock aktif; lock lepas setelah proses anak dihentikan |
| Swing | Tujuh tes mencakup empty state, reveal/review, CRUD editor, draft yang gagal disimpan tetap tersedia, filter/pencarian, impor/ekspor worker, dan render panel |
| `python tools/smoke.py` | 19 pemeriksaan integrasi JAR lulus; dijalankan dari direktori temporer di luar source |
| Oracle CSV | Delapan berkas dibentuk `csv.writer` Python, masing-masing 25 kartu; hasil ekspor JAR dibandingkan melalui `csv.reader` terhadap total 200 kartu dengan koma, quote, multiline, BOM dan Unicode |
| JAR siap jalan | Seluruh class mempunyai major version 61 / Java 17; versi, help, info, impor, ekspor, restore dan demo dieksekusi melalui JAR |
| `java -jar dist/recalldesk.jar demo` | 16/16 pemeriksaan integrasi lulus; enam kartu, dua review, lima due setelah 10 menit, roundtrip CSV dan restore backup berhasil |
| Render visual | Panel Belajar dan Koleksi dirender 1180 × 820, diperiksa secara visual, dan disertakan pada `docs/` |
| Penerbit | 21/21 tes pada publisher terbaru lulus sebelum paket disiapkan |

Oracle CSV memakai implementasi parser/writer dari standard library Python, bukan parser Java yang sama. Tes Swing mengklik tombol, mengisi editor, menunggu worker selesai, dan memeriksa hasil di service serta disk. Gambar menunjukkan komponen aplikasi yang sebenarnya, bukan mockup.

Sebanyak 10.000 langkah penjadwalan merupakan subpemeriksaan di dalam satu tes, dan 200 kartu CSV merupakan kasus di dalam smoke test; angka tersebut tidak dihitung sebagai ribuan metode tes terpisah.

Batas bukti: jendela native, dialog konfirmasi, file chooser, dan lifecycle window pada Windows/macOS belum dijalankan. Tidak ada klaim pengujian kehilangan daya, kerusakan perangkat penyimpanan, atau filesystem jaringan. Dukungan runtime minimum ditetapkan Java 17; pengujian eksekusi dilakukan dengan OpenJDK 17.0.20.
