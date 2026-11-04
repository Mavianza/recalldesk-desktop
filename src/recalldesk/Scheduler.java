package recalldesk;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class Scheduler {
    private static final int[] DAYS = {0, 1, 3, 7, 14, 30};
    public enum Grade { AGAIN, REMEMBER }
    private Scheduler() { }

    public static Card review(Card card, Grade grade, Instant instant) {
        Objects.requireNonNull(grade, "Penilaian wajib ada.");
        Instant now = instant.truncatedTo(ChronoUnit.MILLIS);
        if (card.due().isAfter(now)) throw new IllegalArgumentException("Kartu belum jatuh tempo; muat ulang sesi.");
        int nextBox = grade == Grade.AGAIN ? 0 : Math.min(5, card.box() + 1);
        Instant due = now.plus(grade == Grade.AGAIN ? Duration.ofMinutes(10) : Duration.ofDays(DAYS[nextBox]));
        return new Card(card.id(), card.tag(), card.front(), card.back(), nextBox, due,
                Math.incrementExact(card.reviews()), card.lapses() + (grade == Grade.AGAIN ? 1 : 0));
    }

    public static String intervalLabel(Grade grade, int box) {
        return grade == Grade.AGAIN ? "10 menit" : DAYS[Math.min(5, box + 1)] + " hari";
    }
}
