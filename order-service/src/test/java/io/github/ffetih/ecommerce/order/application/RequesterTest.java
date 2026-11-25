package io.github.ffetih.ecommerce.order.application;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequesterTest {

    private final UUID alice = UUID.randomUUID();

    @Test
    void aCustomerActsOnlyForThemselves() {
        assertThat(Requester.customer(alice).mayActFor(alice)).isTrue();
        assertThat(Requester.customer(alice).mayActFor(UUID.randomUUID())).isFalse();
        assertThat(Requester.customer(alice).mayActFor(null)).isFalse();
    }

    @Test
    void theBackOfficeActsForAnyone() {
        assertThat(Requester.backOffice().mayActFor(alice)).isTrue();
    }

    @Test
    void aCustomerNeedsAnId() {
        assertThatThrownBy(() -> Requester.customer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void isEitherACustomerWithAnIdOrTheBackOffice() {
        assertThatThrownBy(() -> new Requester(null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Requester(alice, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
