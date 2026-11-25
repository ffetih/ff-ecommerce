package io.github.ffetih.ecommerce.customer.adapter.in.web;

import io.github.ffetih.ecommerce.customer.application.CustomerService;
import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Customer profiles. {@code /me} is the caller's own profile: who "me" is comes from the access
 * token ({@code sub} is the customer id, {@code email} the address), never from the request.
 * Lookups by id are for the back office and for other services.
 */
@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping("/me")
    @ResponseStatus(HttpStatus.CREATED)
    CustomerResponse register(JwtAuthenticationToken caller, @Valid @RequestBody ProfileRequest request) {
        String email = caller.getToken().getClaimAsString("email");
        if (email == null) {
            throw new IllegalArgumentException("The access token carries no email address");
        }
        return CustomerResponse.from(
                customers.register(subject(caller), Email.of(email), request.fullName(), request.shippingAddress()));
    }

    @GetMapping("/me")
    CustomerResponse me(JwtAuthenticationToken caller) {
        return CustomerResponse.from(customers.get(subject(caller)));
    }

    @PutMapping("/me/shipping-address")
    CustomerResponse changeShippingAddress(JwtAuthenticationToken caller, @Valid @RequestBody AddressRequest request) {
        return CustomerResponse.from(customers.changeShippingAddress(subject(caller), request.shippingAddress()));
    }

    @GetMapping("/{id}")
    CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.from(customers.get(id));
    }

    @PostMapping("/{id}/deactivate")
    CustomerResponse deactivate(@PathVariable UUID id) {
        return CustomerResponse.from(customers.deactivate(id));
    }

    private static UUID subject(JwtAuthenticationToken caller) {
        return UUID.fromString(caller.getName());
    }

    record ProfileRequest(@NotBlank String fullName, @NotBlank String shippingAddress) {
    }

    record AddressRequest(@NotBlank String shippingAddress) {
    }

    record CustomerResponse(UUID id, String email, String fullName, String shippingAddress, boolean active) {

        static CustomerResponse from(Customer customer) {
            return new CustomerResponse(customer.id(), customer.email().value(), customer.fullName(),
                    customer.shippingAddress(), customer.isActive());
        }
    }
}
