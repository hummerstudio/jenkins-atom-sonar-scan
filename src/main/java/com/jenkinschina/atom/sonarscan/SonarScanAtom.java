package com.jenkinschina.atom.sonarscan;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sonar as an atom: run the scanner, then optionally hold the build until the quality gate answers.
 *
 * <p>The scanner writes {@code .scannerwork/report-task.txt} with the dashboard URL and the compute-engine task;
 * reading it back is how the plugin shows a link instead of guessing one.
 */
public class SonarScanAtom implements AtomTask {

    private static final Pattern VALUE = Pattern.compile("^([A-Za-z0-9_.]+)=(.*)$", Pattern.MULTILINE);

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String scanner = context.getParam("scanner", "sonar-scanner");
        String projectKey = context.getParam("projectKey", "");
        String hostUrl = context.getParam("hostUrl", "");
        String sources = context.getParam("sources", ".");
        String exclusions = context.getParam("exclusions", "");
        String extraArgs = context.getParam("extraArgs", "");
        String token = orEmpty(context.getSecret("sonarToken"));
        if (token.isBlank()) {
            token = context.getParam("token", "");
        }
        if (projectKey.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "projectKey is required");
        }

        List<String> command = new ArrayList<>();
        command.add(scanner);
        command.add("-Dsonar.projectKey=" + projectKey);
        command.add("-Dsonar.sources=" + sources);
        if (!hostUrl.isBlank()) {
            command.add("-Dsonar.host.url=" + hostUrl);
        }
        if (!exclusions.isBlank()) {
            command.add("-Dsonar.exclusions=" + exclusions);
        }
        for (String arg : split(extraArgs)) {
            command.add(arg);
        }

        // the token goes through the environment, never the command line: argv is visible in `ps`
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        if (!token.isBlank()) {
            builder.environment().put("SONAR_TOKEN", token);
            builder.environment().put("SONAR_LOGIN", token);
        }
        context.log("[sonar] " + String.join(" ", command));

        Process process = builder.start();
        String output = drain(process, context);
        int code = process.waitFor();
        if (code != 0) {
            return AtomResult.error(ErrorType.THIRD_PART, 40002,
                    "sonar-scanner exited " + code + ": " + tail(output));
        }

        Path reportTask = workspace.resolve(".scannerwork/report-task.txt");
        String dashboard = "";
        String taskUrl = "";
        String taskId = "";
        if (Files.isRegularFile(reportTask)) {
            String text = Files.readString(reportTask, StandardCharsets.UTF_8);
            dashboard = value(text, "dashboardUrl");
            taskUrl = value(text, "ceTaskUrl");
            taskId = value(text, "ceTaskId");
        }

        AtomResult result = AtomResult.success()
                .data("dashboardUrl", dashboard)
                .data("taskId", taskId);
        if (!dashboard.isBlank() && dashboard.startsWith("http")) {
            result.reportUrl("dashboard", "SonarQube dashboard", dashboard);
        }

        if (!context.getBooleanParam("waitForQualityGate", true) || taskUrl.isBlank()) {
            return result;
        }
        String gate = waitForGate(context, taskUrl, hostUrl, token, context.getIntParam("gateTimeoutSeconds", 300));
        result.data("qualityGate", gate);
        if ("ERROR".equals(gate) && context.getBooleanParam("failOnGate", true)) {
            return AtomResult.failure("the SonarQube quality gate failed")
                    .data("dashboardUrl", dashboard)
                    .data("qualityGate", gate);
        }
        return result;
    }

    /** Poll the compute engine, then the project's gate. Returns OK, ERROR or UNKNOWN (timeout). */
    private String waitForGate(AtomContext context, String taskUrl, String hostUrl, String token, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String status = "PENDING";
        while (System.currentTimeMillis() < deadline) {
            String body = get(taskUrl, token);
            status = field(body, "status");
            if ("SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status)) {
                break;
            }
            Thread.sleep(2000);
        }
        context.log("[sonar] compute engine: " + status);
        if (!"SUCCESS".equals(status)) {
            return "UNKNOWN";
        }
        String projectKey = context.getParam("projectKey", "");
        String base = hostUrl.isBlank() ? hostOf(taskUrl) : hostUrl.replaceAll("/+$", "");
        String gateUrl = base + "/api/qualitygates/project_status?projectKey="
                + java.net.URLEncoder.encode(projectKey, StandardCharsets.UTF_8);
        String gateStatus = field(get(gateUrl, token), "status");
        context.log("[sonar] quality gate: " + gateStatus);
        return gateStatus == null || gateStatus.isBlank() ? "UNKNOWN" : gateStatus;
    }

    private static String get(String url, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((token + ":").getBytes(StandardCharsets.UTF_8)));
            }
            return HttpClient.newHttpClient()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .body();
        } catch (Exception e) {
            return "";
        }
    }

    /** Minimal look at the JSON these two endpoints return: the first matching string value. */
    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String drain(Process process, AtomContext context) {
        StringBuilder all = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.log("[sonar] " + line);
                all.append(line).append('\n');
                if (all.length() > 200_000) {
                    all.delete(0, 100_000);
                }
            }
        } catch (Exception ignored) {
            // the process ended while reading
        }
        return all.toString();
    }

    private static String value(String text, String key) {
        Matcher matcher = VALUE.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(key)) {
                return matcher.group(2).trim();
            }
        }
        return "";
    }

    private static List<String> split(String spec) {
        List<String> parts = new ArrayList<>();
        if (spec == null) {
            return parts;
        }
        for (String part : spec.split("\\s+")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String tail(String text) {
        String[] lines = text == null ? new String[0] : text.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - 5); i < lines.length; i++) {
            out.append(lines[i]).append('\n');
        }
        return out.toString().strip().toLowerCase(Locale.ROOT).isEmpty() ? "(no output)" : out.toString().strip();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
