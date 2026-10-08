package dev.agentcontainers.sbx;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A byte-stream bridge, including Docker's hijacked exec/attach connections. */
final class DockerSocketBridge implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DockerSocketBridge.class);
    private final ServerSocketChannel server;
    private final List<String> command;
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    DockerSocketBridge(Path socket, List<String> command) throws IOException {
        this.command = List.copyOf(command);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(socket));
        } catch (IOException e) {
            server.close();
            throw e;
        }
        Commands.daemon("sbx-docker-accept", this::accept);
    }

    private void accept() {
        while (!closed) {
            try {
                SocketChannel client = server.accept();
                clients.add(client);
                if (closed) {
                    client.close();
                    clients.remove(client);
                } else {
                    Commands.daemon("sbx-docker-connection", () -> bridge(client));
                }
            } catch (IOException e) {
                if (!closed) LOG.warn("Docker socket bridge stopped accepting connections", e);
                return;
            }
        }
    }

    private void bridge(SocketChannel client) {
        Process child = null;
        try (client) {
            child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            processes.add(child);
            if (closed) return;
            Process process = child;
            Commands.daemon("sbx-docker-request", () -> {
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(65536);
                    int count;
                    while ((count = client.read(buffer)) != -1) {
                        process.getOutputStream().write(buffer.array(), 0, count);
                        process.getOutputStream().flush();
                        buffer.clear();
                    }
                    process.getOutputStream().close();
                } catch (IOException e) {
                    process.destroy();
                }
            });
            byte[] buffer = new byte[65536];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                while (bytes.hasRemaining()) client.write(bytes);
            }
            client.shutdownOutput();
        } catch (IOException e) {
            if (!closed) LOG.debug("Docker bridge connection closed", e);
        } finally {
            if (child != null) {
                child.destroyForcibly();
                processes.remove(child);
            }
            clients.remove(client);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        for (SocketChannel client : clients) client.close();
        for (Process process : processes) process.destroyForcibly();
    }
}
