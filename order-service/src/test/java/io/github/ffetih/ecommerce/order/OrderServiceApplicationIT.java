package io.github.ffetih.ecommerce.order;

import io.github.ffetih.ecommerce.order.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderServiceApplicationIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void startsAndAppliesTheFlywaySchema() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains("orders", "order_lines", "outbox_messages", "inbox_messages", "flyway_schema_history");
    }
}
