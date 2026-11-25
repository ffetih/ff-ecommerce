package io.github.ffetih.ecommerce.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Minimal application for the module's integration tests; it plays the role of a business service. */
@SpringBootApplication
class MessagingTestApplication {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** A real service gets its registry from actuator; this module does not depend on it. */
    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
