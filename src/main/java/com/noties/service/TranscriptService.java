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

    // Immutable records to return transcript text and video metadata together
    public record TranscriptResult(String text, int segmentCount) {}

    public record VideoMetadata(String title, String description, String author, String duration, List<String> keywords) {}

    public record VideoData(
            String videoId,
            String title,
            String description,
            String author,
            String duration,
            List<String> keywords,
            String transcriptText,
            int segmentCount,
            boolean hasTranscript
    ) {}

    // ── Fetch Video Data & Metadata ─────────────────────────────────────

    public VideoData fetchVideoData(String videoId) {
        log.info("Fetching video data & metadata for video: {}", videoId);
        VideoMetadata metadata = fetchVideoMetadata(videoId);

        try {
            TranscriptResult result = fetchTranscript(videoId);
            if (result != null && result.text() != null && !result.text().isBlank()) {
                return new VideoData(
                        videoId,
                        metadata.title(),
                        metadata.description(),
                        metadata.author(),
                        metadata.duration(),
                        metadata.keywords(),
                        result.text(),
                        result.segmentCount(),
                        true
                );
            }
        } catch (Exception e) {
            log.warn("Transcript unavailable for video {}: {}. Using video metadata mode.", videoId, e.getMessage());
        }

        return new VideoData(
                videoId,
                metadata.title(),
                metadata.description(),
                metadata.author(),
                metadata.duration(),
                metadata.keywords(),
                "",
                0,
                false
        );
    }

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

            // Format fragments cleanly
            StringBuilder sb = new StringBuilder();
            for (var frag : fragments) {
                String text = frag.getText().replace("\n", " ").trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n");
                }
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
                    log.warn("Page scrape fallback failed for {}: {}. Trying timedtext API fallback...", videoId, fallback2.getMessage());
                    try {
                        return fetchTranscriptViaTimedText(videoId);
                    } catch (Exception fallback3) {
                        log.warn("All transcript fetch methods failed for {}", videoId);
                        throw new RuntimeException("Could not retrieve video captions/subtitles", e);
                    }
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

        if (!captionUrl.contains("fmt=")) {
            captionUrl += "&fmt=xml";
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
                .header("Cookie", "CONSENT=PENDING+999; YES=cb")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        int idx = html.indexOf("\"captionTracks\":");
        if (idx == -1) {
            throw new RuntimeException("No caption tracks in page HTML");
        }

        String jsonSubstring = html.substring(idx + 16);
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

        if (!captionUrl.contains("fmt=")) {
            captionUrl += "&fmt=xml";
        }

        return fetchAndParseCaptionXml(captionUrl);
    }

    // ── Shared: Fetch and parse caption XML ────────────────────────────

    private TranscriptResult fetchAndParseCaptionXml(String captionUrl) throws Exception {
        StringBuilder sb = new StringBuilder();
        int totalSegments = 0;
        double lastStartSeconds = 0;
        int maxPages = 15; // Support up to 15 pages for multi-hour videos

        for (int page = 0; page < maxPages; page++) {
            String currentUrl = captionUrl;
            if (page > 0 && lastStartSeconds > 0) {
                int startSec = (int) Math.floor(lastStartSeconds) + 1;
                currentUrl += (captionUrl.contains("?") ? "&" : "?") + "start=" + startSec;
            }

            HttpRequest xmlRequest = HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> xmlResponse = http.send(xmlRequest, HttpResponse.BodyHandlers.ofString());
            String xml = xmlResponse.body();
            if (xml == null || xml.isBlank()) break;

            int pageSegments = 0;
            double pageMaxStart = lastStartSeconds;

            java.util.regex.Matcher matcher = Pattern.compile("<text\\s+start=\"([^\"]+)\"[^>]*>(.*?)</text>", Pattern.DOTALL).matcher(xml);
            while (matcher.find()) {
                double start = Double.parseDouble(matcher.group(1));
                if (start <= lastStartSeconds && page > 0) continue;
                String text = decodeHtmlEntities(matcher.group(2)).replaceAll("<[^>]+>", "").replace("\n", " ").trim();
                if (text.isEmpty()) continue;

                sb.append(text).append("\n");
                pageSegments++;
                totalSegments++;
                if (start > pageMaxStart) pageMaxStart = start;
            }

            if (pageSegments == 0) {
                // Fallback for <p t="12345" ...> XML format
                java.util.regex.Matcher pMatcher = Pattern.compile("<p\\s+t=\"(\\d+)\"[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(xml);
                while (pMatcher.find()) {
                    double start = Double.parseDouble(pMatcher.group(1)) / 1000.0;
                    if (start <= lastStartSeconds && page > 0) continue;
                    String text = decodeHtmlEntities(pMatcher.group(2)).replaceAll("<[^>]+>", "").replace("\n", " ").trim();
                    if (text.isEmpty()) continue;

                    sb.append(text).append("\n");
                    pageSegments++;
                    totalSegments++;
                    if (start > pageMaxStart) pageMaxStart = start;
                }
            }

            if (pageSegments < 50 || pageMaxStart <= lastStartSeconds) {
                break;
            }

            lastStartSeconds = pageMaxStart;
            log.info("Parsed caption page {} up to {}s ({} total segments so far)", page + 1, (int) lastStartSeconds, totalSegments);
        }

        if (totalSegments == 0) {
            throw new RuntimeException("No segments parsed from caption XML");
        }

        log.info("Full caption XML parsed successfully ({} total segments across all pages)", totalSegments);
        return new TranscriptResult(sb.toString().trim(), totalSegments);
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

    // ── Fallback 3: Direct TimedText API ────────────────────────────────

    private TranscriptResult fetchTranscriptViaTimedText(String videoId) throws Exception {
        String[] langs = {"en", "en-US", "en-GB", "a.en"};
        for (String lang : langs) {
            try {
                String captionUrl = "https://www.youtube.com/api/timedtext?v=" + videoId + "&lang=" + lang;
                return fetchAndParseCaptionXml(captionUrl);
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("TimedText API returned no captions");
    }

    // ── Fetch Video Metadata (Title, Description, Author, Keywords) ─────

    public VideoMetadata fetchVideoMetadata(String videoId) {
        String title = "YouTube Video";
        String description = "";
        String author = "YouTube Creator";
        String duration = "Unknown Duration";
        List<String> keywords = new ArrayList<>();

        try {
            String payload = """
                    {
                        "context": {
                            "client": {
                                "clientName": "WEB",
                                "clientVersion": "2.20240530.00.00",
                                "hl": "en",
                                "gl": "US"
                            }
                        },
                        "videoId": "%s"
                    }
                    """.formatted(videoId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.youtube.com/youtubei/v1/player?key=" + YT_INNERTUBE_KEY + "&prettyPrint=false"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode videoDetails = root.path("videoDetails");
                if (!videoDetails.isMissingNode()) {
                    if (videoDetails.has("title")) title = videoDetails.path("title").asText(title);
                    if (videoDetails.has("shortDescription")) description = videoDetails.path("shortDescription").asText(description);
                    if (videoDetails.has("author")) author = videoDetails.path("author").asText(author);
                    if (videoDetails.has("lengthSeconds")) {
                        long totalSecs = videoDetails.path("lengthSeconds").asLong(0);
                        long h = totalSecs / 3600;
                        long m = (totalSecs % 3600) / 60;
                        duration = (h > 0) ? String.format("%d Hours %d Minutes", h, m) : String.format("%d Minutes", m);
                    }
                    if (videoDetails.has("keywords") && videoDetails.path("keywords").isArray()) {
                        for (JsonNode kw : videoDetails.path("keywords")) {
                            keywords.add(kw.asText());
                        }
                    }
                    return new VideoMetadata(title, description, author, duration, keywords);
                }
            }
        } catch (Exception e) {
            log.warn("InnerTube metadata fetch failed for {}: {}", videoId, e.getMessage());
        }

        title = fetchVideoTitle(videoId);
        return new VideoMetadata(title, description, author, duration, keywords);
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
