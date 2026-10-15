package dev.agentcontainers.sbx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SandboxRuntime implements AutoCloseable {
    static final String GUEST_SOCKET = "/var/run/docker.sock";
    private static final Logger LOG = LoggerFactory.getLogger(SandboxRuntime.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SbxConfiguration configuration;
    private final String name;
    private URI dockerHost;
    private final Set<SandboxPorts.Port> published = new HashSet<>();
    private ProjectLease lease;
    private String sandboxId;
    private boolean closed;

    private SandboxRuntime(SbxConfiguration configuration) {
        this.configuration = configuration;
        name = configuration.sandboxName();
    }

    static SandboxRuntime open(SbxConfiguration configuration) {
        SandboxRuntime runtime = null;
        try {
            runtime = new SandboxRuntime(configuration);
            runtime.lease = ProjectLease.open(Path.of(System.getProperty("user.home"),
                ".testcontainers", "sbx", runtime.name + ".lock"));
            runtime.ensureSandbox();
            runtime.sandboxId = runtime.findId();
            runtime.sbx("run", "--name", runtime.name, "--detach");
            runtime.dockerHost = dockerEndpoint(JSON.readTree(runtime.sbx("ports", runtime.name, "--json")));
            runtime.awaitDocker();
            SandboxRuntime owned = runtime;
            Runtime.getRuntime().addShutdownHook(new Thread(owned::close, "sbx-docker-cleanup"));
            LOG.info("Using project Docker sandbox {}", runtime.name);
            return runtime;
        } catch (IOException | RuntimeException e) {
            if (runtime != null) runtime.close();
            throw new IllegalStateException("Cannot start sbx Docker runtime. Check sbx login and kit support. Remove an older project sandbox to recreate it with the Docker TCP kit.", e);
        }
    }

    private void ensureSandbox() throws IOException {
        if (findId() != null) return;
        Path kit = Files.createTempDirectory("tc-sbx-kit-");
        try {
            // spec.yaml selects the legacy loader; retain the v3 descriptor filename.
            try (var resource = SandboxRuntime.class.getResourceAsStream("/docker-tcp/docker-tcp.yaml")) {
                if (resource == null) throw new IOException("Bundled Docker TCP kit is missing");
                Files.copy(resource, kit.resolve("docker-tcp.yaml"));
            }
            try {
                sbx("create", configuration.kit(), "--name", name, "--kit", kit.toString(),
                    "--pull", "missing",
                    "--cpus", Integer.toString(configuration.cpus()),
                    "--memory", configuration.memory(), "--skills", "off");
            } catch (IOException e) {
                // Another JVM for this project may have created it after our lookup.
                if (findId() == null) throw e;
            }
        } finally {
            Files.deleteIfExists(kit.resolve("docker-tcp.yaml"));
            Files.deleteIfExists(kit);
        }
        if (findId() == null) throw new IOException("Created sandbox is missing: " + name);
    }

    URI dockerHost() {
        return dockerHost;
    }

    static URI dockerEndpoint(JsonNode ports) throws IOException {
        if (!ports.isArray()) throw new IOException("Unexpected sbx ports JSON: " + ports);
        for (JsonNode port : ports) {
            String protocol = port.path("protocol").asText();
            int hostPort = port.path("host_port").asInt();
            if (port.path("sandbox_port").asInt() == 2375
                    && "127.0.0.1".equals(port.path("host_ip").asText())
                    && ("tcp".equals(protocol) || "tcp4".equals(protocol))
                    && hostPort > 0 && hostPort <= 65535) {
                return URI.create("tcp://127.0.0.1:" + hostPort);
            }
        }
        throw new IOException("No loopback Docker API publication found; remove the old project sandbox and retry");
    }

    void checkAlive() {
        if (closed) throw new IllegalStateException("Sandbox runtime is closed for " + name);
    }

    synchronized void publish(List<SandboxPorts.Port> required) {
        checkAlive();
        if (published.containsAll(required)) return;
        try {
            // Publications survive JVM exit, and other JVMs may share this sandbox.
            published.addAll(publishedPorts());
            List<SandboxPorts.Port> missing = required.stream().filter(port -> !published.contains(port)).toList();
            if (missing.isEmpty()) return;
            List<String> args = new ArrayList<>(List.of("ports", name));
            for (SandboxPorts.Port port : missing) {
                args.add("--publish");
                args.add("127.0.0.1:" + port.number() + ":" + port.number() + "/" + port.protocol());
            }
            try {
                sbx(args.toArray(String[]::new));
            } catch (IOException e) {
                // A concurrent JVM may have published these exact bindings first.
                if (!publishedPorts().containsAll(missing)) throw e;
            }
            published.addAll(missing);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot publish Docker ports " + required + " from " + name
                + ". The same port numbers must be free on host loopback.", e);
        }
    }

    private List<SandboxPorts.Port> publishedPorts() throws IOException {
        JsonNode result = JSON.readTree(sbx("ports", name, "--json"));
        if (!result.isArray()) throw new IOException("Unexpected sbx ports JSON: " + result);
        List<SandboxPorts.Port> ports = new ArrayList<>();
        for (JsonNode item : result) {
            int host = item.path("host_port").asInt();
            if (item.path("sandbox_port").asInt() != 2375
                    && host == item.path("sandbox_port").asInt()
                    && "127.0.0.1".equals(item.path("host_ip").asText())) {
                ports.add(new SandboxPorts.Port(host, item.path("protocol").asText()));
            }
        }
        return ports;
    }

    private String findId() throws IOException {
        JsonNode result = JSON.readTree(sbx("ls", "--json"));
        // Released CLI versions have used both an object envelope and an array.
        JsonNode sandboxes = result.isArray() ? result : result.path("sandboxes");
        if (!sandboxes.isArray()) throw new IOException("Unexpected sbx ls JSON: " + result);
        for (JsonNode sandbox : sandboxes) {
            if (name.equals(sandbox.path("name").asText())) {
                String id = sandbox.path("id").asText();
                if (id.isBlank()) throw new IOException("Sandbox identity is empty: " + name);
                return id;
            }
        }
        return null;
    }

    private void awaitDocker() throws IOException {
        long deadline = System.nanoTime() + configuration.timeout().toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Duration remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(remaining).build()) {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + dockerHost.getPort() + "/_ping"))
                        .timeout(remaining).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && "OK".equals(response.body().trim())) return;
                    throw new IOException("Docker ping returned " + response.statusCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for Docker", e);
                }
            } catch (IOException e) {
                last = e;
                if (Thread.currentThread().isInterrupted()) throw e;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for Docker in " + name, e);
            }
        }
        throw new IOException("Docker did not become ready in " + name + " within " + configuration.timeout(), last);
    }

    private String sbx(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(configuration.executable()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        // Build the bundled v3 source kit without requiring a host Docker daemon.
        builder.environment().putIfAbsent("SBX_KIT_BUILDER", "sandbox");
        return Commands.run(configuration.timeout(), builder);
    }

    private void removePublishedPorts() {
        if (sandboxId == null) return;
        try {
            // A user may have replaced the sandbox under the same project name.
            if (!sandboxId.equals(findId())) return;
            List<SandboxPorts.Port> ports = publishedPorts();
            if (ports.isEmpty()) return;
            List<String> command = new ArrayList<>(List.of(configuration.executable(), "ports", name));
            for (SandboxPorts.Port port : ports) {
                command.add("--unpublish");
                command.add("127.0.0.1:" + port.number() + ":" + port.number() + "/" + port.protocol());
            }
            Commands.run(Duration.ofSeconds(30), command);
        } catch (IOException e) {
            LOG.warn("Could not remove port publications from project sandbox {}", name, e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (lease != null) {
            try { lease.close(this::removePublishedPorts); }
            catch (IOException e) { LOG.warn("Could not release project sandbox lease", e); }
        }
    }
}
