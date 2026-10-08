package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports.Binding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxPortsTest {
    @Test
    void mirrorsDockerAssignedPortsAndCollapsesDualStackBindings() {
        assertEquals(List.of(new SandboxPorts.Port(32789, "tcp4")), SandboxPorts.required(Map.of(
            ExposedPort.tcp(5432), new Binding[] {
                Binding.bindIpAndPort("0.0.0.0", 32789), Binding.bindIpAndPort("::", 32789)})));
    }

    @Test
    void preservesUdpProtocol() {
        assertEquals(List.of(new SandboxPorts.Port(30053, "udp4")), SandboxPorts.required(Map.of(
            ExposedPort.udp(53), new Binding[] {Binding.bindIpAndPort("0.0.0.0", 30053)})));
    }

    @Test
    void rejectsBindingsThatCannotBeReachedThroughTheSupportedIpv4Endpoint() {
        for (String address : List.of("::", "127.0.0.1", "192.168.1.2")) {
            assertThrows(IllegalStateException.class, () -> SandboxPorts.required(Map.of(
                ExposedPort.tcp(80), new Binding[] {Binding.bindIpAndPort(address, 8080)})));
        }
    }
}
