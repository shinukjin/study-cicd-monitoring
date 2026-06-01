package com.example.study_cicd_monitoring.cicd;

import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class CicdService {

    private static final String USER_AGENT = "study-cicd-monitoring";
    private static final String DEFAULT_WORKFLOW = "Build and Push Image";
    private static final int MAX_AUDIT_SIZE = 200;
    private static final String AUDIT_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS cicd_deploy_audit (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              requested_at VARCHAR(64) NOT NULL,
              action VARCHAR(32) NOT NULL,
              operator_name VARCHAR(128) NOT NULL,
              image_tag VARCHAR(255) NOT NULL,
              status VARCHAR(32) NOT NULL,
              message TEXT
            )
            """;
    private static final String REQUEST_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS cicd_deploy_request (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              requested_at VARCHAR(64) NOT NULL,
              requested_by VARCHAR(128) NOT NULL,
              image_tag VARCHAR(255) NOT NULL,
              status VARCHAR(32) NOT NULL,
              note VARCHAR(500),
              approved_at VARCHAR(64),
              approved_by VARCHAR(128),
              processed_at VARCHAR(64),
              process_message TEXT
            )
            """;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();
    private final Deque<Map<String, Object>> auditLogs = new ConcurrentLinkedDeque<>();
    private final Deque<Map<String, Object>> requestLogs = new ConcurrentLinkedDeque<>();
    private final AtomicLong requestSeq = new AtomicLong(1L);
    private final JdbcTemplate jdbcTemplate;

    @Value("${cicd.github.api-base}")
    private String githubApiBase;
    @Value("${cicd.github.owner}")
    private String owner;
    @Value("${cicd.github.repo}")
    private String repo;
    @Value("${cicd.github.token:}")
    private String token;
    @Value("${cicd.github.deploy-workflow:deploy.yml}")
    private String deployWorkflow;
    @Value("${cicd.github.deploy-ref:main}")
    private String deployRef;
    @Value("${cicd.deploy.approval-key:}")
    private String deployApprovalKey;
    @Value("${cicd.deploy.admin-users:}")
    private String deployAdminUsers;

    public CicdService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        try {
            this.jdbcTemplate.execute(AUDIT_TABLE_SQL);
            this.jdbcTemplate.execute(REQUEST_TABLE_SQL);
        } catch (Exception ignored) {
            // DB unavailable: keep in-memory audit as fallback.
        }
    }

    public Map<String, Object> summary() {
        if (!isRepoConfigured()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("repository", "-");
            fallback.put("generatedAt", Instant.now().toString());
            fallback.put("configured", false);
            fallback.put("runs", List.of());
            fallback.put("latestByPipeline", List.of());
            fallback.put("deployableTags", List.of("latest"));
            fallback.put("message", "Set CICD_GITHUB_OWNER and CICD_GITHUB_REPO");
            return fallback;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repository", owner + "/" + repo);
        result.put("generatedAt", Instant.now().toString());
        result.put("configured", true);
        try {
            List<Map<String, Object>> runs = runs(DEFAULT_WORKFLOW, 20);
            result.put("runs", runs);
            result.put("latestByPipeline", latestByPipeline(runs));
            result.put("deployableTags", deployableTags(runs));
        } catch (ResponseStatusException e) {
            result.put("runs", List.of());
            result.put("latestByPipeline", List.of());
            result.put("deployableTags", List.of("latest"));
            result.put("message", "GitHub API unavailable: " + (e.getReason() == null ? "unknown error" : e.getReason()));
        }
        return result;
    }

    public List<Map<String, Object>> runs(String workflowName, int limit) {
        if (!isRepoConfigured()) {
            return List.of();
        }
        try {
            int perPage = Math.max(1, Math.min(limit, 100));
            String path = "/repos/" + owner + "/" + repo + "/actions/runs?per_page=" + perPage;
            Map<String, Object> response = getJson(path);
            List<Map<String, Object>> all = castListOfMap(response.get("workflow_runs"));
            String normalized = (workflowName == null || workflowName.isBlank()) ? "" : workflowName.trim();
            List<Map<String, Object>> filtered = normalized.isEmpty()
                    ? all
                    : all.stream()
                    .filter(run -> normalized.equals(String.valueOf(run.get("name"))))
                    .toList();
            return filtered.stream().map(this::toRunSummary).toList();
        } catch (ResponseStatusException e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> runJobs(long runId) {
        if (!isRepoConfigured()) {
            return List.of();
        }
        try {
            String path = "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/jobs?per_page=100";
            Map<String, Object> response = getJson(path);
            return castListOfMap(response.get("jobs")).stream().map(this::toJobSummary).toList();
        } catch (ResponseStatusException e) {
            return List.of();
        }
    }

    public Map<String, Object> jobLogsUrl(long jobId) {
        ensureRepoConfigured();
        String path = "/repos/" + owner + "/" + repo + "/actions/jobs/" + jobId + "/logs";
        URI uri = URI.create(trimSlash(githubApiBase) + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT);
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", jobId);
            result.put("statusCode", response.statusCode());
            result.put("downloadUrl", response.headers().firstValue("location").orElse(""));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Job log request interrupted");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Job log request failed: " + e.getMessage());
        }
    }

    public Map<String, Object> triggerDeploy(String imageTag, String approvalKey, String operator) {
        ensureRepoConfigured();
        String op = normalizeOperator(operator);
        ensureOperatorAllowed(op);
        if (token == null || token.isBlank()) {
            recordAudit("DEPLOY", op, imageTag, "FAILED", "CICD_GITHUB_TOKEN is required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CICD_GITHUB_TOKEN is required for deploy");
        }
        if (deployApprovalKey != null && !deployApprovalKey.isBlank()) {
            if (approvalKey == null || !deployApprovalKey.equals(approvalKey)) {
                recordAudit("DEPLOY", op, imageTag, "DENIED", "Invalid deploy approval key");
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid deploy approval key");
            }
        }
        String tag = (imageTag == null || imageTag.isBlank()) ? "latest" : imageTag.trim();
        String path = "/repos/" + owner + "/" + repo + "/actions/workflows/" + encode(deployWorkflow) + "/dispatches";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ref", deployRef);
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("image_tag", tag);
        payload.put("inputs", inputs);

        try {
            postNoContent(path, payload);
        } catch (ResponseStatusException e) {
            recordAudit("DEPLOY", op, tag, "FAILED", e.getReason());
            throw e;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "REQUESTED");
        result.put("repository", owner + "/" + repo);
        result.put("workflow", deployWorkflow);
        result.put("ref", deployRef);
        result.put("imageTag", tag);
        result.put("operator", op);
        result.put("requestedAt", Instant.now().toString());
        recordAudit("DEPLOY", op, tag, "REQUESTED", "workflow dispatch accepted");
        return result;
    }

    public List<Map<String, Object>> auditLogs(int limit) {
        int max = Math.max(1, Math.min(limit, 200));
        try {
            String sql = """
                    SELECT requested_at, action, operator_name, image_tag, status, message
                    FROM cicd_deploy_audit
                    ORDER BY id DESC
                    LIMIT %d
                    """.formatted(max);
            return jdbcTemplate.queryForList(sql).stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("requestedAt", String.valueOf(row.get("requested_at")));
                item.put("action", String.valueOf(row.get("action")));
                item.put("operator", String.valueOf(row.get("operator_name")));
                item.put("imageTag", String.valueOf(row.get("image_tag")));
                item.put("status", String.valueOf(row.get("status")));
                item.put("message", row.get("message") == null ? "" : String.valueOf(row.get("message")));
                return item;
            }).toList();
        } catch (Exception ignored) {
            // DB unavailable: fallback to memory.
        }
        return auditLogs.stream()
                .sorted(Comparator.comparing(m -> String.valueOf(m.get("requestedAt")), Comparator.reverseOrder()))
                .limit(max)
                .toList();
    }

    public Map<String, Object> createDeployRequest(String imageTag, String note, String operator) {
        String tag = (imageTag == null || imageTag.isBlank()) ? "latest" : imageTag.trim();
        String op = normalizeOperator(operator);
        ensureOperatorAllowed(op);
        String now = Instant.now().toString();
        long requestId;
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("requestedAt", now);
        fallback.put("requestedBy", op);
        fallback.put("imageTag", tag);
        fallback.put("status", "PENDING");
        fallback.put("note", note == null ? "" : note);
        fallback.put("approvedAt", "");
        fallback.put("approvedBy", "");
        fallback.put("processedAt", "");
        fallback.put("processMessage", "memory-mirror");
        try {
            jdbcTemplate.update(
                    "INSERT INTO cicd_deploy_request (requested_at, requested_by, image_tag, status, note) VALUES (?, ?, ?, ?, ?)",
                    now, op, tag, "PENDING", note == null ? "" : note
            );
            requestId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id),0) FROM cicd_deploy_request", Long.class);
        } catch (Exception e) {
            requestId = requestSeq.getAndIncrement();
            fallback.put("processMessage", "memory-fallback");
        }
        fallback.put("id", requestId);
        requestLogs.addFirst(fallback);
        while (requestLogs.size() > MAX_AUDIT_SIZE) {
            requestLogs.removeLast();
        }
        recordAudit("DEPLOY_REQUEST", op, tag, "PENDING", "배포 요청 생성");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("status", "PENDING");
        result.put("requestedAt", now);
        result.put("requestedBy", op);
        result.put("imageTag", tag);
        result.put("note", note == null ? "" : note);
        return result;
    }

    public List<Map<String, Object>> deployRequests(int limit) {
        int max = Math.max(1, Math.min(limit, 100));
        try {
            String sql = """
                    SELECT id, requested_at, requested_by, image_tag, status, note, approved_at, approved_by, processed_at, process_message
                    FROM cicd_deploy_request
                    ORDER BY id DESC
                    LIMIT %d
                    """.formatted(max);
            List<Map<String, Object>> dbRows = jdbcTemplate.queryForList(sql).stream().map(this::mapDeployRequestRow).toList();
            if (!dbRows.isEmpty()) {
                return dbRows;
            }
            return requestLogs.stream().limit(max).toList();
        } catch (Exception e) {
            return requestLogs.stream().limit(max).toList();
        }
    }

    public Map<String, Object> approveDeployRequest(long requestId, String approvalKey, String operator) {
        String op = normalizeOperator(operator);
        ensureOperatorAllowed(op);
        Map<String, Object> req = getDeployRequestById(requestId);
        if (req.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "요청을 찾을 수 없습니다.");
        }
        if (!"PENDING".equals(String.valueOf(req.get("status")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PENDING 상태만 승인할 수 있습니다.");
        }
        String imageTag = String.valueOf(req.get("imageTag"));
        String approvedAt = Instant.now().toString();
        safeUpdateRequestStatus("APPROVED", approvedAt, op, requestId, null, null);
        recordAudit("DEPLOY_APPROVE", op, imageTag, "APPROVED", "요청 승인");
        try {
            Map<String, Object> deployResult = triggerDeploy(imageTag, approvalKey, op);
            safeUpdateRequestStatus("DISPATCHED", null, null, requestId, Instant.now().toString(), "workflow dispatch accepted");
            Map<String, Object> result = new LinkedHashMap<>(deployResult);
            result.put("requestId", requestId);
            result.put("requestStatus", "DISPATCHED");
            return result;
        } catch (ResponseStatusException e) {
            safeUpdateRequestStatus("FAILED", null, null, requestId, Instant.now().toString(), e.getReason());
            throw e;
        }
    }

    public Map<String, Object> rejectDeployRequest(long requestId, String operator, String note) {
        String op = normalizeOperator(operator);
        ensureOperatorAllowed(op);
        Map<String, Object> req = getDeployRequestById(requestId);
        if (req.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "요청을 찾을 수 없습니다.");
        }
        if (!"PENDING".equals(String.valueOf(req.get("status")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PENDING 상태만 반려할 수 있습니다.");
        }
        String message = (note == null || note.isBlank()) ? "반려됨" : note.trim();
        safeUpdateRequestStatus("REJECTED", Instant.now().toString(), op, requestId, Instant.now().toString(), message);
        recordAudit("DEPLOY_REJECT", op, String.valueOf(req.get("imageTag")), "REJECTED", message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("status", "REJECTED");
        result.put("message", message);
        result.put("processedBy", op);
        return result;
    }

    public Map<String, Object> jobLogsPreview(long jobId, int lineLimit) {
        ensureRepoConfigured();
        int maxLines = Math.max(20, Math.min(lineLimit, 500));
        Map<String, Object> logMeta = jobLogsUrl(jobId);
        String downloadUrl = String.valueOf(logMeta.get("downloadUrl"));
        if (downloadUrl.isBlank()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", jobId);
            result.put("lines", List.of("No downloadable logs yet"));
            result.put("entries", List.of());
            return result;
        }

        URI uri = URI.create(downloadUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("User-Agent", USER_AGENT)
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Log download failed: " + response.statusCode());
            }
            return unzipLogPreview(jobId, response.body(), maxLines);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Log preview interrupted");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Log preview failed: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> latestByPipeline(List<Map<String, Object>> runs) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> run : runs) {
            String name = String.valueOf(run.get("name"));
            if (!latest.containsKey(name)) {
                latest.put(name, run);
            }
        }
        return new ArrayList<>(latest.values());
    }

    private List<String> deployableTags(List<Map<String, Object>> runs) {
        List<String> tags = new ArrayList<>();
        tags.add("latest");
        for (Map<String, Object> run : runs) {
            if (!"Build and Push Image".equals(String.valueOf(run.get("name")))) {
                continue;
            }
            if (!"success".equals(String.valueOf(run.get("conclusion")))) {
                continue;
            }
            Object shaObj = run.get("head_sha");
            if (shaObj == null) {
                continue;
            }
            String sha = String.valueOf(shaObj);
            if (!sha.isBlank() && !tags.contains(sha)) {
                tags.add(sha);
            }
            if (tags.size() >= 8) {
                break;
            }
        }
        return tags;
    }

    private void ensureRepoConfigured() {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Set CICD_GITHUB_OWNER and CICD_GITHUB_REPO environment variables"
            );
        }
    }

    private boolean isRepoConfigured() {
        return owner != null && !owner.isBlank() && repo != null && !repo.isBlank();
    }

    private Map<String, Object> getJson(String path) {
        URI uri = URI.create(trimSlash(githubApiBase) + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT);
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub API error " + status + ": " + response.body()
                );
            }
            return jsonParser.parseMap(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub API request interrupted");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub API request failed: " + e.getMessage());
        }
    }

    private void postNoContent(String path, Object payload) {
        URI uri = URI.create(trimSlash(githubApiBase) + path);
        String body = toJson(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT);
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 204) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Deploy dispatch failed " + status + ": " + response.body()
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Deploy dispatch interrupted");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Deploy dispatch failed: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> castListOfMap(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> casted = new HashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    casted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                result.add(casted);
            }
        }
        return result;
    }

    private Map<String, Object> toRunSummary(Map<String, Object> run) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", run.get("id"));
        item.put("name", run.get("name"));
        item.put("status", run.get("status"));
        item.put("conclusion", run.get("conclusion"));
        item.put("head_sha", run.get("head_sha"));
        item.put("head_branch", run.get("head_branch"));
        item.put("event", run.get("event"));
        item.put("run_number", run.get("run_number"));
        item.put("created_at", run.get("created_at"));
        item.put("updated_at", run.get("updated_at"));
        item.put("html_url", run.get("html_url"));
        return item;
    }

    private Map<String, Object> toJobSummary(Map<String, Object> job) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", job.get("id"));
        item.put("name", job.get("name"));
        item.put("status", job.get("status"));
        item.put("conclusion", job.get("conclusion"));
        item.put("started_at", job.get("started_at"));
        item.put("completed_at", job.get("completed_at"));
        item.put("html_url", job.get("html_url"));
        item.put("steps", summarizeSteps(job.get("steps")));
        return item;
    }

    private List<Map<String, Object>> summarizeSteps(Object rawSteps) {
        if (!(rawSteps instanceof List<?> steps)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object stepObj : steps) {
            if (!(stepObj instanceof Map<?, ?> step)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", step.get("name"));
            item.put("status", step.get("status"));
            item.put("conclusion", step.get("conclusion"));
            item.put("number", step.get("number"));
            item.put("started_at", step.get("started_at"));
            item.put("completed_at", step.get("completed_at"));
            result.add(item);
        }
        return result;
    }

    private String trimSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(toJson(String.valueOf(entry.getKey())));
                builder.append(':');
                builder.append(toJson(entry.getValue()));
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(toJson(list.get(i)));
            }
            builder.append(']');
            return builder.toString();
        }
        return toJson(String.valueOf(value));
    }

    private void ensureOperatorAllowed(String operator) {
        Set<String> admins = parseAdmins();
        if (admins.isEmpty()) {
            return;
        }
        if (!admins.contains(operator)) {
            recordAudit("DEPLOY", operator, "-", "DENIED", "Operator not allowed");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operator is not allowed");
        }
    }

    private Map<String, Object> getDeployRequestById(long id) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, requested_at, requested_by, image_tag, status, note, approved_at, approved_by, processed_at, process_message FROM cicd_deploy_request WHERE id=?",
                    id
            );
            if (rows.isEmpty()) {
                return requestLogs.stream().filter(x -> String.valueOf(x.get("id")).equals(String.valueOf(id))).findFirst().orElse(Map.of());
            }
            return mapDeployRequestRow(rows.get(0));
        } catch (Exception e) {
            return requestLogs.stream().filter(x -> String.valueOf(x.get("id")).equals(String.valueOf(id))).findFirst().orElse(Map.of());
        }
    }

    private Map<String, Object> mapDeployRequestRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("id"));
        item.put("requestedAt", String.valueOf(row.get("requested_at")));
        item.put("requestedBy", String.valueOf(row.get("requested_by")));
        item.put("imageTag", String.valueOf(row.get("image_tag")));
        item.put("status", String.valueOf(row.get("status")));
        item.put("note", row.get("note") == null ? "" : String.valueOf(row.get("note")));
        item.put("approvedAt", row.get("approved_at") == null ? "" : String.valueOf(row.get("approved_at")));
        item.put("approvedBy", row.get("approved_by") == null ? "" : String.valueOf(row.get("approved_by")));
        item.put("processedAt", row.get("processed_at") == null ? "" : String.valueOf(row.get("processed_at")));
        item.put("processMessage", row.get("process_message") == null ? "" : String.valueOf(row.get("process_message")));
        return item;
    }

    private void safeUpdateRequestStatus(String status, String approvedAt, String approvedBy, long requestId, String processedAt, String processMessage) {
        try {
            if (approvedAt != null && approvedBy != null) {
                jdbcTemplate.update("UPDATE cicd_deploy_request SET status=?, approved_at=?, approved_by=? WHERE id=?", status, approvedAt, approvedBy, requestId);
            }
            if (processedAt != null || processMessage != null) {
                jdbcTemplate.update("UPDATE cicd_deploy_request SET status=?, processed_at=?, process_message=? WHERE id=?", status, processedAt, processMessage, requestId);
            }
        } catch (Exception ignored) {
            requestLogs.stream()
                    .filter(x -> String.valueOf(x.get("id")).equals(String.valueOf(requestId)))
                    .findFirst()
                    .ifPresent(x -> {
                        x.put("status", status);
                        if (approvedAt != null) x.put("approvedAt", approvedAt);
                        if (approvedBy != null) x.put("approvedBy", approvedBy);
                        if (processedAt != null) x.put("processedAt", processedAt);
                        if (processMessage != null) x.put("processMessage", processMessage);
                    });
        }
    }

    private Set<String> parseAdmins() {
        if (deployAdminUsers == null || deployAdminUsers.isBlank()) {
            return Set.of();
        }
        String[] parts = deployAdminUsers.split(",");
        Set<String> admins = new HashSet<>();
        for (String part : parts) {
            String v = part.trim();
            if (!v.isBlank()) {
                admins.add(v);
            }
        }
        return admins;
    }

    private String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "unknown";
        }
        return operator.trim();
    }

    private void recordAudit(String action, String operator, String imageTag, String status, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("requestedAt", Instant.now().toString());
        item.put("action", action);
        item.put("operator", normalizeOperator(operator));
        item.put("imageTag", imageTag == null || imageTag.isBlank() ? "-" : imageTag);
        item.put("status", status);
        item.put("message", message == null ? "" : message);
        auditLogs.addFirst(item);
        while (auditLogs.size() > MAX_AUDIT_SIZE) {
            auditLogs.removeLast();
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO cicd_deploy_audit (requested_at, action, operator_name, image_tag, status, message) VALUES (?, ?, ?, ?, ?, ?)",
                    item.get("requestedAt"),
                    item.get("action"),
                    item.get("operator"),
                    item.get("imageTag"),
                    item.get("status"),
                    item.get("message")
            );
        } catch (Exception ignored) {
            // DB unavailable: keep in-memory only.
        }
    }

    private Map<String, Object> unzipLogPreview(long jobId, byte[] zipBytes, int maxLines) throws IOException {
        List<String> entries = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try (InputStream in = new java.io.ByteArrayInputStream(zipBytes);
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.add(entry.getName());
                String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                List<String> split = List.of(content.split("\\R"));
                lines.addAll(split);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        List<String> tail;
        if (lines.size() > maxLines) {
            tail = lines.subList(lines.size() - maxLines, lines.size());
        } else {
            tail = lines;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("entries", entries);
        result.put("lineCount", lines.size());
        result.put("lines", Collections.unmodifiableList(new ArrayList<>(tail)));
        return result;
    }
}
