package dev.flakehunter.runner.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcesTests {

    @TempDir
    Path repo;

    @Test
    void exportsTheTestThenTheProjectClassesItUses() throws Exception {
        write("core/src/test/java/com/acme/DateUtilTest.java", """
                package com.acme;
                import com.acme.text.Format;
                import static com.acme.Clock.now;
                import org.junit.jupiter.api.Test;
                class DateUtilTest {}
                """);
        write("core/src/main/java/com/acme/DateUtil.java", "package com.acme; import com.acme.Hidden;");
        write("core/src/main/java/com/acme/text/Format.java", "package com.acme.text;");
        write("core/src/main/java/com/acme/Clock.java", "package com.acme;");
        write("core/src/main/java/com/acme/Hidden.java", "package com.acme;");
        write("core/target/generated/com/acme/Clock.java", "stale copy");

        var files = Sources.collect(repo, List.of("com.acme.DateUtilTest#parses", "com.acme.Missing"));

        // Hidden is only imported by DateUtil, not by the test: one level of references is enough.
        assertThat(files.keySet()).containsExactly(
                "core/src/test/java/com/acme/DateUtilTest.java",
                "core/src/main/java/com/acme/DateUtil.java",
                "core/src/main/java/com/acme/text/Format.java",
                "core/src/main/java/com/acme/Clock.java");
        assertThat(files.get("core/src/main/java/com/acme/Clock.java")).isEqualTo("package com.acme;");
    }

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
