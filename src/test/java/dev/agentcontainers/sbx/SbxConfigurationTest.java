package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SbxConfigurationTest {
    @TempDir Path directory;

    @Test
    void usesTheCanonicalProjectPathAsSandboxIdentity() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), project);
        String name = SbxConfiguration.projectName(project);
        assertEquals(name, SbxConfiguration.projectName(project.resolve(".")));
        assertEquals(name, SbxConfiguration.projectName(alias));
        assertNotEquals(name, SbxConfiguration.projectName(Files.createDirectory(directory.resolve("other"))));
        assertTrue(name.matches("tc-java-tcp-v1-[a-f0-9-]{36}"));
    }

    @Test
    void doesNotReuseTheIncompatibleSshRuntimeSandbox() throws Exception {
        String legacyName = "tc-java-" + UUID.nameUUIDFromBytes(directory.toRealPath().toString()
            .getBytes(StandardCharsets.UTF_8));
        assertNotEquals(legacyName, SbxConfiguration.projectName(directory));
    }
}
