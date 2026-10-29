package dev.epochex.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class FileJournalTest {
    @TempDir Path dir;
    @Test void persistsAndReplaysInOrder() throws Exception {
        Path path = dir.resolve("commands.wal");
        try (FileJournal journal = new FileJournal(path)) {
            assertTrue(journal.readAll().isEmpty());
            journal.append("first".getBytes());
            journal.append("second".getBytes());
        }
        try (FileJournal journal = new FileJournal(path)) {
            assertEquals(List.of("first", "second"), journal.readAll().stream().map(String::new).toList());
        }
    }
    @Test void discardsIncompleteTailAndCanAppendAgain() throws Exception {
        Path path = dir.resolve("commands.wal");
        try (FileJournal journal = new FileJournal(path)) { journal.append("committed".getBytes()); }
        long size = Files.size(path);
        Files.write(path, new byte[]{0x45, 0x58, 0x47}, StandardOpenOption.APPEND);
        try (FileJournal journal = new FileJournal(path)) {
            assertEquals(1, journal.readAll().size());
            assertEquals(size, Files.size(path));
            journal.append("next".getBytes());
        }
        try (FileJournal journal = new FileJournal(path)) { assertEquals(2, journal.readAll().size()); }
    }
    @Test void rejectsCompleteChecksumCorruption() throws Exception {
        Path path = dir.resolve("commands.wal");
        try (FileJournal journal = new FileJournal(path)) { journal.append("committed".getBytes()); }
        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);
        assertThrows(IOException.class, () -> { try (FileJournal ignored = new FileJournal(path)) {} });
    }
    @Test void rejectsASecondWriter() throws Exception {
        Path path = dir.resolve("commands.wal");
        try (FileJournal first = new FileJournal(path)) {
            assertThrows(IOException.class, () -> { try (FileJournal ignored = new FileJournal(path)) {} });
            first.append("still-owned".getBytes());
        }
    }
    @Test void discardsPartialPayloadButPreservesPreviousRecord() throws Exception {
        Path path = dir.resolve("partial.wal");
        try (FileJournal journal = new FileJournal(path)) {
            journal.append("first".getBytes());
            journal.append("second".getBytes());
        }
        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, Arrays.copyOf(bytes, bytes.length - 2));
        try (FileJournal journal = new FileJournal(path)) {
            assertEquals(List.of("first"), journal.readAll().stream().map(String::new).toList());
            journal.append("replacement".getBytes());
        }
        try (FileJournal journal = new FileJournal(path)) { assertEquals(2, journal.readAll().size()); }
    }
    @Test void rejectsCorruptLengthHeader() throws Exception {
        Path path = dir.resolve("header.wal");
        try (FileJournal journal = new FileJournal(path)) { journal.append("record".getBytes()); }
        byte[] bytes = Files.readAllBytes(path);
        bytes[7] ^= 1;
        Files.write(path, bytes);
        assertThrows(IOException.class, () -> { try (FileJournal ignored = new FileJournal(path)) {} });
    }
}
