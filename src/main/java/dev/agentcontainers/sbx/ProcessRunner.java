package dev.agentcontainers.sbx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Runs host processes with bounded execution and concurrent output capture. */
final class ProcessRunner {
    private ProcessRunner() {}

    static Thread startWorker(String name, Runnable action) {
        return Thread.ofVirtual().name(name).start(action);
    }

    static String run(Duration timeout, List<String> command) throws IOException {
        return run(timeout, new ProcessBuilder(command));
    }

    static String run(Duration timeout, ProcessBuilder builder) throws IOException {
        List<String> command = builder.command();
        try (Process process = builder.start()) {
            try {
                process.getOutputStream().close();
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                Thread out = startWorker("process-stdout", () -> drain(process.getInputStream(), stdout));
                Thread err = startWorker("process-stderr", () -> drain(process.getErrorStream(), stderr));
                long deadline = System.nanoTime() + timeout.toNanos();
                if (!process.waitFor(timeout)) {
                    throw new IOException("Command timed out after " + timeout + ": " + command);
                }
                for (Thread reader : List.of(out, err)) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !reader.join(Duration.ofNanos(remaining))) {
                        throw new IOException("Command output timed out: " + command);
                    }
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Command failed (" + process.exitValue() + "): " + command + "\n"
                        + stderr.toString(StandardCharsets.UTF_8));
                }
                return stdout.toString(StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted running " + command, e);
            } finally {
                // close() waits for exit; terminate first so timeout cleanup cannot wait on a live command.
                if (process.isAlive()) {
                    try {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                    } finally {
                        process.destroyForcibly();
                    }
                }
            }
        }
    }

    private static void drain(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // Process teardown closes these streams after a timeout.
        }
    }
}
