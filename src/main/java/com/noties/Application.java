package com.noties;

import com.noties.config.EnvConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // Load environment variables before starting Spring Boot
        EnvConfig.load();
        SpringApplication.run(Application.class, args);
    }
}
