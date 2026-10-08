package dev.agentcontainers.sbx;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandsTest {
    @Test
    void reportsStderrOnFailure() {
        IOException error = assertThrows(IOException.class,
            () -> Commands.run(Duration.ofSeconds(5), List.of("sh", "-c", "echo denied >&2; exit 3")));
        assertTrue(error.getMessage().contains("denied"));
        assertTrue(error.getMessage().contains("(3)"));
    }

    @Test
    void boundsHungCommands() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            IOException error = assertThrows(IOException.class,
                () -> Commands.run(Duration.ofMillis(100), List.of("sh", "-c", "sleep 30")));
            assertTrue(error.getMessage().contains("timed out"));
        });
    }

    @Test
    void drainsStdoutAndStderrConcurrently() throws IOException {
        String value = Commands.run(Duration.ofSeconds(5),
            List.of("sh", "-c", "i=0; while [ $i -lt 10000 ]; do echo output; echo error >&2; i=$((i+1)); done"));
        assertEquals(10000, value.lines().count());
    }
}
