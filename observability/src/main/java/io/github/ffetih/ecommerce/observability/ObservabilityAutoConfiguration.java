package io.github.ffetih.ecommerce.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers the platform's observation rules in every servlet application that depends on this module.
 * Spring Boot applies every {@link ObservationPredicate} bean to the application's ObservationRegistry.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ObservabilityAutoConfiguration {

    @Bean
    ObservationPredicate actuatorRequestsNotObserved() {
        return new ActuatorRequestsNotObserved();
    }
}
