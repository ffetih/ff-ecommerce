package io.github.ffetih.ecommerce.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One service, started from its packaged jar in a separate JVM, exactly as it would run in production.
 * Its output goes to {@code e2e-tests/target/e2e-logs/<service>.log} for troubleshooting.
 */
final class ServiceProcess implements AutoCloseable {

    private final String name;
    private final int port;
    private final Process process;
    private final Path log;

    private ServiceProcess(String name, int port, Process process, Path log) {
        this.name = name;
        this.port = port;
        this.process = process;
        this.log = log;
    }

    static ServiceProcess start(String name, Path jar, int port, Map<String, String> environment) throws IOException {
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Missing " + jar + ": build the services first (./mvnw verify from the root)");
        }
        Path logDirectory = Path.of("target", "e2e-logs");
        Files.createDirectories(logDirectory);
        Path log = logDirectory.resolve(name + ".log");

        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java, "-Xmx384m", "-jar", jar.toString()));
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        builder.environment().put("SERVER_PORT", String.valueOf(port));
        return new ServiceProcess(name, port, builder.start(), log);
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Waits until {@code /actuator/health} answers 200, or fails with a pointer to the log. */
    void awaitHealthy(Duration timeout) throws InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest health = HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health"))
                .timeout(Duration.ofSeconds(2)).build();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited during startup; see " + log.toAbsolutePath());
            }
            try {
                if (http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException notYetListening) {
                // keep waiting
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(name + " not healthy within " + timeout + "; see " + log.toAbsolutePath());
    }

    /** Fails if the process has exited, pointing to its log. */
    void requireAlive() {
        if (!process.isAlive()) {
            throw new IllegalStateException(name + " is not running (exit " + process.exitValue() + "); see "
                    + log.toAbsolutePath());
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
