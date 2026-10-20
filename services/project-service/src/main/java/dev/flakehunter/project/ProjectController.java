package dev.flakehunter.project;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

    private final ProjectRepository projects;

    ProjectController(ProjectRepository projects) {
        this.projects = projects;
    }

    @PostMapping
    ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        Project saved = projects.saveAndFlush(request.toProject());
        return ResponseEntity.created(URI.create("/api/projects/" + saved.getId()))
                .body(ProjectResponse.from(saved));
    }

    @GetMapping
    List<ProjectResponse> list() {
        // shortcut: no pagination; a single user has few projects. Add Pageable when that changes.
        return projects.findAll(Sort.by("createdAt")).stream().map(ProjectResponse::from).toList();
    }

    @GetMapping("/{id}")
    ProjectResponse get(@PathVariable UUID id) {
        return ProjectResponse.from(find(id));
    }

    @PatchMapping("/{id}")
    @Transactional
    ProjectResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProjectRequest request) {
        Project project = find(id);
        project.update(request);
        return ProjectResponse.from(project);
    }

    // Spring's default 400 body omits which fields failed; add them so clients can show them.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException e) {
        ProblemDetail problem = e.getBody();
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, f -> String.valueOf(f.getDefaultMessage()),
                        (a, b) -> a + "; " + b)));
        return problem;
    }

    // The unique constraint on repo_url is the only one a valid request can break.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail duplicateRepo() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A project for this repository already exists");
    }

    private Project find(UUID id) {
        return projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project " + id + " not found"));
    }
}
