package io.github.ffetih.ecommerce.customer.adapter.in.web;

import io.github.ffetih.ecommerce.customer.application.CustomerService;
import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Customer accounts. Until s05 the API is unauthenticated; ownership checks arrive with OAuth2. */
@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CustomerResponse register(@Valid @RequestBody RegisterRequest request) {
        return CustomerResponse.from(
                customers.register(Email.of(request.email()), request.fullName(), request.shippingAddress()));
    }

    @GetMapping("/{id}")
    CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.from(customers.get(id));
    }

    @PutMapping("/{id}/shipping-address")
    CustomerResponse changeShippingAddress(@PathVariable UUID id, @Valid @RequestBody AddressRequest request) {
        return CustomerResponse.from(customers.changeShippingAddress(id, request.shippingAddress()));
    }

    @PostMapping("/{id}/deactivate")
    CustomerResponse deactivate(@PathVariable UUID id) {
        return CustomerResponse.from(customers.deactivate(id));
    }

    record RegisterRequest(@NotBlank String email, @NotBlank String fullName, @NotBlank String shippingAddress) {
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
