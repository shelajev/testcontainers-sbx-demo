package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports.Binding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxPortsTest {
    @Test
    void translatesTcpAndUdpAndRemovesUnreachableIpv6Bindings() throws Exception {
        var container = new ObjectMapper().readValue("""
            {"State":{"Running":true},"NetworkSettings":{"Ports":{
              "80/tcp":[{"HostIp":"0.0.0.0","HostPort":"32000"},{"HostIp":"::","HostPort":"32000"}],
              "53/udp":[{"HostIp":"0.0.0.0","HostPort":"32000"}]
            }}}
            """, InspectContainerResponse.class);
        SandboxPorts.remap(container, Map.of(new SandboxPorts.Port(32000, "tcp4"), 45000,
            new SandboxPorts.Port(32000, "udp4"), 45001));
        var ports = container.getNetworkSettings().getPorts().getBindings();
        assertEquals(1, ports.get(ExposedPort.tcp(80)).length);
        assertEquals("45000", ports.get(ExposedPort.tcp(80))[0].getHostPortSpec());
        assertEquals("127.0.0.1", ports.get(ExposedPort.tcp(80))[0].getHostIp());
        assertEquals("45001", ports.get(ExposedPort.udp(53))[0].getHostPortSpec());
    }

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
