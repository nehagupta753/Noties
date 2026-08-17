package com.noties.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupBanner {

    private final Environment env;

    public StartupBanner(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port = env.getProperty("server.port", "3000");
        System.out.println("Notes is running at http://localhost:" + port);

        String apiKey = EnvConfig.get("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("your_gemini_api_key_here")) {
            System.out.println("Warning: GEMINI_API_KEY is not set. Please add it to your .env file.");
        }
    }
}
