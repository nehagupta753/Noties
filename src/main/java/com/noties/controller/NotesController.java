package com.noties.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noties.service.ElevenLabsService;
import com.noties.service.GeminiService;
import com.noties.service.TranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class NotesController {

    private static final Logger log = LoggerFactory.getLogger(NotesController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    private final GeminiService gemini;
    private final TranscriptService transcripts;
    private final ElevenLabsService tts;

    public NotesController(GeminiService gemini, TranscriptService transcripts, ElevenLabsService tts) {
        this.gemini = gemini;
        this.transcripts = transcripts;
        this.tts = tts;
    }

    // ── Generate Notes with SSE Progress Stream ──────────────────────────

    @PostMapping(value = "/generate-notes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateNotes(@RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 minutes timeout

        String url = body.get("url");
        if (url == null || url.isBlank()) {
            sendError(emitter, "Please provide a YouTube video URL.");
            return emitter;
        }

        String videoId = transcripts.extractVideoId(url.trim());
        if (videoId == null) {
            sendError(emitter, "Invalid YouTube URL. Please check the link.");
            return emitter;
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);

        executor.submit(() -> {
            try {
                sendProgress(emitter, "Fetching video title...", 5);
                String title = transcripts.fetchVideoTitle(videoId);

                log.info("[Req:{}] Starting note generation for videoId: '{}', title: '{}'", requestId, videoId, title);

                sendProgress(emitter, "Extracting video transcript...", 10);
                String transcript;
                int segmentCount;
                try {
                    var result = transcripts.fetchTranscript(videoId);
                    if (result == null || result.text() == null || result.text().isBlank()) {
                        throw new RuntimeException("Transcript returned empty text");
                    }
                    transcript = result.text();
                    segmentCount = result.segmentCount();
                } catch (Exception e) {
                    log.warn("[Req:{}] Transcript fetch failed for videoId '{}': {}. Aborting generation.", requestId, videoId, e.getMessage());
                    sendError(emitter, "Transcript could not be fetched for this video.");
                    return;
                }

                sendProgress(emitter, "Transcript extracted!", 15);

                String preview = transcript.substring(0, Math.min(200, transcript.length())).replace("\n", " ");
                log.info("[Req:{}] Transcript validated. Chars: {}, Segments: {}. Preview: '{}'",
                        requestId, transcript.length(), segmentCount, preview);

                String detailedNotes;
                String revisionNotes;

                int CHUNK_THRESHOLD = 60_000;
                int CHUNK_SIZE = 50_000;

                if (transcript.length() < CHUNK_THRESHOLD) {
                    // Short video: single call generates both detailed notes and revision sheet
                    sendProgress(emitter, "Generating notes with AI...", 30);
                    var notes = gemini.generateNotes(title, transcript);
                    detailedNotes = notes.getOrDefault("detailed", "");
                    revisionNotes = notes.getOrDefault("revision", "");
                } else {
                    // Long video: split into chunks and process in parallel
                    List<String> chunks = transcripts.splitTranscriptIntoChunks(transcript, CHUNK_SIZE);
                    log.info("[Req:{}] Long video: processing {} chunks in parallel", requestId, chunks.size());
                    sendProgress(emitter, "Long video detected! Processing in " + chunks.size() + " parts...", 20);

                    CompletableFuture<String>[] futures = new CompletableFuture[chunks.size()];

                    for (int i = 0; i < chunks.size(); i++) {
                        final int idx = i;
                        final String chunk = chunks.get(i);

                        futures[idx] = CompletableFuture.supplyAsync(() -> {
                            String chunkNotes = gemini.generateNotesForChunk(title, chunk, idx, chunks.size());
                            int progress = 20 + Math.round(((float) (idx + 1) / chunks.size()) * 55);
                            sendProgress(emitter, "Generated notes for part " + (idx + 1) + " of " + chunks.size() + "...", progress);
                            return chunkNotes;
                        }, executor);
                    }

                    CompletableFuture.allOf(futures).join();

                    List<String> chunkResults = new ArrayList<>();
                    for (var f : futures) {
                        chunkResults.add(f.join());
                    }

                    detailedNotes = String.join("\n\n---\n\n", chunkResults);

                    sendProgress(emitter, "Creating comprehensive revision notes...", 80);
                    revisionNotes = gemini.generateConsolidatedRevision(title, chunkResults);
                }

                log.info("[Req:{}] Note generation complete for videoId: '{}'", requestId, videoId);

                // Send complete event with full payload
                Map<String, Object> completeEvent = Map.of(
                        "type", "complete",
                        "requestId", requestId,
                        "notes", detailedNotes,
                        "revision", revisionNotes,
                        "videoId", videoId,
                        "videoTitle", title,
                        "transcriptLength", segmentCount
                );

                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(completeEvent)).build());
                emitter.complete();

            } catch (Exception e) {
                log.error("[Req:{}] Note generation error for videoId '{}': {}", requestId, videoId, e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage() : "";

                String errorMsg = "Something went wrong while generating notes. Please try again.";
                if (msg.contains("API key")) {
                    errorMsg = "Missing or invalid Gemini API key in .env file.";
                } else if (msg.matches("(?i).*(503|demand|overloaded|Unavailable).*")) {
                    errorMsg = "Gemini servers are busy right now. Please try again in a moment.";
                } else if (msg.matches("(?i).*(quota|rate|limit|429).*")) {
                    errorMsg = "API rate limit reached. Please wait a moment or add another key.";
                }

                sendError(emitter, errorMsg);
            }
        });

        return emitter;
    }

    private void sendProgress(SseEmitter emitter, String message, int progress) {
        try {
            Map<String, Object> payload = Map.of("type", "progress", "message", message, "progress", progress);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(payload)).build());
        } catch (Exception ignored) {}
    }

    private void sendError(SseEmitter emitter, String errorMsg) {
        try {
            Map<String, Object> payload = Map.of("type", "error", "error", errorMsg);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(payload)).build());
            emitter.complete();
        } catch (Exception ignored) {}
    }

    // ── Panda Chat Endpoint ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @PostMapping("/panda-chat")
    public ResponseEntity<?> pandaChat(@RequestBody Map<String, Object> body) {
        String message = (String) body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please provide a message."));
        }

        var history = (List<Map<String, String>>) body.get("history");
        boolean isExplanation = Boolean.TRUE.equals(body.get("isExplanation"));

        try {
            String reply = gemini.pandaChat(message, history, isExplanation);
            return ResponseEntity.ok(Map.of("success", true, "reply", reply));

        } catch (Exception e) {
            log.error("Panda chat error: {}", e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage() : "";

            if (msg.matches("(?i).*(quota|rate|limit|429).*"))
                return ResponseEntity.status(429).body(Map.of("error", "Pandy hit a rate limit! Try again in a minute 🐼", "retryable", true));
            if (msg.matches("(?i).*(503|overloaded|Unavailable).*"))
                return ResponseEntity.status(503).body(Map.of("error", "Pandy is busy right now. Try again in a moment! 🐼", "retryable", true));

            return ResponseEntity.status(500).body(Map.of("error", "Panda is resting. Please try chatting again shortly! 🐼", "retryable", true));
        }
    }

    // ── ElevenLabs TTS Endpoints ────────────────────────────────────────

    @GetMapping("/tts-status")
    public Map<String, Object> ttsStatus() {
        return Map.of("enabled", tts.isConfigured());
    }

    @PostMapping("/panda-speak")
    public ResponseEntity<?> pandaSpeak(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) return ResponseEntity.badRequest().build();
        if (!tts.isConfigured()) return ResponseEntity.status(503).build();

        try {
            byte[] audio = tts.synthesizeSpeech(text);
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("audio/mpeg"))
                    .header("Cache-Control", "no-cache")
                    .body(audio);
        } catch (Exception e) {
            log.error("TTS synthesis error: {}", e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }
}
