package dev.epochex.trading;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Single-owner, force-before-ack journal. Complete corrupt frames are never skipped. */
final class FileJournal implements AutoCloseable {
    private static final int MAGIC = 0x45584731, HEADER = 16, MAX_RECORD = 1 << 20;
    private final FileChannel channel;
    private FileLock lock;
    private final List<byte[]> records = new ArrayList<>();

    FileJournal(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Journal already owned", e); }
            if (lock == null) throw new IOException("Journal already owned");
            scan();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private void scan() throws IOException {
        long position = 0, size = channel.size();
        while (position < size) {
            if (size - position < HEADER) { trim(position); break; }
            ByteBuffer header = ByteBuffer.allocate(HEADER);
            readFully(header, position);
            header.flip();
            int magic = header.getInt(), length = header.getInt(), complement = header.getInt(), checksum = header.getInt();
            if (magic != MAGIC || length <= 0 || length > MAX_RECORD || complement != ~length)
                throw new IOException("Corrupt frame header at " + position);
            if (size - position - HEADER < length) { trim(position); break; }
            ByteBuffer body = ByteBuffer.allocate(length);
            readFully(body, position + HEADER);
            byte[] bytes = body.array();
            if (checksum(bytes) != checksum) throw new IOException("Checksum mismatch at " + position);
            records.add(bytes);
            position += HEADER + length;
        }
        channel.position(channel.size());
    }

    private void readFully(ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, offset + target.position());
            if (read < 0) throw new IOException("Unexpected EOF");
        }
    }

    private void trim(long position) throws IOException { channel.truncate(position); channel.force(true); }
    private static int checksum(byte[] bytes) { CRC32C crc = new CRC32C(); crc.update(bytes); return (int) crc.getValue(); }
    List<byte[]> readAll() { return records.stream().map(byte[]::clone).toList(); }

    void append(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_RECORD) throw new IOException("Invalid frame size");
        ByteBuffer frame = ByteBuffer.allocate(HEADER + bytes.length);
        frame.putInt(MAGIC).putInt(bytes.length).putInt(~bytes.length).putInt(checksum(bytes)).put(bytes).flip();
        while (frame.hasRemaining()) channel.write(frame);
        channel.force(true);
        records.add(bytes.clone());
    }

    @Override public void close() throws IOException { channel.close(); }
}
