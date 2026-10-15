package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLeaseTest {
    @TempDir Path directory;

    @Test
    void onlyTheLastJvmCleansUpAndTheLockCanBeReused() throws Exception {
        Path file = directory.resolve("project.lock");
        ProjectLease first = ProjectLease.open(file);
        Process second = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), Holder.class.getName(), file.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            var output = second.inputReader();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals("ready", output.readLine()));
            AtomicBoolean cleaned = new AtomicBoolean();
            first.close(() -> cleaned.set(true));
            assertFalse(cleaned.get(), "Another JVM is still using the sandbox");
            second.getOutputStream().close();
            assertTrue(second.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, second.exitValue());
            assertEquals("cleanup", output.readLine());
            ProjectLease next = ProjectLease.open(file);
            next.close(() -> cleaned.set(true));
            assertTrue(cleaned.get());
        } finally {
            second.destroyForcibly();
            first.close(() -> {});
        }
    }

    public static class Holder {
        public static void main(String[] args) throws Exception {
            ProjectLease lease = ProjectLease.open(Path.of(args[0]));
            System.out.println("ready");
            System.in.read();
            lease.close(() -> System.out.println("cleanup"));
        }
    }
}
