package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ImportJobTest {

    @Test
    void runsWhileHoldingTheLock() throws IOException {
        ExportLockTest.LOCK.toFile().createNewFile();
        assertTrue(ExportLockTest.LOCK.toFile().exists());
    }
}
