package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.application.ConflictException;
import io.github.ffetih.ecommerce.catalog.application.port.ProductRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import io.github.ffetih.ecommerce.catalog.domain.Money;
import io.github.ffetih.ecommerce.catalog.domain.Product;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.support.TestData;
import io.github.ffetih.ecommerce.catalog.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inserting a product never overwrites one with the same SKU. Concurrent creations rely on it: both
 * pass the existence check, and only the primary key can refuse the second. Deterministic, unlike
 * the race itself (see CatalogApiIT).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductPersistenceIT {

    @Autowired
    ProductRepository products;

    @Autowired
    Transactions transactions;

    @Test
    void aSecondInsertWithTheSameSkuFailsAndLeavesTheFirstProductAsItWas() {
        Sku sku = TestData.uniqueSku("INS");
        insert(Product.create(sku, "First", Money.of("10.00", "EUR")));

        assertThatThrownBy(() -> insert(Product.create(sku, "Second", Money.of("1.00", "EUR"))))
                .isInstanceOf(ConflictException.class);
        Product stored = transactions.inTransaction(() -> products.findBySku(sku)).orElseThrow();
        assertThat(stored.name()).isEqualTo("First");
        assertThat(stored.unitPrice()).isEqualTo(Money.of("10.00", "EUR"));
    }

    private void insert(Product product) {
        transactions.inTransaction(() -> products.insert(product));
    }
}
