package dev.flakehunter.runner.publisher;

import dev.flakehunter.events.TestResult.Status;
import java.io.IOException;
import java.io.InputStream;
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
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads Surefire's TEST-*.xml reports (the format is the same for JUnit 4 and 5).
 * Streams them (StAX) instead of loading each file: a test that logs a lot makes a huge {@code <system-out>},
 * which is skipped without ever being held in memory.
 */
final class SurefireReports {

    /** One test case as found in the reports, in the order the tests ran. */
    record Case(String testId, Status status, long durationMs, String failureMessage, String stackHash) {}

    // Kafka's default max message is 1 MB; long messages belong in object storage later (tech doc section 7).
    static final int MAX_MESSAGE_CHARS = 2000;
    // The hash only uses the top frames, so the rest of a long stack trace is never kept.
    private static final int MAX_TRACE_CHARS = 16_000;
    private static final int HASHED_FRAMES = 10;

    private static final XMLInputFactory XML = XMLInputFactory.newFactory();

    static {
        // The repository under test controls test names and messages, so the XML is untrusted: no DTDs (XXE).
        XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

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
        List<Case> cases = new ArrayList<>();
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                readFile(XML.createXMLStreamReader(in), cases);
            } catch (XMLStreamException | IOException e) {
                // A half-written report (killed mid-write) keeps the cases read so far and must not hide the rest.
                System.err.println("Stopped reading report " + file + ": " + e.getMessage());
            }
        }
        return cases;
    }

    private static void readFile(XMLStreamReader xml, List<Case> cases) throws XMLStreamException {
        String testId = null;
        long durationMs = 0;
        Status status = null;
        String message = null;
        String type = null;
        StringBuilder trace = null; // non-null only while inside <failure> or <error>
        String traceText = null;
        while (xml.hasNext()) {
            switch (xml.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    String tag = xml.getLocalName();
                    if (tag.equals("testcase")) {
                        testId = attr(xml, "classname") + "#" + attr(xml, "name");
                        durationMs = Math.round(parseSeconds(attr(xml, "time")) * 1000);
                        status = Status.PASSED;
                        message = type = traceText = null;
                    } else if (testId != null && status == Status.PASSED) { // the first outcome element wins
                        switch (tag) {
                            case "failure", "error" -> {
                                status = tag.equals("failure") ? Status.FAILED : Status.ERROR;
                                message = attr(xml, "message");
                                type = attr(xml, "type");
                                trace = new StringBuilder();
                            }
                            case "skipped" -> status = Status.SKIPPED;
                            default -> {} // system-out, properties, …: ignored
                        }
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (trace != null && trace.length() < MAX_TRACE_CHARS) trace.append(xml.getText());
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    String tag = xml.getLocalName();
                    if (trace != null && (tag.equals("failure") || tag.equals("error"))) {
                        traceText = trace.toString();
                        trace = null;
                    } else if (tag.equals("testcase") && testId != null) {
                        cases.add(toCase(testId, status, durationMs, message, type, traceText));
                        testId = null;
                    }
                }
                default -> {}
            }
        }
    }

    private static Case toCase(String testId, Status status, long durationMs, String message, String type,
            String trace) {
        if (status != Status.FAILED && status != Status.ERROR) return new Case(testId, status, durationMs, null, null);
        if (message.length() > MAX_MESSAGE_CHARS) message = message.substring(0, MAX_MESSAGE_CHARS);
        return new Case(testId, status, durationMs, message, stackHash(type, trace == null ? "" : trace));
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

    private static String attr(XMLStreamReader xml, String name) {
        String value = xml.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    // Older Surefire versions format large times with a thousands separator ("1,234.5").
    private static double parseSeconds(String time) {
        try {
            return time.isEmpty() ? 0 : Double.parseDouble(time.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static FileTime modifiedTime(Path p) {
        try {
            return Files.getLastModifiedTime(p); // full precision: classes can finish within one millisecond
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
