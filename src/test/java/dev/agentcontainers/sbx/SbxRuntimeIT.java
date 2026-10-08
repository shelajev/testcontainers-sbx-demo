package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

class SbxRuntimeIT {
    @Test
    @org.junit.jupiter.api.Timeout(value = 3, unit = java.util.concurrent.TimeUnit.MINUTES)
    void provisionsLazilyAndSupportsHttpExecCopyAndRestart() throws Exception {
        try (GenericContainer<?> nginx = new GenericContainer<>("nginx:1.27-alpine")
            .withExposedPorts(80).waitingFor(Wait.forHttp("/").forStatusCode(200))) {
            nginx.start();
            assertTrue(DockerClientFactory.instance().isUsing(SbxDockerClientProviderStrategy.class),
                "Another Docker provider was selected; check DOCKER_HOST, tc.host and saved strategy configuration");
            assertTrue(nginx.getLogs().contains("Configuration complete"));
            var result = nginx.execInContainer("sh", "-c", "printf sbx-exec");
            assertEquals(0, result.getExitCode());
            assertEquals("sbx-exec", result.getStdout());
            nginx.copyFileToContainer(Transferable.of("served from sbx"), "/usr/share/nginx/html/index.html");
            assertEquals("served from sbx", fetch(nginx));
            DockerClientFactory.instance().client().restartContainerCmd(nginx.getContainerId()).exec();
            // A low-level Docker restart can reassign ports; GenericContainer caches its original inspect.
            int currentPort = Integer.parseInt(nginx.getCurrentContainerInfo().getNetworkSettings().getPorts()
                .getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(80))[0].getHostPortSpec());
            assertEquals("served from sbx", fetch(URI.create("http://" + nginx.getHost() + ":" + currentPort)));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
    void rejectsAnOccupiedHostPortBeforeReturningTheContainer() throws Exception {
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"));
            GenericContainer<?> nginx = new GenericContainer<>("nginx:1.27-alpine")
                .withExposedPorts(80)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(
                        com.github.dockerjava.api.model.Ports.Binding.bindPort(occupied.getLocalPort()),
                        com.github.dockerjava.api.model.ExposedPort.tcp(80))))) {
            Exception error = assertThrows(Exception.class, nginx::start);
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertTrue(messages.toString().contains("Cannot publish Docker ports"), messages.toString());
            assertTrue(DockerClientFactory.instance().isUsing(SbxDockerClientProviderStrategy.class));
        }
    }

    private static String fetch(GenericContainer<?> container) throws Exception {
        return fetch(URI.create("http://" + container.getHost() + ":" + container.getMappedPort(80)));
    }

    private static String fetch(URI endpoint) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        java.io.IOException last = null;
        do {
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return response.body();
            } catch (java.io.IOException e) { last = e; }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("HTTP endpoint did not become ready: " + endpoint, last);
    }
}
