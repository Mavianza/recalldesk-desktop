package recalldesk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record Library(long revision, List<Card> cards) {
    public static final int MAX_CARDS = 1000;

    public Library {
        if (revision < 0 || cards == null || cards.size() > MAX_CARDS) {
            throw new IllegalArgumentException("Revisi negatif atau jumlah kartu melebihi 1000.");
        }
        cards = List.copyOf(cards);
        Set<String> ids = new HashSet<>(), content = new HashSet<>();
        for (Card card : cards) {
            if (!ids.add(card.id())) throw new IllegalArgumentException("ID kartu duplikat.");
            if (!content.add(card.contentKey())) throw new IllegalArgumentException("Pertanyaan pada topik yang sama sudah ada: " + card.tag());
        }
    }

    public static Library empty() { return new Library(0, List.of()); }
}
