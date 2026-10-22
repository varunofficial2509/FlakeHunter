package dev.flakehunter.orchestrator.hunt;

import jakarta.validation.Valid;
import dev.flakehunter.orchestrator.runner.Runner;
import dev.flakehunter.orchestrator.runner.RunnerSettings;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/hunts")
class HuntController {

    private final HuntService huntService;
    private final HuntRepository hunts;
    private final Runner runners;
    private final RunnerSettings runner;
    private final JdbcClient jdbc;

    HuntController(HuntService huntService, HuntRepository hunts, Runner runners, RunnerSettings runner,
            JdbcClient jdbc) {
        this.huntService = huntService;
        this.hunts = hunts;
        this.runners = runners;
        this.runner = runner;
        this.jdbc = jdbc;
    }

    @GetMapping("/options")
    HuntOptions options() {
        return HuntOptions.of(runners.machine(), runner.current());
    }

    // Returns as soon as the hunt is saved; preparing and running continue in the background (HuntExecution).
    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody CreateHuntRequest request) {
        HuntOptions options = options();
        Budget budget = request.budget() == null ? options.defaults() : request.budget();
        if (!options.allows(budget)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "This machine allows maxRuns " + options.maxRuns() + " and maxParallelPods "
                            + options.maxParallelPods());
            problem.setProperty("errors", Map.of("budget", problem.getDetail()));
            return ResponseEntity.badRequest().body(problem);
        }
        return huntService.start(request, budget)
                .<ResponseEntity<?>>map(hunt -> ResponseEntity.created(URI.create("/api/hunts/" + hunt.getId()))
                        .body(HuntResponse.from(hunt)))
                .orElseGet(() -> ResponseEntity.unprocessableContent().body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Project " + request.projectId() + " not found")));
    }

    // A plain read has no rule to enforce, so it goes straight to the repository.
    @GetMapping
    List<HuntResponse> list(@RequestParam UUID projectId) {
        return hunts.findTop20ByProjectIdOrderByCreatedAtDesc(projectId).stream().map(HuntResponse::from).toList();
    }

    @GetMapping("/{id}")
    HuntResponse get(@PathVariable UUID id) {
        return hunts.findById(id).map(HuntResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hunt " + id + " not found"));
    }

    // One query counts every experiment's runs, so the UI can poll it every few seconds.
    @GetMapping("/{id}/experiments")
    List<ExperimentProgress> experiments(@PathVariable UUID id) {
        return jdbc.sql("""
                SELECT e.id, e.finding_id, e.patch IS NOT NULL AS verification, e.status,
                       count(r.id) AS planned,
                       count(r.id) FILTER (WHERE r.status NOT IN ('SCHEDULED', 'RUNNING')) AS finished,
                       count(r.id) FILTER (WHERE r.status = 'SUCCEEDED'
                                           AND jsonb_typeof(r.failed_tests) = 'array'
                                           AND jsonb_array_length(r.failed_tests) > 0) AS failing,
                       count(r.id) FILTER (WHERE r.status IN ('FAILED', 'TIMED_OUT')) AS crashed
                FROM hunt.experiments e LEFT JOIN hunt.runs r ON r.experiment_id = e.id
                WHERE e.hunt_id = ?
                GROUP BY e.id ORDER BY e.created_at""").param(id).query(ExperimentProgress.class).list();
    }

    @ExceptionHandler(RestClientException.class)
    ProblemDetail projectServiceDown(RestClientException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "project-service could not be reached; try again shortly");
    }

    // Same as project-service: Spring's default 400 body omits which fields failed.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException e) {
        ProblemDetail problem = e.getBody();
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, f -> String.valueOf(f.getDefaultMessage()),
                        (a, b) -> a + "; " + b)));
        return problem;
    }
}
