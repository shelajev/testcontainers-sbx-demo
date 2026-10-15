package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports.Binding;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

@DisplayName("Testcontainers on Docker Sandboxes")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class SbxRuntimeIT {
    private static final int HTTP_PORT = 80;
    private static final String DOCUMENT_ROOT = "/usr/share/nginx/html/index.html";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @BeforeAll
    static void requireSandboxProvider() {
        DockerClientFactory factory = DockerClientFactory.instance();
        factory.client();
        assertTrue(factory.isUsing(SbxDockerClientProviderStrategy.class),
            "Another Docker provider was selected; check DOCKER_HOST, tc.host and saved strategy configuration");
    }

    @Test
    @DisplayName("The host JVM can reach a container over HTTP")
    void servesHttpThroughTheMappedPort() throws Exception {
        // Given an nginx container running inside the sandbox
        try (GenericContainer<?> nginx = nginx()) {
            nginx.start();

            // When the host JVM requests its published HTTP endpoint
            String page = fetch(endpoint(nginx));

            // Then it receives the nginx welcome page
            assertTrue(page.contains("Welcome to nginx!"), page);
        }
    }

    @Test
    @DisplayName("Container startup logs are available to the test")
    void readsContainerLogs() {
        // Given a running nginx container
        try (GenericContainer<?> nginx = nginx()) {
            nginx.start();

            // When the test reads its logs
            String logs = nginx.getLogs();

            // Then nginx has reported a successful startup
            assertTrue(logs.contains("Configuration complete"), logs);
        }
    }

    @Test
    @DisplayName("Commands run inside the container")
    void executesCommandsInTheContainer() throws Exception {
        // Given a running nginx container
        try (GenericContainer<?> nginx = nginx()) {
            nginx.start();

            // When the test executes a command inside it
            var result = nginx.execInContainer("printf", "hello from sbx");

            // Then the command succeeds and returns its output
            assertEquals(0, result.getExitCode(), result.getStderr());
            assertEquals("hello from sbx", result.getStdout());
        }
    }

    @Test
    @DisplayName("Files copied from the host are served by the container")
    void servesACopiedFile() throws Exception {
        // Given a running nginx container
        try (GenericContainer<?> nginx = nginx()) {
            nginx.start();

            // When the test replaces its home page using the file-copy API
            nginx.copyFileToContainer(Transferable.of("served from sbx"), DOCUMENT_ROOT);

            // Then the host can read the copied content over HTTP
            assertEquals("served from sbx", fetch(endpoint(nginx)));
        }
    }

    @Test
    @DisplayName("A restarted container remains reachable with its files intact")
    void servesTheSameFileAfterRestart() throws Exception {
        // Given nginx serving a page copied from the host
        try (GenericContainer<?> nginx = nginx()) {
            nginx.start();
            nginx.copyFileToContainer(Transferable.of("still here after restart"), DOCUMENT_ROOT);

            // When the Docker client restarts the container
            DockerClientFactory.instance().client().restartContainerCmd(nginx.getContainerId()).exec();

            // Then its current published port serves the same page
            assertEquals("still here after restart", fetch(currentEndpoint(nginx)));
        }
    }

    @Test
    @DisplayName("An occupied host port fails with a publication error")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void rejectsAnOccupiedHostPort() throws Exception {
        // Given a host port already held by another service
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            GenericContainer<?> nginx = nginx().withCreateContainerCmdModifier(cmd ->
                cmd.getHostConfig().withPortBindings(new PortBinding(
                    Binding.bindPort(occupied.getLocalPort()), ExposedPort.tcp(HTTP_PORT))))) {

            // When a container tries to publish that same port
            Exception error = assertThrows(Exception.class, nginx::start);

            // Then startup reports that the port cannot be published
            String messages = failureMessages(error);
            assertTrue(messages.contains("Cannot publish Docker ports"), messages);
        }
    }

    private static GenericContainer<?> nginx() {
        return new GenericContainer<>("nginx:1.27-alpine")
            .withExposedPorts(HTTP_PORT)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));
    }

    private static URI endpoint(GenericContainer<?> container) {
        return endpoint(container.getHost(), container.getMappedPort(HTTP_PORT));
    }

    private static URI currentEndpoint(GenericContainer<?> container) {
        // Docker can reassign ports on restart; GenericContainer caches its original inspect.
        String port = container.getCurrentContainerInfo().getNetworkSettings().getPorts()
            .getBindings().get(ExposedPort.tcp(HTTP_PORT))[0].getHostPortSpec();
        return endpoint(container.getHost(), Integer.parseInt(port));
    }

    private static URI endpoint(String host, int port) {
        return URI.create("http://" + host + ":" + port);
    }

    private static String failureMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static String fetch(URI endpoint) throws Exception {
        // A Docker restart returns before nginx is ready to serve requests again.
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        IOException lastFailure = null;
        do {
            try {
                HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
            } catch (IOException error) {
                lastFailure = error;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("HTTP endpoint did not become ready: " + endpoint, lastFailure);
    }
}
