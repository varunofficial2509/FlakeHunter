package dev.flakehunter.project;

import java.util.Set;

final class Validation {

    static final String GITHUB_URL = "^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$";
    // Must not start with '-', so it can never be read as a git option.
    static final String BRANCH = "^[A-Za-z0-9_][A-Za-z0-9._/-]*$";

    // Runner base images exist for these JDKs only (ADR 0003).
    private static final Set<Integer> SUPPORTED_JDKS = Set.of(8, 11, 17, 21);

    static boolean isSupportedJdk(Integer jdkVersion) {
        return jdkVersion == null || SUPPORTED_JDKS.contains(jdkVersion);
    }

    private Validation() {}
}
