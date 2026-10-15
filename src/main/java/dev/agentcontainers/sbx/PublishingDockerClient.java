package dev.agentcontainers.sbx;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.UnpauseContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import java.util.ArrayList;
import java.util.Locale;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class PublishingDockerClient {
    private PublishingDockerClient() {}

    static DockerClient wrap(DockerClient underlying, Consumer<InspectContainerResponse> publish) {
        return (DockerClient) Proxy.newProxyInstance(DockerClient.class.getClassLoader(),
            new Class<?>[] {DockerClient.class}, (proxy, method, args) -> {
                Object result = invoke(method, underlying, args);
                return switch (method.getName()) {
                    case "startContainerCmd", "restartContainerCmd", "unpauseContainerCmd" -> command(
                        method.getReturnType(), result,
                        (cmd, ignored) -> publish.accept(underlying.inspectContainerCmd(containerId(cmd)).exec()));
                    case "inspectContainerCmd" -> command(method.getReturnType(), result,
                        (cmd, value) -> publish.accept((InspectContainerResponse) value));
                    case "listContainersCmd" -> command(method.getReturnType(), result, (cmd, value) -> {
                        @SuppressWarnings("unchecked") List<Container> containers = (List<Container>) value;
                        for (Container container : containers) {
                            try {
                                InspectContainerResponse inspected = underlying.inspectContainerCmd(container.getId()).exec();
                                publish.accept(inspected);
                                if (inspected.getState() != null && Boolean.TRUE.equals(inspected.getState().getRunning())
                                        && inspected.getNetworkSettings() != null
                                        && inspected.getNetworkSettings().getPorts() != null) {
                                    var ports = new ArrayList<ContainerPort>();
                                    inspected.getNetworkSettings().getPorts().getBindings().forEach((exposed, bindings) -> {
                                        if (bindings == null || bindings.length == 0) {
                                            ports.add(new ContainerPort()
                                                .withPrivatePort(exposed.getPort())
                                                .withType(exposed.getProtocol().name().toLowerCase(Locale.ROOT)));
                                        } else for (var binding : bindings) {
                                            ports.add(new ContainerPort()
                                                .withPrivatePort(exposed.getPort())
                                                .withType(exposed.getProtocol().name().toLowerCase(Locale.ROOT))
                                                .withIp(binding.getHostIp())
                                                .withPublicPort(Integer.parseInt(binding.getHostPortSpec())));
                                        }
                                    });
                                    container.ports = ports.toArray(ContainerPort[]::new);
                                }
                            } catch (com.github.dockerjava.api.exception.NotFoundException ignored) {
                                // It was removed after the list snapshot.
                            }
                        }
                    });
                    default -> result;
                };
            });
    }

    private static Object command(Class<?> type, Object target, BiConsumer<Object, Object> after) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object result = invoke(method, target, args);
            if (method.getName().equals("exec")) after.accept(target, result);
            // docker-java command builders return themselves; keep interception after withX(...).
            return result == target ? proxy : result;
        });
    }

    private static String containerId(Object command) {
        return switch (command) {
            case StartContainerCmd start -> start.getContainerId();
            case RestartContainerCmd restart -> restart.getContainerId();
            case UnpauseContainerCmd unpause -> unpause.getContainerId();
            default -> throw new IllegalArgumentException("Unsupported command: " + command.getClass());
        };
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
