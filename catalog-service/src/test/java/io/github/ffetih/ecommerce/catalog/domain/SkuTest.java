package io.github.ffetih.ecommerce.catalog.domain;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SkuTest {

    @Test
    void normalizesToUpperCase() {
        assertThat(Sku.of("  kb-mech-01 ").value()).isEqualTo("KB-MECH-01");
    }

    @Test
    void normalizesIndependentlyOfTheTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // With the default locale, "mi".toUpperCase() would give "Mİ" (dotted capital I).
            assertThat(Sku.of("kb-mini-01").value()).isEqualTo("KB-MINI-01");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsInvalidCharacters() {
        assertThatIllegalArgumentException().isThrownBy(() -> Sku.of("KB MECH"));
        assertThatIllegalArgumentException().isThrownBy(() -> Sku.of("-KB"));
        assertThatIllegalArgumentException().isThrownBy(() -> Sku.of(""));
    }
}
