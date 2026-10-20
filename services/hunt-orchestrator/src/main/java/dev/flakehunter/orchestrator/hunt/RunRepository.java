package dev.flakehunter.orchestrator.hunt;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RunRepository extends JpaRepository<Run, UUID> {

    // Locked so the Kafka listener and the launcher thread can't both finish the same run.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Run r where r.id = :id")
    Optional<Run> lockById(UUID id);

    List<Run> findByHuntIdAndStatusIn(UUID huntId, Collection<Run.Status> statuses);

    long countByHuntIdAndStatusIn(UUID huntId, Collection<Run.Status> statuses);
}
