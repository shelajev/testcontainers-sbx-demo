package dev.agentcontainers.sbx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

record SbxConfiguration(String executable, String kit, String memory, int cpus, Duration timeout,
                        String sandboxName) {
    static SbxConfiguration load() {
        int cpus = Integer.parseInt(setting("cpus", "2"));
        long seconds = Long.parseLong(setting("startupTimeoutSeconds", "120"));
        if (cpus < 1 || seconds < 1) {
            throw new IllegalArgumentException("sbx.cpus and sbx.startupTimeoutSeconds must be positive");
        }
        return new SbxConfiguration(
            executable(setting("executable", "sbx")),
            setting("kit", "docker.io/docker/sbx-kit-shell:1.0.0"),
            setting("memory", "2g"), cpus, Duration.ofSeconds(seconds),
            projectName(Path.of(setting("projectDirectory", System.getProperty("user.dir")))));
    }

    static String projectName(Path directory) {
        try {
            // Incompatible runtime layouts must not reuse an older project's sandbox.
            return "tc-java-tcp-v1-" + UUID.nameUUIDFromBytes(directory.toRealPath().toString()
                .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve sbx project directory: " + directory, e);
        }
    }

    private static String setting(String name, String fallback) {
        return System.getProperty("sbx." + name, fallback);
    }

    private static String executable(String value) {
        Path path = Path.of(value);
        if (path.isAbsolute() || value.contains("/")) {
            if (Files.isExecutable(path)) return path.toAbsolutePath().toString();
        } else {
            for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) {
                Path candidate = Path.of(directory, value);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }
        throw new IllegalStateException("Cannot find sbx executable: " + value + ". Install sbx and run sbx login first.");
    }
}
