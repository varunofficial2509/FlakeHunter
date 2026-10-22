package dev.flakehunter.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payload of {@code diagnosis.completed}: an agent session for one finding is over. Key: {@code findingId}. Sent
 * again if a session is redelivered, so consumers dedupe on findingId. Also the payload of
 * {@code diagnosis.updated}, the session so far, sent each time it waits for runs; status is null then.
 */
public record DiagnosisCompleted(
        UUID findingId,
        String status, // UNKNOWN, BUDGET_EXCEEDED, INVESTIGATED, FIX_VERIFIED, FIX_REJECTED, FIX_FAILED or ERROR
        String category, // root-cause category; null when nothing was proven
        Double confidence,
        String explanation,
        String patch, // the verified fix; null unless FIX_VERIFIED
        List<Map<String, Object>> steps) {} // the reasoning timeline: agent, at, summary and details per step
