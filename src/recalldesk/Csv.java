package recalldesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** CSV tiga kolom; quoted multiline dan escaped quotes, validasi seluruh batch. */
public final class Csv {
    private Csv() { }

    public static List<Card.Draft> read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Input CSV harus berkas biasa.");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(Codec.MAX_BYTES + 1);
        }
        if (bytes.length > Codec.MAX_BYTES) throw new IOException("CSV melebihi 16 MiB.");
        return parse(Codec.utf8(bytes));
    }

    public static List<Card.Draft> parse(String raw) throws IOException {
        String input = raw.startsWith("\ufeff") ? raw.substring(1) : raw;
        if (input.length() > Codec.MAX_BYTES) throw new IOException("CSV terlalu besar.");
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closedQuote = false, start = true, pending = false;
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            pending = true;
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < input.length() && input.charAt(index + 1) == '"') { field.append('"'); index++; }
                    else { quoted = false; closedQuote = true; }
                } else field.append(current);
                continue;
            }
            if (current == ',' || current == '\n' || current == '\r') {
                row.add(field.toString()); field.setLength(0); start = true; closedQuote = false;
                if (row.size() > 3) throw new IOException("CSV harus tepat tiga kolom.");
                if (current != ',') {
                    rows.add(List.copyOf(row)); row.clear(); pending = false;
                    if (rows.size() > Library.MAX_CARDS + 1) throw new IOException("CSV melebihi 1000 kartu.");
                    if (current == '\r' && index + 1 < input.length() && input.charAt(index + 1) == '\n') index++;
                }
            } else if (closedQuote) throw new IOException("Karakter setelah quote penutup CSV tidak valid.");
            else if (current == '"') {
                if (!start) throw new IOException("Quote harus berada pada awal field CSV.");
                quoted = true; start = false;
            } else { field.append(current); start = false; }
        }
        if (quoted) throw new IOException("Quote CSV belum ditutup.");
        if (pending || !row.isEmpty()) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        if (rows.isEmpty() || !rows.get(0).equals(List.of("tag", "front", "back"))) {
            throw new IOException("Header CSV harus tepat: tag,front,back");
        }
        if (rows.size() < 2 || rows.size() > Library.MAX_CARDS + 1) throw new IOException("CSV harus memuat 1–1000 kartu.");
        List<Card.Draft> drafts = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> cells = rows.get(rowIndex);
            if (cells.size() != 3) throw new IOException("Baris " + (rowIndex + 1) + " harus tiga kolom.");
            try { drafts.add(new Card.Draft(cells.get(0), cells.get(1), cells.get(2))); }
            catch (IllegalArgumentException error) { throw new IOException("Baris " + (rowIndex + 1) + ": " + error.getMessage(), error); }
        }
        return List.copyOf(drafts);
    }

    public static String format(List<Card> cards) {
        StringBuilder output = new StringBuilder("tag,front,back\r\n");
        for (Card card : cards) {
            output.append(quote(card.tag())).append(',').append(quote(card.front())).append(',').append(quote(card.back())).append("\r\n");
        }
        return output.toString();
    }

    public static void writeNew(Path path, List<Card> cards) throws IOException {
        Files.writeString(path, format(cards), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String quote(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
}
