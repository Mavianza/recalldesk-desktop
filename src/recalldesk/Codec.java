package recalldesk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Envelope berversi + checksum; payload Properties UTF-8, tanpa Java object deserialization. */
public final class Codec {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final byte[] MAGIC = {'R', 'D', 'S', 'K', '0', '0', '0', '1'};
    private Codec() { }

    public static byte[] encode(Library library) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("revision", Long.toString(library.revision()));
        properties.setProperty("count", Integer.toString(library.cards().size()));
        for (int index = 0; index < library.cards().size(); index++) {
            Card card = library.cards().get(index);
            String prefix = "card." + index + ".";
            properties.setProperty(prefix + "id", card.id());
            properties.setProperty(prefix + "tag", card.tag());
            properties.setProperty(prefix + "front", card.front());
            properties.setProperty(prefix + "back", card.back());
            properties.setProperty(prefix + "box", Integer.toString(card.box()));
            properties.setProperty(prefix + "due", Long.toString(card.due().toEpochMilli()));
            properties.setProperty(prefix + "reviews", Integer.toString(card.reviews()));
            properties.setProperty(prefix + "lapses", Integer.toString(card.lapses()));
        }
        StringWriter writer = new StringWriter();
        properties.store(writer, "RecallDesk 1.0.0");
        byte[] payload = writer.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_BYTES - 44) throw new IOException("Pustaka melebihi batas 16 MiB.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC); output.writeInt(payload.length); output.write(payload); output.write(digest(payload));
        }
        return bytes.toByteArray();
    }

    public static Library decode(byte[] data) throws IOException {
        if (data.length < 44 || data.length > MAX_BYTES) throw new IOException("Ukuran pustaka tidak valid.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (!Arrays.equals(input.readNBytes(8), MAGIC)) throw new IOException("Versi/format pustaka tidak dikenal.");
            int length = input.readInt();
            if (length < 0 || length != data.length - 44) throw new IOException("Panjang payload pustaka tidak cocok.");
            byte[] payload = input.readNBytes(length), expected = input.readNBytes(32);
            if (!MessageDigest.isEqual(expected, digest(payload))) throw new IOException("Checksum pustaka tidak cocok; periksa backup.");
            Properties values = new Properties();
            values.load(new StringReader(utf8(payload)));
            if (!"1".equals(values.getProperty("version"))) throw new IOException("Versi payload tidak didukung.");
            long revision = Long.parseLong(required(values, "revision"));
            int count = Integer.parseInt(required(values, "count"));
            if (count < 0 || count > Library.MAX_CARDS) throw new IOException("Jumlah kartu tidak valid.");
            Set<String> keys = new HashSet<>(List.of("version", "revision", "count"));
            List<Card> cards = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String prefix = "card." + index + ".";
                for (String key : List.of("id", "tag", "front", "back", "box", "due", "reviews", "lapses")) keys.add(prefix + key);
                cards.add(new Card(required(values, prefix + "id"), required(values, prefix + "tag"),
                        required(values, prefix + "front"), required(values, prefix + "back"),
                        Integer.parseInt(required(values, prefix + "box")), Instant.ofEpochMilli(Long.parseLong(required(values, prefix + "due"))),
                        Integer.parseInt(required(values, prefix + "reviews")), Integer.parseInt(required(values, prefix + "lapses"))));
            }
            if (!values.stringPropertyNames().equals(keys)) throw new IOException("Kolom pustaka hilang atau tidak dikenal.");
            return new Library(revision, cards);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Isi pustaka tidak valid: " + error.getMessage(), error);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) throw new IOException("Kolom pustaka hilang: " + key);
        return value;
    }

    public static String utf8(byte[] data) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}
