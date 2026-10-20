package dev.flakehunter.orchestrator.hunt;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface HuntRepository extends JpaRepository<Hunt, UUID> {}
