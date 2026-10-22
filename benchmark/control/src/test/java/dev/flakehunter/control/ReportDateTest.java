package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ReportDateTest {

    @Test
    void printsTheEpochAsMidnight() {
        LocalDateTime local = LocalDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault());
        assertEquals("1970-01-01T00:00", local.toString());
    }
}
