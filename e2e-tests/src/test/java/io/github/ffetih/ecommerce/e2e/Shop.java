package io.github.ffetih.ecommerce.e2e;

import static org.assertj.core.api.Assertions.assertThat;

/** Steps that several end-to-end tests share. */
final class Shop {

    private Shop() {
    }

    /**
     * Registers the profile of the user behind the API's token, unless an earlier test already did
     * (201 or 409), and returns the customer id, which is that user's Keycloak id.
     */
    static String registered(Api customers, String fullName) {
        int status = customers.post("/api/v1/customers/me",
                "{\"fullName\":\"" + fullName + "\",\"shippingAddress\":\"Bağdat Cd. 1, İstanbul\"}").status();
        assertThat(status).isIn(201, 409);
        Api.Response me = customers.get("/api/v1/customers/me");
        assertThat(me.status()).isEqualTo(200);
        return me.text("id");
    }
}
