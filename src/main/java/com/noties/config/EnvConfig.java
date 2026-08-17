package com.noties.config;

import io.github.cdimascio.dotenv.Dotenv;

// Load values from .env
public class EnvConfig {

    private static Dotenv dotenv;

    public static void load() {
        dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        // Make environment values available to Spring
        for (String key : new String[]{"PORT", "GEMINI_API_KEY", "ELEVENLABS_API_KEY", "ELEVENLABS_VOICE_ID"}) {
            String val = dotenv.get(key);
            if (val != null && !val.isEmpty()) {
                System.setProperty(key, val);
            }
        }
    }

    public static String get(String key) {
        if (dotenv == null) load();
        return dotenv.get(key);
    }

    public static String get(String key, String fallback) {
        String val = get(key);
        return (val != null && !val.isEmpty()) ? val : fallback;
    }
}
