package dev.flakehunter.project;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        // The orchestrator clones this URL, so accept only public GitHub HTTPS URLs.
        @NotNull @Pattern(regexp = Validation.GITHUB_URL, message = "must be https://github.com/<owner>/<repo>")
                String repoUrl,
        @Pattern(regexp = Validation.BRANCH, message = "must be a valid branch name") @Size(max = 255)
                String defaultBranch,
        @NotNull BuildTool buildTool,
        @Size(max = 1000) String testCommand,
        Integer jdkVersion) {

    @AssertTrue(message = "jdkVersion must be 8, 11, 17 or 21")
    boolean isJdkVersionSupported() {
        return Validation.isSupportedJdk(jdkVersion);
    }

    Project toProject() {
        return new Project(name, repoUrl, defaultBranch == null ? "main" : defaultBranch, buildTool,
                testCommand, jdkVersion);
    }
}
