package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class SandboxRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void discoversTheAllocatedIpv4DockerPortAmongApplicationPorts() throws Exception {
        assertEquals(URI.create("tcp://127.0.0.1:49152"), SandboxRuntime.dockerEndpoint(json.readTree("""
            [
              {"host_ip":"127.0.0.1","host_port":8080,"sandbox_port":8080,"protocol":"tcp"},
              {"host_ip":"::1","host_port":49151,"sandbox_port":2375,"protocol":"tcp"},
              {"host_ip":"127.0.0.1","host_port":49152,"sandbox_port":2375,"protocol":"tcp4"}
            ]
            """)));
    }

    @Test
    void rejectsMissingUnsafeOrInvalidDockerBindings() throws Exception {
        for (String binding : new String[] {
                "[]", "{}",
                "[{\"host_ip\":\"0.0.0.0\",\"host_port\":49152,\"sandbox_port\":2375,\"protocol\":\"tcp\"}]",
                "[{\"host_ip\":\"127.0.0.1\",\"host_port\":0,\"sandbox_port\":2375,\"protocol\":\"tcp\"}]",
                "[{\"host_ip\":\"127.0.0.1\",\"host_port\":49152,\"sandbox_port\":2375,\"protocol\":\"udp\"}]"
        }) {
            var ports = json.readTree(binding);
            assertThrows(IOException.class, () -> SandboxRuntime.dockerEndpoint(ports));
        }
    }
}
