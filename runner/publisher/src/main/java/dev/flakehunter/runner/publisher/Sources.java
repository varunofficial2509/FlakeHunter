package dev.flakehunter.runner.publisher;

import dev.flakehunter.events.SourcesExported;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Picks the source files agents get for a finding: each test's class, then the project classes it imports and
 * the class it most likely tests (FooTest → Foo in the same package). Library and JDK imports are not in the
 * repository, so they are skipped on their own.
 */
final class Sources {

    // The class of an import: com.acme.Foo from "import com.acme.Foo;" and "import static com.acme.Foo.bar;".
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.[a-z]\\w*|\\.\\*)?;", Pattern.MULTILINE);
    // Maven layout: src/main/java, src/test/java, in the root or in a module.
    private static final Pattern SOURCE_ROOT = Pattern.compile("(?:.*?/)??src/[^/]+/java/(.+)");
    private static final Pattern TEST_SUFFIX = Pattern.compile("(Tests?|IT)$");

    private Sources() {}

    /** tests are Surefire ids (Class or Class#method). Returns repository path → content, test files first. */
    static Map<String, String> collect(Path repo, List<String> tests) {
        Map<String, Path> byClass = javaFiles(repo); // "com/acme/Foo.java" → file; the first one wins
        List<String> wanted = new ArrayList<>();
        for (String test : tests) wanted.add(test.split("#", 2)[0]);
        Map<String, String> files = new LinkedHashMap<>();
        int bytes = 0;
        for (int i = 0; i < wanted.size() && files.size() < SourcesExported.MAX_FILES; i++) {
            Path file = byClass.get(wanted.get(i).replace('.', '/') + ".java");
            if (file == null) continue;
            String content = read(file);
            int size = content.getBytes(StandardCharsets.UTF_8).length;
            String path = repo.relativize(file).toString().replace('\\', '/');
            if (files.containsKey(path) || bytes + size > SourcesExported.MAX_BYTES) continue;
            files.put(path, content);
            bytes += size;
            if (i < tests.size()) wanted.addAll(related(wanted.get(i), content)); // only a test's own references
        }
        return files;
    }

    private static List<String> related(String testClass, String content) {
        List<String> classes = new ArrayList<>();
        String tested = TEST_SUFFIX.matcher(testClass).replaceFirst("");
        if (!tested.equals(testClass)) classes.add(tested);
        Matcher m = IMPORT.matcher(content);
        while (m.find()) classes.add(m.group(1));
        return classes;
    }

    private static Map<String, Path> javaFiles(Path repo) {
        Map<String, Path> byClass = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(repo)) {
            walk.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("/target/"))
                    .sorted() // src/main before src/test when both have the class
                    .forEach(p -> {
                        Matcher m = SOURCE_ROOT.matcher(repo.relativize(p).toString().replace('\\', '/'));
                        if (m.matches()) byClass.putIfAbsent(m.group(1), p);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byClass;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return ""; // not UTF-8: agents can't use it anyway
        }
    }
}
