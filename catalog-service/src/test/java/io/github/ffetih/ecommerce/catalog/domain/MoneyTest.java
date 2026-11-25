package io.github.ffetih.ecommerce.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MoneyTest {

    @Test
    void storesTheCurrencyScale() {
        assertThat(Money.of("12.5", "EUR").amount().toPlainString()).isEqualTo("12.50");
        assertThat(Money.of("12.500", "EUR").amount().toPlainString()).isEqualTo("12.50");
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyAllowsInsteadOfRounding() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("12.345", "EUR"));
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("-1.00", "EUR"));
    }

    @Test
    void multipliesAndAdds() {
        Money line = Money.of("19.99", "EUR").times(3);
        assertThat(line).isEqualTo(Money.of("59.97", "EUR"));
        assertThat(line.plus(Money.of("0.03", "EUR"))).isEqualTo(Money.of("60.00", "EUR"));
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of("1.00", "EUR").plus(Money.of("1.00", "USD")));
    }
}
