package dev.agentcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TestcontainersOnSbxTest {

  @Container
  static final GenericContainer<?> nginx = new GenericContainer<>(
    DockerImageName.parse("nginx:1.27-alpine")
  ).withExposedPorts(80).waitingFor(Wait.forHttp("/").forStatusCode(200));

  @Test
  void reachesAContainerThroughItsMappedPort() throws Exception {
    URI endpoint = URI.create(
      "http://" + nginx.getHost() + ":" + nginx.getMappedPort(80)
    );

    System.out.printf("%nTest JVM -> %s -> nginx container%n", endpoint);

    int pauseSeconds = Integer.parseInt(
      System.getenv().getOrDefault("DEMO_PAUSE_SECONDS", "0")
    );
    if (pauseSeconds > 0) {
      System.out.printf(
        "nginx is running; pausing for %d seconds so it can be inspected...%n",
        pauseSeconds
      );
      Thread.sleep(Duration.ofSeconds(pauseSeconds));
    }

    HttpRequest request = HttpRequest.newBuilder(endpoint)
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();
    HttpResponse<String> response = HttpClient.newHttpClient().send(
      request,
      HttpResponse.BodyHandlers.ofString()
    );

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("Welcome to nginx!"));
  }
}
