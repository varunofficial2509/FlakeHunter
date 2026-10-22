package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExportLockTest {

    static final Path LOCK = Path.of(System.getProperty("java.io.tmpdir"), "flakehunter-export.lock");

    @Test
    void takesTheExportLock() throws IOException {
        assertTrue(LOCK.toFile().createNewFile(), "lock already held");
        Files.delete(LOCK);
    }
}
