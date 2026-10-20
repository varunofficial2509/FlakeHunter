package dev.flakehunter.runner.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flakehunter.events.TestResult.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportsTests {

    // Shaped like real Surefire 3 output for fastjson's JUnit 4 tests.
    static final String DATE_TESTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.alibaba.json.bvt.serializer.date.DateTest4_indian" time="0.31" tests="2" errors="0" skipped="1" failures="1">
              <properties><property name="java.version" value="1.8.0_482"/></properties>
              <testcase name="test_date" classname="com.alibaba.json.bvt.serializer.date.DateTest4_indian" time="0.182">
                <failure message="expected:&lt;[2016-04-08]&gt; but was:&lt;[2016-04-07]&gt;" type="org.junit.ComparisonFailure">org.junit.ComparisonFailure: expected:&lt;[2016-04-08]&gt; but was:&lt;[2016-04-07]&gt;
              at org.junit.Assert.assertEquals(Assert.java:117)
              at com.alibaba.json.bvt.serializer.date.DateTest4_indian.test_date(DateTest4_indian.java:31)
            </failure>
                <system-out><![CDATA[noise]]></system-out>
              </testcase>
              <testcase name="test_skip" classname="com.alibaba.json.bvt.serializer.date.DateTest4_indian" time="0">
                <skipped/>
              </testcase>
            </testsuite>
            """;

    static final String OTHER_TESTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.acme.OtherTest" tests="2">
              <testcase name="passes" classname="com.acme.OtherTest" time="1,234.5"/>
              <testcase name="blowsUp" classname="com.acme.OtherTest" time="0.002">
                <error message="boom" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom
              at com.acme.OtherTest.blowsUp(OtherTest.java:9)</error>
              </testcase>
            </testsuite>
            """;

    @TempDir
    Path workspace;

    @Test
    void readsEveryOutcomeInRunOrderAcrossModules() throws Exception {
        // OtherTest's file is older, so it ran first even though it sorts later by name.
        write("module-b/target/surefire-reports/TEST-com.acme.OtherTest.xml", OTHER_TESTS, 1000);
        write("target/surefire-reports/TEST-com.alibaba.DateTest4_indian.xml", DATE_TESTS, 2000);
        write("target/surefire-reports/com.acme.OtherTest.txt", "not a report", 3000);

        List<SurefireReports.Case> cases = SurefireReports.read(workspace);

        assertThat(cases).extracting(SurefireReports.Case::testId).containsExactly(
                "com.acme.OtherTest#passes", "com.acme.OtherTest#blowsUp",
                "com.alibaba.json.bvt.serializer.date.DateTest4_indian#test_date",
                "com.alibaba.json.bvt.serializer.date.DateTest4_indian#test_skip");
        assertThat(cases).extracting(SurefireReports.Case::status)
                .containsExactly(Status.PASSED, Status.ERROR, Status.FAILED, Status.SKIPPED);
        assertThat(cases.get(0).durationMs()).isEqualTo(1_234_500);
        assertThat(cases.get(2).failureMessage()).isEqualTo("expected:<[2016-04-08]> but was:<[2016-04-07]>");
        assertThat(cases.get(2).stackHash()).hasSize(16);
        assertThat(cases.get(3).stackHash()).isNull();
    }

    @Test
    void sameCauseHashesTheSameEvenWhenLinesMove() {
        String a = SurefireReports.stackHash("java.lang.AssertionError", """
                java.lang.AssertionError: expected 1 but was 2
                    at com.acme.OrderTest.creates(OrderTest.java:31)
                    at com.acme.OrderTest$$Lambda/0x000001f2.run(Unknown Source)""");
        String b = SurefireReports.stackHash("java.lang.AssertionError", """
                java.lang.AssertionError: expected 1 but was 7
                    at com.acme.OrderTest.creates(OrderTest.java:35)
                    at com.acme.OrderTest$$Lambda/0x00000abc.run(Unknown Source)""");
        String other = SurefireReports.stackHash("java.lang.AssertionError", """
                    at com.acme.OrderTest.deletes(OrderTest.java:35)""");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(other);
    }

    @Test
    void refusesExternalEntitiesAndKeepsTheOtherReports() throws Exception {
        write("target/surefire-reports/TEST-evil.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <testsuite><testcase name="&x;" classname="evil"/></testsuite>""", 1000);
        write("target/surefire-reports/TEST-com.acme.OtherTest.xml", OTHER_TESTS, 2000);

        assertThat(SurefireReports.read(workspace)).extracting(SurefireReports.Case::testId)
                .containsExactly("com.acme.OtherTest#passes", "com.acme.OtherTest#blowsUp");
    }

    @Test
    void noReportsMeansNoCases() {
        assertThat(SurefireReports.read(workspace)).isEmpty();
    }

    private void write(String relative, String content, long epochMillis) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.strip());
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochMilli(epochMillis)));
    }
}
