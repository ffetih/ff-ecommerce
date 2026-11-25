package io.github.ffetih.ecommerce.order.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.ffetih.ecommerce.order.application.DownstreamUnavailableException;
import io.github.ffetih.ecommerce.order.application.port.CustomerDirectory;
import io.github.ffetih.ecommerce.order.config.ClientProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads customers from customer-service.
 * <ul>
 *   <li>404: the customer does not exist, a business answer.</li>
 *   <li>5xx, timeout, connection refused: we could not find out; the caller may retry later.</li>
 *   <li>Any other 4xx: our request was wrong, which is a bug on this side.</li>
 * </ul>
 */
@Component
class HttpCustomerDirectory implements CustomerDirectory {

    private final RestClient rest;

    HttpCustomerDirectory(ClientProperties properties) {
        this.rest = RestClients.create(properties.customer());
    }

    @Override
    public Optional<CustomerSnapshot> find(UUID customerId) {
        try {
            CustomerDto customer = rest.get().uri("/api/v1/customers/{id}", customerId).retrieve().body(CustomerDto.class);
            return Optional.ofNullable(customer)
                    .map(dto -> new CustomerSnapshot(dto.id(), dto.active(), dto.shippingAddress()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("customer-service rejected our request: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("customer-service is unavailable", e);
        }
    }

    /** Tolerant reader: only the fields we use; new fields on the other side do not break us. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CustomerDto(UUID id, String shippingAddress, boolean active) {
    }
}
