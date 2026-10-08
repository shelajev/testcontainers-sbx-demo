package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;

class StrategyDiscoveryTest {
    @Test
    void discoversWithoutRequiringSbxOrStartingTheRuntime() {
        String previous = System.getProperty("sbx.executable");
        System.setProperty("sbx.executable", "/does-not-exist/sbx");
        try {
            SbxDockerClientProviderStrategy strategy = ServiceLoader.load(DockerClientProviderStrategy.class)
                .stream().filter(provider -> provider.type() == SbxDockerClientProviderStrategy.class)
                .map(provider -> (SbxDockerClientProviderStrategy) provider.get()).findFirst().orElseThrow();
            assertEquals("Docker Sandboxes (sbx)", strategy.getDescription());
            assertFalse(strategy.isPersistable());
            assertEquals("/var/run/docker.sock", strategy.getRemoteDockerUnixSocketPath());
            // Executable validation, like provisioning, happens only when a client is requested.
            IllegalStateException error = assertThrows(IllegalStateException.class, strategy::getTransportConfig);
            assertTrue(error.getMessage().contains("Cannot find sbx executable"));
        } finally {
            if (previous == null) System.clearProperty("sbx.executable");
            else System.setProperty("sbx.executable", previous);
        }
    }
}
