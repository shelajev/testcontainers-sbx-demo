package dev.agentcontainers.sbx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SandboxRuntime implements AutoCloseable {
    static final String GUEST_SOCKET = "/var/run/docker.sock";
    private static final Logger LOG = LoggerFactory.getLogger(SandboxRuntime.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SbxConfiguration configuration;
    private final String name;
    private URI dockerHost;
    private final Map<SandboxPorts.Port, Integer> published = new HashMap<>();
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

    synchronized Map<SandboxPorts.Port, Integer> publish(List<SandboxPorts.Port> required) {
        checkAlive();
        if (published.keySet().containsAll(required)) return Map.copyOf(published);
        // Serialize discovery + allocation across JVMs sharing this sandbox.
        Path lock = Path.of(System.getProperty("user.home"), ".testcontainers", "sbx", name + ".ports.lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            published.putAll(publishedPorts());
            List<SandboxPorts.Port> missing = required.stream().filter(port -> !published.containsKey(port)).toList();
            if (!missing.isEmpty()) {
                List<String> args = new ArrayList<>(List.of("ports", name));
                for (SandboxPorts.Port port : missing) {
                    args.add("--publish");
                    // SBX binds an ephemeral loopback port atomically; no host-side probe race.
                    args.add(port.number() + "/" + port.protocol());
                }
                sbx(args.toArray(String[]::new));
                published.putAll(publishedPorts());
                if (!published.keySet().containsAll(required)) {
                    throw new IOException("SBX did not report all requested port mappings");
                }
            }
            return Map.copyOf(published);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot publish Docker ports " + required + " from " + name, e);
        }
    }

    private Map<SandboxPorts.Port, Integer> publishedPorts() throws IOException {
        return applicationPorts(JSON.readTree(sbx("ports", name, "--json")));
    }

    static Map<SandboxPorts.Port, Integer> applicationPorts(JsonNode result) throws IOException {
        if (!result.isArray()) throw new IOException("Unexpected sbx ports JSON: " + result);
        Map<SandboxPorts.Port, Integer> ports = new HashMap<>();
        for (JsonNode item : result) {
            int host = item.path("host_port").asInt();
            int guest = item.path("sandbox_port").asInt();
            String protocol = item.path("protocol").asText();
            if (protocol.equals("tcp") || protocol.equals("udp")) protocol += "4";
            if (guest != 2375 && guest > 0 && guest <= 65535 && host > 0 && host <= 65535
                    && (protocol.equals("tcp4") || protocol.equals("udp4"))
                    && "127.0.0.1".equals(item.path("host_ip").asText())) {
                ports.putIfAbsent(new SandboxPorts.Port(guest, protocol), host);
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
        return ProcessRunner.run(configuration.timeout(), builder);
    }

    private void removePublishedPorts() {
        if (sandboxId == null) return;
        try {
            // A user may have replaced the sandbox under the same project name.
            if (!sandboxId.equals(findId())) return;
            Map<SandboxPorts.Port, Integer> ports = publishedPorts();
            if (ports.isEmpty()) return;
            List<String> command = new ArrayList<>(List.of(configuration.executable(), "ports", name));
            for (var mapping : ports.entrySet()) {
                SandboxPorts.Port port = mapping.getKey();
                command.add("--unpublish");
                command.add("127.0.0.1:" + mapping.getValue() + ":" + port.number() + "/" + port.protocol());
            }
            ProcessRunner.run(Duration.ofSeconds(30), command);
        } catch (IOException e) {
            LOG.warn("Could not remove port publications from project sandbox {}", name, e);
        }
    }

    private void cleanup() {
        removePublishedPorts();
        try {
            if (sandboxId != null && sandboxId.equals(findId())) {
                ProcessRunner.run(Duration.ofSeconds(30), List.of(configuration.executable(), "stop", name));
            }
        } catch (IOException e) {
            LOG.warn("Could not stop project sandbox {}", name, e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (lease != null) {
            try { lease.close(this::cleanup); }
            catch (IOException e) { LOG.warn("Could not release project sandbox lease", e); }
        }
    }
}
