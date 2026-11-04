package recalldesk;

import java.util.List;

public final class Samples {
    private Samples() { }
    public static List<Card.Draft> cards() {
        return List.of(
            new Card.Draft("SQL", "Apa manfaat parameter binding pada query SQL?", "Nilai masukan dipisahkan dari struktur query. Gunakan placeholder dan binding, sehingga nilai diperlakukan sebagai data."),
            new Card.Draft("SQL", "Apa tujuan sebuah transaksi database?", "Mengelompokkan perubahan yang harus berhasil bersama. Commit menyimpan hasil, sedangkan rollback membatalkan perubahan yang belum committed."),
            new Card.Draft("Algoritma", "Struktur data apa yang lazim dipakai oleh BFS?", "Queue FIFO. Simpul diproses sesuai urutan penemuannya sehingga penelusuran berjalan per lapisan."),
            new Card.Draft("Algoritma", "Apa kompleksitas pencarian biner pada array terurut?", "O(log n) per pencarian. Setiap langkah membuang sekitar separuh ruang pencarian."),
            new Card.Draft("Java", "Apa perbedaan == dan equals pada objek Java?", "== membandingkan identitas referensi. equals membandingkan kesetaraan sesuai implementasi kelas; String menggunakannya untuk kesamaan isi."),
            new Card.Draft("Java", "Mengapa operasi IO panjang dipindahkan dari Event Dispatch Thread?", "EDT menangani event dan repaint Swing. Pekerjaan panjang di sana membuat antarmuka tidak merespons; SwingWorker dapat menjalankan IO pada worker thread.")
        );
    }
}
