package dev.agentcontainers.sbx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SandboxRuntime implements AutoCloseable {
    static final String GUEST_SOCKET = "/var/run/docker.sock";
    private static final Logger LOG = LoggerFactory.getLogger(SandboxRuntime.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SbxConfiguration configuration;
    private final String name = "tc-java-" + UUID.randomUUID();
    private final Path directory;
    private final Path sshConfig;
    private String sandboxId;
    private DockerSocketBridge bridge;
    private Process lifetime;
    private boolean closed;

    private SandboxRuntime(SbxConfiguration configuration) throws IOException {
        this.configuration = configuration;
        // A short path avoids the platform's Unix-domain socket pathname limit.
        directory = Files.createTempDirectory(Path.of("/tmp"), "tc-sbx-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        sshConfig = directory.resolve("ssh_config");
        Files.writeString(sshConfig, """
            Host %s.sbx
                User _default_user_
                ProxyCommand %s ssh proxy %%n
                KnownHostsCommand %s ssh known-hosts %%H
                UserKnownHostsFile %s
                StrictHostKeyChecking yes
                IdentityAgent none
                IdentityFile /dev/null
                IdentitiesOnly yes
                ControlMaster no
                ControlPath none
                BatchMode yes
                ConnectTimeout 10
            """.formatted(name, quoteCommand(configuration.executable()), quoteCommand(configuration.executable()),
                quote(directory.resolve("known_hosts").toString())));
    }

    static SandboxRuntime open(SbxConfiguration configuration) {
        SandboxRuntime runtime = null;
        try {
            runtime = new SandboxRuntime(configuration);
            runtime.sbx("create", "--name", runtime.name, "--template", configuration.template(),
                "--pull", "missing", "--cpus", Integer.toString(configuration.cpus()),
                "--memory", configuration.memory(), "--skills", "off", "shell");
            runtime.sandboxId = runtime.findId();
            if (runtime.sandboxId == null) throw new IOException("Created sandbox is missing: " + runtime.name);
            runtime.awaitDocker();
            runtime.lifetime = new ProcessBuilder(runtime.ssh("cat"))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            runtime.bridge = new DockerSocketBridge(runtime.socket(),
                runtime.ssh("socat", "STDIO", "UNIX-CONNECT:" + GUEST_SOCKET));
            SandboxRuntime owned = runtime;
            Runtime.getRuntime().addShutdownHook(new Thread(owned::close, "sbx-docker-cleanup"));
            LOG.info("Created Docker sandbox {} ({}) for this JVM", runtime.name, runtime.sandboxId);
            return runtime;
        } catch (IOException | RuntimeException e) {
            if (runtime != null) runtime.close();
            throw new IllegalStateException("Cannot start sbx Docker runtime. Check sbx login, the template, and SSH access.", e);
        }
    }

    Path socket() {
        return directory.resolve("docker.sock");
    }

    String name() {
        return name;
    }

    void checkAlive() {
        if (closed || lifetime == null || !lifetime.isAlive()) {
            throw new IllegalStateException("Sandbox SSH session ended for " + name + "; restart the test JVM");
        }
    }

    synchronized void publish(List<SandboxPorts.Port> required) {
        checkAlive();
        if (required.isEmpty()) return;
        try {
            List<SandboxPorts.Port> actual = publishedPorts();
            List<SandboxPorts.Port> missing = required.stream().filter(port -> !actual.contains(port)).toList();
            if (!missing.isEmpty()) {
                List<String> args = new ArrayList<>(List.of("ports", name));
                for (SandboxPorts.Port port : missing) {
                    args.add("--publish");
                    args.add("127.0.0.1:" + port.number() + ":" + port.number() + "/" + port.protocol());
                }
                sbx(args.toArray(String[]::new));
            }
            if (!publishedPorts().containsAll(required)) {
                throw new IOException("Port publication is incomplete: " + required);
            }
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
            int guest = item.path("sandbox_port").asInt();
            if (host == guest && "127.0.0.1".equals(item.path("host_ip").asText())) {
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
                Commands.run(Duration.ofNanos(deadline - System.nanoTime()), ssh("docker", "info"));
                Commands.run(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())), ssh("which", "socat"));
                return;
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

    private List<String> ssh(String... command) {
        List<String> args = new ArrayList<>(List.of("ssh", "-F", sshConfig.toString(), "-T", name + ".sbx"));
        args.addAll(List.of(command));
        return args;
    }

    private String sbx(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(configuration.executable()));
        command.addAll(List.of(arguments));
        return Commands.run(configuration.timeout(), command);
    }

    private static String quoteCommand(String value) {
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("Newlines are not allowed in the sbx executable path");
        }
        // OpenSSH expands percent tokens before handing ProxyCommand to the shell.
        return "'" + value.replace("%", "%%").replace("'", "'\\''") + "'";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (bridge != null) {
            try { bridge.close(); } catch (IOException e) { LOG.warn("Cannot close sbx Docker bridge", e); }
        }
        if (lifetime != null) lifetime.destroyForcibly();
        // Never remove a sandbox that has been replaced under the same name.
        if (sandboxId != null) {
            try {
                if (sandboxId.equals(findId())) {
                    Commands.run(Duration.ofSeconds(30), List.of(configuration.executable(), "rm", "--force", name));
                    LOG.info("Removed Docker sandbox {}", name);
                }
            } catch (IOException e) {
                LOG.warn("Could not remove owned sandbox {}. Remove it with sbx rm --force {}", name, name, e);
            }
        }
        try {
            Files.deleteIfExists(socket());
            Files.deleteIfExists(sshConfig);
            Files.deleteIfExists(directory.resolve("known_hosts"));
            Files.deleteIfExists(directory.resolve("known_hosts.old"));
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            LOG.warn("Could not remove transport directory {}", directory, e);
        }
    }
}
