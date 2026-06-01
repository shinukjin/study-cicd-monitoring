package com.example.study_cicd_monitoring.cicd;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackageClasses = CicdApiController.class)
public class CicdExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName() == null ? "parameter" : ex.getName();
        Object value = ex.getValue();
        String requiredType = ex.getRequiredType() == null ? "unknown" : ex.getRequiredType().getSimpleName();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVALID_PATH_PARAMETER");
        body.put("message", name + " 값이 올바르지 않습니다. " + requiredType + " 형식의 숫자 ID를 입력하세요.");
        body.put("parameter", name);
        body.put("value", value);
        body.put("hint", "requestId에는 운영자 ID(ops)가 아니라 배포 요청 목록의 숫자 ID를 입력하세요.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "REQUEST_FAILED");
        body.put("message", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }
}
