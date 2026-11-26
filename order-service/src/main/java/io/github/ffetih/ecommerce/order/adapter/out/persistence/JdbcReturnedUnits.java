package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import io.github.ffetih.ecommerce.order.application.port.ReturnedUnits;
import io.github.ffetih.ecommerce.order.domain.Sku;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The counter behind "never more than was bought". The whole check is one statement: the first
 * return of a line inserts its row, later ones add to it only {@code WHERE} the sum stays within the
 * ordered quantity. PostgreSQL runs a conflicting insert as an update on the locked row, so two
 * concurrent requests are counted one after the other, and the second sees the first.
 */
@Component
class JdbcReturnedUnits implements ReturnedUnits {

    private final JdbcClient jdbc;

    JdbcReturnedUnits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean take(UUID orderId, Sku sku, int ordered, int quantity) {
        if (quantity > ordered) {
            return false;
        }
        return jdbc.sql("""
                        INSERT INTO returned_units (order_id, sku, ordered, returned)
                        VALUES (:orderId, :sku, :ordered, :quantity)
                        ON CONFLICT (order_id, sku) DO UPDATE
                            SET returned = returned_units.returned + EXCLUDED.returned
                            WHERE returned_units.returned + EXCLUDED.returned <= returned_units.ordered""")
                .param("orderId", orderId)
                .param("sku", sku.value())
                .param("ordered", ordered)
                .param("quantity", quantity)
                .update() == 1;
    }

    @Override
    public void giveBack(UUID orderId, Sku sku, int quantity) {
        jdbc.sql("UPDATE returned_units SET returned = returned - :quantity WHERE order_id = :orderId AND sku = :sku")
                .param("orderId", orderId)
                .param("sku", sku.value())
                .param("quantity", quantity)
                .update();
    }

    @Override
    public Map<Sku, Integer> of(UUID orderId) {
        return jdbc.sql("SELECT sku, returned FROM returned_units WHERE order_id = :orderId AND returned > 0")
                .param("orderId", orderId)
                .query((rs, row) -> Map.entry(Sku.of(rs.getString("sku")), rs.getInt("returned")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
