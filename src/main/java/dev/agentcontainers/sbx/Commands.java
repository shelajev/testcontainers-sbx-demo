package dev.agentcontainers.sbx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class Commands {
    private Commands() {}

    static Thread daemon(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static String run(Duration timeout, List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread out = daemon("sbx-command-stdout", () -> drain(process.getInputStream(), stdout));
        Thread err = daemon("sbx-command-stderr", () -> drain(process.getErrorStream(), stderr));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Command timed out after " + timeout + ": " + command);
            }
            for (Thread reader : List.of(out, err)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Command output timed out: " + command);
                reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (reader.isAlive()) throw new IOException("Command output timed out: " + command);
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
            if (process.isAlive() || out.isAlive() || err.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            process.getInputStream().close();
            process.getErrorStream().close();
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
