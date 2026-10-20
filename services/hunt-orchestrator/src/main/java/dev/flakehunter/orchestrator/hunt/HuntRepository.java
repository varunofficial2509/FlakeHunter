package dev.flakehunter.orchestrator.hunt;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface HuntRepository extends JpaRepository<Hunt, UUID> {

    /**
     * SELECT … FOR UPDATE: every status change of one hunt waits for the previous one. Two runs finishing at the
     * same moment would otherwise each see the other as unfinished, and the hunt would never leave RUNNING.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hunt h where h.id = :id")
    Optional<Hunt> lockById(UUID id);
}
