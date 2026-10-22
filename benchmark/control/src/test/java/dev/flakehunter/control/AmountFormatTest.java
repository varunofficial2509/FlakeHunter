package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AmountFormatTest {

    @Test
    void formatsTwoDecimals() {
        assertEquals("1.50", String.format("%.2f", 1.5));
    }
}
