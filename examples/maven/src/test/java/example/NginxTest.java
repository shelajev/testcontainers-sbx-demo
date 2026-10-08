package example;

import static org.junit.jupiter.api.Assertions.*;
import dev.agentcontainers.sbx.SbxDockerClientProviderStrategy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class NginxTest {
    @Test
    void reachesNginxRunningInAnAutomaticallyCreatedSandbox() throws Exception {
        // The dependency registers the provider. This first client request provisions sbx.
        DockerClientFactory factory = DockerClientFactory.instance();
        factory.client();
        assertTrue(factory.isUsing(SbxDockerClientProviderStrategy.class),
            "sbx was not selected. Check the logs, DOCKER_HOST and tc.host configuration.");

        try (GenericContainer<?> nginx = new GenericContainer<>("nginx:1.27-alpine")
            .withExposedPorts(80).waitingFor(Wait.forHttp("/").forStatusCode(200))) {
            nginx.start();
            URI endpoint = URI.create("http://" + nginx.getHost() + ":" + nginx.getMappedPort(80));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Welcome to nginx!"));
            assertEquals("hello from sbx", nginx.execInContainer("printf", "hello from sbx").getStdout());
            System.out.println("Host test JVM reached sandbox nginx at " + endpoint);
        }
    }
}
