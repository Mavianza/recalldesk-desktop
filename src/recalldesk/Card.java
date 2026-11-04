package recalldesk;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Card(String id, String tag, String front, String back, int box,
                   Instant due, int reviews, int lapses) {
    public Card {
        Objects.requireNonNull(id, "ID kartu wajib ada.");
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("ID kartu tidak valid.", error);
        }
        tag = text(tag, 40, false, "Topik");
        front = text(front, 300, true, "Pertanyaan");
        back = text(back, 4000, true, "Jawaban");
        if (box < 0 || box > 5 || reviews < 0 || reviews > 1_000_000_000 || lapses < 0 || lapses > reviews || reviews - lapses < box) {
            throw new IllegalArgumentException("Progres kartu tidak valid.");
        }
        Objects.requireNonNull(due, "Jadwal wajib ada.");
        if (due.isBefore(Instant.EPOCH) || due.isAfter(Instant.parse("9999-12-31T00:00:00Z")) ||
                !Instant.ofEpochMilli(due.toEpochMilli()).equals(due)) {
            throw new IllegalArgumentException("Jadwal kartu di luar rentang atau presisi milidetik.");
        }
    }

    public static String text(String value, int maximum, boolean multiline, String field) {
        if (value == null) throw new IllegalArgumentException(field + " wajib diisi.");
        String clean = Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n').strip(), Normalizer.Form.NFC);
        if (clean.isEmpty() || clean.length() > maximum || !StandardCharsets.UTF_8.newEncoder().canEncode(clean)) {
            throw new IllegalArgumentException(field + " harus berisi 1–" + maximum + " karakter Unicode valid.");
        }
        for (int index = 0; index < clean.length(); index++) {
            char current = clean.charAt(index);
            if ((Character.isISOControl(current) && !(multiline && (current == '\n' || current == '\t')))) {
                throw new IllegalArgumentException(field + " memuat karakter kontrol yang tidak diizinkan.");
            }
        }
        return clean;
    }

    public String contentKey() { return tag + "\0" + front; }

    public record Draft(String tag, String front, String back) {
        public Draft {
            tag = text(tag, 40, false, "Topik");
            front = text(front, 300, true, "Pertanyaan");
            back = text(back, 4000, true, "Jawaban");
        }
    }
}
