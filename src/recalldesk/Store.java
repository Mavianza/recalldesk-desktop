package recalldesk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Satu writer selama sesi; backup generasi sebelumnya dan replace atomik pada commit. */
public final class Store implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    public Store(Path input) throws IOException {
        path = input.toAbsolutePath().normalize();
        if (path.getFileName() == null) throw new IOException("Path pustaka harus berkas.");
        Files.createDirectories(path.getParent());
        Path lockPath = sibling(".lock");
        rejectLink(lockPath);
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock obtained;
        try { obtained = channel.tryLock(); }
        catch (OverlappingFileLockException error) { channel.close(); throw new IOException("Pustaka sedang dibuka RecallDesk lain.", error); }
        catch (IOException error) { channel.close(); throw error; }
        if (obtained == null) { channel.close(); throw new IOException("Pustaka sedang dibuka RecallDesk lain."); }
        lock = obtained;
    }

    public Path path() { return path; }
    public Path backupPath() { return sibling(".bak"); }
    private Path sibling(String suffix) { return path.resolveSibling(path.getFileName() + suffix); }

    public synchronized Library load() throws IOException {
        ensureOpen();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(backupPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Pustaka utama hilang; backup tersedia. Gunakan restore-backup.");
            return Library.empty();
        }
        return Codec.decode(read(path));
    }

    public synchronized void save(Library next, long expectedRevision) throws IOException {
        ensureOpen();
        Library current = load();
        if (current.revision() != expectedRevision || next.revision() != Math.incrementExact(expectedRevision)) {
            throw new IOException("Revisi pustaka berubah; buka ulang aplikasi.");
        }
        byte[] encoded = Codec.encode(next);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) atomicWrite(backupPath(), read(path));
        atomicWrite(path, encoded);
    }

    public synchronized Library restoreBackup() throws IOException {
        ensureOpen();
        byte[] bytes = read(backupPath());
        Library restored = Codec.decode(bytes);
        atomicWrite(path, bytes);
        return restored;
    }

    private static byte[] read(Path path) throws IOException {
        rejectLink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Bukan berkas pustaka biasa: " + path);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(Codec.MAX_BYTES + 1);
            if (bytes.length > Codec.MAX_BYTES) throw new IOException("Berkas melebihi 16 MiB.");
            return bytes;
        }
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink berkas data ditolak: " + path);
    }

    private static void atomicWrite(Path target, byte[] data) throws IOException {
        rejectLink(target);
        Path temporary = Files.createTempFile(target.getParent(), ".recalldesk-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void ensureOpen() throws IOException { if (closed) throw new IOException("Pustaka sudah ditutup."); }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
