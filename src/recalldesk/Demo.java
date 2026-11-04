package recalldesk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Integrasi reproducible dengan waktu tetap dan direktori temporer. */
public final class Demo {
    private Demo() { }

    public static void run() throws IOException {
        Path root = Files.createTempDirectory("recalldesk-demo-");
        int checks = 0;
        try {
            Path data = root.resolve("library.rdb"), csv = root.resolve("cards.csv");
            Instant now = Instant.parse("2026-11-05T00:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            Library expected;
            try (Store store = new Store(data)) {
                StudyService service = new StudyService(store, clock);
                require(service.snapshot().cards().isEmpty(), "Pustaka awal kosong"); checks++;
                service.add(Samples.cards()); require(service.snapshot().cards().size() == 6, "Enam kartu ditambahkan"); checks++;
                require(service.due(null).size() == 6 && service.tags().size() == 3, "Queue dan topik"); checks++;
                String first = service.due(null).get(0).id();
                Card remembered = service.review(first, Scheduler.Grade.REMEMBER);
                require(remembered.box() == 1 && remembered.due().equals(now.plus(Duration.ofDays(1))), "Interval ingat"); checks++;
                Library before = service.snapshot();
                boolean refused = false;
                try { service.review(first, Scheduler.Grade.REMEMBER); } catch (IllegalArgumentException error) { refused = true; }
                require(refused && service.snapshot().equals(before), "Double review ditolak"); checks++;
                Card forgotten = service.review(service.due(null).get(0).id(), Scheduler.Grade.AGAIN);
                require(forgotten.box() == 0 && forgotten.due().equals(now.plusSeconds(600)) && forgotten.lapses() == 1, "Interval lupa"); checks++;
                require(service.stats(null).reviews() == 2 && service.stats(null).due() == 4 && service.stats(null).lapses() == 1, "Statistik progres"); checks++;
                service.exportCsv(csv); require(Csv.read(csv).size() == 6, "CSV ekspor dapat dibaca"); checks++;
                before = service.snapshot(); refused = false;
                try { service.importCsv(csv); } catch (IllegalArgumentException error) { refused = true; }
                require(refused && service.snapshot().equals(before), "Impor duplikat atomik"); checks++;
                expected = service.snapshot();
                require(Codec.decode(Files.readAllBytes(store.backupPath())).cards().stream().mapToInt(Card::reviews).sum() == 1, "Backup generasi sebelumnya"); checks++;
            }
            try (Store reopened = new Store(data)) {
                StudyService service = new StudyService(reopened, clock);
                require(service.snapshot().equals(expected), "Persistensi setelah reopen"); checks++;
                StudyService later = new StudyService(reopened, Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC));
                require(later.due(null).size() == 5, "Kartu lupa kembali tepat 10 menit"); checks++;
            }
            try (Store imported = new Store(root.resolve("import.rdb"))) {
                StudyService service = new StudyService(imported, clock); service.importCsv(csv);
                List<String> actual = service.snapshot().cards().stream().map(card -> card.contentKey() + "\0" + card.back()).sorted().toList();
                List<String> wanted = expected.cards().stream().map(card -> card.contentKey() + "\0" + card.back()).sorted().toList();
                require(actual.equals(wanted) && service.stats(null).reviews() == 0, "Roundtrip isi CSV, progres baru"); checks++;
            }
            Files.write(data, new byte[]{1, 2, 3});
            try (Store damaged = new Store(data)) {
                boolean rejected = false;
                try { damaged.load(); } catch (IOException error) { rejected = true; }
                require(rejected && Files.size(data) == 3, "Korupsi tidak ditimpa kosong"); checks++;
                Library restored = damaged.restoreBackup();
                require(restored.cards().size() == 6 && restored.cards().stream().mapToInt(Card::reviews).sum() == 1, "Pemulihan backup"); checks++;
                require(damaged.load().equals(restored), "Hasil pemulihan dapat dibuka"); checks++;
            }
            System.out.println("{\"passed\":" + checks + ",\"cards\":6,\"reviews_before_restore\":2,\"due_after_10_minutes\":5,\"csv_roundtrip\":true,\"backup_restore\":true}");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
