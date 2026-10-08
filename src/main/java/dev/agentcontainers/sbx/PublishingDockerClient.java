package dev.agentcontainers.sbx;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.UnpauseContainerCmd;
import com.github.dockerjava.api.model.Container;
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
                                publish.accept(underlying.inspectContainerCmd(container.getId()).exec());
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
        if (command instanceof StartContainerCmd start) return start.getContainerId();
        if (command instanceof RestartContainerCmd restart) return restart.getContainerId();
        if (command instanceof UnpauseContainerCmd unpause) return unpause.getContainerId();
        throw new IllegalArgumentException("Unsupported command: " + command.getClass());
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
