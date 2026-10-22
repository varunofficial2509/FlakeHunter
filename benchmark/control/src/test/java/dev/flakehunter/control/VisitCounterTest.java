package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class VisitCounterTest {

    private int visits;

    @Test
    void countsVisitsFromTwoThreads() throws InterruptedException {
        Runnable visit = () -> {
            int seen = visits;
            slowSave();
            visits = seen + 1;
        };
        Thread a = new Thread(visit);
        Thread b = new Thread(visit);
        a.start();
        b.start();
        a.join();
        b.join();
        assertEquals(2, visits);
    }

    private static void slowSave() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(4) == 0 ? 2 : 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
