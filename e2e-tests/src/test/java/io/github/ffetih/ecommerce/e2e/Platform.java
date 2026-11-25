package io.github.ffetih.ecommerce.e2e;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
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
 * compose.yaml), one Kafka, and the four services as separate processes.
 */
final class Platform implements AutoCloseable {

    private static final List<String> TOPICS = List.of(
            "catalog.stock", "catalog.reservations", "order.lifecycle", "shipping.shipments",
            "order.lifecycle-dlt", "catalog.reservations-dlt", "shipping.shipments-dlt");

    final PostgreSQLContainer postgres;
    final KafkaContainer kafka;
    final ServiceProcess catalog;
    final ServiceProcess customer;
    final ServiceProcess order;
    final ServiceProcess shipping;

    private Platform(PostgreSQLContainer postgres, KafkaContainer kafka, ServiceProcess catalog,
                     ServiceProcess customer, ServiceProcess order, ServiceProcess shipping) {
        this.postgres = postgres;
        this.kafka = kafka;
        this.catalog = catalog;
        this.customer = customer;
        this.order = order;
        this.shipping = shipping;
    }

    static Platform start() throws Exception {
        Path root = Path.of(System.getProperty("e2e.rootDirectory", ".."));
        String version = System.getProperty("e2e.projectVersion", "0.1.0-SNAPSHOT");

        PostgreSQLContainer postgres = Infrastructure.postgres().withCopyFileToContainer(
                MountableFile.forHostPath(root.resolve("infra/postgres/initdb")), "/docker-entrypoint-initdb.d/");
        KafkaContainer kafka = Infrastructure.kafka();
        postgres.start();
        kafka.start();
        // Create topics up front so four services starting at once do not race to create the same ones.
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 3, (short) 1)).toList()).all().get();
        }

        String jdbc = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/";
        String bootstrap = kafka.getBootstrapServers();
        int catalogPort = freePort();
        int customerPort = freePort();

        ServiceProcess catalog = ServiceProcess.start("catalog-service", jar(root, "catalog-service", version), catalogPort,
                Map.of("CATALOG_DB_URL", jdbc + "catalog_db", "KAFKA_BOOTSTRAP_SERVERS", bootstrap));
        ServiceProcess customer = ServiceProcess.start("customer-service", jar(root, "customer-service", version), customerPort,
                Map.of("CUSTOMER_DB_URL", jdbc + "customer_db"));
        ServiceProcess order = ServiceProcess.start("order-service", jar(root, "order-service", version), freePort(),
                Map.of("ORDER_DB_URL", jdbc + "order_db", "KAFKA_BOOTSTRAP_SERVERS", bootstrap,
                        "CATALOG_SERVICE_URL", "http://localhost:" + catalogPort,
                        "CUSTOMER_SERVICE_URL", "http://localhost:" + customerPort));
        ServiceProcess shipping = ServiceProcess.start("shipping-service", jar(root, "shipping-service", version), freePort(),
                Map.of("SHIPPING_DB_URL", jdbc + "shipping_db", "KAFKA_BOOTSTRAP_SERVERS", bootstrap));

        Platform platform = new Platform(postgres, kafka, catalog, customer, order, shipping);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> startups = new ArrayList<>();
            for (ServiceProcess service : List.of(catalog, customer, order, shipping)) {
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
        for (ServiceProcess service : List.of(shipping, order, customer, catalog)) {
            service.close();
        }
        kafka.stop();
        postgres.stop();
    }
}
