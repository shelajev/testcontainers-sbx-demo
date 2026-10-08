package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishingDockerClientTest {
    @Test
    void publishesBeforeReturningFromAFluentStartCommand() {
        List<String> events = new ArrayList<>();
        InspectContainerResponse response = new InspectContainerResponse();
        StartContainerCmd start = proxy(StartContainerCmd.class, (self, method, args) -> switch (method.getName()) {
            case "withContainerId" -> self;
            case "getContainerId" -> "container";
            case "exec" -> { events.add("start"); yield null; }
            default -> null;
        });
        InspectContainerCmd inspect = proxy(InspectContainerCmd.class, (self, method, args) -> {
            events.add("inspect");
            return response;
        });
        DockerClient underlying = proxy(DockerClient.class, (self, method, args) -> switch (method.getName()) {
            case "startContainerCmd" -> start;
            case "inspectContainerCmd" -> { assertEquals("container", args[0]); yield inspect; }
            default -> null;
        });
        DockerClient client = PublishingDockerClient.wrap(underlying, value -> {
            assertSame(response, value);
            events.add("publish");
        });
        client.startContainerCmd("container").withContainerId("container").exec();
        assertEquals(List.of("start", "inspect", "publish"), events);
    }

    @Test
    void propagatesPublicationFailure() {
        InspectContainerCmd inspect = proxy(InspectContainerCmd.class,
            (self, method, args) -> new InspectContainerResponse());
        DockerClient underlying = proxy(DockerClient.class, (self, method, args) -> inspect);
        IllegalStateException collision = new IllegalStateException("occupied host port");
        DockerClient client = PublishingDockerClient.wrap(underlying, value -> { throw collision; });
        assertSame(collision, assertThrows(IllegalStateException.class, () -> client.inspectContainerCmd("id").exec()));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
