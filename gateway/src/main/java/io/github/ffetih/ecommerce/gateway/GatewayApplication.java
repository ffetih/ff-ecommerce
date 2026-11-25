package io.github.ffetih.ecommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single entry point for clients. It only routes: every request goes, unchanged and with its
 * {@code Authorization} header, to the service that owns the path, and that service decides who may
 * do what (ADR-0008). Paths that belong to no service are not forwarded.
 *
 * @see <a href="https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/adr/0008-security.html">ADR-0008</a>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
