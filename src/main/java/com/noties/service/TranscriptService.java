package com.noties.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TranscriptService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // YouTube URL matching patterns
    private static final Pattern[] VIDEO_ID_PATTERNS = {
            Pattern.compile("(?:youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("(?:youtube\\.com/embed/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("(?:youtube\\.com/v/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("(?:youtu\\.be/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("(?:youtube\\.com/shorts/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("^([a-zA-Z0-9_-]{11})$"),
    };

    // Immutable record to return transcript text and segment count together
    public record TranscriptResult(String text, int segmentCount) {}

    // ── Fetch YouTube Transcript ────────────────────────────────────────

    public TranscriptResult fetchTranscript(String videoId) {
        log.info("Fetching transcript for video: {}", videoId);

        try {
            YoutubeTranscriptApi api = TranscriptApiFactory.createDefault();
            var transcriptList = api.listTranscripts(videoId);

            io.github.thoroldvix.api.Transcript transcript = null;
            try {
                transcript = transcriptList.findTranscript("en");
            } catch (Exception e) {
                // Fallback to first available language if English is not found
                var iterator = transcriptList.iterator();
                if (iterator.hasNext()) {
                    transcript = iterator.next();
                }
            }

            if (transcript == null) {
                throw new RuntimeException("No transcripts available for video ID: " + videoId);
            }

            TranscriptContent content = transcript.fetch();
            var fragments = content.getContent();
            int segmentCount = fragments.size();

            // Format fragments with timestamps: [mm:ss] Text
            StringBuilder sb = new StringBuilder();
            for (var frag : fragments) {
                double start = frag.getStart();
                int mins = (int) (start / 60);
                int secs = (int) (start % 60);
                sb.append(String.format("[%d:%02d] %s\n", mins, secs, frag.getText()));
            }

            log.info("Transcript fetched successfully ({} segments)", segmentCount);
            return new TranscriptResult(sb.toString().trim(), segmentCount);

        } catch (Exception e) {
            log.warn("Primary transcript fetch failed for {}: {}. Attempting fallback...", videoId, e.getMessage());
            try {
                return fetchTranscriptFallback(videoId);
            } catch (Exception fallbackEx) {
                log.error("Fallback fetch also failed for {}: {}", videoId, fallbackEx.getMessage());
                throw new RuntimeException("Could not retrieve video captions/subtitles", e);
            }
        }
    }

    private TranscriptResult fetchTranscriptFallback(String videoId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.youtube.com/watch?v=" + videoId))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        int captionTracksIndex = html.indexOf("\"captionTracks\":");
        if (captionTracksIndex == -1) {
            throw new RuntimeException("No caption tracks found in page HTML");
        }

        String jsonSubstring = html.substring(captionTracksIndex + "\"captionTracks\":".length());
        int arrayEnd = jsonSubstring.indexOf("]");
        if (arrayEnd == -1) {
            throw new RuntimeException("Could not parse caption tracks array");
        }
        String captionTracksJson = jsonSubstring.substring(0, arrayEnd + 1);
        JsonNode tracks = mapper.readTree(captionTracksJson);

        String captionUrl = null;
        for (JsonNode track : tracks) {
            if (track.has("languageCode") && "en".equals(track.get("languageCode").asText())) {
                captionUrl = track.get("baseUrl").asText();
                break;
            }
        }

        if (captionUrl == null && tracks.size() > 0) {
            captionUrl = tracks.get(0).get("baseUrl").asText();
        }

        if (captionUrl == null) {
            throw new RuntimeException("No valid caption URL found");
        }

        HttpRequest xmlRequest = HttpRequest.newBuilder()
                .uri(URI.create(captionUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<String> xmlResponse = http.send(xmlRequest, HttpResponse.BodyHandlers.ofString());
        String xml = xmlResponse.body();

        java.util.regex.Matcher matcher = Pattern.compile("<text\\s+start=\"([^\"]+)\"[^>]*>(.*?)</text>").matcher(xml);
        StringBuilder sb = new StringBuilder();
        int segmentCount = 0;

        while (matcher.find()) {
            double start = Double.parseDouble(matcher.group(1));
            String text = matcher.group(2)
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'");
            
            java.util.regex.Matcher entityMatcher = Pattern.compile("&#(\\d+);").matcher(text);
            StringBuilder unescapedText = new StringBuilder();
            while (entityMatcher.find()) {
                char ch = (char) Integer.parseInt(entityMatcher.group(1));
                entityMatcher.appendReplacement(unescapedText, String.valueOf(ch));
            }
            entityMatcher.appendTail(unescapedText);
            text = unescapedText.toString();

            int mins = (int) (start / 60);
            int secs = (int) (start % 60);
            sb.append(String.format("[%d:%02d] %s\n", mins, secs, text));
            segmentCount++;
        }

        if (segmentCount == 0) {
            throw new RuntimeException("No segments parsed from XML");
        }

        log.info("Fallback transcript fetched successfully ({} segments)", segmentCount);
        return new TranscriptResult(sb.toString().trim(), segmentCount);
    }

    // ── Fetch Video Title (noembed fallback) ─────────────────────────────

    public String fetchVideoTitle(String videoId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://noembed.com/embed?url=https://www.youtube.com/watch?v=" + videoId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode title = mapper.readTree(response.body()).get("title");
                if (title != null && !title.asText().isBlank()) {
                    return title.asText().trim();
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch title for {}: {}", videoId, e.getMessage());
        }
        return "YouTube Video";
    }

    // ── Extract Video ID from URL ───────────────────────────────────────

    public String extractVideoId(String url) {
        if (url == null || url.isBlank()) return null;

        String trimmed = url.trim();
        for (Pattern pattern : VIDEO_ID_PATTERNS) {
            var matcher = pattern.matcher(trimmed);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    // Split long transcripts into smaller chunks for parallel processing
    public List<String> splitTranscriptIntoChunks(String transcript, int maxCharsPerChunk) {
        List<String> chunks = new ArrayList<>();
        String[] lines = transcript.split("\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String line : lines) {
            if (currentChunk.length() + line.length() + 1 > maxCharsPerChunk && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
            }
            currentChunk.append(line).append("\n");
        }
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }
}
