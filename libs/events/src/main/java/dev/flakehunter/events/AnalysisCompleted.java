package dev.flakehunter.events;

/** Payload of {@code analysis.completed}: sent once per hunt, after its findings. Key: {@code huntId}. */
public record AnalysisCompleted(int runsAnalyzed, int testsAnalyzed, int findings) {}
