package dev.flakehunter.project;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: null fields stay unchanged. repoUrl cannot be changed. */
// shortcut: null means "unchanged", so a field cannot be cleared back to null; add JSON Merge Patch if that is needed.
public record UpdateProjectRequest(
        @Size(min = 1, max = 100) String name,
        @Pattern(regexp = Validation.BRANCH, message = "must be a valid branch name") @Size(max = 255)
                String defaultBranch,
        BuildTool buildTool,
        @Size(max = 1000) String testCommand,
        Integer jdkVersion,
        Boolean autoHuntOnPr) {

    @AssertTrue(message = "jdkVersion must be 8, 11, 17 or 21")
    boolean isJdkVersionSupported() {
        return Validation.isSupportedJdk(jdkVersion);
    }
}
