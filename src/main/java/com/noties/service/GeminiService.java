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

    // Generate detailed notes and revision sheet from video metadata (title, description, author, duration, keywords)
    public Map<String, String> generateNotesFromMetadata(String videoTitle, String description, String author, String duration, List<String> keywords) {
        boolean isLongCourse = duration != null && (duration.contains("Hours") || duration.contains("hour") || duration.contains("hr"));

        if (isLongCourse) {
            log.info("Long full-course video detected in metadata mode (duration: {}). Generating 4 comprehensive course parts + revision sheet...", duration);

            // Part 1: Foundations & Core Architecture
            String prompt1 = buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 1);
            String part1Notes = callWithRetry(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt1)))),
                    "generationConfig", Map.of("maxOutputTokens", 65536)
            ), null);

            // Part 2: State Management, Forms & Effect Hooks
            String prompt2 = buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 2);
            String part2Notes = callWithRetry(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt2)))),
                    "generationConfig", Map.of("maxOutputTokens", 65536)
            ), null);

            // Part 3: Advanced Hooks, Routing & Global State Management
            String prompt3 = buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 3);
            String part3Notes = callWithRetry(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt3)))),
                    "generationConfig", Map.of("maxOutputTokens", 65536)
            ), null);

            // Part 4: Real-World Projects, Performance Optimization & Production Deployment
            String prompt4 = buildMetadataPartPrompt(videoTitle, description, author, duration, keywords, 4);
            String part4Notes = callWithRetry(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt4)))),
                    "generationConfig", Map.of("maxOutputTokens", 65536)
            ), null);

            // Part 5: Comprehensive Revision Sheet
            String promptRev = buildMetadataRevisionPrompt(videoTitle, description, author, duration, keywords);
            String revisionNotes = callWithRetry(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", promptRev)))),
                    "generationConfig", Map.of("maxOutputTokens", 65536)
            ), null);

            String detailedNotes = part1Notes.trim() + "\n\n---\n\n" + part2Notes.trim() + "\n\n---\n\n" + part3Notes.trim() + "\n\n---\n\n" + part4Notes.trim();
            return Map.of("detailed", detailedNotes, "revision", revisionNotes.trim());

        } else {
            String prompt = buildMetadataNotesPrompt(videoTitle, description, author, duration, keywords);
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
                2. NO TOPIC SWITCHING: Do NOT change the subject, do NOT invent another topic, and do NOT reuse knowledge from other videos.
                3. FULL CHRONOLOGICAL COVERAGE (FROM START TO VERY END):
                   - Cover every concept, formula, mechanism, code snippet, definition, and insight from start to the VERY LAST SECOND of the transcript.
                   - Do NOT cut off early, do NOT stop midway, and do NOT skip ending topics or conclusions.
                4. RICH FORMATTING & HIERARCHY:
                   - Use clean markdown `#`, `##`, `###` headers for logical module separation.
                   - Use bold text for key terms, definitions, and important syntax.
                   - Use bullet points and numbered lists for readability.
                   - For tutorials/technical topics: Provide clean, commented, fully explained code blocks or command sequences.
                   - Highlight major takeaways with `✅ **Key Takeaway:** ...` and pro-tips with `💡 **Pro Tip:** ...`.
                5. DO NOT include meta commentary (like "In this video...", "Here are your notes..."). Start directly with the main title and structured content.
                
                ---
                
                **PART 1: Detailed Study Notes**
                Write a complete, beautifully structured, thorough textbook-grade reference guide based ONLY on the transcript from start to the very end of the video.
                
                Then write EXACTLY this separator line on its own line:
                ===REVISION_NOTES===
                
                **PART 2: Quick Revision & Exam Cheat Sheet**
                Create an exhaustive, high-yield summary designed for rapid review based ONLY on the transcript:
                - `## 📚 Topic-by-Topic Fast Recap`: 1-2 sentence bullet points per concept in chronological order.
                - `## ⚡ Core Principles & Definitions`: Must-know laws, formulas, theorems, and definitions from this transcript.
                - `## 📝 Quick Syntax & Formula Cheat Sheet`: Tables, code snippets, hotkeys, commands, or formulas from this transcript.
                - `## 🧠 High-Yield Flashcard Q&A`: At least 15 clear Question & Answer flashcard pairs (`**Q:** ...` / `**A:** ...`) based on this transcript.
                
                ---
                TRANSCRIPT FOR VIDEO "%s":
                %s
                """.formatted(videoTitle, videoTitle, videoTitle, transcript);
    }

    private String buildChunkNotesPrompt(String videoTitle, String chunk, int chunkIndex, int totalChunks) {
        boolean isFinalChunk = (chunkIndex == totalChunks - 1);
        String finalInstruction = isFinalChunk ?
                "3. CRITICAL FINAL PART REQUIREMENT: This is Part " + (chunkIndex + 1) + " of " + totalChunks + " (the FINAL section of the video transcript). You MUST cover all topics and code examples up to the very LAST line of the transcript. Conclude Part 1 with a '🎓 Final Course Conclusion & Master Takeaways' section." : "";

        return """
                You are a master educator and textbook author.
                You are given PART %d of %d of the transcript for the video titled "%s".
                
                CRITICAL GUARDRAILS:
                1. Cover EVERY concept in THIS SECTION from the first line to the very last line of this chunk. Do NOT skip details or cut off early.
                2. Do NOT change the topic or introduce unrelated subjects.
                %s
                
                FORMATTING RULES:
                - Use clear markdown headers (`## Topic`, `### Subtopic`), bold keywords, and clean bulleted explanations.
                - For programming/math: write full, commented code blocks or formulas with line-by-line intuition.
                - Include `✅ **Key Takeaway**` and `💡 **Pro Tip**` callouts.
                - Do not include conversational filler or meta intros.
                
                ---
                Transcript Chunk %d of %d for "%s":
                %s
                """.formatted(chunkIndex + 1, totalChunks, videoTitle, finalInstruction, chunkIndex + 1, totalChunks, videoTitle, chunk);
    }

    private String buildMergePrompt(String videoTitle, List<String> allChunkNotes) {
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
                
                ## 🧠 Flashcard Recall Q&A
                - Minimum 15-20 rapid-fire flashcards formatted as:
                  - **Q:** [Question]
                    **A:** [Direct, accurate answer]
                
                ---
                Compiled Notes from all parts of "%s":
                %s
                """.formatted(allChunkNotes.size(), videoTitle, allChunkNotes.size(), videoTitle, allChunkNotes.size(), videoTitle, combined);
    }

    private String buildMetadataNotesPrompt(String videoTitle, String description, String author, String duration, List<String> keywords) {
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
                4. DO NOT include meta commentary (like "In this video...", "Here are your notes..."). Start directly with the main title and structured content.
                
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
                """.formatted(videoTitle, durText, author != null ? author : "YouTube Creator", kwList, descText, videoTitle, durText, durText, durText, videoTitle, durText, durText, durText);
    }

    private String buildMetadataPartPrompt(String videoTitle, String description, String author, String duration, List<String> keywords, int partNum) {
        String kwList = (keywords != null && !keywords.isEmpty()) ? String.join(", ", keywords) : "N/A";
        String descText = (description != null && !description.isBlank()) ? description.trim() : "No detailed description provided.";
        String durText = (duration != null && !duration.isBlank()) ? duration : "Full Length Course";

        String sectionFocus = switch (partNum) {
            case 1 -> "PART 1 OF 4: FOUNDATIONS & CORE ARCHITECTURE (Environment Setup with Node/Vite/npm, JSX Rules & Transpilation, Virtual DOM & Fiber Engine, Functional Components & Composition, Props, Component Trees, and Unidirectional Data Flow). Write thorough textbook explanations with full commented code snippets for every module.";
            case 2 -> "PART 2 OF 4: STATE MANAGEMENT, FORMS & EFFECT HOOKS (useState Hook in-depth, Immutable state updates, Event Handling & Forms with e.preventDefault, Conditional Rendering patterns, List Rendering & Key reconciliation, and useEffect Hook lifecycle & cleanup functions). Write thorough textbook explanations with full commented code snippets for every module.";
            case 3 -> "PART 3 OF 4: ADVANCED HOOKS, ROUTING & GLOBAL STATE (Advanced Performance Hooks: useRef for DOM & mutable refs, useMemo for memoization, useCallback for function reference stability; Building Custom Hooks; React Router DOM v6+ with Dynamic Params, Nested Routes & Protected Routes; Context API & Provider Pattern; Global State with Redux Toolkit / Zustand; and Async Data Fetching with Axios/fetch, Loading/Error States). Write thorough textbook explanations with full commented code snippets for every module.";
            default -> "PART 4 OF 4: REAL-WORLD PROJECTS, ARCHITECTURE, OPTIMIZATION & PRODUCTION DEPLOYMENT (Scalable Project Folder Architecture, Error Boundaries, Code Splitting with React.lazy & Suspense, Re-render Prevention Strategies, Environment Variables .env, Production Build with Vite/dist, and Cloud Deployment to Vercel/Netlify/Render/AWS). Conclude with a '🎓 Master Course Summary & Final Key Takeaways' section.";
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
                4. DO NOT include meta commentary (like "In this video...", "Here are your notes..."). Start directly with the structured module headers and content.
                """.formatted(videoTitle, durText, author != null ? author : "YouTube Creator", kwList, descText, sectionFocus, videoTitle);
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
}
