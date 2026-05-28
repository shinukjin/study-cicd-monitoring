package com.example.study_cicd_monitoring.monitoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/monitoring")
public class MonitoringApiController {

    private final MonitoringSummaryService monitoringSummaryService;

    public MonitoringApiController(MonitoringSummaryService monitoringSummaryService) {
        this.monitoringSummaryService = monitoringSummaryService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return monitoringSummaryService.getSummary();
    }

    @GetMapping("/db/probe")
    public Map<String, Object> dbProbe(@RequestParam(defaultValue = "manual-probe") String probeTag) {
        return monitoringSummaryService.runDbProbe(probeTag);
    }
}

