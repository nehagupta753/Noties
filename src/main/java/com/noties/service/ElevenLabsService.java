package com.noties.service;

import com.noties.config.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class ElevenLabsService {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsService.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public boolean isConfigured() {
        String key = EnvConfig.get("ELEVENLABS_API_KEY");
        return key != null && !key.isBlank() && !key.equals("your_elevenlabs_api_key_here");
    }

    public byte[] synthesizeSpeech(String text) {
        String apiKey = EnvConfig.get("ELEVENLABS_API_KEY");
        String voiceId = EnvConfig.get("ELEVENLABS_VOICE_ID", "8baRIHZEGj62eS9YHzC6");

        // Clean up markdown, code blocks, and emojis before synthesis
        String clean = text
                .replaceAll("```[\\s\\S]*?```", " code example ")
                .replaceAll("`[^`]+`", "")
                .replaceAll("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}]", "")
                .replaceAll("[#*_~>|\\-]", "")
                .replaceAll("\\n+", ". ")
                .replaceAll("\\s+", " ")
                .trim();

        if (clean.length() > 800) {
            clean = clean.substring(0, 800) + "... and more.";
        }

        if (clean.isBlank()) {
            throw new RuntimeException("No speakable text after cleanup");
        }

        try {
            String escaped = clean.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");

            String body = """
                    {
                        "text": "%s",
                        "model_id": "eleven_multilingual_v2",
                        "voice_settings": {
                            "stability": 0.4,
                            "similarity_boost": 0.8,
                            "style": 0.35,
                            "use_speaker_boost": true
                        }
                    }
                    """.formatted(escaped);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId))
                    .timeout(Duration.ofSeconds(30))
                    .header("xi-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "audio/mpeg")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.error("ElevenLabs error HTTP {}", response.statusCode());
                throw new RuntimeException("ElevenLabs TTS failed (HTTP " + response.statusCode() + ")");
            }

            return response.body();

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("TTS synthesis failed: " + e.getMessage(), e);
        }
    }
}
