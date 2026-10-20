package dev.flakehunter.project;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectRepository extends JpaRepository<Project, UUID> {}
