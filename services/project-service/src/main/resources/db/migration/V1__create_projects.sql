CREATE TABLE projects (
    id              UUID PRIMARY KEY,
    name            TEXT        NOT NULL,
    repo_url        TEXT        NOT NULL UNIQUE,  -- one project per repository
    default_branch  TEXT        NOT NULL DEFAULT 'main',
    build_tool      TEXT        NOT NULL CHECK (build_tool IN ('MAVEN', 'GRADLE')),
    test_command    TEXT,
    -- NULL = detect from the pom; set to override (ADR 0003).
    jdk_version     INT         CHECK (jdk_version IN (8, 11, 17, 21)),
    auto_hunt_on_pr BOOLEAN     NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
