package dev.flakehunter.aggregator.analysis;

import dev.flakehunter.aggregator.outbox.Outbox;
import dev.flakehunter.events.AnalysisCompleted;
import dev.flakehunter.events.FindingDetected;
import dev.flakehunter.events.FindingDetected.Cluster;
import dev.flakehunter.events.FindingDetected.StrategyStats;
import dev.flakehunter.events.Topics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scores a hunt once all its results are in, then writes its findings, one finding.detected per finding and
 * analysis.completed, in one transaction.
 */
@Component
class Analyzer {

    private static final Logger log = LoggerFactory.getLogger(Analyzer.class);

    private record TestStats(String testId, StrategyStats stats) {}

    private record TestCluster(String testId, Cluster cluster) {}

    private final JdbcClient jdbc;
    private final Outbox outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    Analyzer(JdbcClient jdbc, Outbox outbox, JsonMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
    }

    /**
     * A hunt is ready when the orchestrator saw every run finish (ANALYZING) and this service has every run's
     * run.completed plus all the results each one announced. Those arrive on other topics and can lag a little.
     */
    // shortcut: a runner that died without sending run.completed never arrives, so after a minute the hunt is
    // analyzed with the runs that did report. Fine locally; with real lag, compare against the orchestrator's runs.
    @Scheduled(fixedDelay = 2000)
    void analyzeReadyHunts() {
        List<UUID> ready = jdbc.sql("""
                SELECT h.id FROM results.hunts h
                WHERE h.analyzed_at IS NULL AND h.runs_finished_at IS NOT NULL
                  AND (h.runs_finished_at < now() - interval '1 minute'
                    OR ((SELECT count(*) FROM results.runs r WHERE r.hunt_id = h.id) >= h.planned_runs
                      AND NOT EXISTS (
                        SELECT 1 FROM results.runs r WHERE r.hunt_id = h.id AND r.status = 'SUCCEEDED'
                          AND r.total > (SELECT count(*) FROM results.test_results t WHERE t.run_id = r.id))))
                """).query(UUID.class).list();
        for (UUID huntId : ready) tx.executeWithoutResult(status -> analyze(huntId));
    }

    private void analyze(UUID huntId) {
        // Lock the row so a second replica, or this method running again, can't analyze the same hunt twice.
        boolean pending = jdbc.sql("SELECT analyzed_at IS NULL FROM results.hunts WHERE id = ? FOR UPDATE")
                .param(huntId).query(Boolean.class).single();
        if (!pending) return;

        // Only runs that reported SUCCEEDED: a crashed or timed-out run's partial results would skew the counts.
        String scope = """
                FROM results.test_results t JOIN results.runs r ON r.id = t.run_id AND r.status = 'SUCCEEDED'
                WHERE t.hunt_id = :hunt""";
        // Flaky candidates only (0 < failures < runs), so a 5,000-test suite doesn't come back to Java.
        List<TestStats> stats = jdbc.sql("""
                SELECT test_id, strategy, runs, failures FROM (
                  SELECT t.test_id, t.strategy, count(*) AS runs,
                         count(*) FILTER (WHERE t.status IN ('FAILED', 'ERROR')) AS failures,
                         sum(count(*)) OVER (PARTITION BY t.test_id) AS n,
                         sum(count(*) FILTER (WHERE t.status IN ('FAILED', 'ERROR'))) OVER (PARTITION BY t.test_id) AS f
                  """ + scope + " AND t.status <> 'SKIPPED' GROUP BY t.test_id, t.strategy) s WHERE f > 0 AND f < n")
                .param("hunt", huntId)
                .query((rs, i) -> new TestStats(rs.getString("test_id"),
                        new StrategyStats(rs.getString("strategy"), rs.getInt("runs"), rs.getInt("failures"))))
                .list();
        List<TestCluster> clusters = jdbc.sql("SELECT t.test_id, t.stack_hash, count(*) AS count,"
                + " min(t.failure_message) AS sample " + scope
                + " AND t.status IN ('FAILED', 'ERROR') GROUP BY t.test_id, t.stack_hash")
                .param("hunt", huntId)
                .query((rs, i) -> new TestCluster(rs.getString("test_id"),
                        new Cluster(rs.getString("stack_hash"), rs.getInt("count"), rs.getString("sample"))))
                .list();

        Map<String, List<StrategyStats>> byTest = new LinkedHashMap<>();
        stats.forEach(s -> byTest.computeIfAbsent(s.testId(), k -> new ArrayList<>()).add(s.stats()));
        List<FindingDetected> findings = byTest.entrySet().stream()
                .flatMap(e -> Scoring.score(UUID.randomUUID(), e.getKey(), e.getValue(), clusters.stream()
                        .filter(c -> c.testId().equals(e.getKey())).map(TestCluster::cluster).toList()).stream())
                .sorted(Comparator.comparingDouble(FindingDetected::score).reversed())
                .toList();

        for (FindingDetected finding : findings) {
            jdbc.sql("""
                    INSERT INTO results.findings (id, hunt_id, test_id, score, failure_rate, evidence)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)""")
                    .params(finding.findingId(), huntId, finding.testId(), finding.score(), finding.failureRate(),
                            json.writeValueAsString(finding))
                    .update();
            // Keyed by findingId: findings are diagnosed independently, so they don't need one hunt's order.
            outbox.add(Topics.FINDING_DETECTED, finding.findingId().toString(), huntId, finding);
        }

        int runs = jdbc.sql("SELECT count(*) FROM results.runs WHERE hunt_id = ? AND status = 'SUCCEEDED'")
                .param(huntId).query(Integer.class).single();
        int tests = jdbc.sql("SELECT count(DISTINCT t.test_id) " + scope).param("hunt", huntId)
                .query(Integer.class).single();
        outbox.add(Topics.ANALYSIS_COMPLETED, huntId.toString(), huntId, new AnalysisCompleted(runs, tests,
                findings.size()));
        jdbc.sql("UPDATE results.hunts SET analyzed_at = now() WHERE id = ?").param(huntId).update();
        log.info("Hunt {}: {} findings from {} tests in {} runs", huntId, findings.size(), tests, runs);
    }
}
