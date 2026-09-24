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
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    // Sentinel returned when the model's output was cut short by MAX_TOKENS
    private static final String TRUNCATED_SENTINEL = "__TRUNCATED_OUTPUT__";

    private final List<String> apiKeys = new ArrayList<>();

    // Working models in priority order
    private static final List<String> MODEL_CHAIN = List.of(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
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

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message, int percent);
    }

    // ── Public API Methods ──────────────────────────────────────────────

    // Generate detailed notes and revision sheet for a short/medium video
    public Map<String, String> generateNotes(String videoTitle, String transcript) {
        return generateNotes(videoTitle, transcript, false, false, null);
    }

    public Map<String, String> generateNotes(String videoTitle, String transcript, boolean isHandwritten, boolean includeDiagrams) {
        return generateNotes(videoTitle, transcript, isHandwritten, includeDiagrams, null);
    }

    public Map<String, String> generateNotes(String videoTitle, String transcript, boolean isHandwritten, boolean includeDiagrams, ProgressListener listener) {
        if (listener != null) listener.onProgress("Generating comprehensive detailed study notes...", 30);

        // Step 1: Dedicated call for exhaustive detailed study notes
        String detailedPrompt = buildDetailedNotesPrompt(videoTitle, transcript, includeDiagrams);
        Map<String, Object> bodyDetailed = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", detailedPrompt)))),
                "generationConfig", Map.of("maxOutputTokens", 65536)
        );
        String detailedNotes = callWithRetry(bodyDetailed, null);

        if (listener != null) listener.onProgress("Creating Quick Revision Sheet & Flashcards...", 75);

        // Step 2: Dedicated call for Quick Revision Sheet & Flashcard Recall Q&A
        String revisionPrompt = buildRevisionNotesPrompt(videoTitle, detailedNotes, includeDiagrams);
        Map<String, Object> bodyRevision = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", revisionPrompt)))),
                "generationConfig", Map.of("maxOutputTokens", 65536)
        );
        String revisionNotes = callWithRetry(bodyRevision, null);

        return Map.of("detailed", detailedNotes.trim(), "revision", revisionNotes.trim());
    }

    // Generate notes for a single chunk of a long video (backwards compatible)
    public String generateNotesForChunk(String videoTitle, String chunk, int chunkIndex, int totalChunks) {
        return generateNotesForChunk(videoTitle, chunk, chunkIndex, totalChunks, false, false);
    }

    public String generateNotesForChunk(String videoTitle, String chunk, int chunkIndex, int totalChunks, boolean isHandwritten, boolean includeDiagrams) {
        String prompt = isHandwritten
                ? buildHandwrittenChunkNotesPrompt(videoTitle, chunk, chunkIndex, totalChunks, includeDiagrams)
                : buildChunkNotesPrompt(videoTitle, chunk, chunkIndex, totalChunks, includeDiagrams);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("maxOutputTokens", 65536)
        );
        return callWithRetry(body, chunkIndex);
    }

    // Combine chunk notes into one consolidated revision sheet (backwards compatible)
    public String generateConsolidatedRevision(String videoTitle, List<String> allChunkNotes) {
        return generateConsolidatedRevision(videoTitle, allChunkNotes, false, false);
    }

    public String generateConsolidatedRevision(String videoTitle, List<String> allChunkNotes, boolean isHandwritten, boolean includeDiagrams) {
        String prompt = isHandwritten
                ? buildHandwrittenMergePrompt(videoTitle, allChunkNotes, includeDiagrams)
                : buildMergePrompt(videoTitle, allChunkNotes, includeDiagrams);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("maxOutputTokens", 65536)
        );
        return callWithRetry(body, null);
    }

    // Overload without listener for backwards compatibility
    public Map<String, String> generateNotesFromMetadata(String videoTitle, String description, String author, String duration, List<String> keywords) {
        return generateNotesFromMetadata(videoTitle, description, author, duration, keywords, false, false, null);
    }

    public Map<String, String> generateNotesFromMetadata(String videoTitle, String description, String author, String duration, List<String> keywords, ProgressListener listener) {
        return generateNotesFromMetadata(videoTitle, description, author, duration, keywords, false, false, listener);
    }

    // Generate detailed notes and revision sheet from video metadata (title, description, author, duration, keywords)
    public Map<String, String> generateNotesFromMetadata(String videoTitle, String description, String author, String duration, List<String> keywords, boolean isHandwritten, boolean includeDiagrams, ProgressListener listener) {
        boolean isLongCourse = duration != null && (duration.contains("Hours") || duration.contains("hour") || duration.contains("hr"));

        if (isLongCourse) {
            log.info("Long full-course video detected in metadata mode (duration: {}, handwritten: {}). Generating 4 comprehensive course parts + revision sheet...", duration, isHandwritten);
            List<String> partsList = new ArrayList<>();

            // Part 1: Foundations & Core Architecture
            if (listener != null) listener.onProgress("Generating Part 1 of 4: Foundations & Core Architecture...", 35);
            try {
                String prompt1 = isHandwritten
                        ? buildHandwrittenMetadataPartPrompt(videoTitle, description, author, duration, keywords, 1, includeDiagrams)
                        : buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 1, includeDiagrams);
                String p1 = callWithRetry(Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt1)))),
                        "generationConfig", Map.of("maxOutputTokens", 65536)
                ), null);
                if (p1 != null && !p1.isBlank()) partsList.add(p1.trim());
            } catch (Exception e) {
                log.warn("Metadata Part 1 generation warning: {}", e.getMessage());
            }

            // Part 2: State Management, Forms & Effect Hooks
            if (listener != null) listener.onProgress("Generating Part 2 of 4: State Management & Essential Hooks...", 50);
            try {
                String prompt2 = isHandwritten
                        ? buildHandwrittenMetadataPartPrompt(videoTitle, description, author, duration, keywords, 2, includeDiagrams)
                        : buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 2, includeDiagrams);
                String p2 = callWithRetry(Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt2)))),
                        "generationConfig", Map.of("maxOutputTokens", 65536)
                ), null);
                if (p2 != null && !p2.isBlank()) partsList.add(p2.trim());
            } catch (Exception e) {
                log.warn("Metadata Part 2 generation warning: {}", e.getMessage());
            }

            // Part 3: Advanced Hooks, Routing & Global State Management
            if (listener != null) listener.onProgress("Generating Part 3 of 4: Advanced Hooks, Routing & Global State...", 65);
            try {
                String prompt3 = isHandwritten
                        ? buildHandwrittenMetadataPartPrompt(videoTitle, description, author, duration, keywords, 3, includeDiagrams)
                        : buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 3, includeDiagrams);
                String p3 = callWithRetry(Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt3)))),
                        "generationConfig", Map.of("maxOutputTokens", 65536)
                ), null);
                if (p3 != null && !p3.isBlank()) partsList.add(p3.trim());
            } catch (Exception e) {
                log.warn("Metadata Part 3 generation warning: {}", e.getMessage());
            }

            // Part 4: Real-World Projects, Performance Optimization & Production Deployment
            if (listener != null) listener.onProgress("Generating Part 4 of 4: Real-World Projects & Production Deployment...", 80);
            try {
                String prompt4 = isHandwritten
                        ? buildHandwrittenMetadataPartPrompt(videoTitle, description, author, duration, keywords, 4, includeDiagrams)
                        : buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 4, includeDiagrams);
                String p4 = callWithRetry(Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt4)))),
                        "generationConfig", Map.of("maxOutputTokens", 65536)
                ), null);
                if (p4 != null && !p4.isBlank()) partsList.add(p4.trim());
            } catch (Exception e) {
                log.warn("Metadata Part 4 generation warning: {}", e.getMessage());
            }

            // Part 5: Comprehensive Revision Sheet
            if (listener != null) listener.onProgress("Creating comprehensive Quick Revision Sheet...", 90);
            String revisionNotes = "# Quick Revision\n\n*Included in detailed notes above.*";
            try {
                String promptRev = isHandwritten
                        ? buildHandwrittenMetadataRevisionPrompt(videoTitle, description, author, duration, keywords)
                        : buildMetadataRevisionPrompt(videoTitle, description, author, duration, keywords);
                revisionNotes = callWithRetry(Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", promptRev)))),
                        "generationConfig", Map.of("maxOutputTokens", 65536)
                ), null);
            } catch (Exception e) {
                log.warn("Metadata Revision generation warning: {}", e.getMessage());
            }

            String detailedNotes = String.join("\n\n---\n\n", partsList);
            return Map.of("detailed", detailedNotes, "revision", revisionNotes.trim());

        } else {
            if (listener != null) listener.onProgress("Generating complete study guide from video outline...", 50);
            String prompt = isHandwritten
                    ? buildHandwrittenNotesPrompt(videoTitle, (description != null ? description : "") + "\nKeywords: " + keywords, includeDiagrams)
                    : buildMetadataNotesPrompt(videoTitle, description, author, duration, keywords, includeDiagrams);
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
    // Internal result from a single Gemini API call, carrying both text and truncation status
    private record GeminiResult(String text, boolean truncated) {}

    private GeminiResult callGeminiRaw(Map<String, ?> requestBody, String model, String apiKey) {
        long startTime = System.currentTimeMillis();
        String maskedKey = apiKey.length() > 8 ? apiKey.substring(0, 8) + "..." : apiKey;

        try {
            String json = mapper.writeValueAsString(requestBody);
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Gemini {} [key {}] -> HTTP {} ({}ms)", model, maskedKey, response.statusCode(), elapsed);

            if (response.statusCode() != 200) {
                throw new RuntimeException("Gemini API error (HTTP " + response.statusCode() + "): " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode candidate = root.path("candidates").path(0);
            JsonNode textNode = candidate.path("content").path("parts").path(0).path("text");
            if (textNode.isMissingNode()) {
                throw new RuntimeException("No text in Gemini response: " + response.body());
            }

            // Check if output was truncated due to reaching maxOutputTokens
            String finishReason = candidate.path("finishReason").asText("");
            boolean truncated = "MAX_TOKENS".equalsIgnoreCase(finishReason) || "LENGTH".equalsIgnoreCase(finishReason);
            if (truncated) {
                log.warn("Gemini {} output was TRUNCATED (finishReason={}). Will attempt continuation.", model, finishReason);
            }

            return new GeminiResult(textNode.asText(), truncated);

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Gemini request failed: " + e.getMessage(), e);
        }
    }

    // Legacy wrapper that returns just the text (used by callWithRetry)
    private String callGemini(Map<String, ?> requestBody, String model, String apiKey) {
        GeminiResult result = callGeminiRaw(requestBody, model, apiKey);
        if (result.truncated()) {
            return result.text() + TRUNCATED_SENTINEL;
        }
        return result.text();
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
                    String result = callGemini(requestBody, combo.model, activeKey);

                    // If the output was truncated, automatically continue generating
                    if (result.endsWith(TRUNCATED_SENTINEL)) {
                        result = handleTruncatedOutput(result, requestBody, combo.model, activeKey);
                    }

                    return result;

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

    /**
     * When the model's output is truncated (MAX_TOKENS), send a continuation prompt
     * asking it to resume from where it left off. Repeats up to MAX_CONTINUATIONS times.
     */
    private String handleTruncatedOutput(String truncatedResult, Map<String, ?> originalRequest, String model, String apiKey) {
        final int MAX_CONTINUATIONS = 5;
        StringBuilder accumulated = new StringBuilder();
        accumulated.append(truncatedResult.replace(TRUNCATED_SENTINEL, ""));

        for (int i = 0; i < MAX_CONTINUATIONS; i++) {
            log.info("Output truncated — sending continuation request {} of {} (accumulated {} chars so far)",
                    i + 1, MAX_CONTINUATIONS, accumulated.length());

            // Get the last ~500 chars as context for seamless continuation
            String lastChunk = accumulated.length() > 500
                    ? accumulated.substring(accumulated.length() - 500)
                    : accumulated.toString();

            String continuationPrompt = """
                    You were generating detailed study notes but your output was cut off mid-way.
                    Here is where you stopped (last portion of your output):
                    ---
                    %s
                    ---
                    CONTINUE EXACTLY from where you left off. Do NOT repeat any content above.
                    Do NOT add any introductory text like "Continuing from..." or "Here is the rest...".
                    Just seamlessly continue the notes from the exact point they were interrupted.
                    Cover ALL remaining topics thoroughly until completion.
                    """.formatted(lastChunk);

            Map<String, Object> contBody = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", continuationPrompt)))),
                    "generationConfig", Map.of("maxOutputTokens", 65536)
            );

            try {
                String contResult = callGemini(contBody, model, apiKey);

                if (contResult.endsWith(TRUNCATED_SENTINEL)) {
                    // Still truncated — append what we got and loop
                    accumulated.append("\n").append(contResult.replace(TRUNCATED_SENTINEL, ""));
                    log.warn("Continuation {} was also truncated, will retry...", i + 1);
                } else {
                    // Complete! Append and return
                    accumulated.append("\n").append(contResult);
                    log.info("Continuation completed successfully after {} extra call(s). Total output: {} chars",
                            i + 1, accumulated.length());
                    return accumulated.toString();
                }
            } catch (Exception e) {
                log.warn("Continuation {} failed: {}. Returning partial output.", i + 1, e.getMessage());
                break;
            }
        }

        log.warn("Reached max continuations ({}). Returning accumulated output ({} chars).",
                MAX_CONTINUATIONS, accumulated.length());
        return accumulated.toString();
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

    // ── Shared Diagram Instruction ────────────────────────────────────

    private static String getDiagramInstruction(boolean includeDiagrams) {
        if (!includeDiagrams) {
            return "6. NO DIAGRAMS: Do not include Mermaid diagrams; keep explanations in structured text with bullet points, code blocks, and callout boxes.";
        }
        return """
                6. VISUAL DIAGRAMS (ESSENTIAL & VALUE-ADD):
                   - Wherever a visual diagram genuinely clarifies a concept (e.g. data flow, system architecture, state machine, algorithm steps, lifecycle, or component tree), insert a clean Mermaid.js diagram enclosed in a ```mermaid ... ``` code block.
                   - STRICT MERMAID SYNTAX RULES (CRITICAL - MUST BE VALID MERMAID):
                     * Always start the diagram block with `flowchart TD` (or `sequenceDiagram` / `stateDiagram-v2`).
                     * Node IDs must be simple alphanumeric strings (e.g., A, B, C, Step1, Step2).
                     * ALL node text labels MUST be enclosed in double quotes inside brackets:
                       A["User Interface"] --> B["API Gateway"]
                       B --> C["Database (PostgreSQL)"]
                     * NEVER use unquoted parentheses `()`, brackets `[]`, braces `{}`, colons `:`, or raw arrows `->` inside node labels.
                     * Keep diagrams clean, concise, and focused (4 to 8 nodes maximum).
                     * Do NOT put markdown formatting (bold, italic) or bullet points inside the mermaid block.
                """;
    }

    // ── Prompts matching original Node.js ──────────────────────────────

    private String buildDetailedNotesPrompt(String videoTitle, String transcript, boolean includeDiagrams) {
        return """
                You are an elite professor, master educator, and top-scoring student creating authentic, deeply comprehensive study notes for students.
                
                TARGET VIDEO TITLE: "%s"
                
                CRITICAL MODULE & CHAPTER NAMING MANDATE:
                1. OFFICIAL VIDEO CHAPTERS / MODULE NAMES (CRITICAL REQUIREMENT):
                   - If official video chapters or module timestamps appear in the transcript header (e.g., [01:00:58] JWTAuthFilter to Authenticate, [01:12:57] Exception Handling with ControllerAdvice), you MUST USE THOSE EXACT MODULE NAMES as your section headings (e.g. `## Module: JWTAuthFilter to Authenticate [01:00:58]`).
                   - Place ALL explanations, code snippets, definitions, and worked examples for that section under that EXACT module header!
                2. IF OFFICIAL VIDEO CHAPTERS ARE NOT PROVIDED:
                   - Create clear, logical, textbook-grade module headings yourself based strictly on the content (e.g. `## Module 1: Entity Models & Architecture [00:00:00]`).
                3. STRICT ACCURACY & ZERO HALLUCINATION (MAN SE KUCH BHI MAT BANANA):
                   - Write notes strictly and exclusively from what is taught in the video transcript. Do NOT invent imaginary APIs, methods, or rules not present in the video.
                4. 100%% EXHAUSTIVE COVERAGE (FROM THE FIRST SECOND TO THE VERY LAST SECOND):
                   - Cover EVERY SINGLE concept, subtopic, definition, formula, mechanism, step-by-step procedure, code example, and takeaway present in the transcript.
                   - Do NOT skip any section or topic. Write complete, textbook-grade explanations with full context and clarity.
                5. AUTHENTIC STUDENT NOTEBOOK FORMATTING & HIERARCHY:
                   - `# [Master Notebook Title]`
                   - `## Module: [Exact Chapter / Module Name] [Timestamp]`
                   - `### [Subtopic / Detailed Concept]`
                   - Definitions in callouts: `> 📖 **Definition: [Term]**`
                   - Exam highlights: `★ **Important:** [Crucial takeaway]`
                   - Syntax/Formulas: `> 📐 **Formula / Syntax:**`
                   - Worked Code Examples: Full, commented code snippets with line-by-line intuition
                   - Pitfall warnings: `> ⚠️ **Common Mistake:**`
                   - Key takeaways: `✅ **Key Takeaway:** ...`
                6. %s
                7. NO FILLER OR META INTROS: Do NOT include phrases like "Here are your notes" or "In this video". Start directly with the main title and structured content.
                
                ---
                TRANSCRIPT FOR VIDEO "%s":
                %s
                """.formatted(videoTitle, getDiagramInstruction(includeDiagrams), videoTitle, transcript);
    }

    private String buildRevisionNotesPrompt(String videoTitle, String detailedNotes, boolean includeDiagrams) {
        return """
                You are a master educator and exam preparation specialist.
                Below are the detailed study notes for the video titled "%s".
                
                Create an EXHAUSTIVE, BEAUTIFULLY STRUCTURED Quick Revision Sheet covering ALL concepts from start to finish across the entire video.
                
                STRUCTURE:
                # 🚀 Quick Revision & Exam Preparation Guide: %s
                
                ## 📚 Module-by-Module Fast Recap
                - Chronological breakdown covering all major modules/chapters from beginning to end.
                - 1-2 punchy, high-yield bullet points summarizing each takeaway.
                
                ## ⚡ Core Principles & Key Definitions
                - Bulleted list of every crucial term, definition, law, theorem, or pattern introduced.
                
                ## 📝 Syntax, Commands & Formulas Cheat Sheet
                - Clean code syntax tables, command cheat sheets, or key formulas for quick reference.
                
                %s
                
                ## 🧠 Flashcard Recall Q&A
                - Minimum 20-25 rapid-fire flashcards covering all video sections formatted as:
                  - **Q:** [Question]
                    **A:** [Direct, accurate answer]
                
                ---
                DETAILED STUDY NOTES FOR "%s":
                %s
                """.formatted(videoTitle, videoTitle, getDiagramInstruction(includeDiagrams), videoTitle, detailedNotes);
    }

    private String buildChunkNotesPrompt(String videoTitle, String chunk, int chunkIndex, int totalChunks, boolean includeDiagrams) {
        boolean isFinalChunk = (chunkIndex == totalChunks - 1);
        String finalInstruction = isFinalChunk ?
                "4. CRITICAL FINAL PART REQUIREMENT: This is Part " + (chunkIndex + 1) + " of " + totalChunks + " (the FINAL section of the video transcript). You MUST cover all topics and code examples up to the very LAST line of the transcript. Conclude with a '🎓 Final Course Conclusion & Master Takeaways' section." : "";

        return """
                You are a master educator and textbook author creating study notes for a full-length course video.
                You are given PART %d of %d of the transcript for the video titled "%s".
                
                CRITICAL GUARDRAILS & MODULE NAMING MANDATE:
                1. OFFICIAL VIDEO CHAPTERS / MODULE NAMES:
                   - If official video chapters or timestamps appear in the transcript (e.g. [01:00:58] JWTAuthFilter to Authenticate), you MUST USE THOSE EXACT MODULE NAMES as your section headings (`## Module: [Exact Chapter Name] [Timestamp]`).
                   - Place ALL explanations, code snippets, and definitions for that section under that EXACT module header!
                2. IF OFFICIAL VIDEO CHAPTERS ARE NOT PROVIDED:
                   - Create clear, logical, textbook-grade module headings yourself based strictly on the content in this chunk.
                3. STRICT ACCURACY & ZERO HALLUCINATION (MAN SE KUCH BHI MAT BANANA):
                   - Write notes strictly and exclusively from what is taught in this transcript chunk. Do NOT invent imaginary code, APIs, or rules.
                4. 100%% EXHAUSTIVE LINE-BY-LINE COVERAGE FOR THIS PART:
                   - Process EVERY SINGLE SECTION of this transcript chunk from start to end. Do NOT skip, summarize away, or condense ANY concept or topic in this part.
                %s
                
                FORMATTING RULES:
                - `# Part %d: [Course Section]`
                - `## Module: [Exact Chapter / Module Name] [Timestamp]`
                - `### [Subtopic Name]`
                - Use boxed definitions `> 📖 **Definition:**`, important points `★ **Important:**`, commented code blocks, and callouts `✅ **Key Takeaway**`.
                - %s
                - Do not include conversational filler or meta intros.
                
                ---
                Transcript Chunk Part %d of %d for "%s":
                %s
                """.formatted(chunkIndex + 1, totalChunks, videoTitle, finalInstruction, chunkIndex + 1, getDiagramInstruction(includeDiagrams), chunkIndex + 1, totalChunks, videoTitle, chunk);
    }

    private String buildMergePrompt(String videoTitle, List<String> allChunkNotes, boolean includeDiagrams) {
        String combined = String.join("\n\n---\n\n", allChunkNotes);
        return """
                You are a master educator and revision guide specialist.
                Below are detailed study notes compiled from %d sections of the video titled "%s".
                
                Create an EXHAUSTIVE, BEAUTIFULLY ORGANIZED Quick Revision Sheet covering ALL concepts from ALL %d parts of this video from start to the very end.
                
                STRUCTURE:
                # 🚀 Quick Revision & Exam Preparation Guide: %s
                
                ## 📚 Comprehensive Topic Recap
                - Go through every single module/topic from Part 1 to Part %d in chronological order.
                - Provide 1-2 punchy, high-yield bullet points summarizing each key takeaway.
                
                ## ⚡ Core Principles & Key Definitions
                - Bulleted list of every crucial term, definition, theory, or rule introduced.
                
                ## 📝 Syntax, Commands & Formulas Cheat Sheet
                - Provide code syntax tables, command cheat sheets, or key formulas from this video.
                
                %s
                
                ## 🧠 Flashcard Recall Q&A
                - Minimum 15-20 rapid-fire flashcards formatted as:
                  - **Q:** [Question]
                    **A:** [Direct, accurate answer]
                
                ---
                Compiled Notes from all parts of "%s":
                %s
                """.formatted(allChunkNotes.size(), videoTitle, allChunkNotes.size(), videoTitle, allChunkNotes.size(), getDiagramInstruction(includeDiagrams), videoTitle, combined);
    }

    private String buildMetadataNotesPrompt(String videoTitle, String description, String author, String duration, List<String> keywords, boolean includeDiagrams) {
        String kwList = (keywords != null && !keywords.isEmpty()) ? String.join(", ", keywords) : "N/A";
        String descText = (description != null && !description.isBlank()) ? description.trim() : "No detailed description provided.";
        String durText = (duration != null && !duration.isBlank()) ? duration : "Full Length Course";

        return """
                You are a master educator and textbook author creating high-yield, aesthetic study notes for students.
                You are creating study notes for a student watching a YouTube video.
                
                VIDEO INFORMATION:
                - Title: "%s"
                - Total Video Duration: %s
                - Channel / Author: %s
                - Topic Keywords: %s
                
                DETAILED VIDEO OUTLINE & DESCRIPTION:
                %s
                
                CRITICAL INSTRUCTIONS & STRICT TOPIC GUARDRAILS:
                1. STRICT TOPIC COMPLIANCE: Generate notes ONLY and EXCLUSIVELY about the exact topic of THIS video titled "%s".
                2. FULL END-TO-END COURSE COVERAGE (%s TOTAL DURATION):
                   - This video is %s long. You MUST generate notes covering the ENTIRE course curriculum from start to the very end of the course.
                   - Cover all chapters and modules from Module 1 (Beginner) through Intermediate Modules up to the final Advanced Production/Deployment Modules at the end (%s).
                3. RICH FORMATTING & HIERARCHY:
                   - Use clean markdown `#`, `##`, `###` headers for logical module separation.
                   - Use bold text for key terms, definitions, and important syntax.
                   - Use bullet points and numbered lists for readability.
                   - For tutorials/technical topics: Provide clean, commented, fully explained code blocks or command sequences.
                   - Highlight major takeaways with `✅ **Key Takeaway:** ...` and pro-tips with `💡 **Pro Tip:** ...`.
                4. %s
                5. DO NOT include meta commentary (like "In this video...", "Here are your notes..."). Start directly with the main title and structured content.
                
                ---
                
                **PART 1: Detailed Study Notes**
                Write a complete, beautifully structured, thorough textbook-grade reference guide based strictly on this video's topic ("%s") spanning the full %s course duration.
                
                Then write EXACTLY this separator line on its own line:
                ===REVISION_NOTES===
                
                **PART 2: Quick Revision & Exam Cheat Sheet**
                Create an exhaustive, high-yield summary designed for rapid review based strictly on this video's topic covering the entire %s duration:
                - `## 📚 Topic-by-Topic Fast Recap`: 1-2 sentence bullet points per concept.
                - `## ⚡ Core Principles & Definitions`: Must-know laws, formulas, theorems, and definitions from this topic.
                - `## 📝 Quick Syntax & Formula Cheat Sheet`: Tables, code snippets, hotkeys, commands, or formulas for this topic.
                - `## 🧠 High-Yield Flashcard Q&A`: At least 15 clear Question & Answer flashcard pairs (`**Q:** ...` / `**A:** ...`) based on this video's topic.
                """.formatted(videoTitle, durText, author != null ? author : "YouTube Creator", kwList, descText, videoTitle, durText, durText, durText, getDiagramInstruction(includeDiagrams), videoTitle, durText, durText, durText);
    }

    private String buildMetadataPartPrompt(String videoTitle, String description, String author, String duration, List<String> keywords, int partNum, boolean includeDiagrams) {
        String kwList = (keywords != null && !keywords.isEmpty()) ? String.join(", ", keywords) : "N/A";
        String descText = (description != null && !description.isBlank()) ? description.trim() : "No detailed description provided.";
        String durText = (duration != null && !duration.isBlank()) ? duration : "Full Length Course";

        String sectionFocus = switch (partNum) {
            case 1 -> "PART 1 OF 4: FOUNDATIONS & CORE CONCEPTS — Cover the introductory concepts, environment setup, basic building blocks, fundamental principles, and core terminology of the topic described in the video title and keywords. Write thorough textbook explanations with full commented code snippets or detailed examples for every module.";
            case 2 -> "PART 2 OF 4: INTERMEDIATE CONCEPTS & PRACTICAL APPLICATION — Cover intermediate-level techniques, essential patterns, data handling, common workflows, and hands-on practical implementations of the topic. Write thorough textbook explanations with full commented code snippets or detailed examples for every module.";
            case 3 -> "PART 3 OF 4: ADVANCED TECHNIQUES & ARCHITECTURE — Cover advanced concepts, architectural patterns, performance considerations, integrations with other tools/libraries, and complex workflows of the topic. Write thorough textbook explanations with full commented code snippets or detailed examples for every module.";
            default -> "PART 4 OF 4: REAL-WORLD PROJECTS, OPTIMIZATION & PRODUCTION DEPLOYMENT — Cover real-world project patterns, best practices, scalable architecture, performance optimization, testing strategies, and production deployment of the topic. Conclude with a '🎓 Master Course Summary & Final Key Takeaways' section.";
        };

        return """
                You are a master educator and textbook author creating high-yield, aesthetic study notes for students.
                You are creating study notes for a student watching a YouTube video.
                
                VIDEO INFORMATION:
                - Title: "%s"
                - Total Video Duration: %s
                - Channel / Author: %s
                - Topic Keywords: %s
                
                DETAILED VIDEO OUTLINE & DESCRIPTION:
                %s
                
                CRITICAL INSTRUCTIONS FOR THIS SECTION:
                1. FOCUS REQUIREMENT: %s
                2. STRICT TOPIC COMPLIANCE: Generate notes ONLY and EXCLUSIVELY about the exact topic of THIS video titled "%s".
                3. RICH FORMATTING & HIERARCHY:
                   - Use clean markdown `#`, `##`, `###` headers for logical module separation.
                   - Use bold text for key terms, definitions, and important syntax.
                   - For tutorials/technical topics: Provide clean, commented, fully explained code blocks or command sequences.
                   - Highlight major takeaways with `✅ **Key Takeaway:** ...` and pro-tips with `💡 **Pro Tip:** ...`.
                4. %s
                5. DO NOT include meta commentary (like "In this video...", "Here are your notes..."). Start directly with the structured module headers and content.
                """.formatted(videoTitle, durText, author != null ? author : "YouTube Creator", kwList, descText, sectionFocus, videoTitle, getDiagramInstruction(includeDiagrams));
    }

    private String buildMetadataRevisionPrompt(String videoTitle, String description, String author, String duration, List<String> keywords) {
        String kwList = (keywords != null && !keywords.isEmpty()) ? String.join(", ", keywords) : "N/A";
        String descText = (description != null && !description.isBlank()) ? description.trim() : "No detailed description provided.";
        String durText = (duration != null && !duration.isBlank()) ? duration : "Full Length Course";

        return """
                You are a master educator and revision guide specialist.
                Create an exhaustive, high-yield Quick Revision Sheet covering the ENTIRE %s course titled "%s" (%s long) from start to finish.
                
                STRUCTURE:
                # 🚀 Quick Revision & Exam Preparation Guide: %s
                
                ## 📚 Comprehensive Course Module Recap
                - Bulleted breakdown covering all modules from Beginner Fundamentals to Advanced State, Projects & Deployment across the entire %s course.
                - 1-2 punchy, high-yield bullet points summarizing each key takeaway.
                
                ## ⚡ Core Principles & Key Definitions
                - Bulleted list of every crucial term, definition, law, or pattern introduced in this course.
                
                ## 📝 Syntax, Commands & Formulas Cheat Sheet
                - Provide code syntax tables, command cheat sheets, or key formulas for this topic.
                
                ## 🧠 Flashcard Recall Q&A
                - Minimum 15-20 rapid-fire flashcards formatted as:
                  - **Q:** [Question]
                    **A:** [Direct, accurate answer]
                """.formatted(videoTitle, videoTitle, durText, videoTitle, durText);
    }

    // ── Handwritten Mode Prompts ───────────────────────────────────────

    private String buildHandwrittenNotesPrompt(String videoTitle, String transcript, boolean includeDiagrams) {
        return """
                You are a top-scoring, meticulous computer science student creating authentic, human-made handwritten study notes from a lecture video transcript.
                Create notes EXACTLY like a dedicated student writes in a neat, aesthetic personal notebook.

                TARGET VIDEO TITLE: "%s"

                STRICT ACCURACY & MODULE MATCHING RULES (MAN SE KUCH BHI MAT BANANA):
                1. OFFICIAL MODULE / CHAPTER HEADINGS:
                   - If official video chapters or timestamps appear in the transcript header (e.g. [01:10:05] Exception Handling), you MUST USE THOSE EXACT MODULE NAMES as your section headings (e.g. `## Module: Exception Handling [01:10:05]`).
                   - Place ALL explanations, code snippets (e.g. @ExceptionHandler, handleGenericException, ResponseEntity<ApiError>, @RestControllerAdvice), definitions, and worked examples for that section under that EXACT module header!
                2. IF OFFICIAL VIDEO CHAPTERS ARE NOT PROVIDED:
                   - Create clear, logical, textbook-grade module headings yourself based strictly on the content in that section (e.g. `## Module 1: Architecture & Entity Models [00:00:00]`).
                3. ZERO HALLUCINATIONS / NO GENERIC FILLER:
                   - Write notes strictly and exclusively from what is taught in the video transcript at each timestamp.
                   - Do NOT output generic textbook topics (like "Secure Secret Key Storage" or "Token Blacklisting") if they are NOT taught at that timestamp in the video!
                4. 100%% EXHAUSTIVE LINE-BY-LINE COVERAGE (START TO VERY END):
                   - Cover EVERY single concept, explanation, formula, code example, derivation, and concept in the transcript from [00:00:00] to the final second.
                   - Write full, commented, working code blocks for every code example demonstrated in the video.
                5. AUTHENTIC STUDENT NOTEBOOK FORMAT & ANNOTATIONS:
                   - Master Notebook Title: `# 📓 [Video Title] — Student Study Notes`
                   - Module Headings: `## Module: [Exact Chapter / Module Name] [Timestamp]`
                   - Subtopics: `### [Detailed Sub-Concept]`
                   - Boxed Definitions:
                     > 📖 **Definition: [Term]**
                     > [Clear, intuitive explanation of the concept]
                   - Exam Highlights:
                     ★ **Important:** [Crucial exam point, key takeaway, or memory trick]
                   - Formulas & Code Syntax Boxes:
                     > 📐 **Formula / Syntax:**
                     > `[Formula or key code syntax with explanation of terms]`
                   - Worked Practical Code Examples:
                     > 💡 **Worked Example:** [Step-by-step worked example or practical code snippet with line-by-line intuition]
                   - Pitfalls & Traps:
                     > ⚠️ **Common Mistake:** [Frequent error to avoid]
                   - Bullet Points & Sequences: Use `→` for step-by-step sequences and `•` for item lists.
                6. %s
                7. Do NOT include meta-introductory text or conversational filler. Start directly with the notebook title.

                ---

                **PART 1: Handwritten Detailed Notebook Notes**
                Write complete, deeply detailed student notebook notes based strictly on the transcript from start to the very end of the video.

                Then write EXACTLY this separator line on its own line:
                ===REVISION_NOTES===

                **PART 2: Handwritten Quick Revision Sheet**
                Create a high-yield notebook cheat sheet:
                - `## 📚 Key Concept Rapid Recap` (bullet points with `→`)
                - `## ⚡ Core Rules & Definitions`
                - `## 📝 Quick Syntax & Formula Cheat Box`
                - `## 🧠 Fast Recall Q&A` (20-25 concise flashcard pairs `**Q:** ...` / `**A:** ...`)

                ---
                TRANSCRIPT FOR VIDEO "%s":
                %s
                """.formatted(videoTitle, getDiagramInstruction(includeDiagrams), videoTitle, transcript);
    }

    private String buildHandwrittenChunkNotesPrompt(String videoTitle, String chunk, int chunkIndex, int totalChunks, boolean includeDiagrams) {
        boolean isFinalChunk = (chunkIndex == totalChunks - 1);
        String finalInstruction = isFinalChunk ?
                "4. FINAL PART REQUIREMENT: This is Part " + (chunkIndex + 1) + " of " + totalChunks + " (the FINAL section of the video). Cover all concepts up to the very last second and conclude with '🎓 Notebook Summary & Key Takeaways'." : "";

        return """
                You are a dedicated computer science student writing neat, deeply comprehensive handwritten notebook study notes for PART %d of %d of the video titled "%s".

                STRICT ACCURACY & MODULE MATCHING RULES (MAN SE KUCH BHI MAT BANANA):
                1. OFFICIAL MODULE / CHAPTER HEADINGS:
                   - If official video chapters or timestamps appear in this transcript chunk (e.g. [01:10:05] Exception Handling), you MUST USE THOSE EXACT MODULE NAMES as your section headings (e.g. `## Module: Exception Handling [01:10:05]`).
                   - Place ALL explanations, code snippets, definitions, and worked examples for that timestamp under that EXACT module header!
                2. IF OFFICIAL VIDEO CHAPTERS ARE NOT PROVIDED:
                   - Create clear, logical, textbook-grade module headings yourself based strictly on the content in this chunk.
                3. ZERO HALLUCINATIONS / NO GENERIC FILLER:
                   - Write notes strictly and exclusively from what is taught in this transcript chunk. Do NOT invent generic filler topics not in the video.
                4. 100%% EXHAUSTIVE LINE-BY-LINE COVERAGE FOR THIS PART:
                   - Process EVERY SINGLE LINE of this transcript chunk. Do NOT skip, summarize, or condense ANY content.
                   - Include full, commented code snippets for code demonstrated in the video.
                5. AUTHENTIC NOTEBOOK FORMATTING:
                   - `# Part %d: [Course Section]`
                   - `## Module: [Exact Chapter / Module Name] [Timestamp]`
                   - `### [Subtopic Name]`
                   - `> 📖 **Definition: [Term]**` for key definitions
                   - `★ **Important:** [Key concept / exam takeaway]` for critical points
                   - `→` for sequential steps and bullet points
                   - `> 📐 **Formula / Syntax:**` with formulas and code syntax
                   - `> 💡 **Worked Example:**` for concrete worked examples
                   - `> ⚠️ **Common Mistake:**` for errors to watch out for
                6. %s
                %s
                7. No fluff or conversational intro. Start directly with the section content.

                ---
                Transcript Chunk Part %d of %d for "%s":
                %s
                """.formatted(chunkIndex + 1, totalChunks, videoTitle, chunkIndex + 1, getDiagramInstruction(includeDiagrams), finalInstruction, chunkIndex + 1, totalChunks, videoTitle, chunk);
    }

    private String buildHandwrittenMergePrompt(String videoTitle, List<String> allChunkNotes, boolean includeDiagrams) {
        String combined = String.join("\n\n---\n\n", allChunkNotes);

        return """
                You are creating an aesthetic Quick Revision Notebook Cheat Sheet compiled from %d sections of the video titled "%s".

                STRUCTURE:
                # ✍️ Quick Revision Sheet: %s

                ## 📚 Complete Topic-by-Topic Recap
                - Bulleted points with `→` covering each module in order.

                ## ⚡ Core Definitions & Exam Rules
                - Key definitions formatted with `> 📖 **Definition: [Term]**` and `★ **Important:**`.

                ## 📝 Syntax & Formula Reference Box
                - Concise tables and syntax snippets.

                %s

                ## 🧠 Quick Flashcard Recall
                - 15-20 rapid questions & answers (`**Q:** ...` / `**A:** ...`).

                ---
                Compiled Notes from all parts of "%s":
                %s
                """.formatted(allChunkNotes.size(), videoTitle, videoTitle, getDiagramInstruction(includeDiagrams), videoTitle, combined);
    }

    private String buildHandwrittenMetadataPartPrompt(String videoTitle, String description, String author, String duration, List<String> keywords, int partNum, boolean includeDiagrams) {
        String kwList = (keywords != null && !keywords.isEmpty()) ? String.join(", ", keywords) : "N/A";
        String descText = (description != null && !description.isBlank()) ? description.trim() : "No detailed description provided.";
        String durText = (duration != null && !duration.isBlank()) ? duration : "Full Length Course";

        String sectionFocus = switch (partNum) {
            case 1 -> "PART 1 OF 4: FOUNDATIONS & CORE CONCEPTS (Introduction, Environment Setup, Core Building Blocks, Fundamental Principles, Core Terminology).";
            case 2 -> "PART 2 OF 4: INTERMEDIATE CONCEPTS & PRACTICAL APPLICATION (Intermediate Techniques, Essential Patterns, Data Handling, Common Workflows, Hands-On Implementations).";
            case 3 -> "PART 3 OF 4: ADVANCED TECHNIQUES & ARCHITECTURE (Advanced Concepts, Architectural Patterns, Performance Considerations, Integrations, Complex Workflows).";
            default -> "PART 4 OF 4: REAL-WORLD PROJECTS, OPTIMIZATION & DEPLOYMENT (Real-World Patterns, Best Practices, Scalable Architecture, Testing, Production Deployment). Conclude with '🎓 Final Notebook Takeaways'.";
        };

        return """
                You are creating handwritten-style notebook study notes for a student watching a comprehensive course.

                COURSE INFO:
                - Title: "%s" (%s)
                - Channel / Author: %s
                - Keywords: %s
                - Focus: %s

                OUTLINE:
                %s

                RULES:
                1. Write in clear, structured notebook format with handwriting feel.
                2. Use:
                   - `##` Section Headings
                   - `> 📖 **Definition: [Term]**` for definitions
                   - `★ **Important:** [Key concept]` for exam notes
                   - `→` for points & steps
                   - `> 📐 **Formula / Syntax:**` with code or equations
                   - `> 💡 **Example:**` for examples
                3. %s
                5. Do NOT include conversational intros. Start immediately with content.
                """.formatted(videoTitle, durText, author != null ? author : "YouTube Creator", kwList, sectionFocus, descText, getDiagramInstruction(includeDiagrams));
    }

    private String buildHandwrittenMetadataRevisionPrompt(String videoTitle, String description, String author, String duration, List<String> keywords) {
        String durText = (duration != null && !duration.isBlank()) ? duration : "Full Length Course";

        return """
                You are creating an aesthetic Quick Revision Notebook Cheat Sheet covering the ENTIRE %s course titled "%s".

                STRUCTURE:
                # ✍️ Quick Revision Sheet: %s

                ## 📚 Topic-by-Topic Fast Walkthrough
                - Bulleted points with `→` covering each module from beginning to end.

                ## ⚡ Must-Know Definitions & Core Laws
                - Key definitions formatted with `> 📖 **Definition:**` and `★ **Important:**`.

                ## 📝 Syntax, Commands & Formula Cheat Box
                - Concise reference tables and syntax.

                ## 🧠 High-Yield Flashcard Q&A
                - 15-20 rapid-fire flashcard pairs (`**Q:** ...` / `**A:** ...`).
                """.formatted(durText, videoTitle, videoTitle);
    }
}

