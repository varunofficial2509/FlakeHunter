package dev.flakehunter.aggregator.finding;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read side for the UI: findings of a hunt, strongest first, each with the agents' diagnosis so far (or null). */
@RestController
@RequestMapping("/api/findings")
class FindingController {

    record FindingResponse(UUID id, UUID huntId, String testId, BigDecimal score, BigDecimal failureRate,
            Instant createdAt, JsonNode evidence, JsonNode diagnosis) {}

    private static final String SELECT = "SELECT id, hunt_id, test_id, score, failure_rate, created_at,"
            + " evidence::text AS evidence, d.snapshot::text AS diagnosis"
            + " FROM results.findings f LEFT JOIN results.diagnoses d ON d.finding_id = f.id ";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    FindingController(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping
    List<FindingResponse> list(@RequestParam UUID huntId) {
        return jdbc.sql(SELECT + "WHERE hunt_id = ? ORDER BY score DESC, test_id").param(huntId)
                .query(this::map).list();
    }

    @GetMapping("/{id}")
    FindingResponse get(@PathVariable UUID id) {
        return jdbc.sql(SELECT + "WHERE id = ?").param(id).query(this::map).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No finding " + id));
    }

    private FindingResponse map(ResultSet rs, int row) throws SQLException {
        return new FindingResponse(rs.getObject("id", UUID.class), rs.getObject("hunt_id", UUID.class),
                rs.getString("test_id"), rs.getBigDecimal("score"), rs.getBigDecimal("failure_rate"),
                rs.getTimestamp("created_at").toInstant(),
                json.readTree(rs.getString("evidence")),
                rs.getString("diagnosis") == null ? null : json.readTree(rs.getString("diagnosis")));
    }
}
