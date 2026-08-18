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
            log.warn("Primary transcript fetch failed for {}: {}. Trying InnerTube fallback...", videoId, e.getMessage());
            try {
                return fetchTranscriptViaInnerTube(videoId);
            } catch (Exception fallback1) {
                log.warn("InnerTube fallback failed for {}: {}. Trying page scrape fallback...", videoId, fallback1.getMessage());
                try {
                    return fetchTranscriptViaPageScrape(videoId);
                } catch (Exception fallback2) {
                    log.error("All transcript fetch methods failed for {}", videoId);
                    throw new RuntimeException("Could not retrieve video captions/subtitles", e);
                }
            }
        }
    }

    // ── Fallback 1: YouTube InnerTube API ──────────────────────────────

    private static final String YT_INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";

    private TranscriptResult fetchTranscriptViaInnerTube(String videoId) throws Exception {
        // Try multiple InnerTube client contexts — each has different IP blocking thresholds
        // Order: IOS (least blocked) → ANDROID → TV_EMBEDDED → WEB → MWEB
        String[][] clients = {
                // { clientName, clientVersion, userAgent, clientNameId }
                {"IOS", "19.29.1", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)", "5"},
                {"ANDROID", "19.29.37", "com.google.android.youtube/19.29.37 (Linux; U; Android 14; en_US) gzip", "3"},
                {"TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) AppleWebKit/537.36 Chrome/85.0.4183.93 Safari/537.36", "85"},
                {"WEB", "2.20240530.00.00", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36", "1"},
                {"MWEB", "2.20240530.00.00", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36", "2"},
        };

        Exception lastError = null;

        for (String[] client : clients) {
            try {
                log.info("Trying InnerTube client {} for video {}", client[0], videoId);
                String captionUrl = getCaptionUrlViaInnerTube(videoId, client[0], client[1], client[2], client[3]);
                if (captionUrl != null) {
                    return fetchAndParseCaptionXml(captionUrl);
                }
            } catch (Exception ex) {
                log.warn("InnerTube client {} failed for {}: {}", client[0], videoId, ex.getMessage());
                lastError = ex;
            }
        }

        throw lastError != null ? lastError : new RuntimeException("All InnerTube clients failed");
    }

    private String getCaptionUrlViaInnerTube(String videoId, String clientName, String clientVersion, String userAgent, String clientNameId) throws Exception {
        // Build client-specific fields
        String extraClientFields = "";
        if (clientName.equals("ANDROID")) {
            extraClientFields = ", \"androidSdkVersion\": 34, \"osName\": \"Android\", \"osVersion\": \"14\", \"platform\": \"MOBILE\"";
        } else if (clientName.equals("IOS")) {
            extraClientFields = ", \"deviceMake\": \"Apple\", \"deviceModel\": \"iPhone16,2\", \"osName\": \"iOS\", \"osVersion\": \"17.5.1.21F90\", \"platform\": \"MOBILE\"";
        }

        String thirdParty = (clientName.contains("EMBED") || clientName.contains("TV"))
                ? ", \"thirdParty\": { \"embedUrl\": \"https://www.youtube.com\" }"
                : "";

        String payload = """
                {
                    "context": {
                        "client": {
                            "clientName": "%s",
                            "clientVersion": "%s",
                            "hl": "en",
                            "gl": "US"%s
                        }%s
                    },
                    "videoId": "%s"
                }
                """.formatted(clientName, clientVersion, extraClientFields, thirdParty, videoId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.youtube.com/youtubei/v1/player?key=" + YT_INNERTUBE_KEY + "&prettyPrint=false"))
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("X-YouTube-Client-Name", clientNameId)
                .header("X-YouTube-Client-Version", clientVersion)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("InnerTube HTTP " + response.statusCode() + " for client " + clientName);
        }

        JsonNode root = mapper.readTree(response.body());

        // Check for playability errors
        JsonNode status = root.path("playabilityStatus").path("status");
        if (!status.isMissingNode() && !"OK".equals(status.asText())) {
            String reason = root.path("playabilityStatus").path("reason").asText("unknown");
            throw new RuntimeException("Video not playable (" + clientName + "): " + reason);
        }

        // Extract caption track URLs
        JsonNode captionTracks = root.path("captions")
                .path("playerCaptionsTracklistRenderer")
                .path("captionTracks");

        if (captionTracks.isMissingNode() || !captionTracks.isArray() || captionTracks.size() == 0) {
            throw new RuntimeException("No caption tracks in response (" + clientName + ")");
        }

        // Prefer English, fallback to first available
        String captionUrl = null;
        for (JsonNode track : captionTracks) {
            String lang = track.path("languageCode").asText("");
            if (lang.startsWith("en")) {
                captionUrl = track.path("baseUrl").asText();
                break;
            }
        }
        if (captionUrl == null) {
            captionUrl = captionTracks.get(0).path("baseUrl").asText("");
        }

        if (captionUrl.isEmpty()) {
            throw new RuntimeException("Empty caption URL (" + clientName + ")");
        }

        log.info("Got caption URL via InnerTube client {}", clientName);
        return captionUrl;
    }

    // ── Fallback 2: Page scrape (legacy approach) ──────────────────────

    private TranscriptResult fetchTranscriptViaPageScrape(String videoId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.youtube.com/watch?v=" + videoId))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Cookie", "CONSENT=PENDING+987")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        int captionTracksIndex = html.indexOf("\"captionTracks\":");
        if (captionTracksIndex == -1) {
            throw new RuntimeException("No caption tracks found in page HTML (possible consent page or IP block)");
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
            if (track.has("languageCode") && track.get("languageCode").asText("").startsWith("en")) {
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

        return fetchAndParseCaptionXml(captionUrl);
    }

    // ── Shared: Fetch and parse caption XML ────────────────────────────

    private TranscriptResult fetchAndParseCaptionXml(String captionUrl) throws Exception {
        HttpRequest xmlRequest = HttpRequest.newBuilder()
                .uri(URI.create(captionUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> xmlResponse = http.send(xmlRequest, HttpResponse.BodyHandlers.ofString());
        String xml = xmlResponse.body();

        java.util.regex.Matcher matcher = Pattern.compile("<text\\s+start=\"([^\"]+)\"[^>]*>(.*?)</text>", Pattern.DOTALL).matcher(xml);
        StringBuilder sb = new StringBuilder();
        int segmentCount = 0;

        while (matcher.find()) {
            double start = Double.parseDouble(matcher.group(1));
            String text = decodeHtmlEntities(matcher.group(2)).replace("\n", " ").trim();
            if (text.isEmpty()) continue;

            int mins = (int) (start / 60);
            int secs = (int) (start % 60);
            sb.append(String.format("[%d:%02d] %s\n", mins, secs, text));
            segmentCount++;
        }

        if (segmentCount == 0) {
            throw new RuntimeException("No segments parsed from caption XML");
        }

        log.info("Caption XML parsed successfully ({} segments)", segmentCount);
        return new TranscriptResult(sb.toString().trim(), segmentCount);
    }

    private String decodeHtmlEntities(String text) {
        String result = text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");

        // Decode numeric HTML entities like &#123;
        java.util.regex.Matcher entityMatcher = Pattern.compile("&#(\\d+);").matcher(result);
        StringBuilder decoded = new StringBuilder();
        while (entityMatcher.find()) {
            char ch = (char) Integer.parseInt(entityMatcher.group(1));
            entityMatcher.appendReplacement(decoded, java.util.regex.Matcher.quoteReplacement(String.valueOf(ch)));
        }
        entityMatcher.appendTail(decoded);
        return decoded.toString();
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
