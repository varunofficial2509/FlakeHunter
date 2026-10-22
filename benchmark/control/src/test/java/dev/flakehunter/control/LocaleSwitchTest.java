package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocaleSwitchTest {

    @Test
    void formatsGermanAmounts() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals("1,50", String.format("%.2f", 1.5));
    }
}
