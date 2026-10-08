package dev.agentcontainers.sbx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

record SbxConfiguration(String executable, String template, String memory, int cpus, Duration timeout) {
    static SbxConfiguration load() {
        int cpus = Integer.parseInt(setting("cpus", "2"));
        long seconds = Long.parseLong(setting("startupTimeoutSeconds", "120"));
        if (cpus < 1 || seconds < 1) {
            throw new IllegalArgumentException("sbx.cpus and sbx.startupTimeoutSeconds must be positive");
        }
        return new SbxConfiguration(
            executable(setting("executable", "sbx")),
            setting("template", "docker.io/docker/sandbox-templates:shell-docker"),
            setting("memory", "2g"), cpus, Duration.ofSeconds(seconds));
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
