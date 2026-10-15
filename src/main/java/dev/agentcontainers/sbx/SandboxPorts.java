package dev.agentcontainers.sbx;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports.Binding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SandboxPorts {
    record Port(int number, String protocol) {}

    static void remap(InspectContainerResponse container, Map<Port, Integer> mappings) {
        if (required(container).isEmpty()) return;
        container.getNetworkSettings().getPorts().getBindings().replaceAll((exposed, bindings) -> {
            if (bindings == null) return null;
            String protocol = exposed.getProtocol().name().toLowerCase(Locale.ROOT) + "4";
            return Arrays.stream(bindings)
                .filter(binding -> !"::".equals(binding.getHostIp()))
                .map(binding -> Binding.bindIpAndPort("127.0.0.1",
                    mappings.get(new Port(Integer.parseInt(binding.getHostPortSpec()), protocol))))
                .toArray(Binding[]::new);
        });
    }

    static List<Port> required(InspectContainerResponse container) {
        if (container.getState() == null || !Boolean.TRUE.equals(container.getState().getRunning())
            || container.getNetworkSettings() == null || container.getNetworkSettings().getPorts() == null) {
            return List.of();
        }
        return required(container.getNetworkSettings().getPorts().getBindings());
    }

    static List<Port> required(Map<ExposedPort, Binding[]> bindings) {
        Set<Port> required = new LinkedHashSet<>();
        if (bindings == null) return List.of();
        for (Map.Entry<ExposedPort, Binding[]> entry : bindings.entrySet()) {
            if (entry.getValue() == null) continue;
            Set<Integer> ipv4 = new LinkedHashSet<>();
            Set<Integer> ipv6 = new LinkedHashSet<>();
            for (Binding binding : entry.getValue()) {
                int port = Integer.parseInt(binding.getHostPortSpec());
                String ip = binding.getHostIp();
                if ("::".equals(ip)) {
                    ipv6.add(port);
                    continue;
                }
                if (ip != null && !ip.isBlank() && !"0.0.0.0".equals(ip)) {
                    throw new IllegalStateException("Unsupported Docker binding address: " + ip);
                }
                String protocol = entry.getKey().getProtocol().name().toLowerCase(Locale.ROOT);
                if (!protocol.equals("tcp") && !protocol.equals("udp")) {
                    throw new IllegalStateException("Unsupported Docker protocol: " + protocol);
                }
                ipv4.add(port);
                required.add(new Port(port, protocol + "4"));
            }
            if (!ipv4.containsAll(ipv6)) throw new IllegalStateException("IPv6-only Docker bindings are not supported");
        }
        return new ArrayList<>(required);
    }
}
