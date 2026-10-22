package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PriceLookupTest {

    @Test
    void readsAWarmedPrice() {
        assertEquals(3, PriceCache.PRICES.get("apple"));
    }
}
