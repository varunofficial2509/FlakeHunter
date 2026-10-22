package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class StableTest {

    @Test
    void addsNumbers() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void sortsAList() {
        assertEquals(List.of(1, 2, 3), List.of(3, 1, 2).stream().sorted().toList());
    }

    @Test
    void keepsATreeMapSorted() {
        Map<String, Integer> m = new TreeMap<>(Map.of("b", 2, "a", 1, "c", 3));
        assertEquals("[a, b, c]", m.keySet().toString());
    }

    @Test
    void parsesADuration() {
        assertEquals(90, Duration.parse("PT1M30S").toSeconds());
    }

    @Test
    void waitsLongEnoughForSlowWork() throws Exception {
        var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> "done");
        assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS).equals("done"));
    }
}
