package io.github.ffetih.ecommerce.customer.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private static Customer ada() {
        return Customer.register(UUID.randomUUID(), Email.of("ada@example.com"), " Ada Lovelace ", "12 Analytical St, London", NOW);
    }

    @Test
    void registersAsActiveWithTrimmedFields() {
        Customer customer = ada();

        assertThat(customer.isActive()).isTrue();
        assertThat(customer.fullName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void deactivationIsIdempotentAndBlocksAddressChanges() {
        Customer deactivated = ada().deactivate(NOW);

        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.deactivate(NOW.plusSeconds(1))).isSameAs(deactivated);
        assertThatThrownBy(() -> deactivated.changeShippingAddress("Elsewhere", NOW))
                .isInstanceOf(CustomerDeactivatedException.class);
    }
}
