package com.noties.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noties.config.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final List<String> apiKeys = new ArrayList<>();

    // Working models in priority order
    private static final List<String> MODEL_CHAIN = List.of(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
    );

    // Track permanently unavailable models and rate-limited key pairs
    private final Set<String> deadModels = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> exhaustedPairs = new ConcurrentHashMap<>();

    private static final String PANDA_SYSTEM_PROMPT = """
            You are "Pandy", a friendly 3D study helper panda mascot for the "Noties" web app.
            Help students learn by explaining concepts clearly, answering questions, and keeping replies warm and concise (2-4 sentences).
            If the user asks in Hindi/Hinglish, reply in Hinglish.
            """;

    public GeminiService() {
        String rawKeys = EnvConfig.get("GEMINI_API_KEY", "");
        for (String key : rawKeys.split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty() && !trimmed.equals("your_gemini_api_key_here")) {
                apiKeys.add(trimmed);
            }
        }
        if (apiKeys.isEmpty()) {
            log.warn("No Gemini API keys found in configuration!");
        } else {
            log.info("Loaded {} Gemini API keys", apiKeys.size());
        }
    }

    // ── Public API Methods ──────────────────────────────────────────────

    // Generate detailed notes and revision sheet for a short video
    public Map<String, String> generateNotes(String videoTitle, String transcript) {
        String prompt = buildCombinedNotesPrompt(videoTitle, transcript);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("maxOutputTokens", 65536)
        );

        String response = callWithRetry(body, null);
        String[] parts = response.split("===REVISION_NOTES===");
        String detailed = parts[0].trim();
        String revision = parts.length > 1 ? parts[1].trim() : "# Quick Revision\n\n*Included in detailed notes above.*";

        return Map.of("detailed", detailed, "revision", revision);
    }

    // Generate notes for a single chunk of a long video
    public String generateNotesForChunk(String videoTitle, String chunk, int chunkIndex, int totalChunks) {
        String prompt = buildChunkNotesPrompt(videoTitle, chunk, chunkIndex, totalChunks);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("maxOutputTokens", 65536)
        );
        return callWithRetry(body, chunkIndex);
    }

    // Combine chunk notes into one consolidated revision sheet
    public String generateConsolidatedRevision(String videoTitle, List<String> allChunkNotes) {
        String prompt = buildMergePrompt(videoTitle, allChunkNotes);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("maxOutputTokens", 65536)
        );
        return callWithRetry(body, null);
    }

    // Chat with Panda mascot
    public String pandaChat(String message, List<Map<String, String>> history, boolean isExplanation) {
        String query = isExplanation ? "Please explain this selected concept: \"" + message + "\"" : message;
        List<Map<String, Object>> contents = buildChatContents(query, history);

        try {
            return callWithRetry(Map.of("contents", contents), null);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            // If token limit exceeded, retry with recent history only
            if (msg.matches("(?i).*(token|context|length|INVALID_ARGUMENT).*") && history != null && history.size() > 2) {
                var trimmed = history.subList(Math.max(0, history.size() - 4), history.size());
                return callWithRetry(Map.of("contents", buildChatContents(query, trimmed)), null);
            }
            throw new RuntimeException("Panda chat failed: " + msg, e);
        }
    }

    // ── HTTP Request & Retry Logic ──────────────────────────────────────

    // Send HTTP POST request to Google Gemini API
    private String callGemini(Map<String, ?> requestBody, String model, String apiKey) {
        long startTime = System.currentTimeMillis();
        String maskedKey = apiKey.length() > 8 ? apiKey.substring(0, 8) + "..." : apiKey;

        try {
            String json = mapper.writeValueAsString(requestBody);
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Gemini {} [key {}] -> HTTP {} ({}ms)", model, maskedKey, response.statusCode(), elapsed);

            if (response.statusCode() != 200) {
                throw new RuntimeException("Gemini API error (HTTP " + response.statusCode() + "): " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (textNode.isMissingNode()) {
                throw new RuntimeException("No text in Gemini response: " + response.body());
            }
            return textNode.asText();

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Gemini request failed: " + e.getMessage(), e);
        }
    }

    // Retry loop with automatic model fallback and key rotation
    private String callWithRetry(Map<String, ?> requestBody, Integer preferredKeyIndex) {
        if (apiKeys.isEmpty()) {
            throw new RuntimeException("No valid Gemini API keys configured in .env file.");
        }

        long maxWaitTime = 10 * 60 * 1000L; // 10 minutes maximum retry time
        long startTime = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - startTime > maxWaitTime) {
                throw new RuntimeException("API quota exhausted after retrying for 10 minutes. Please try again later.");
            }

            List<Combo> combos = getAvailableCombos(preferredKeyIndex);

            // If all combos are exhausted, wait for the soonest cooldown to expire
            if (combos.isEmpty()) {
                int waitSecs = getSoonestRetrySecs();
                int waitMs = (waitSecs > 0 && waitSecs <= 120 ? waitSecs : 15) * 1000;
                log.info("All model/key combinations on cooldown. Waiting {}s...", waitMs / 1000);
                sleep(waitMs);
                continue;
            }

            for (Combo combo : combos) {
                if (deadModels.contains(combo.model)) {
                    continue; // Skip permanently dead model
                }
                try {
                    String activeKey = apiKeys.get(combo.keyIndex);
                    return callGemini(requestBody, combo.model, activeKey);

                } catch (RuntimeException e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";

                    // 404: Model not supported/available -> mark permanently dead and continue
                    if (msg.contains("404") || msg.contains("not found") || msg.contains("not supported")) {
                        deadModels.add(combo.model);
                        log.warn("Model {} unavailable (404) - skipping", combo.model);
                        continue;
                    }

                    // 429: Rate limit hit -> mark this key/model pair exhausted for cooldown duration
                    if (msg.contains("429") || msg.contains("quota") || msg.contains("Quota exceeded") || msg.contains("ResourceExhausted")) {
                        int delay = extractRetryDelay(msg);
                        if (delay <= 0) delay = 60;
                        markPairExhausted(combo.model, combo.keyIndex, delay);
                        log.warn("{} [key #{}] rate-limited. Cooldown: {}s", combo.model, combo.keyIndex + 1, delay);
                        continue;
                    }

                    // 503: Temporary server busy -> short pause and try next combo
                    if (msg.contains("503") || msg.contains("overloaded") || msg.contains("Unavailable")) {
                        log.warn("{} busy (503) - trying next model", combo.model);
                        sleep(2000);
                        continue;
                    }

                    // Network error -> brief pause and try next combo
                    if (msg.contains("Connection reset") || msg.contains("timed out") || msg.contains("network")) {
                        log.warn("{} network error - trying next model", combo.model);
                        sleep(3000);
                        continue;
                    }

                    throw e; // Unexpected error
                }
            }

            // If full round of available combos failed, wait briefly before next cycle
            int retrySecs = Math.max(getSoonestRetrySecs(), 10);
            log.info("Retrying next cycle in {}s...", retrySecs);
            sleep(retrySecs * 1000L);
        }
    }

    // ── Helper Methods ──────────────────────────────────────────────────

    private List<Combo> getAvailableCombos(Integer preferredKeyIndex) {
        List<Combo> combos = new ArrayList<>();
        int nKeys = apiKeys.size();
        if (nKeys == 0) return combos;

        // Order keys starting from preferred index
        List<Integer> keyOrder = new ArrayList<>();
        int start = (preferredKeyIndex != null && preferredKeyIndex >= 0) ? preferredKeyIndex % nKeys : 0;
        for (int i = 0; i < nKeys; i++) {
            keyOrder.add((start + i) % nKeys);
        }

        for (String model : MODEL_CHAIN) {
            if (deadModels.contains(model)) continue;
            for (int keyIndex : keyOrder) {
                if (isPairAvailable(model, keyIndex)) {
                    combos.add(new Combo(model, keyIndex));
                }
            }
        }
        return combos;
    }

    private void markPairExhausted(String model, int keyIndex, int delaySecs) {
        long retryTime = System.currentTimeMillis() + (delaySecs > 0 ? delaySecs : 60) * 1000L;
        exhaustedPairs.put(model + ":" + keyIndex, retryTime);
    }

    private boolean isPairAvailable(String model, int keyIndex) {
        Long retryTime = exhaustedPairs.get(model + ":" + keyIndex);
        if (retryTime == null) return true;
        if (System.currentTimeMillis() >= retryTime) {
            exhaustedPairs.remove(model + ":" + keyIndex);
            return true;
        }
        return false;
    }

    private int getSoonestRetrySecs() {
        long soonest = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (var entry : exhaustedPairs.entrySet()) {
            if (entry.getValue() <= now) {
                exhaustedPairs.remove(entry.getKey());
            } else if (entry.getValue() < soonest) {
                soonest = entry.getValue();
            }
        }
        return soonest == Long.MAX_VALUE ? 0 : Math.max(1, (int) Math.ceil((soonest - now) / 1000.0));
    }

    private int extractRetryDelay(String errorMsg) {
        Matcher m = Pattern.compile("retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)(s|ms)\"", Pattern.CASE_INSENSITIVE).matcher(errorMsg);
        if (m.find()) {
            double val = Double.parseDouble(m.group(1));
            return m.group(2).equalsIgnoreCase("ms") ? (int) Math.ceil(val / 1000.0) : (int) Math.ceil(val);
        }
        return 0;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", ie);
        }
    }

    private List<Map<String, Object>> buildChatContents(String message, List<Map<String, String>> history) {
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", "System: " + PANDA_SYSTEM_PROMPT))));
        contents.add(Map.of("role", "model", "parts", List.of(Map.of("text", "Hi! I'm Pandy, your study buddy! 🐼"))));

        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                var entry = history.get(i);
                String role = entry.get("role");
                String text = entry.getOrDefault("text", entry.get("content"));
                if (role == null || text == null) continue;
                String geminiRole = role.equalsIgnoreCase("assistant") ? "model" : role;
                contents.add(Map.of("role", geminiRole, "parts", List.of(Map.of("text", text))));
            }
        }

        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", message))));
        return contents;
    }

    private static class Combo {
        final String model;
        final int keyIndex;
        Combo(String model, int keyIndex) {
            this.model = model;
            this.keyIndex = keyIndex;
        }
    }

    // ── Prompts matching original Node.js ──────────────────────────────

    private String buildCombinedNotesPrompt(String videoTitle, String transcript) {
        return """
                You are a master educator and textbook author creating high-yield, aesthetic study notes for students.
                
                TARGET VIDEO TITLE: "%s"
                
                CRITICAL INSTRUCTIONS & ACCURACY GUARDRAILS:
                1. TOPIC & CONTENT STRICTNESS: Generate notes ONLY and EXCLUSIVELY from the provided transcript below for the video titled "%s".
                2. NO TOPIC SWITCHING: Do NOT change the subject, do NOT invent another topic (e.g. C++, Python, Deep Learning, or general computer science unless explicitly present in this transcript), and do NOT reuse knowledge from other videos.
                3. INSUFFICIENT DATA RULE: If the provided transcript is empty or insufficient, state: "The transcript provided is insufficient to generate study notes for this video."
                4. FULL CHRONOLOGICAL COVERAGE: Cover every concept, formula, mechanism, code snippet, definition, and insight from start to end of this transcript. Do NOT truncate or rush through later topics.
                5. RICH FORMATTING & HIERARCHY:
                   - Use clean markdown `#`, `##`, `###` headers for logical module separation.
                   - Use bold text for key terms, definitions, and important syntax.
                   - Use bullet points and numbered lists for readability.
                   - For tutorials/technical topics: Provide clean, commented, fully explained code blocks or command sequences.
                   - Highlight major takeaways with `✅ **Key Takeaway:** ...` and pro-tips with `💡 **Pro Tip:** ...`.
                6. DO NOT include meta commentary (like "In this video...", "Here are your notes..."). Start directly with the top-level title and content.
                
                ---
                
                **PART 1: Detailed Study Notes**
                Write a complete, beautifully structured, thorough textbook-grade reference guide based ONLY on the transcript.
                
                Then write EXACTLY this separator line on its own line:
                ===REVISION_NOTES===
                
                **PART 2: Quick Revision & Exam Cheat Sheet**
                Create an exhaustive, high-yield summary designed for rapid review based ONLY on the transcript:
                - `## 📚 Topic-by-Topic Fast Recap`: 1-2 sentence bullet points per concept, chronologically.
                - `## ⚡ Core Principles & Definitions`: Must-know laws, formulas, theorems, and definitions from this transcript.
                - `## 📝 Quick Syntax & Formula Cheat Sheet`: Tables, code snippets, hotkeys, commands, or formulas from this transcript.
                - `## 🧠 High-Yield Flashcard Q&A`: At least 15 clear Question & Answer flashcard pairs (`**Q:** ...` / `**A:** ...`) based on this transcript.
                
                ---
                TRANSCRIPT FOR VIDEO "%s":
                %s
                """.formatted(videoTitle, videoTitle, videoTitle, transcript);
    }

    private String buildChunkNotesPrompt(String videoTitle, String chunk, int chunkIndex, int totalChunks) {
        return """
                You are a master educator and textbook author.
                You are given PART %d of %d of the transcript for the video titled "%s".
                
                CRITICAL GUARDRAIL:
                Generate exhaustive study notes covering EVERY concept in THIS SECTION ONLY.
                Do NOT change the topic or introduce unrelated subjects. Generate content strictly from this transcript chunk.
                
                FORMATTING RULES:
                - Use clear markdown headers (`##`, `###`), bold keywords, and clean bulleted explanations.
                - For programming/math: write full, commented code blocks or formulas with line-by-line intuition.
                - Include `✅ **Key Takeaway**` and `💡 **Pro Tip**` callouts.
                - Cover from the very first line to the very last line of this chunk. Do not skip details.
                - Do not include conversational filler or meta intros.
                
                ---
                Transcript Chunk %d of %d for "%s":
                %s
                """.formatted(chunkIndex + 1, totalChunks, videoTitle, chunkIndex + 1, totalChunks, videoTitle, chunk);
    }

    private String buildMergePrompt(String videoTitle, List<String> allChunkNotes) {
        String combined = String.join("\n\n---\n\n", allChunkNotes);
        return """
                You are a master educator and revision guide specialist.
                Below are detailed study notes compiled from %d sections of the video titled "%s".
                
                Create an EXHAUSTIVE, BEAUTIFULLY ORGANIZED Quick Revision Sheet covering ALL concepts from ALL %d parts of this video.
                STRICT RULE: Do NOT introduce unrelated topics outside of this video's notes.
                
                STRUCTURE:
                # 🚀 Quick Revision & Exam Preparation Guide: %s
                
                ## 📚 Comprehensive Topic Recap
                - Go through every single module/topic from Part 1 to Part %d in chronological order.
                - Provide 1-2 punchy, high-yield bullet points summarizing each key takeaway.
                
                ## ⚡ Core Principles & Key Definitions
                - Bulleted list of every crucial term, definition, theory, or rule introduced.
                
                ## 📝 Syntax, Commands & Formulas Cheat Sheet
                - Provide code syntax tables, command cheat sheets, or key formulas from this video.
                
                ## 🧠 Flashcard Recall Q&A
                - Minimum 15-20 rapid-fire flashcards formatted as:
                  - **Q:** [Question]
                    **A:** [Direct, accurate answer]
                
                ---
                Compiled Notes from all parts of "%s":
                %s
                """.formatted(allChunkNotes.size(), videoTitle, allChunkNotes.size(), videoTitle, allChunkNotes.size(), videoTitle, combined);
    }
}
