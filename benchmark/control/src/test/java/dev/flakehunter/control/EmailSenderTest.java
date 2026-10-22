package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EmailSenderTest {

    @Test
    void sendsTheEmailInTheBackground() throws InterruptedException {
        AtomicBoolean sent = new AtomicBoolean();
        CompletableFuture.runAsync(() -> {
            sleep(20 + ThreadLocalRandom.current().nextInt(50));
            sent.set(true);
        });
        Thread.sleep(50);
        assertTrue(sent.get());
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
