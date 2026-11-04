package recalldesk;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Snapshot immutable baru terlihat setelah commit disk berhasil. */
public final class StudyService {
    private final Store store;
    private final Clock clock;
    private Library library;

    public StudyService(Store store, Clock clock) throws IOException {
        this.store = store; this.clock = clock; library = store.load();
    }

    public synchronized Library snapshot() { return library; }
    public Path dataPath() { return store.path(); }
    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }

    public synchronized List<Card> due(String tag) {
        Instant now = now();
        return library.cards().stream().filter(card -> tag == null || card.tag().equals(tag))
                .filter(card -> !card.due().isAfter(now)).sorted(Comparator.comparing(Card::due).thenComparing(Card::id)).toList();
    }

    public synchronized List<String> tags() { return library.cards().stream().map(Card::tag).distinct().sorted().toList(); }

    public synchronized Stats stats(String tag) {
        List<Card> cards = library.cards().stream().filter(card -> tag == null || card.tag().equals(tag)).toList();
        return new Stats(cards.size(), due(tag).size(), (int) cards.stream().filter(card -> card.box() >= 3).count(),
                cards.stream().mapToLong(Card::reviews).sum(), cards.stream().mapToLong(Card::lapses).sum());
    }

    public synchronized void add(List<Card.Draft> drafts) throws IOException {
        if (drafts.isEmpty()) throw new IllegalArgumentException("Tidak ada kartu untuk ditambahkan.");
        List<Card> next = new ArrayList<>(library.cards());
        Instant now = now();
        for (Card.Draft draft : drafts) {
            next.add(new Card(UUID.randomUUID().toString(), draft.tag(), draft.front(), draft.back(), 0, now, 0, 0));
        }
        commit(next);
    }

    public synchronized void edit(String id, Card.Draft draft) throws IOException {
        Card old = find(id);
        replace(id, new Card(id, draft.tag(), draft.front(), draft.back(), old.box(), old.due(), old.reviews(), old.lapses()));
    }

    public synchronized void delete(String id) throws IOException {
        find(id);
        commit(library.cards().stream().filter(card -> !card.id().equals(id)).toList());
    }

    public synchronized Card review(String id, Scheduler.Grade grade) throws IOException {
        Card reviewed = Scheduler.review(find(id), grade, now());
        replace(id, reviewed);
        return reviewed;
    }

    public synchronized int importCsv(Path path) throws IOException {
        List<Card.Draft> drafts = Csv.read(path);
        add(drafts);
        return drafts.size();
    }

    public synchronized void exportCsv(Path path) throws IOException { Csv.writeNew(path, library.cards()); }

    public synchronized Card find(String id) {
        return library.cards().stream().filter(card -> card.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kartu tidak ditemukan; muat ulang koleksi."));
    }

    private void replace(String id, Card replacement) throws IOException {
        commit(library.cards().stream().map(card -> card.id().equals(id) ? replacement : card).toList());
    }

    private void commit(List<Card> cards) throws IOException {
        Library next = new Library(Math.incrementExact(library.revision()), cards);
        store.save(next, library.revision());
        library = next;
    }

    public record Stats(int total, int due, int advanced, long reviews, long lapses) { }
}
