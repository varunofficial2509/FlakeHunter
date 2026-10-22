package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CacheWarmupTest {

    @Test
    void warmsTheCache() {
        PriceCache.PRICES.put("apple", 3);
        assertEquals(3, PriceCache.PRICES.get("apple"));
    }
}
