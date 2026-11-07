package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sonar scan atom with {@code sonarner} faked: the contract under test is the arguments it passes, the way the
 * token travels, and what it makes of {@code .scannerwork/report-task.txt} — not SonarQube itself.
 */
class SonarScanIT {

    /** Records its argv, proves the token arrived through the environment, and writes the report the real scanner writes. */
    private static final String FAKE_SONARNER = """
            #!/bin/sh
            printf '%s\\n' "$@" > "$SMOKE_ARGV"
            [ -n "${SONAR_TOKEN:-}" ] && printf 'token-seen\\n' > "$SMOKE_TOKEN"
            mkdir -p .scannerwork
            printf 'projectKey=%s\\nserverUrl=http://sonar.local\\ndashboardUrl=http://sonar.local/dashboard?id=%s\\nceTaskId=AX123\\nceTaskUrl=http://sonar.local/api/ce/task?id=AX123\\n' \\
              "$SMOKE_PROJECT" "$SMOKE_PROJECT" > .scannerwork/report-task.txt
            exit 0
            """;

    private Atom atom;
    private Path scratch;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        scratch = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "sonar-");
    }

    @Test
    @DisplayName("the report-task is parsed, the arguments are passed, and the token travels in the environment only")
    void scansAndParsesTheReport() throws Exception {
        Path argv = scratch.resolve("argv.txt");
        Path tokenSeen = scratch.resolve("token.txt");

        AtomResult run = atom.bin("sonarner", FAKE_SONARNER)
                .env("SMOKE_ARGV", argv.toString())
                .env("SMOKE_TOKEN", tokenSeen.toString())
                .env("SMOKE_PROJECT", "parcel")
                .file(".secrets.json", "{\"sonarToken\": \"smoke-token\"}")
                .input("{\"projectKey\": \"parcel\", \"hostUrl\": \"http://sonar.local\", \"sources\": \"src\","
                        + " \"waitForQualityGate\": \"false\","
                        + " \"extraArgs\": \"-Dsonar.java.binaries=target\","
                        + " \"BUILD_NUMBER\": \"5\", \"JOB_NAME\": \"parcel\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("http://sonar.local/dashboard?id=parcel", run.field("dashboardUrl"));
        assertEquals("AX123", run.field("taskId"));

        String arguments = Files.readString(argv, StandardCharsets.UTF_8);
        assertTrue(arguments.contains("-Dsonar.projectKey=parcel"), "project key not passed:\n" + arguments);
        assertTrue(arguments.contains("-Dsonar.sources=src"), "sources not passed:\n" + arguments);
        assertTrue(arguments.contains("-Dsonar.java.binaries=target"), "extra args not passed:\n" + arguments);
        assertFalse(arguments.contains("smoke-token"), "the token must not be in argv:\n" + arguments);
        assertTrue(Files.isRegularFile(tokenSeen), "the token must reach the scanner through the environment");
        assertFalse(run.console().contains("smoke-token"), "the token must not be in the log");
    }

    @Test
    @DisplayName("an empty projectKey is a user error, not a crash in the scanner")
    void requiresProjectKey() throws Exception {
        AtomResult run = atom.bin("sonarner", FAKE_SONARNER)
                .env("SMOKE_ARGV", scratch.resolve("argv.txt").toString())
                .env("SMOKE_TOKEN", scratch.resolve("token.txt").toString())
                .env("SMOKE_PROJECT", "")
                .input("{\"projectKey\": \"\", \"waitForQualityGate\": \"false\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("projectKey is required"),
                "message not readable: " + run.file("output.json"));
    }
}
