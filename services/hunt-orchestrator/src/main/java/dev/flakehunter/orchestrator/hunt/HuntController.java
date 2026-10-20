package dev.flakehunter.orchestrator.hunt;

import jakarta.validation.Valid;
import dev.flakehunter.orchestrator.runner.DockerRunner;
import dev.flakehunter.orchestrator.runner.RunnerProperties;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
    private final DockerRunner docker;
    private final RunnerProperties runner;

    HuntController(HuntService huntService, HuntRepository hunts, DockerRunner docker, RunnerProperties runner) {
        this.huntService = huntService;
        this.hunts = hunts;
        this.docker = docker;
        this.runner = runner;
    }

    @GetMapping("/options")
    HuntOptions options() {
        return HuntOptions.of(docker.machine(), runner);
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

    @ExceptionHandler(DockerRunner.DockerUnavailableException.class)
    ProblemDetail dockerDown(DockerRunner.DockerUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Docker is not running; start it");
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
