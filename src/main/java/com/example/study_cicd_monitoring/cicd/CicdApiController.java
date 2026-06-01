package com.example.study_cicd_monitoring.cicd;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cicd")
public class CicdApiController {

    private final CicdService cicdService;

    public CicdApiController(CicdService cicdService) {
        this.cicdService = cicdService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return cicdService.summary();
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(
            @RequestParam(defaultValue = "") String workflow,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return cicdService.runs(workflow, limit);
    }

    @GetMapping("/runs/{runId}/jobs")
    public List<Map<String, Object>> runJobs(@PathVariable String runId) {
        return cicdService.runJobs(CicdPathIdParser.parseRunId(runId));
    }

    @GetMapping("/jobs/{jobId}/logs-url")
    public Map<String, Object> jobLogsUrl(@PathVariable String jobId) {
        return cicdService.jobLogsUrl(CicdPathIdParser.parseJobId(jobId));
    }

    @GetMapping("/jobs/{jobId}/logs-preview")
    public Map<String, Object> jobLogsPreview(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "120") int lines
    ) {
        return cicdService.jobLogsPreview(CicdPathIdParser.parseJobId(jobId), lines);
    }

    @PostMapping("/deploy")
    public Map<String, Object> deploy(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(defaultValue = "") String approvalKey,
            Principal principal
    ) {
        String imageTag = null;
        if (body != null && body.get("imageTag") != null) {
            imageTag = String.valueOf(body.get("imageTag"));
        }
        String operator = principal == null ? "unknown" : principal.getName();
        return cicdService.triggerDeploy(imageTag, approvalKey, operator);
    }

    @PostMapping("/deploy/requests")
    public Map<String, Object> createDeployRequest(
            @RequestBody(required = false) Map<String, Object> body,
            Principal principal
    ) {
        String imageTag = body != null && body.get("imageTag") != null ? String.valueOf(body.get("imageTag")) : "latest";
        String note = body != null && body.get("note") != null ? String.valueOf(body.get("note")) : "";
        String operator = principal == null ? "unknown" : principal.getName();
        return cicdService.createDeployRequest(imageTag, note, operator);
    }

    @GetMapping("/deploy/requests")
    public List<Map<String, Object>> deployRequests(@RequestParam(defaultValue = "20") int limit) {
        return cicdService.deployRequests(limit);
    }

    @PostMapping("/deploy/requests/{requestId}/approve")
    public Map<String, Object> approveDeployRequest(
            @PathVariable String requestId,
            @RequestBody(required = false) Map<String, Object> body,
            Principal principal
    ) {
        String approvalKey = body != null && body.get("approvalKey") != null ? String.valueOf(body.get("approvalKey")) : "";
        String operator = principal == null ? "unknown" : principal.getName();
        return cicdService.approveDeployRequest(CicdPathIdParser.parseRequestId(requestId), approvalKey, operator);
    }

    @PostMapping("/deploy/requests/{requestId}/reject")
    public Map<String, Object> rejectDeployRequest(
            @PathVariable String requestId,
            @RequestBody(required = false) Map<String, Object> body,
            Principal principal
    ) {
        String note = body != null && body.get("note") != null ? String.valueOf(body.get("note")) : "";
        String operator = principal == null ? "unknown" : principal.getName();
        return cicdService.rejectDeployRequest(CicdPathIdParser.parseRequestId(requestId), operator, note);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        return cicdService.auditLogs(limit);
    }
}
