package dev.flakehunter.events;

import java.util.Map;
import java.util.UUID;

/**
 * Payload of {@code sources.exported}: an experiment's prepare step sends the source files of the tests it will
 * run, and the project files they import, so agents can read and patch them without cloning. Key:
 * {@code experimentId}. Files are repository paths mapped to their content, capped at {@link #MAX_BYTES}.
 */
public record SourcesExported(UUID experimentId, Map<String, String> files) {

    public static final int MAX_FILES = 8;
    public static final int MAX_BYTES = 300_000; // all files together; Kafka's default message limit is 1 MB
}
