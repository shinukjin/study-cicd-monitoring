package com.example.study_cicd_monitoring.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tag;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class MonitoringSummaryService {

    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public MonitoringSummaryService(MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate, Environment environment) {
        this.meterRegistry = meterRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    public Map<String, Object> getSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("db", dbSummary());
        result.put("cpu", cpuSummary());
        result.put("heap", heapSummary());
        result.put("apm", apmSummary());
        return result;
    }

    private Map<String, Object> dbSummary() {
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("status", dbHealthStatus());
        db.put("activeConnections", gaugeValue("hikaricp.connections.active"));
        db.put("idleConnections", gaugeValue("hikaricp.connections.idle"));
        db.put("pendingConnections", gaugeValue("hikaricp.connections.pending"));
        db.put("maxConnections", gaugeValue("hikaricp.connections.max"));
        return db;
    }

    private Map<String, Object> cpuSummary() {
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("processUsage", percent(gaugeValue("process.cpu.usage")));
        cpu.put("systemUsage", percent(gaugeValue("system.cpu.usage")));
        cpu.put("systemLoadAverage1m", round(gaugeValue("system.load.average.1m")));
        return cpu;
    }

    private Map<String, Object> heapSummary() {
        Map<String, Object> heap = new LinkedHashMap<>();
        double heapUsed = gaugeValue("jvm.memory.used", "area", "heap");
        double heapMax = gaugeValue("jvm.memory.max", "area", "heap");
        double heapCommitted = gaugeValue("jvm.memory.committed", "area", "heap");
        heap.put("usedMb", mb(heapUsed));
        heap.put("maxMb", mb(heapMax));
        heap.put("committedMb", mb(heapCommitted));
        heap.put("usagePercent", heapMax > 0 ? round((heapUsed / heapMax) * 100.0) : null);
        return heap;
    }

    private Map<String, Object> apmSummary() {
        Map<String, Object> apm = new LinkedHashMap<>();
        Collection<Timer> timers = meterRegistry.find("http.server.requests").timers();
        long totalCount = 0L;
        double totalNanos = 0.0;
        double maxMillis = 0.0;
        for (Timer timer : timers) {
            totalCount += timer.count();
            totalNanos += timer.totalTime(TimeUnit.NANOSECONDS);
            maxMillis = Math.max(maxMillis, timer.max(TimeUnit.MILLISECONDS));
        }
        apm.put("httpRequestCount", totalCount);
        apm.put("httpMeanLatencyMs", totalCount > 0 ? round((totalNanos / totalCount) / 1_000_000.0) : 0.0);
        apm.put("httpMaxLatencyMs", round(maxMillis));
        apm.put("topEndpoints", topEndpointLatency(timers, 7));
        apm.put("tracingEnabled", Boolean.parseBoolean(environment.getProperty("management.tracing.enabled", "false")));
        apm.put("otlpEndpoint", environment.getProperty("management.otlp.tracing.endpoint", ""));
        return apm;
    }

    private List<Map<String, Object>> topEndpointLatency(Collection<Timer> timers, int topN) {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        for (Timer timer : timers) {
            String uri = null;
            for (Tag tag : timer.getId().getTags()) {
                if ("uri".equals(tag.getKey())) {
                    uri = tag.getValue();
                    break;
                }
            }
            if (uri == null || "UNKNOWN".equals(uri)) {
                continue;
            }

            long count = timer.count();
            if (count <= 0) {
                continue;
            }

            double meanMs = (timer.totalTime(TimeUnit.NANOSECONDS) / count) / 1_000_000.0;
            double maxMs = timer.max(TimeUnit.MILLISECONDS);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uri", uri);
            item.put("count", count);
            item.put("meanMs", round(meanMs));
            item.put("maxMs", round(maxMs));
            endpoints.add(item);
        }

        endpoints.sort(Comparator.comparingDouble(m -> -((Number) m.get("meanMs")).doubleValue()));
        if (endpoints.size() > topN) {
            return new ArrayList<>(endpoints.subList(0, topN));
        }
        return endpoints;
    }

    private String dbHealthStatus() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return (value != null && value == 1) ? "UP" : "UNKNOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private Double gaugeValue(String name, String... tags) {
        Gauge gauge = meterRegistry.find(name).tags(tags).gauge();
        if (gauge == null) {
            return null;
        }
        return round(gauge.value());
    }

    private Double percent(Double ratio) {
        if (ratio == null) {
            return null;
        }
        return round(ratio * 100.0);
    }

    private Double mb(Double bytes) {
        if (bytes == null) {
            return null;
        }
        return round(bytes / 1024.0 / 1024.0);
    }

    private Double round(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
