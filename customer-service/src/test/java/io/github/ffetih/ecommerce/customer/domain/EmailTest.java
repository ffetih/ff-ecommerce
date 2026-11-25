package io.github.ffetih.ecommerce.customer.domain;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EmailTest {

    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(Email.of("  Ada.Lovelace@Example.COM ").value()).isEqualTo("ada.lovelace@example.com");
    }

    @Test
    void lowerCasesIndependentlyOfTheTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // With the default locale, "ILKER".toLowerCase() gives "ılker" (dotless ı).
            assertThat(Email.of("ILKER@EXAMPLE.COM").value()).isEqualTo("ilker@example.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsObviouslyInvalidAddresses() {
        assertThatIllegalArgumentException().isThrownBy(() -> Email.of("no-at-sign.example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> Email.of("two@@example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> Email.of("spaces in@example.com"));
    }
}
