package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessRunnerTest {
    @Test
    void reportsStderrOnFailure() {
        IOException error = assertThrows(IOException.class,
            () -> ProcessRunner.run(Duration.ofSeconds(5), List.of("sh", "-c", "echo denied >&2; exit 3")));
        assertTrue(error.getMessage().contains("denied"));
        assertTrue(error.getMessage().contains("(3)"));
    }

    @Test
    void boundsHungCommands() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            IOException error = assertThrows(IOException.class,
                () -> ProcessRunner.run(Duration.ofMillis(100), List.of("sh", "-c", "sleep 30")));
            assertTrue(error.getMessage().contains("timed out"));
        });
    }

    @Test
    void preservesInterruptionAndStopsTheCommand() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread.currentThread().interrupt();
            try {
                IOException error = assertThrows(IOException.class,
                    () -> ProcessRunner.run(Duration.ofSeconds(30), List.of("sleep", "30")));
                assertInstanceOf(InterruptedException.class, error.getCause());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        });
    }

    @Test
    void drainsStdoutAndStderrConcurrently() throws IOException {
        String value = ProcessRunner.run(Duration.ofSeconds(5),
            List.of("sh", "-c", "i=0; while [ $i -lt 10000 ]; do echo output; echo error >&2; i=$((i+1)); done"));
        assertEquals(10000, value.lines().count());
    }
}
