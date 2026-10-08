package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DockerSocketBridgeTest {
    @Test
    void relaysBinaryStreamsAndFinishesTheResponseAfterRequestHalfClose() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("/tmp"), "bridge-test-");
        Path socket = directory.resolve("docker.sock");
        byte[] request = new byte[256 * 1024];
        new Random(42).nextBytes(request);
        try (DockerSocketBridge bridge = new DockerSocketBridge(socket, List.of("cat"))) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    client.connect(UnixDomainSocketAddress.of(socket));
                    Thread writer = Commands.daemon("test-request", () -> {
                        try {
                            ByteBuffer data = ByteBuffer.wrap(request);
                            while (data.hasRemaining()) client.write(data);
                            client.shutdownOutput();
                        } catch (Exception e) { throw new AssertionError(e); }
                    });
                    ByteBuffer response = ByteBuffer.allocate(request.length);
                    while (response.hasRemaining() && client.read(response) != -1) { }
                    assertArrayEquals(request, response.array());
                    assertEquals(-1, client.read(ByteBuffer.allocate(1)));
                    writer.join();
                }
            });
        } finally {
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
    }
}
