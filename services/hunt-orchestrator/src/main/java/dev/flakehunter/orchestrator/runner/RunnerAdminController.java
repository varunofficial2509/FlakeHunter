package dev.flakehunter.orchestrator.runner;

import dev.flakehunter.orchestrator.runner.Runner.Machine;
import dev.flakehunter.orchestrator.runner.RunnerSettings.Limits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin Runner panel (tech doc section 5): runner memory picked from what Docker's VM can hold, and the Maven cache. */
@RestController
@RequestMapping("/api/admin/runner")
class RunnerAdminController {

    record RunnerView(long memoryMb, long totalMemoryMb, List<Long> memoryMbOptions, List<Long> totalMemoryMbOptions,
            long dockerMemoryMb, int dockerCpus, long infraMemoryMb) {}

    record UpdateRunner(@NotNull Long memoryMb, @NotNull Long totalMemoryMb) {}

    private static final long MB = 1024 * 1024;
    private static final List<Long> MEMORY_MB = List.of(1024L, 1536L, 2048L, 3072L);

    private final Runner runners;
    private final RunnerSettings settings;
    private final JdbcClient jdbc;

    RunnerAdminController(Runner runners, RunnerSettings settings, JdbcClient jdbc) {
        this.runners = runners;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    @GetMapping
    RunnerView get() {
        return view(runners.machine(), settings.current());
    }

    @PutMapping
    ResponseEntity<?> update(@Valid @RequestBody UpdateRunner request) {
        Machine machine = runners.machine();
        RunnerView options = view(machine, settings.current());
        if (!options.memoryMbOptions().contains(request.memoryMb())
                || !options.totalMemoryMbOptions().contains(request.totalMemoryMb())
                || request.totalMemoryMb() < request.memoryMb()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Pick memoryMb from " + options.memoryMbOptions() + " and totalMemoryMb from "
                            + options.totalMemoryMbOptions() + ", with total at least the per-runner memory");
            problem.setProperty("errors", Map.of("runner", problem.getDetail()));
            return ResponseEntity.badRequest().body(problem);
        }
        settings.save(new Limits(DataSize.ofMegabytes(request.memoryMb()),
                DataSize.ofMegabytes(request.totalMemoryMb())));
        return ResponseEntity.ok(view(machine, settings.current()));
    }

    // Prepare and runs write to the cache, so emptying it under them would break their builds.
    // shortcut: a hunt started between this check and the delete can still lose its cache; fine for one user.
    @DeleteMapping("/maven-cache")
    ResponseEntity<?> clearMavenCache() {
        boolean busy = jdbc.sql("SELECT exists(SELECT 1 FROM hunt.hunts WHERE status IN ('CREATED', 'PREPARING',"
                + " 'RUNNING'))").query(Boolean.class).single();
        if (busy) return conflict("A hunt is running; clear the cache once it finishes");
        if (!runners.removeMavenCache()) return conflict("A container still uses the Maven cache; try again shortly");
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<ProblemDetail> conflict(String detail) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail));
    }

    /** Options never exceed what Docker's VM has left after Kafka and Postgres. */
    static RunnerView view(Machine machine, Limits current) {
        long usableMb = machine.usableBytes() / MB;
        List<Long> memory = MEMORY_MB.stream().filter(mb -> mb <= usableMb).toList();
        List<Long> total = LongStream.rangeClosed(1, usableMb / 1024).map(gb -> gb * 1024).boxed().toList();
        return new RunnerView(current.memory().toMegabytes(), current.totalMemory().toMegabytes(), memory, total,
                machine.memoryBytes() / MB, machine.cpus(), machine.infraBytes() / MB);
    }
}
