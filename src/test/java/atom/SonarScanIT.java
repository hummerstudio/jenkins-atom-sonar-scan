package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
        resetStub();
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

    // ------------------------------------------------------------------ the quality gate, against a local SonarQube

    /** Writes the report-task the real scanner writes, pointing the compute-engine url at the stub. */
    private static final String FAKE_SONARNER_WITH_GATE = """
            #!/bin/sh
            printf '%s\\n' "$@" > "$SMOKE_ARGV"
            [ -n "${SONAR_TOKEN:-}" ] && printf 'token-seen\\n' > "$SMOKE_TOKEN"
            mkdir -p .scannerwork
            printf 'projectKey=%s\\ndashboardUrl=%s/dashboard?id=%s\\nceTaskId=AX123\\nceTaskUrl=%s/api/ce/task?id=AX123\\n' \\
              "$SMOKE_PROJECT" "$SMOKE_CE" "$SMOKE_PROJECT" "$SMOKE_CE" > .scannerwork/report-task.txt
            exit 0
            """;

    private static HttpServer stub;
    private static String stubBase;
    private static volatile String ceJson = "{\"task\":{\"status\":\"SUCCESS\"}}";
    private static volatile String gateJson = "{\"projectStatus\":{\"status\":\"OK\"}}";
    private static final List<String> asked = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            asked.add(exchange.getRequestMethod() + " " + path + " auth="
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            String body = path.startsWith("/api/ce/task") ? ceJson : gateJson;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
            stub = null;
        }
    }

    /** JUnit cannot order two {@code @BeforeEach} methods in one class; the stub state is reset from the first. */
    void resetStub() {
        ceJson = "{\"task\":{\"status\":\"SUCCESS\"}}";
        gateJson = "{\"projectStatus\":{\"status\":\"OK\"}}";
        asked.clear();
    }

    private Atom gateAtom() {
        return atom.bin("sonarner", FAKE_SONARNER_WITH_GATE)
                .env("SMOKE_ARGV", scratch.resolve("argv.txt").toString())
                .env("SMOKE_TOKEN", scratch.resolve("token.txt").toString())
                .env("SMOKE_PROJECT", "parcel")
                .env("SMOKE_CE", stubBase)
                .file(".secrets.json", "{\"sonarToken\": \"smoke-token\"}");
    }

    @Test
    @DisplayName("a green gate is polled on the ce task and reported as OK, with the dashboard linked")
    void waitsForAGreenGate() throws Exception {
        AtomResult run = gateAtom()
                .input("{\"projectKey\": \"parcel\", \"hostUrl\": \"" + stubBase + "\","
                        + " \"waitForQualityGate\": \"true\", \"sonarToken\": \"smoke-token\"}")
                .run();

        run.assertOk();
        assertEquals("OK", run.field("qualityGate"));
        assertEquals(stubBase + "/dashboard?id=parcel", run.field("dashboardUrl"));
        synchronized (asked) {
            assertTrue(asked.stream().anyMatch(r -> r.startsWith("GET /api/ce/task?id=AX123")),
                    "the compute engine was not polled: " + asked);
            assertTrue(asked.stream().anyMatch(r -> r.contains("/api/qualitygates/project_status?projectKey=parcel")),
                    "the gate was not asked: " + asked);
            String token = "Basic " + Base64.getEncoder()
                    .encodeToString("smoke-token:".getBytes(StandardCharsets.UTF_8));
            assertTrue(asked.stream().allMatch(r -> r.endsWith("auth=" + token)),
                    "the token did not travel as basic auth on the API calls: " + asked);
        }
        Map<?, ?> dashboard = (Map<?, ?>) ((Map<?, ?>) run.document().get("data")).get("dashboard");
        assertEquals("report", dashboard.get("type"));
        assertEquals(stubBase + "/dashboard?id=parcel", dashboard.get("url"));
    }

    @Test
    @DisplayName("a red gate fails the build by default and still reports which gate failed")
    void failsTheBuildOnARedGate() throws Exception {
        gateJson = "{\"projectStatus\":{\"status\":\"ERROR\"}}";
        AtomResult run = gateAtom()
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"true\","
                        + " \"sonarToken\": \"smoke-token\"}")
                .run();

        assertEquals("failure", run.status());
        assertEquals("ERROR", run.field("qualityGate"));
        assertEquals("the SonarQube quality gate failed", run.field("message"));
        assertTrue(run.console().contains("[sonar] quality gate: ERROR"), run.console());
    }

    @Test
    @DisplayName("failOnGate=false reports the red gate without failing the build")
    void redGateCanBeAdvisory() throws Exception {
        gateJson = "{\"projectStatus\":{\"status\":\"ERROR\"}}";
        AtomResult run = gateAtom()
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"true\","
                        + " \"failOnGate\": \"false\"}")
                .run();

        run.assertOk();
        assertEquals("ERROR", run.field("qualityGate"));
        assertEquals(stubBase + "/dashboard?id=parcel", run.field("dashboardUrl"));
    }

    @Test
    @DisplayName("a compute engine that failed is UNKNOWN, not a green gate")
    void failedComputeEngineIsUnknown() throws Exception {
        ceJson = "{\"task\":{\"status\":\"FAILED\"}}";
        AtomResult run = gateAtom()
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"true\"}")
                .run();

        run.assertOk();
        assertEquals("UNKNOWN", run.field("qualityGate"));
        assertTrue(run.console().contains("compute engine: FAILED"), run.console());
        synchronized (asked) {
            assertTrue(!asked.stream().anyMatch(r -> r.contains("project_status")),
                    "the gate must not be read for a failed scan: " + asked);
        }
    }

    @Test
    @DisplayName("a compute engine that never finishes times out as UNKNOWN instead of hanging the build")
    void aSlowComputeEngineTimesOut() throws Exception {
        ceJson = "{\"task\":{\"status\":\"PENDING\"}}";
        long started = System.currentTimeMillis();
        AtomResult run = gateAtom()
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"true\","
                        + " \"gateTimeoutSeconds\": \"1\"}")
                .run();
        long elapsed = System.currentTimeMillis() - started;

        run.assertOk();
        assertEquals("UNKNOWN", run.field("qualityGate"));
        assertTrue(run.console().contains("compute engine: PENDING"), run.console());
        assertTrue(elapsed >= 1500, "the atom did not wait for its timeout: " + elapsed + "ms");
        assertTrue(elapsed < 30_000, "the atom waited far longer than its timeout: " + elapsed + "ms");
    }

    @Test
    @DisplayName("a gate answer without a status is UNKNOWN rather than an empty success")
    void aGateWithoutAStatusIsUnknown() throws Exception {
        gateJson = "{\"projectStatus\":{}}";
        AtomResult run = gateAtom()
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"true\"}")
                .run();

        run.assertOk();
        assertEquals("UNKNOWN", run.field("qualityGate"));
    }

    @Test
    @DisplayName("the gate is skipped when the report task has no ceTaskUrl")
    void skipsTheGateWithoutACEUrl() throws Exception {
        Path argv = scratch.resolve("argv.txt");
        AtomResult run = atom.bin("sonarner", """
                        #!/bin/sh
                        printf '%s\\n' "$@" > "$SMOKE_ARGV"
                        mkdir -p .scannerwork
                        printf 'projectKey=parcel\\ndashboardUrl=http://sonar.local/dashboard?id=parcel\\n' \
                          > .scannerwork/report-task.txt
                        exit 0
                        """)
                .env("SMOKE_ARGV", argv.toString())
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"true\"}")
                .run();

        run.assertOk();
        assertEquals("", run.field("qualityGate"), "no gate result when there is no task to poll");
        assertEquals("http://sonar.local/dashboard?id=parcel", run.field("dashboardUrl"));
        assertEquals(0, asked.size(), "nothing should be polled: " + asked);
    }

    @Test
    @DisplayName("without a report task the run still succeeds, with nothing to link")
    void succeedsWithoutAReportTask() throws Exception {
        Path argv = scratch.resolve("argv.txt");
        AtomResult run = atom.bin("sonarner", "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$SMOKE_ARGV\"\nexit 0\n")
                .env("SMOKE_ARGV", argv.toString())
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"true\"}")
                .run();

        run.assertOk();
        assertEquals("", run.field("dashboardUrl"));
        assertEquals("", run.field("taskId"));
        assertEquals("", run.field("qualityGate"));
    }

    @Test
    @DisplayName("a scanner that exits non-zero fails the step with the tail of what it printed")
    void reportsAScannerFailure() throws Exception {
        AtomResult run = atom.bin("sonarner", """
                        #!/bin/sh
                        echo 'starting the scan'
                        echo 'ERROR: the token is not valid'
                        exit 3
                        """)
                .input("{\"projectKey\": \"parcel\", \"waitForQualityGate\": \"false\"}")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("sonarner exited 3"), output);
        assertTrue(output.contains("ERROR: the token is not valid"), output);
        assertTrue(output.contains("starting the scan"), "the tail should keep the nearby lines too: " + output);
    }

    @Test
    @DisplayName("a scanner that cannot be started says which binary and what to do")
    void reportsAMissingScanner() throws Exception {
        AtomResult run = atom.input("{\"projectKey\": \"parcel\", \"scanner\": \"/no/such/sonarner\","
                        + " \"waitForQualityGate\": \"false\"}")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("could not start /no/such/sonarner"), output);
        assertTrue(output.contains("install the scanner"), output);
    }

    @Test
    @DisplayName("exclusions and several extra arguments are passed through, and the token input is a fallback")
    void passesExclusionsAndExtraArguments() throws Exception {
        Path argv = scratch.resolve("argv.txt");
        Path tokenSeen = scratch.resolve("token.txt");
        AtomResult run = atom.bin("sonarner", FAKE_SONARNER)
                .env("SMOKE_ARGV", argv.toString())
                .env("SMOKE_TOKEN", tokenSeen.toString())
                .env("SMOKE_PROJECT", "parcel")
                .input("{\"projectKey\": \"parcel\", \"hostUrl\": \"http://sonar.local\","
                        + " \"exclusions\": \"**/generated/**,**/vendor/**\","
                        + " \"extraArgs\": \"-Dsonar.java.binaries=target  -Dsonar.scm.disabled=true\","
                        + " \"waitForQualityGate\": \"false\", \"token\": \"plain-token\"}")
                .run();

        run.assertOk();
        String arguments = Files.readString(argv, StandardCharsets.UTF_8);
        assertTrue(arguments.contains("-Dsonar.exclusions=**/generated/**,**/vendor/**"), arguments);
        assertTrue(arguments.contains("-Dsonar.java.binaries=target"), arguments);
        assertTrue(arguments.contains("-Dsonar.scm.disabled=true"), arguments);
        assertFalse(arguments.contains("plain-token"), "the token must not be on the command line:\n" + arguments);
        assertTrue(Files.isRegularFile(tokenSeen), "the token input must still reach the scanner's environment");
        assertFalse(run.console().contains("plain-token"), "the token must not be logged:\n" + run.console());
    }
}
