package cn.nastv.subtitles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up subtitles in The Subtitle DB, with Subtitle Cat as the Simplified Chinese fallback. */
public final class SubtitleService {
    private static final String BASE = "https://api.thesubtitledb.org/v1/";
    private static final String CLIENT = "nas-subtitles-tv";
    private static final int MAX_JSON = 2 * 1024 * 1024;
    private static final int MAX_SUBTITLE = 6 * 1024 * 1024;
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)(?:(?<![a-z0-9])S(\\d{1,2})[ ._-]*E(\\d{1,3})|(?<![a-z0-9])(\\d{1,2})x(\\d{1,3})" +
            "|第\\s*(\\d{1,2})\\s*季\\s*第?\\s*(\\d{1,3})\\s*集)(?!\\d)");
    private static final Pattern MORE_EPISODES = Pattern.compile(
            "(?i)[ ._]*(?:[-+&][ ._]*)?(?:S(\\d{1,2})[ ._-]*E|E|(\\d{1,2})x)(\\d{1,3})(?!\\d)" +
            "|[ ._]*[-+&][ ._]*(\\d{1,3})(?![a-z0-9])");
    private static final Pattern QUALITY = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:2160[pi]|1080[pi]|720[pi]|480[pi]|4k|uhd|bluray|blu[ .-]?ray" +
            "|b[dr]rip|web[ .-]?(?:dl|rip)|hdtv|dvdrip|remux|x26[45]|h[ .]?26[45]|hevc" +
            "|av1|hdr10\\+?|dvdr|hdrip|webrip|dovi|atmos)(?![a-z0-9])");
    private static final Pattern TRAILING_YEAR = Pattern.compile("\\s((?:18|19|20)\\d{2})$");
    private static final Pattern IMDB = Pattern.compile("(?i)\\btt\\d{7,10}\\b");
    private static final Pattern SRT_CUE = Pattern.compile(
            "(?m)^[ \\t]*\\d{1,3}:\\d{2}:\\d{2}[,.]\\d{1,3}[ \\t]*-->[ \\t]*\\d{1,3}:\\d{2}:\\d{2}[,.]\\d{1,3}");
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff]");
    private static final Set<String> FORMATS = new HashSet<>(Arrays.asList("srt", "ass", "ssa", "vtt"));
    private static final Pattern GROUP_SUFFIX = Pattern.compile("-([a-z][a-z0-9]{1,24})(?:\\[[^\\]]+\\])?$");
    private static final List<String> RESOLUTIONS = Arrays.asList("2160p", "1080p", "720p", "576p", "480p");
    private static final List<String> SOURCES = Arrays.asList("bluray", "web", "hdtv", "dvd");
    private static final List<String> CUTS = Arrays.asList("directorscut", "extended", "theatrical",
            "finalcut", "unrated", "uncut", "remastered", "specialedition");

    enum Source {
        PRIMARY("The Subtitle DB"), CAT("Subtitle Cat");
        final String label;
        Source(String label) { this.label = label; }
        boolean accepts(URL url) {
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (this == PRIMARY) return host.equals("thesubtitledb.org") || host.endsWith(".thesubtitledb.org");
            return (host.equals("www.subtitlecat.com") || host.equals("subtitlecat.com"))
                    && url.getPath().startsWith("/subs/") && url.getPath().toLowerCase(Locale.ROOT).endsWith(".srt");
        }
    }

    interface FallbackListener { void searchingCat(boolean primaryUnavailable); }

    static final class WrongEpisode extends IOException {
        WrongEpisode() { super("Subtitle Cat 上标为 S01E03 的字幕内容与 S01E01 相同，已跳过错集文件。"); }
    }

    // Check this specific reported upload by content so a corrected file becomes usable automatically.
    static final String REPORTED_CAT_TITLE = "Mistletoe Murders 2024 S01E03 720p WEB-DL HEVC x265 BONE_track3_[eng]";

    private static void checkEpisodeContent(String title, byte[] bytes) throws WrongEpisode {
        if (!REPORTED_CAT_TITLE.equalsIgnoreCase(title)) return;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            if (Base64.getEncoder().encodeToString(digest).equals("dqfRqVG+VkUlteCGJxInNm0v5pFeFoeOJsg6b6KHuVo="))
                throw new WrongEpisode();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static void verifyReportedTrack(SubtitleCat.Track track) throws IOException {
        checkEpisodeContent(track.title, read(Source.CAT, track.url, MAX_SUBTITLE, false));
    }

    public static final class Result {
        final Source source;
        public final String title;
        public final String detail;
        public final String language;
        public final String extension;
        final boolean canAutoSave;
        private final String url;
        private final String encoding;
        private final int score;

        private Result(Source source, String title, String detail, String language, String extension,
                       String url, String encoding, int score, boolean canAutoSave) {
            this.source = source;
            this.title = title;
            this.detail = detail;
            this.language = language;
            this.extension = extension;
            this.url = url;
            this.encoding = encoding;
            this.score = score;
            this.canAutoSave = canAutoSave;
        }
    }

    private static final class Episode {
        final int start;
        final int season;
        final int episode;
        final int last;

        Episode(Matcher found, String text) {
            int index = found.group(1) != null ? 1 : found.group(3) != null ? 3 : 5;
            start = found.start();
            season = Integer.parseInt(found.group(index));
            episode = Integer.parseInt(found.group(index + 1));
            int lastNumber = episode;
            Matcher extra = MORE_EPISODES.matcher(text);
            int offset = found.end();
            while (extra.region(offset, text.length()).lookingAt()) {
                String nextSeason = extra.group(1) != null ? extra.group(1) : extra.group(2);
                if (nextSeason != null && Integer.parseInt(nextSeason) != season) break;
                String number = extra.group(3) != null ? extra.group(3) : extra.group(4);
                int next = Integer.parseInt(number);
                if (next <= lastNumber) break;
                lastNumber = next;
                offset = extra.end();
            }
            last = lastNumber;
        }

        String label() {
            String first = String.format(Locale.ROOT, "S%02dE%02d", season, episode);
            return last == episode ? first : first + String.format(Locale.ROOT, "-E%02d", last);
        }
    }

    private static Episode episodeIn(String text) {
        String normalized = text.replace('_', ' ');
        Matcher found = EPISODE.matcher(normalized);
        return found.find() ? new Episode(found, normalized) : null;
    }

    private static String stem(String filename) {
        if (filename == null) return "";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return filename.substring(slash + 1).replaceFirst("(?i)\\.(mkv|mp4|mov|avi|wmv|m4v|mpeg|mpg|ts|m2ts|mts|webm|flv|vob|iso|rmvb|m2v|3gp|asf|ogm|divx|rm|f4v)$", "");
    }

    private static String cleanTitle(String text) {
        return text.replaceAll("[._()\\[\\]{}]", " ").replaceAll("\\s+", " ")
                .replaceAll("^[\\s-]+|[\\s-]+$", "").trim();
    }

    /** Preserve the year and episode marker in the initial, editable search. */
    public static String suggestQuery(String filename) {
        String name = stem(filename);
        Episode episode = episodeIn(name);
        if (episode != null) {
            return (cleanTitle(name.substring(0, episode.start)) + " " + episode.label()).trim();
        }
        Matcher quality = QUALITY.matcher(name.replace('_', ' '));
        if (quality.find() && quality.start() > 0) name = name.substring(0, quality.start());
        return cleanTitle(name);
    }

    /**
     * The lookup sends the editable title and episode. Filename tokens are compared
     * locally to rank release variants. An IMDb title id also works in the search field.
     */
    public List<Result> search(String query, String filename, String language) throws IOException {
        return search(query, filename, language, failed -> {});
    }

    List<Result> search(String query, String filename, String language, FallbackListener listener) throws IOException {
        return search(query, filename, language, listener, false);
    }

    List<Result> searchBatch(String query, String filename, FallbackListener listener) throws IOException {
        return search(query, filename, "zh-cn", listener, true);
    }

    private List<Result> search(String query, String filename, String language, FallbackListener listener,
                                boolean batch) throws IOException {
        String text = query == null ? "" : query.trim();
        if (text.length() > 300) throw new IOException("片名太长，请缩短搜索词。");
        Episode episode = episodeIn(text);
        String title = cleanTitle(episode == null ? text : text.substring(0, episode.start));
        if (episode == null) episode = episodeIn(stem(filename));
        if (title.isEmpty()) throw new IOException("请输入片名或剧名，再搜索字幕。");
        String code = corpusLanguage(language);
        Matcher imdb = IMDB.matcher(title);
        boolean byId = imdb.find();
        String endpoint = byId ? "by-imdb/" + imdb.group().toLowerCase(Locale.ROOT) : "by-title";
        if (episode != null) endpoint += "/season/" + episode.season + "/episode/" + episode.episode;
        String url = BASE + endpoint + "?lang=" + code + "&limit=100&client=" + CLIENT;
        if (!byId) url += "&q=" + URLEncoder.encode(title, "UTF-8").replace("+", "%20");
        String catalogTitle = byId ? "" : title;
        IOException primaryError = null;
        try {
            byte[] bytes = read(Source.PRIMARY, url, MAX_JSON, true);
            if (bytes != null) {
                JSONObject bundle = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                JSONObject media = bundle.getJSONObject("title");
                List<Result> matches = primaryResults(bundle, media, title, filename, code, language, episode, byId);
                if (!matches.isEmpty()) return matches;
                if (byId) {
                    catalogTitle = media.optString("name", "").trim();
                    int year = media.optInt("year", 0);
                    if (!catalogTitle.isEmpty() && episode == null && year > 0) catalogTitle += " " + year;
                }
            }
        } catch (JSONException e) {
            primaryError = new IOException("The Subtitle DB 返回了无法读取的搜索结果，请稍后再试。", e);
        } catch (IOException e) {
            primaryError = e;
        }
        checkInterrupted();
        if ("zh".equals(code) && !catalogTitle.isEmpty()) {
            listener.searchingCat(primaryError != null);
            String catQuery = catalogTitle + (episode == null ? "" : " " + episode.label());
            SubtitleCat.Matches tracks;
            try {
                tracks = SubtitleCat.search(catQuery, filename, batch);
            } catch (IOException e) {
                checkInterrupted();
                String context = primaryError == null ? "The Subtitle DB 未找到简中字幕。"
                        : "The Subtitle DB：" + primaryError.getMessage();
                throw new IOException(context + "\nSubtitle Cat：" + e.getMessage(), e);
            }
            List<Result> matches = new ArrayList<>();
            ReleaseTags video = new ReleaseTags(stem(filename));
            for (SubtitleCat.Track track : tracks.tracks.values()) {
                boolean compatible = video.compatibleRelease(new ReleaseTags(track.title));
                String detail = compatible ? "简体中文   ·   SRT" : "简体中文   ·   SRT   ·   请核对片源版本";
                matches.add(new Result(Source.CAT, track.title, detail, language, "srt",
                        track.url, "UTF-8", 0, tracks.complete && compatible));
            }
            if (!matches.isEmpty()) return matches;
            if (tracks.wrongEpisode && primaryError == null) throw new WrongEpisode();
            if (primaryError != null) throw new IOException("Subtitle Cat 未找到匹配的简中字幕。\nThe Subtitle DB："
                    + primaryError.getMessage(), primaryError);
        } else if ("zh".equals(code) && byId && primaryError == null) {
            throw new IOException("首选源未识别这个 IMDb 编号。请将搜索词改为片名或剧名，以查询 Subtitle Cat。");
        }
        if (primaryError != null) throw primaryError;
        if (episode != null && episode.last != episode.episode)
            throw new IOException("没有找到标明 " + episode.label() + " 的合并字幕。若要查单集字幕，可把搜索词中的集号改为单集。");
        return Collections.emptyList();
    }

    private List<Result> primaryResults(JSONObject bundle, JSONObject media, String title, String filename,
                                        String code, String language, Episode episode, boolean byId) throws JSONException {
        int year = media.optInt("year", 0);
        String mediaName = media.optString("name", "");
        // Preserve years which are part of a title, such as "Blade Runner 2049".
        Matcher requestedYear = TRAILING_YEAR.matcher(title);
        if (!byId && !cleanTitle(mediaName).equalsIgnoreCase(title) && year != 0 && requestedYear.find()
                && episode == null && year != Integer.parseInt(requestedYear.group(1))) return Collections.emptyList();
        if (episode != null && "movie".equals(media.optString("media_type"))) return Collections.emptyList();
        String actualTitle = titleKey(mediaName);
        String requestedTitle = titleKey(title);
        boolean sameTitle = byId || !actualTitle.isEmpty() && (requestedTitle.equals(actualTitle)
                || year > 0 && requestedTitle.equals(actualTitle + " " + year));
        if (mediaName.isEmpty()) mediaName = title;
        if (year > 0) mediaName += " (" + year + ")";
        if (episode != null) mediaName += " " + episode.label();
        JSONArray items = bundle.getJSONObject("subtitles").getJSONArray("items");
        return results(items, mediaName, stem(filename), code, language, episode, sameTitle);
    }

    private static String titleKey(String text) {
        return text.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("['’]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String corpusLanguage(String language) throws IOException {
        if ("zh-cn".equalsIgnoreCase(language)) return "zh";
        if ("zh-tw".equalsIgnoreCase(language)) return "zt";
        if ("ze".equals(language) || "en".equals(language)) return language;
        throw new IOException("请选择字幕语言。");
    }

    private List<Result> results(JSONArray items, String mediaName, String filename, String code,
                                 String language, Episode episode, boolean sameTitle) {
        List<Result> matches = new ArrayList<>();
        ReleaseTags fileTags = new ReleaseTags(filename);
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null || !code.equals(item.optString("language"))) continue;
            String format = item.optString("format").toLowerCase(Locale.ROOT);
            if (!FORMATS.contains(format)) continue;
            String url = item.optString("download_url");
            if (url.isEmpty()) continue;
            String release = item.isNull("release_name") ? "" : item.optString("release_name").trim();
            if (episode != null) {
                Episode tagged = episodeIn(release);
                if (tagged != null && (tagged.season != episode.season || tagged.episode != episode.episode
                        || tagged.last != episode.last)) continue;
                if (episode.last != episode.episode && tagged == null) continue;
            }
            int score = rank(fileTags, filename, release, format);
            String name = release.isEmpty() ? mediaName + " · #" + item.optLong("id") : release;
            String detail = mediaName + "   ·   " + format.toUpperCase(Locale.ROOT);
            int cues = item.optInt("cues");
            if (cues > 0) detail += "   ·   " + cues + " 条";
            if (item.optBoolean("hearing_impaired")) detail += "   ·   听障字幕";
            if (item.optBoolean("ai_transcribed")) detail += "   ·   AI 转录";
            boolean compatible = fileTags.compatibleRelease(new ReleaseTags(release));
            if (!compatible) detail += "   ·   请核对片源版本";
            boolean canAutoSave = sameTitle && compatible;
            matches.add(new Result(Source.PRIMARY, name, detail, language, format, url, item.optString("encoding"),
                    score, canAutoSave));
        }
        Collections.sort(matches, (a, b) -> Integer.compare(b.score, a.score));
        return matches;
    }

    private static final class ReleaseTags {
        final Set<String> words;
        final String resolution;
        final String source;
        final String cut;
        final String group;

        ReleaseTags(String release) {
            String normalized = release.toLowerCase(Locale.ROOT).replace('_', ' ')
                    .replaceAll("\\b(?:4k|uhd)\\b", "2160p")
                    .replaceAll("\\b(1080|576|480)i\\b", "$1p")
                    .replaceAll("\\b(?:blu[ .-]?ray|bdrip|brrip|bdremux)\\b", "bluray")
                    .replaceAll("\\bweb[ .-]?(?:dl|rip)\\b", "web")
                    .replaceAll("\\bdvd[ .-]?(?:rip|scr)\\b", "dvd")
                    .replaceAll("\\bdirector['’]?s?[ .-]+cut\\b", "directorscut")
                    .replaceAll("\\bfinal[ .-]+cut\\b", "finalcut")
                    .replaceAll("\\bspecial[ .-]+edition\\b", "specialedition");
            String clean = normalized.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
            words = clean.isEmpty() ? Collections.emptySet() : new HashSet<>(Arrays.asList(clean.split(" +")));
            resolution = firstLabel(RESOLUTIONS);
            source = firstLabel(SOURCES);
            cut = firstLabel(CUTS);
            Matcher suffix = GROUP_SUFFIX.matcher(normalized);
            group = (resolution != null || source != null) && suffix.find() ? suffix.group(1) : null;
        }

        private String firstLabel(List<String> labels) {
            for (String label : labels) if (words.contains(label)) return label;
            return null;
        }

        boolean compatibleRelease(ReleaseTags other) {
            return java.util.Objects.equals(cut, other.cut)
                    && (source == null || other.source == null || source.equals(other.source))
                    && (resolution == null || other.resolution == null || resolution.equals(other.resolution))
                    && (group == null || other.words.contains(group));
        }
    }

    private static int tagScore(String wanted, String actual, int bonus, int conflict) {
        if (wanted == null || actual == null) return 0;
        return wanted.equals(actual) ? bonus : -conflict;
    }

    private static int rank(ReleaseTags file, String filename, String release, String format) {
        ReleaseTags candidate = new ReleaseTags(release);
        // Edits and sources can change the timeline. Resolution and the scene group
        // help choose between otherwise similar releases; unknown tags stay neutral.
        int score = tagScore(file.cut, candidate.cut, 52, 30)
                + tagScore(file.source, candidate.source, 35, 14)
                + tagScore(file.resolution, candidate.resolution, 18, 7);
        if (file.group != null && candidate.words.contains(file.group)) score += 24;
        // Measure how much of the video's name is accounted for. Extra descriptive
        // words in a subtitle release (e.g. an episode title) do not count against it.
        int common = 0;
        for (String word : file.words) if (candidate.words.contains(word)) common++;
        if (!file.words.isEmpty()) score += 12 * common / file.words.size();
        if (!filename.isEmpty() && filename.equalsIgnoreCase(release)) score += 500;
        return 2 * score + ("srt".equals(format) ? 1 : 0);
    }

    static int catalogReleaseScore(String filename, String release) {
        String base = stem(filename);
        return rank(new ReleaseTags(base), base, release, "srt");
    }

    public static final class Downloaded {
        public final byte[] bytes;
        public final String extension;

        private Downloaded(byte[] bytes, String extension) {
            this.bytes = bytes;
            this.extension = extension;
        }
    }

    public Downloaded download(Result result) throws IOException {
        String url = result.url;
        if (result.source == Source.PRIMARY) url += (url.contains("?") ? "&" : "?") + "client=" + CLIENT;
        byte[] bytes = read(result.source, url, MAX_SUBTITLE, false);
        if (result.source == Source.CAT) checkEpisodeContent(result.title, bytes);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // The service usually converts to UTF-8, but some older tracks retain their upload encoding.
            try {
                Charset original = Charset.forName(result.encoding);
                text = original.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (IllegalArgumentException | CharacterCodingException invalid) {
                throw new IOException("字幕编码无法识别，请选择另一个版本。", invalid);
            }
        }
        return parseText(text, result.source == Source.CAT);
    }

    static Downloaded parseText(String text, boolean chineseSrt) throws IOException {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        // Some uploads labelled ASS are served as SRT, even by their HTTP header.
        // Use the cues in the received file to choose the on-disk extension.
        String actualFormat = null;
        if (text.startsWith("WEBVTT") && text.contains("-->")) {
            actualFormat = "vtt";
        } else if (text.contains("[Events]") && text.contains("Dialogue:")) {
            actualFormat = text.contains("[V4+ Styles]") || text.contains("ScriptType: v4.00+") ? "ass" : "ssa";
        } else if (!text.trim().startsWith("<") && SRT_CUE.matcher(text).find()) {
            actualFormat = "srt";
        }
        if (actualFormat == null || text.indexOf('\0') >= 0) {
            throw new IOException("下载内容不符合字幕格式，请选择另一个版本。");
        }
        if (chineseSrt && (!actualFormat.equals("srt") || !HAN.matcher(text).find()))
            throw new IOException("该文件未包含可识别的中文 SRT 字幕，请选择另一个版本。");
        return new Downloaded(text.getBytes(StandardCharsets.UTF_8), actualFormat);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消搜索。");
    }

    private static byte[] read(Source source, String rawUrl, int maxBytes, boolean lookup) throws IOException {
        URL url = new URL(rawUrl);
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkInterrupted();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                    || !source.accepts(url)
                    || (url.getPort() != -1 && url.getPort() != 443)) {
                throw new IOException("字幕服务返回了无效的下载地址。");
            }
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            try {
                http.setInstanceFollowRedirects(false);
                http.setConnectTimeout(12000);
                http.setReadTimeout(18000);
                http.setRequestProperty("User-Agent", "NasSubtitlesTV/1.3.1");
                http.setRequestProperty("Accept", lookup ? "application/json" : "*/*");
                int status = http.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = http.getHeaderField("Location");
                    if (location == null) throw new IOException("字幕服务未返回文件地址。");
                    url = new URL(url, location);
                    continue;
                }
                if (status == 404 && lookup) return null;
                if (status == 404 || status == 410) throw new IOException("这个字幕文件已下架，请选择另一个版本。");
                if (status == 429) throw new IOException("字幕服务请求次数已达上限，请稍后再试。");
                if (status != 200) throw new IOException("字幕服务暂时无法响应 (HTTP " + status + ")。");
                if (http.getContentLengthLong() > maxBytes) throw new IOException("字幕服务返回的数据过大。");
                try (InputStream input = http.getInputStream()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkInterrupted();
                        if (output.size() + count > maxBytes) throw new IOException("字幕服务返回的数据过大。");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } catch (SocketTimeoutException e) {
                throw new IOException("连接字幕服务超时，请稍后重试。", e);
            } catch (UnknownHostException e) {
                throw new IOException("无法连接字幕服务，请检查 Fire TV 的互联网连接。", e);
            } finally {
                http.disconnect();
            }
        }
        throw new IOException("字幕服务重定向次数过多。");
    }
}
