package com.example.study_cicd_monitoring.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupDbLogRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDbLogRunner.class);

    private final Environment environment;

    public StartupDbLogRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String url = environment.getProperty("spring.datasource.url", "");
        String username = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");

        String maskedPassword = (password == null || password.isBlank())
                ? "(empty)"
                : "****(len=" + password.length() + ")";

        log.info("[STARTUP-DB] url={}", url);
        log.info("[STARTUP-DB] username={}", username);
        log.info("[STARTUP-DB] password={}", maskedPassword);
    }
}
