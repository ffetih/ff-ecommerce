package io.github.ffetih.ecommerce.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Payment settings ({@code ecommerce.payments.*}). */
@ConfigurationProperties("ecommerce.payments")
public record PaymentProperties(@DefaultValue Resolver resolver) {

    /**
     * Finishes provider calls whose outcome is unknown.
     *
     * @param enabled      whether this instance runs it; tests may switch it off and call it directly
     * @param staleAfter   how long a call may stay unanswered before it is asked again
     * @param scanInterval pause between two runs
     * @param batchSize    payments handled per run at most, oldest first
     */
    public record Resolver(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration staleAfter,
            @DefaultValue("15s") Duration scanInterval,
            @DefaultValue("50") int batchSize) {
    }
}
