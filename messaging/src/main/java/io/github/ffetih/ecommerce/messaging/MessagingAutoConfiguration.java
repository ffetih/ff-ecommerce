package io.github.ffetih.ecommerce.messaging;

import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.messaging.outbox.OutboxRelay;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;

/** Registers the outbox writer and relay for any service that depends on this module. */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Outbox outbox(DataSource dataSource, JsonMapper json, ObjectProvider<Clock> clock,
                  MessagingProperties properties, Environment environment) {
        String source = properties.source() != null
                ? properties.source()
                : environment.getProperty("spring.application.name", "unknown-service");
        return new Outbox(JdbcClient.create(dataSource), json, clock.getIfAvailable(Clock::systemUTC), source);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(DataSource dataSource, KafkaTemplate<String, String> kafka,
                            PlatformTransactionManager transactionManager, MessagingProperties properties) {
        return new OutboxRelay(JdbcClient.create(dataSource), kafka,
                new TransactionTemplate(transactionManager), properties.outbox());
    }
}
