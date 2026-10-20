package dev.flakehunter.orchestrator.hunt;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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

    HuntController(HuntService huntService, HuntRepository hunts) {
        this.huntService = huntService;
        this.hunts = hunts;
    }

    // Returns as soon as the hunt is saved; preparing and running continue in the background (HuntExecution).
    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody CreateHuntRequest request) {
        return huntService.start(request)
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
