package recalldesk;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class RecallDeskTest {
    private static int passed, assertions;
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private RecallDeskTest() { }
    @FunctionalInterface interface Checked { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        test("validasi teks dan normalisasi Unicode", () -> {
            Card.Draft draft = new Card.Draft(" SQL ", " cafe\u0301\r\ncontoh ", " Jawaban 日本 ");
            eq(draft.tag(), "SQL"); eq(draft.front(), "café\ncontoh"); eq(draft.back(), "Jawaban 日本");
            for (String value : List.of("", " ", "\u0000", "\ud800", "x".repeat(301))) {
                expect(IllegalArgumentException.class, () -> new Card.Draft("SQL", value, "A"));
            }
            expect(IllegalArgumentException.class, () -> new Card.Draft("a\nb", "Q", "A"));
            expect(IllegalArgumentException.class, () -> new Card.Draft("SQL", "Q", "x".repeat(4001)));
        });
        test("identitas dan progres kartu divalidasi", () -> {
            expect(IllegalArgumentException.class, () -> new Card("abc", "x", "Q", "A", 0, NOW, 0, 0));
            for (int box : new int[]{-1, 6}) expect(IllegalArgumentException.class, () -> card(box, NOW, 1, 0));
            expect(IllegalArgumentException.class, () -> card(0, NOW, -1, 0));
            expect(IllegalArgumentException.class, () -> card(0, NOW, 1, 2));
            expect(IllegalArgumentException.class, () -> card(4, NOW, 3, 0));
            expect(IllegalArgumentException.class, () -> card(0, Instant.EPOCH.minusMillis(1), 0, 0));
            expect(IllegalArgumentException.class, () -> card(0, NOW.plusNanos(1), 0, 0));
        });
        test("semua transisi kotak dan interval tepat", () -> {
            int[] days = {1, 3, 7, 14, 30, 30};
            for (int box = 0; box <= 5; box++) {
                Card source = card(box, NOW, 10, 2);
                Card remembered = Scheduler.review(source, Scheduler.Grade.REMEMBER, NOW);
                eq(remembered.box(), Math.min(5, box + 1)); eq(remembered.due(), NOW.plus(Duration.ofDays(days[box])));
                eq(remembered.reviews(), 11); eq(remembered.lapses(), 2); eq(source.reviews(), 10);
                Card forgotten = Scheduler.review(source, Scheduler.Grade.AGAIN, NOW);
                eq(forgotten.box(), 0); eq(forgotten.due(), NOW.plusSeconds(600)); eq(forgotten.lapses(), 3);
            }
        });
        test("batas jatuh tempo dan duplicate review", () -> {
            expect(IllegalArgumentException.class, () -> Scheduler.review(card(0, NOW.plusMillis(1), 0, 0), Scheduler.Grade.REMEMBER, NOW));
            Card result = Scheduler.review(card(0, NOW, 0, 0), Scheduler.Grade.REMEMBER, NOW.plusNanos(100));
            eq(result.due(), NOW.plusSeconds(86400));
        });
        test("10000 langkah review cocok dengan oracle interval", () -> {
            java.util.Random random = new java.util.Random(20261105);
            Card current = card(0, NOW, 0, 0);
            int expectedBox = 0, expectedLapses = 0;
            int[] days = {0, 1, 3, 7, 14, 30};
            for (int index = 1; index <= 10_000; index++) {
                boolean remember = random.nextBoolean();
                Instant instant = current.due();
                expectedBox = remember ? Math.min(5, expectedBox + 1) : 0;
                if (!remember) expectedLapses++;
                current = Scheduler.review(current, remember ? Scheduler.Grade.REMEMBER : Scheduler.Grade.AGAIN, instant);
                eq(current.box(), expectedBox); eq(current.reviews(), index); eq(current.lapses(), expectedLapses);
                eq(current.due(), instant.plusSeconds(remember ? days[expectedBox] * 86400L : 600));
            }
        });
        test("pustaka immutable dan duplikat ID/isi ditolak", () -> {
            Card card = card(0, NOW, 0, 0); List<Card> source = new ArrayList<>(List.of(card));
            Library library = new Library(0, source); source.clear(); eq(library.cards().size(), 1);
            expect(UnsupportedOperationException.class, () -> library.cards().clear());
            expect(IllegalArgumentException.class, () -> new Library(0, List.of(card, card)));
            expect(IllegalArgumentException.class, () -> new Library(0, List.of(card, card(0, NOW, 0, 0))));
            expect(IllegalArgumentException.class, () -> new Library(-1, List.of()));
        });
        test("codec kosong dan Unicode multiline roundtrip", () -> {
            eq(Codec.decode(Codec.encode(Library.empty())), Library.empty());
            Library source = new Library(9, List.of(new Card(UUID.randomUUID().toString(), "日本", "Q = café\nbaris", "A : \\ \"\n😀", 2, NOW, 4, 1)));
            eq(Codec.decode(Codec.encode(source)), source);
        });
        test("codec menolak truncation, bit flip, magic, dan trailing data", () -> {
            byte[] original = Codec.encode(new Library(1, List.of(card(0, NOW, 0, 0))));
            for (int length : new int[]{0, 1, 8, 43, original.length - 1}) {
                expect(IOException.class, () -> Codec.decode(Arrays.copyOf(original, length)));
            }
            for (int position : new int[]{0, 7, 8, 11, 12, original.length - 1}) {
                byte[] damaged = original.clone(); damaged[position] ^= 1; expect(IOException.class, () -> Codec.decode(damaged));
            }
            expect(IOException.class, () -> Codec.decode(Arrays.copyOf(original, original.length + 1)));
        });
        test("codec batas ukuran dan UTF-8 ketat", () -> {
            expect(IOException.class, () -> Codec.decode(new byte[Codec.MAX_BYTES + 1]));
            expect(IOException.class, () -> Codec.utf8(new byte[]{(byte) 0xff}));
        });
        test("CSV BOM, CRLF, koma, quote dan multiline", () -> {
            List<Card.Draft> drafts = Csv.parse("\ufefftag,front,back\r\nSQL,\"Apa, ini?\",\"Jawab \"\"ya\"\"\r\nbaris 2\"\r\n");
            eq(drafts.size(), 1); eq(drafts.get(0).front(), "Apa, ini?"); eq(drafts.get(0).back(), "Jawab \"ya\"\nbaris 2");
        });
        test("CSV quote header dan EOF tanpa newline", () -> {
            eq(Csv.parse("\"tag\",\"front\",\"back\"\nA,Q,B").get(0).tag(), "A");
            eq(Csv.parse("tag,front,back\rA,Q,B\r").size(), 1);
        });
        test("CSV syntax rusak dan data kosong ditolak", () -> {
            for (String text : List.of("", "tag,front,back\n", "front,back,tag\nQ,A,X", "tag,front,back\nx,\"q,a", "tag,front,back\nx,q\"x,a", "tag,front,back\nx,\"q\"x,a", "tag,front,back\nx,q,a,z", "tag,front,back\nx,q", "tag,front,back\nx,q,", "tag,front,back\n\n")) {
                expect(IOException.class, () -> Csv.parse(text));
            }
        });
        test("CSV batas 1000 kartu", () -> {
            StringBuilder csv = new StringBuilder("tag,front,back\n");
            for (int index = 0; index < 1000; index++) csv.append("X,Q").append(index).append(",A\n");
            eq(Csv.parse(csv.toString()).size(), 1000);
            csv.append("X,too much,A\n"); expect(IOException.class, () -> Csv.parse(csv.toString()));
        });
        test("CSV roundtrip teks berbagai delimiter", () -> {
            List<Card> cards = List.of(new Card(UUID.randomUUID().toString(), "Algoritma", "Q, = \"\n日本", "A\n\t=SUM(1,2)", 0, NOW, 0, 0));
            Card.Draft draft = Csv.parse(Csv.format(cards)).get(0);
            eq(draft.front(), cards.get(0).front()); eq(draft.back(), cards.get(0).back());
        });
        test("koleksi tambah-edit-hapus dan reopen", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); String id = f.service.snapshot().cards().get(0).id();
                f.service.edit(id, new Card.Draft("Topik baru", "Q baru", "A baru"));
                eq(f.service.find(id).front(), "Q baru"); eq(f.store.load(), f.service.snapshot());
                f.service.delete(id); eq(f.service.snapshot().cards().size(), 5);
                f.store.close();
                try (Store reopened = new Store(f.data)) { eq(reopened.load(), f.service.snapshot()); }
            }
        });
        test("impor duplikat seluruh batch dibatalkan", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); Library before = f.service.snapshot(); byte[] disk = Files.readAllBytes(f.data);
                expect(IllegalArgumentException.class, () -> f.service.add(List.of(new Card.Draft("Baru", "Pertanyaan unik", "A"), Samples.cards().get(0))));
                eq(f.service.snapshot(), before); check(Arrays.equals(disk, Files.readAllBytes(f.data)));
            }
        });
        test("batch melampaui kapasitas dibatalkan tanpa commit", () -> {
            try (Fixture f = new Fixture()) {
                List<Card.Draft> drafts = new ArrayList<>();
                for (int index = 0; index < 1001; index++) drafts.add(new Card.Draft("X", "Q" + index, "A"));
                expect(IllegalArgumentException.class, () -> f.service.add(drafts));
                eq(f.service.snapshot(), Library.empty()); check(!Files.exists(f.data));
            }
        });
        test("gagal penulisan menjaga snapshot memori dan disk", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); Library before = f.service.snapshot(); byte[] disk = Files.readAllBytes(f.data);
                Files.createDirectory(f.store.backupPath());
                expect(IOException.class, () -> f.service.add(List.of(new Card.Draft("Lain", "Q", "A"))));
                eq(f.service.snapshot(), before); check(Arrays.equals(disk, Files.readAllBytes(f.data)));
            }
        });
        test("review tersimpan, filter topik dan tempo 10 menit", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); eq(f.service.due("SQL").size(), 2); eq(f.service.due("Asing").size(), 0);
                String id = f.service.due("SQL").get(0).id(); f.service.review(id, Scheduler.Grade.AGAIN);
                eq(f.service.stats("SQL").due(), 1); eq(f.service.stats(null).lapses(), 1L);
                f.clock.instant = NOW.plusSeconds(599); eq(f.service.due("SQL").size(), 1);
                f.clock.instant = NOW.plusSeconds(600); eq(f.service.due("SQL").size(), 2);
                eq(f.store.load(), f.service.snapshot());
            }
        });
        test("penilaian kartu belum due tidak menambah revisi", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); String id = f.service.due(null).get(0).id();
                f.service.review(id, Scheduler.Grade.REMEMBER); Library before = f.service.snapshot();
                expect(IllegalArgumentException.class, () -> f.service.review(id, Scheduler.Grade.REMEMBER)); eq(f.service.snapshot(), before);
            }
        });
        test("edit kartu mempertahankan progres", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); String id = f.service.due(null).get(0).id();
                Card old = f.service.review(id, Scheduler.Grade.REMEMBER);
                f.service.edit(id, new Card.Draft("Revisi", "Q edit", "A edit")); Card edited = f.service.find(id);
                eq(edited.box(), old.box()); eq(edited.due(), old.due()); eq(edited.reviews(), old.reviews());
            }
        });
        test("revision conflict antar snapshot service ditolak", () -> {
            try (Fixture f = new Fixture()) {
                StudyService stale = new StudyService(f.store, f.clock); f.service.add(Samples.cards());
                expect(IOException.class, () -> stale.add(List.of(new Card.Draft("X", "Q", "A"))));
                eq(stale.snapshot(), Library.empty()); eq(f.store.load(), f.service.snapshot());
            }
        });
        test("dua Store dalam JVM ditolak dan lock dilepas", () -> {
            try (Fixture f = new Fixture()) {
                expect(IOException.class, () -> { try (Store other = new Store(f.data)) { other.load(); } });
                f.store.close(); try (Store other = new Store(f.data)) { eq(other.load(), Library.empty()); }
            }
        });
        test("lock antarproses dilepas setelah proses mati", () -> {
            try (Fixture f = new Fixture()) {
                f.store.close(); String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), "recalldesk.LockHolder", f.data.toString()).redirectErrorStream(true).start();
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                    check(reader.readLine().startsWith("LOCKED"));
                    expect(IOException.class, () -> { try (Store other = new Store(f.data)) { other.load(); } });
                } finally { child.destroyForcibly(); check(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); }
                try (Store reopened = new Store(f.data)) { eq(reopened.load(), Library.empty()); }
            }
        });
        test("backup generasi sebelumnya dan restore kerusakan", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); Library previous = f.service.snapshot();
                f.service.review(previous.cards().get(0).id(), Scheduler.Grade.REMEMBER);
                eq(Codec.decode(Files.readAllBytes(f.store.backupPath())), previous);
                Files.write(f.data, new byte[]{1, 2, 3}); expect(IOException.class, () -> f.store.load());
                eq(Files.size(f.data), 3L); eq(f.store.restoreBackup(), previous); eq(f.store.load(), previous);
            }
        });
        test("main hilang dengan backup tidak menjadi pustaka kosong", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); f.service.delete(f.service.snapshot().cards().get(0).id());
                Files.delete(f.data); expect(IOException.class, () -> f.store.load());
                eq(f.store.restoreBackup().cards().size(), 6);
            }
        });
        test("backup rusak tidak menimpa data utama", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); byte[] original = Files.readAllBytes(f.data);
                Files.write(f.store.backupPath(), new byte[]{9}); expect(IOException.class, () -> f.store.restoreBackup());
                check(Arrays.equals(original, Files.readAllBytes(f.data)));
            }
        });
        test("Store tertutup menolak akses", () -> {
            try (Fixture f = new Fixture()) { f.store.close(); expect(IOException.class, () -> f.store.load()); }
        });
        test("impor-ekspor CSV dan proteksi output lama", () -> {
            try (Fixture f = new Fixture()) {
                f.service.importCsv(Path.of("examples", "kartu-informatika.csv")); eq(f.service.snapshot().cards().size(), 6);
                Path output = f.root.resolve("export.csv"); f.service.exportCsv(output); eq(Csv.read(output).size(), 6);
                byte[] original = Files.readAllBytes(output); expect(IOException.class, () -> f.service.exportCsv(output)); check(Arrays.equals(original, Files.readAllBytes(output)));
            }
        });
        test("impor CSV tidak valid tidak memodifikasi koleksi", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); Library before = f.service.snapshot(); Path bad = f.root.resolve("bad.csv");
                Files.writeString(bad, "tag,front,back\nX,Q,A\nY,\"belum ditutup"); expect(IOException.class, () -> f.service.importCsv(bad)); eq(f.service.snapshot(), before);
            }
        });
        test("GUI empty state dan penilaian hanya setelah reveal", () -> {
            try (Fixture f = new Fixture()) {
                AppPanel panel = onEdt(() -> new AppPanel(f.service));
                onEdt(() -> { check(!panel.reveal.isEnabled()); check(!panel.remember.isEnabled()); eq(panel.total.getText(), "0"); });
                f.service.add(Samples.cards());
            }
        });
        test("GUI reveal → remember → persisted progress", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); AppPanel panel = onEdt(() -> new AppPanel(f.service));
                onEdt(() -> {
                    check(panel.reveal.isEnabled()); check(!panel.remember.isEnabled());
                    panel.reveal.doClick(); check(panel.remember.isEnabled()); check(!panel.answer.getText().equals("Jawaban masih tertutup."));
                    panel.remember.doClick(); check(panel.isBusy());
                });
                waitDone(panel); eq(f.service.stats(null).reviews(), 1L); eq(f.service.stats(null).due(), 5);
                onEdt(() -> { eq(panel.reviews.getText(), "1"); check(!panel.remember.isEnabled()); eq(panel.lastError, ""); });
                eq(f.store.load(), f.service.snapshot());
            }
        });
        test("GUI CRUD editor benar-benar tersimpan", () -> {
            try (Fixture f = new Fixture()) {
                AppPanel panel = onEdt(() -> new AppPanel(f.service));
                onEdt(() -> { panel.tabs.setSelectedIndex(1); panel.tagInput.setText("SQL"); panel.frontInput.setText("Q awal"); panel.backInput.setText("A awal"); panel.save.doClick(); });
                waitDone(panel); eq(f.service.snapshot().cards().size(), 1);
                onEdt(() -> { panel.table.setRowSelectionInterval(0, 0); panel.frontInput.setText("Q edit"); panel.save.doClick(); });
                waitDone(panel); eq(f.service.snapshot().cards().get(0).front(), "Q edit");
                onEdt(() -> { panel.table.setRowSelectionInterval(0, 0); panel.delete.doClick(); });
                waitDone(panel); eq(f.store.load().cards().size(), 0);
            }
        });
        test("GUI validasi draft tidak mengubah data", () -> {
            try (Fixture f = new Fixture()) {
                AppPanel panel = onEdt(() -> new AppPanel(f.service)); onEdt(() -> panel.save.doClick());
                check(!panel.lastError.isEmpty()); eq(f.service.snapshot(), Library.empty()); check(!Files.exists(f.data));
                f.service.add(Samples.cards());
                AppPanel withCards = onEdt(() -> new AppPanel(f.service));
                Card.Draft duplicate = Samples.cards().get(0); Library before = f.service.snapshot();
                onEdt(() -> { withCards.tagInput.setText(duplicate.tag()); withCards.frontInput.setText(duplicate.front()); withCards.backInput.setText("Jawaban baru yang belum disimpan"); withCards.save.doClick(); });
                waitIdle(withCards);
                onEdt(() -> { check(!withCards.lastError.isEmpty()); eq(withCards.backInput.getText(), "Jawaban baru yang belum disimpan"); });
                eq(f.service.snapshot(), before);
            }
        });
        test("GUI filter, pencarian, dan nama topik khusus", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); f.service.add(List.of(new Card.Draft("Semua topik", "Pertanyaan khusus", "Jawaban")));
                AppPanel panel = onEdt(() -> new AppPanel(f.service));
                onEdt(() -> {
                    panel.selectTopic("SQL"); eq(panel.tableModel.getRowCount(), 2); eq(panel.total.getText(), "2");
                    panel.search.setText("rollback"); eq(panel.tableModel.getRowCount(), 1);
                    panel.search.setText(""); panel.selectTopic("Semua topik"); eq(panel.total.getText(), "1");
                    panel.selectTopic(null); eq(panel.total.getText(), "7");
                });
            }
        });
        test("GUI impor dan ekspor memakai worker", () -> {
            try (Fixture f = new Fixture()) {
                AppPanel panel = onEdt(() -> new AppPanel(f.service));
                onEdt(() -> panel.importFile(Path.of("examples", "kartu-informatika.csv"))); waitDone(panel); eq(f.service.snapshot().cards().size(), 6);
                Path output = f.root.resolve("from-ui.csv"); onEdt(() -> panel.exportFile(output)); waitDone(panel); eq(Csv.read(output).size(), 6);
            }
        });
        test("render panel Swing ke PNG", () -> {
            try (Fixture f = new Fixture()) {
                f.service.add(Samples.cards()); AppPanel panel = onEdt(() -> new AppPanel(f.service));
                onEdt(() -> {
                    panel.selectTopic("SQL"); panel.reveal.doClick(); panel.setSize(1180, 820); layout(panel); layout(panel);
                    BufferedImage image = new BufferedImage(1180, 820, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
                    check(image.getRGB(10, 10) != image.getRGB(10, 400));
                    Files.createDirectories(Path.of("build")); check(ImageIO.write(image, "png", Path.of("build", "ui-preview.png").toFile()));
                    panel.tabs.setSelectedIndex(1); panel.table.setRowSelectionInterval(0, 0); layout(panel); layout(panel);
                    graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
                    check(ImageIO.write(image, "png", Path.of("build", "ui-collection-preview.png").toFile()));
                });
                check(Files.size(Path.of("build", "ui-preview.png")) > 10_000);
            }
        });
        System.out.println("LULUS " + passed + " tes; " + assertions + " assertion; tanpa tes dilewati.");
    }

    private static Card card(int box, Instant due, int reviews, int lapses) { return new Card(UUID.randomUUID().toString(), "SQL", "Q", "A", box, due, reviews, lapses); }
    private static void test(String name, Checked checked) throws Exception { checked.run(); passed++; System.out.println("OK " + name); }
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("Assertion " + assertions + " gagal"); }
    private static void eq(Object actual, Object expected) { assertions++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Aktual=" + actual + "; harapan=" + expected); }
    private static void expect(Class<? extends Exception> type, Checked checked) throws Exception {
        try { checked.run(); } catch (Exception error) { check(type.isInstance(error)); return; }
        throw new AssertionError("Seharusnya melempar " + type.getName());
    }
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { value.set(callable.call()); } catch (Exception issue) { error.set(issue); } });
        if (error.get() != null) throw error.get(); return value.get();
    }
    private static void onEdt(Checked checked) throws Exception { onEdt(() -> { checked.run(); return null; }); }
    private static void waitDone(AppPanel panel) throws Exception {
        waitIdle(panel); onEdt(() -> eq(panel.lastError, ""));
    }
    private static void waitIdle(AppPanel panel) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (panel.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
        onEdt(() -> check(!panel.isBusy()));
    }
    private static void layout(Container container) { container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
    static final class MutableClock extends Clock {
        Instant instant = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
    static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("recalldesk-test-");
        final Path data = root.resolve("library.rdb");
        final Store store = new Store(data);
        final MutableClock clock = new MutableClock();
        final StudyService service = new StudyService(store, clock);
        Fixture() throws IOException { }
        @Override public void close() throws IOException {
            store.close(); try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
