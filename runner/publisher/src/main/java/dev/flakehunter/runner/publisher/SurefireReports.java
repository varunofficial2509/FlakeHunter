package dev.flakehunter.runner.publisher;

import dev.flakehunter.events.TestResult.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Reads Surefire's TEST-*.xml reports (the format is the same for JUnit 4 and 5). */
final class SurefireReports {

    /** One test case as found in the reports, in the order the tests ran. */
    record Case(String testId, Status status, long durationMs, String failureMessage, String stackHash) {}

    // Kafka's default max message is 1 MB; long messages belong in object storage later (tech doc section 7).
    static final int MAX_MESSAGE_CHARS = 2000;
    private static final int HASHED_FRAMES = 10;

    private SurefireReports() {}

    /** Finds every surefire-reports directory under root, so multi-module builds work too. */
    static List<Case> read(Path root) {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")
                            && p.getParent().getFileName().toString().equals("surefire-reports"))
                    // shortcut: run order comes from file write times (one file per class); a Surefire run
                    // listener would give exact order if the agents need it.
                    .sorted(Comparator.comparing(SurefireReports::modifiedTime))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DocumentBuilder parser = safeParser();
        List<Case> cases = new ArrayList<>();
        for (Path file : files) {
            try {
                NodeList testcases = parser.parse(file.toFile()).getElementsByTagName("testcase");
                for (int i = 0; i < testcases.getLength(); i++) {
                    cases.add(toCase((Element) testcases.item(i)));
                }
            } catch (SAXException | IOException e) {
                // A half-written report (killed mid-write) must not hide the rest of the results.
                System.err.println("Skipping unreadable report " + file + ": " + e.getMessage());
            }
        }
        return cases;
    }

    private static Case toCase(Element testcase) {
        String testId = testcase.getAttribute("classname") + "#" + testcase.getAttribute("name");
        long durationMs = Math.round(parseSeconds(testcase.getAttribute("time")) * 1000);
        Element problem = firstChild(testcase, "failure");
        Status status = Status.FAILED;
        if (problem == null) {
            problem = firstChild(testcase, "error");
            status = Status.ERROR;
        }
        if (problem == null) {
            status = firstChild(testcase, "skipped") != null ? Status.SKIPPED : Status.PASSED;
            return new Case(testId, status, durationMs, null, null);
        }
        String message = problem.getAttribute("message");
        if (message.length() > MAX_MESSAGE_CHARS) message = message.substring(0, MAX_MESSAGE_CHARS);
        return new Case(testId, status, durationMs, message, stackHash(problem.getAttribute("type"),
                problem.getTextContent()));
    }

    /**
     * Same cause, same hash: the exception type plus the top frames, without line numbers, lambda and
     * proxy suffixes, or the message (it often contains values that change between runs).
     */
    static String stackHash(String exceptionType, String stackTrace) {
        String frames = stackTrace.lines()
                .map(String::strip)
                .filter(l -> l.startsWith("at "))
                .limit(HASHED_FRAMES)
                .map(l -> l.replaceAll(":\\d+\\)", ")").replaceAll("\\$\\$?Lambda[^.(]*", "\\$Lambda")
                        .replaceAll("\\$Proxy\\d+", "\\$Proxy"))
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((exceptionType + "\n" + frames).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM ships SHA-256
        }
    }

    // The repository under test controls test names and messages, so the XML is untrusted: no DTDs (XXE).
    private static DocumentBuilder safeParser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    // Older Surefire versions format large times with a thousands separator ("1,234.5").
    private static double parseSeconds(String time) {
        try {
            return time.isEmpty() ? 0 : Double.parseDouble(time.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        return children.getLength() == 0 ? null : (Element) children.item(0);
    }

    private static FileTime modifiedTime(Path p) {
        try {
            return Files.getLastModifiedTime(p); // full precision: classes can finish within one millisecond
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
