package io.github.ffetih.ecommerce.e2e;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The whole system for end-to-end tests: one PostgreSQL (initialized with the same scripts as
 * compose.yaml), one Kafka, Keycloak with the same realm as compose.yaml, the five services and the
 * gateway as separate processes. Tests talk to the gateway, as clients do.
 *
 * <p>Started once per test JVM and shared by all end-to-end test classes; stopped when the JVM exits.
 */
final class Platform implements AutoCloseable {

    private static final List<String> TOPICS = List.of(
            "catalog.stock", "catalog.reservations", "order.lifecycle", "shipping.shipments",
            "payment.commands", "payment.events",
            "order.lifecycle-dlt", "catalog.reservations-dlt", "shipping.shipments-dlt",
            "payment.commands-dlt", "payment.events-dlt");

    private static Platform shared;

    final PostgreSQLContainer postgres;
    final KafkaContainer kafka;
    final GenericContainer<?> keycloak;
    final Tokens tokens;
    final ServiceProcess catalog;
    final ServiceProcess customer;
    final ServiceProcess order;
    final ServiceProcess shipping;
    final ServiceProcess payment;
    final ServiceProcess gateway;

    private Platform(PostgreSQLContainer postgres, KafkaContainer kafka, GenericContainer<?> keycloak, Tokens tokens,
                     ServiceProcess catalog, ServiceProcess customer, ServiceProcess order, ServiceProcess shipping,
                     ServiceProcess payment, ServiceProcess gateway) {
        this.postgres = postgres;
        this.kafka = kafka;
        this.keycloak = keycloak;
        this.tokens = tokens;
        this.catalog = catalog;
        this.customer = customer;
        this.order = order;
        this.shipping = shipping;
        this.payment = payment;
        this.gateway = gateway;
    }

    static synchronized Platform shared() throws Exception {
        if (shared == null) {
            shared = start();
            Runtime.getRuntime().addShutdownHook(new Thread(shared::close, "e2e-platform-shutdown"));
        }
        return shared;
    }

    private static Platform start() throws Exception {
        Path root = Path.of(System.getProperty("e2e.rootDirectory", ".."));
        String version = System.getProperty("e2e.projectVersion", "0.1.0-SNAPSHOT");

        PostgreSQLContainer postgres = Infrastructure.postgres().withCopyFileToContainer(
                MountableFile.forHostPath(root.resolve("infra/postgres/initdb")), "/docker-entrypoint-initdb.d/");
        KafkaContainer kafka = Infrastructure.kafka();
        GenericContainer<?> keycloak = Infrastructure.keycloak(root.resolve("infra/keycloak/import"));
        Startables.deepStart(postgres, kafka, keycloak).join();
        // Create topics up front so five services starting at once do not race to create the same ones.
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 3, (short) 1)).toList()).all().get();
        }

        String jdbc = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/";
        String bootstrap = kafka.getBootstrapServers();
        // Services and tests reach Keycloak at the same address, so the "iss" claim matches on both sides.
        String issuer = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/ecommerce";
        int catalogPort = freePort();
        int customerPort = freePort();
        int orderPort = freePort();
        int shippingPort = freePort();

        ServiceProcess catalog = ServiceProcess.start("catalog-service", jar(root, "catalog-service", version), catalogPort,
                Map.of("CATALOG_DB_URL", jdbc + "catalog_db", "KAFKA_BOOTSTRAP_SERVERS", bootstrap, "ISSUER_URI", issuer));
        ServiceProcess customer = ServiceProcess.start("customer-service", jar(root, "customer-service", version), customerPort,
                Map.of("CUSTOMER_DB_URL", jdbc + "customer_db", "ISSUER_URI", issuer));
        ServiceProcess order = ServiceProcess.start("order-service", jar(root, "order-service", version), orderPort,
                Map.of("ORDER_DB_URL", jdbc + "order_db", "KAFKA_BOOTSTRAP_SERVERS", bootstrap, "ISSUER_URI", issuer,
                        "CATALOG_SERVICE_URL", "http://localhost:" + catalogPort,
                        "CUSTOMER_SERVICE_URL", "http://localhost:" + customerPort));
        ServiceProcess shipping = ServiceProcess.start("shipping-service", jar(root, "shipping-service", version), shippingPort,
                Map.of("SHIPPING_DB_URL", jdbc + "shipping_db", "KAFKA_BOOTSTRAP_SERVERS", bootstrap, "ISSUER_URI", issuer));
        // A provider call without an answer is asked again after a second, not 30 (tok_slow tests).
        ServiceProcess payment = ServiceProcess.start("payment-service", jar(root, "payment-service", version), freePort(),
                Map.of("PAYMENT_DB_URL", jdbc + "payment_db", "KAFKA_BOOTSTRAP_SERVERS", bootstrap, "ISSUER_URI", issuer,
                        "ECOMMERCE_PAYMENTS_RESOLVER_STALEAFTER", "1s",
                        "ECOMMERCE_PAYMENTS_RESOLVER_SCANINTERVAL", "500ms"));

        ServiceProcess gateway = ServiceProcess.start("gateway", jar(root, "gateway", version), freePort(),
                Map.of("CATALOG_SERVICE_URL", "http://localhost:" + catalogPort,
                        "CUSTOMER_SERVICE_URL", "http://localhost:" + customerPort,
                        "ORDER_SERVICE_URL", "http://localhost:" + orderPort,
                        "SHIPPING_SERVICE_URL", "http://localhost:" + shippingPort));

        Platform platform = new Platform(postgres, kafka, keycloak, new Tokens(issuer),
                catalog, customer, order, shipping, payment, gateway);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> startups = new ArrayList<>();
            for (ServiceProcess service : List.of(catalog, customer, order, shipping, payment, gateway)) {
                startups.add(executor.submit(() -> {
                    service.awaitHealthy(Duration.ofMinutes(3));
                    return null;
                }));
            }
            for (Future<?> startup : startups) {
                startup.get();
            }
        } catch (Exception e) {
            platform.close();
            throw e;
        }
        return platform;
    }

    private static Path jar(Path root, String service, String version) {
        return root.resolve(service).resolve("target").resolve(service + "-" + version + ".jar");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() {
        for (ServiceProcess service : List.of(gateway, payment, shipping, order, customer, catalog)) {
            service.close();
        }
        keycloak.stop();
        kafka.stop();
        postgres.stop();
    }
}
