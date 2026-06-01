package com.example.study_cicd_monitoring.cicd;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class CicdPathIdParser {

    private CicdPathIdParser() {
    }

    static long parseRequestId(String raw) {
        return parsePositiveLong(raw, "requestId", "배포 요청 ID");
    }

    static long parseRunId(String raw) {
        return parsePositiveLong(raw, "runId", "워크플로 실행 ID");
    }

    static long parseJobId(String raw) {
        return parsePositiveLong(raw, "jobId", "Job ID");
    }

    private static long parsePositiveLong(String raw, String fieldName, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "가 비어 있습니다.");
        }
        if (!value.matches("\\d+")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + "는 숫자만 입력할 수 있습니다. (입력값: " + value + ")"
            );
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 너무 큽니다.");
        }
    }
}
