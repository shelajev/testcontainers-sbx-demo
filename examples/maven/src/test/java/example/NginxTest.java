package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcontainers.sbx.SbxDockerClientProviderStrategy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@DisplayName("Run ordinary Testcontainers tests on Docker Sandboxes")
class NginxTest {
    @BeforeAll
    static void requireSandboxProvider() {
        // The dependency registers the provider; the first client request starts the runtime.
        DockerClientFactory factory = DockerClientFactory.instance();
        factory.client();
        assertTrue(factory.isUsing(SbxDockerClientProviderStrategy.class),
            "sbx was not selected. Check the logs, DOCKER_HOST and tc.host configuration.");
    }

    @Test
    @DisplayName("Call nginx from the host using its mapped port")
    void servesTheWelcomePage() throws Exception {
        // Given nginx running inside an automatically provisioned sandbox
        try (GenericContainer<?> nginx = nginx()) {
            nginx.start();

            // When the host JVM calls the standard Testcontainers endpoint
            URI endpoint = URI.create("http://" + nginx.getHost() + ":" + nginx.getMappedPort(80));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());

            // Then nginx responds with its welcome page
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Welcome to nginx!"), response.body());
        }
    }

    @Test
    @DisplayName("Execute a command inside the sandbox's container")
    void executesACommand() throws Exception {
        // Given nginx running inside the sandbox
        try (GenericContainer<?> nginx = nginx()) {
            nginx.start();

            // When the test executes a command in the container
            var result = nginx.execInContainer("printf", "hello from sbx");

            // Then the command succeeds and returns its output
            assertEquals(0, result.getExitCode(), result.getStderr());
            assertEquals("hello from sbx", result.getStdout());
        }
    }

    private static GenericContainer<?> nginx() {
        return new GenericContainer<>("nginx:1.27-alpine")
            .withExposedPorts(80)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));
    }
}
