package io.github.ffetih.ecommerce.catalog.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private static Reservation.Line line(String sku, int quantity) {
        return new Reservation.Line(Sku.of(sku), quantity);
    }

    @Test
    void keepsLinesSortedBySkuSoLocksAreAlwaysTakenInTheSameOrder() {
        Reservation reservation = Reservation.place(ORDER, List.of(line("MOUSE-01", 1), line("KB-01", 2)), NOW);

        assertThat(reservation.lines()).extracting(l -> l.sku().value()).containsExactly("KB-01", "MOUSE-01");
    }

    @Test
    void rejectsDuplicateSkusAndEmptyReservations() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Reservation.place(ORDER, List.of(line("KB-01", 1), line("kb-01", 2)), NOW));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Reservation.place(ORDER, List.of(), NOW));
    }

    @Test
    void comparesLinesIndependentlyOfOrder() {
        Reservation reservation = Reservation.place(ORDER, List.of(line("KB-01", 2), line("MOUSE-01", 1)), NOW);

        assertThat(reservation.hasSameLines(List.of(line("MOUSE-01", 1), line("KB-01", 2)))).isTrue();
        assertThat(reservation.hasSameLines(List.of(line("KB-01", 3), line("MOUSE-01", 1)))).isFalse();
    }

    @Test
    void commitAndReleaseAreIdempotent() {
        Reservation committed = Reservation.place(ORDER, List.of(line("KB-01", 1)), NOW).commit(NOW);
        Reservation released = Reservation.place(ORDER, List.of(line("KB-01", 1)), NOW).release(NOW);

        assertThat(committed.commit(NOW.plusSeconds(5))).isSameAs(committed);
        assertThat(released.release(NOW.plusSeconds(5))).isSameAs(released);
    }

    @Test
    void cannotReleaseAfterCommitOrCommitAfterRelease() {
        Reservation committed = Reservation.place(ORDER, List.of(line("KB-01", 1)), NOW).commit(NOW);
        Reservation released = Reservation.place(ORDER, List.of(line("KB-01", 1)), NOW).release(NOW);

        assertThatThrownBy(() -> committed.release(NOW)).isInstanceOf(InvalidReservationStateException.class);
        assertThatThrownBy(() -> released.commit(NOW)).isInstanceOf(InvalidReservationStateException.class);
    }
}
