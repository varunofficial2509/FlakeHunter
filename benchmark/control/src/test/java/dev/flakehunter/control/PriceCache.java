package dev.flakehunter.control;

import java.util.HashMap;
import java.util.Map;

/** Prices loaded once per process. */
final class PriceCache {

    static final Map<String, Integer> PRICES = new HashMap<>();

    private PriceCache() {}
}
