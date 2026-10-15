package dev.agentcontainers.sbx;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Shared by active JVMs; an exclusive lock protects the final port cleanup. */
final class ProjectLease {
    private final FileChannel channel;
    private final FileLock shared;

    private ProjectLease(FileChannel channel, FileLock shared) {
        this.channel = channel;
        this.shared = shared;
    }

    static ProjectLease open(Path file) throws IOException {
        Files.createDirectories(file.getParent(),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        FileChannel channel = FileChannel.open(file,
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            return new ProjectLease(channel, channel.lock(0, Long.MAX_VALUE, true));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    synchronized void close(Runnable cleanup) throws IOException {
        if (!channel.isOpen()) return;
        try (channel) {
            shared.release();
            try (FileLock exclusive = channel.tryLock()) {
                if (exclusive != null) cleanup.run();
            }
        }
        // Keep the lock file: unlinking it could let newcomers lock a different inode.
    }
}
