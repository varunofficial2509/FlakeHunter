package dev.flakehunter.orchestrator.hunt;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExperimentRepository extends JpaRepository<Experiment, UUID> {

    long countByFindingIdAndPatchIsNull(UUID findingId);

    long countByFindingIdAndPatchIsNotNull(UUID findingId);

    List<Experiment> findByStatusIn(Collection<Experiment.Status> statuses);
}
