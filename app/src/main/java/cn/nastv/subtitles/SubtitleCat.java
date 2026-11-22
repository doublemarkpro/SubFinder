package cn.nastv.subtitles;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only public catalog pages and already-existing Simplified Chinese downloads. */
final class SubtitleCat {
    private static final String HOME = "https://www.subtitlecat.com/";
    private static final String HEADING = "All language subtitles for ";
    private static final int MAX_DETAILS = 36;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final Pattern EPISODE = Pattern.compile("(?:^| )(S(\\d{2})E(\\d{2,3})(?:-E\\d{2,3})?)$");
    private static final Pattern YEAR = Pattern.compile(" ((?:18|19|20)\\d{2})$");
    private static final Pattern ASSET = Pattern.compile("/subs/[0-9]+/[^/\\\\]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANGUAGE_COUNT = Pattern.compile("(\\d{1,3})\\s+languages?", Pattern.CASE_INSENSITIVE);

    static final class Track {
        final String title;
        final String pageUrl;
        final String url;

        Track(String title, String pageUrl, String url) {
            this.title = title;
            this.pageUrl = pageUrl;
            this.url = url;
        }
    }

    static final class Page {
        final String title;
        final String url;
        final int priority;
        final int languages;

        Page(String title, String url, int priority, int languages) {
            this.title = title;
            this.url = url;
            this.priority = priority;
            this.languages = languages;
        }
    }

    private static final class MediaKey {
        final String title;
        final String name;
        final String withoutYear;
        final String episode;
        final String alternativeEpisode;

        MediaKey(String input) {
            String suggested = SubtitleService.suggestQuery(input.replace('/', ' ').replace('\\', ' '));
            Matcher marker = EPISODE.matcher(suggested);
            if (marker.find()) {
                episode = marker.group(1);
                title = suggested.substring(0, marker.start()).trim();
                alternativeEpisode = episode.contains("-") ? "" :
                        Integer.parseInt(marker.group(2)) + "x" + marker.group(3);
            } else {
                title = suggested;
                episode = "";
                alternativeEpisode = "";
            }
            name = words(title);
            withoutYear = YEAR.matcher(name).replaceFirst("");
        }

        boolean matches(String release) {
            MediaKey candidate = new MediaKey(release);
            if (name.isEmpty() || !episode.equals(candidate.episode)) return false;
            if (name.equals(candidate.name) || name.equals(candidate.withoutYear)) return true;
            // Series releases sometimes omit the premiere year. A conflicting year on
            // two releases, or a movie without the requested year, cannot identify a remake.
            return !episode.isEmpty() && withoutYear.equals(candidate.name) &&
                    candidate.name.equals(candidate.withoutYear);
        }
    }

    private SubtitleCat() {}

    static final class Matches {
        final Map<String, Track> tracks = new LinkedHashMap<>();
        boolean complete;
        boolean wrongEpisode;
    }

    // Cache only checked outcomes, never HTTP errors. A retry can spend its request budget on new pages.
    private static final class Checked {
        final Track track;
        final long checkedAt = System.nanoTime();
        Checked(Track track) { this.track = track; }
    }
    private static final Map<String, Checked> recent = new LinkedHashMap<String, Checked>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Checked> eldest) { return size() > 256; }
    };

    private static synchronized Checked cached(String key) {
        Checked entry = recent.get(key);
        if (entry != null && System.nanoTime() - entry.checkedAt > TimeUnit.MINUTES.toNanos(5)) {
            recent.remove(key);
            return null;
        }
        return entry;
    }

    private static synchronized void remember(String key, Track track) {
        recent.put(key, new Checked(track));
    }

    private static List<String> searchTerms(MediaKey wanted) {
        Set<String> terms = new LinkedHashSet<>();
        terms.add((wanted.title + " " + wanted.episode).trim());
        if (!wanted.alternativeEpisode.isEmpty()) {
            String title = YEAR.matcher(wanted.title).replaceFirst("");
            terms.add(title + " " + wanted.episode);
            terms.add(title + " " + wanted.alternativeEpisode);
            terms.add(title + " " + wanted.episode.substring(1, 3) + "x" + wanted.episode.substring(4));
        }
        return new ArrayList<>(terms);
    }

    private static Matches incomplete(Matches matches, IOException cause, int inspected) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消字幕搜索。");
        if (!matches.tracks.isEmpty()) return matches;
        throw new IOException("已核查 " + inspected + " 个候选，搜索未完成。" + cause.getMessage(), cause);
    }

    static Matches search(String query, String filename, boolean checkVersions) throws IOException {
        Matches matches = new Matches();
        if (query == null || query.trim().isEmpty() || query.length() > 300) return matches;
        MediaKey wanted = new MediaKey(query);
        if (wanted.title.isEmpty()) return matches;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Set<String> visited = new HashSet<>();
        int requests = 0, inspected = 0, consecutiveErrors = 0;
        IOException lastPageFailure = null;
        for (String term : searchTerms(wanted)) {
            List<Page> pages;
            try {
                pages = lookup(term, query, filename, deadline);
            } catch (IOException failure) {
                return incomplete(matches, failure, inspected);
            }
            for (Page page : pages) {
                if (!visited.add(page.url)) continue;
                String key = query + "\n" + page.url;
                Checked entry = cached(key);
                Track track;
                if (entry != null) track = entry.track;
                else {
                    if (requests >= MAX_DETAILS) return incomplete(matches,
                            new IOException("目录里还有片源待核查，请重试以继续。"), inspected);
                    requests++;
                    try {
                        Document detail = readPage(page.url, false, deadline);
                        track = null;
                        if (detail != null) {
                            Element heading = detail.selectFirst("h2.sec-title");
                            if (heading == null || !heading.text().regionMatches(true, 0, HEADING, 0, HEADING.length()))
                                throw new IOException("Subtitle Cat 返回了无法识别的字幕详情页。");
                            track = parseTrack(detail, page, query);
                            if (track != null && SubtitleService.REPORTED_CAT_TITLE.equalsIgnoreCase(track.title)) {
                                try {
                                    SubtitleService.verifyReportedTrack(track);
                                } catch (SubtitleService.WrongEpisode wrong) {
                                    matches.wrongEpisode = true;
                                    consecutiveErrors = 0;
                                    inspected++;
                                    continue;
                                }
                            }
                            if (track != null && !matches.tracks.containsKey(track.url) && !hasFile(track.url, deadline))
                                track = null;
                        }
                        remember(key, track);
                        consecutiveErrors = 0;
                    } catch (SocketTimeoutException | HttpRetryException failure) {
                        if (++consecutiveErrors >= 2 || System.nanoTime() >= deadline)
                            return incomplete(matches, failure, inspected);
                        lastPageFailure = failure;
                        continue;
                    } catch (IOException failure) {
                        return incomplete(matches, failure, inspected);
                    }
                }
                inspected++;
                if (track != null) {
                    matches.tracks.put(track.url, track);
                    if (!checkVersions) return matches;
                }
            }
        }
        if (lastPageFailure != null) return incomplete(matches, lastPageFailure, inspected);
        matches.complete = true;
        return matches;
    }

    private static List<Page> lookup(String term, String query, String filename, long deadline) throws IOException {
        String url = HOME + "index.php?search=" + URLEncoder.encode(term, "UTF-8");
        Document search = readPage(url, true, deadline);
        if (search == null || search.selectFirst("table.sub-table tbody, .nores__title") == null)
            throw new IOException("Subtitle Cat 返回了无法识别的搜索页。");
        List<Page> pages = candidates(search, query, filename);
        // Inspect the closest releases first, then releases that offer more existing language files.
        if (pages.size() > 2) pages.subList(2, pages.size()).sort((a, b) -> Integer.compare(b.languages, a.languages));
        return pages;
    }

    static List<Page> candidates(Document search, String query, String filename) {
        MediaKey wanted = new MediaKey(query);
        String base = filename == null ? "" : filename.substring(
                Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1).replaceFirst("\\.[^.]+$", "");
        boolean hasReleaseInfo = !base.isEmpty() && !words(base).equals(words(SubtitleService.suggestQuery(base)));
        Map<String, Page> unique = new LinkedHashMap<>();
        for (Element row : search.select("table tbody tr")) {
            Element link = row.selectFirst("td a[href]");
            if (link == null) continue;
            String title = link.text().trim();
            if (title.isEmpty() || title.length() > 400 || !wanted.matches(title)) continue;
            URI uri = safeUri(link.absUrl("href"), "html");
            if (uri == null) continue;
            // Search rows show how many language files actually exist. Prioritize a
            // well-covered release before spending a request on its language page.
            Matcher langs = LANGUAGE_COUNT.matcher(row.text());
            int languages = langs.find() ? Math.min(100, Integer.parseInt(langs.group(1))) : 0;
            int priority = hasReleaseInfo ? SubtitleService.catalogReleaseScore(filename, title) +
                    Math.min(22, languages) : languages;
            String url = uri.toASCIIString();
            unique.put(url, new Page(title, url, priority, languages));
        }
        List<Page> pages = new ArrayList<>(unique.values());
        Collections.sort(pages, (a, b) -> Integer.compare(b.priority, a.priority));
        return pages;
    }

    static Track parseTrack(Document detail, Page page, String query) {
        Element header = detail.selectFirst("h2.sec-title");
        if (header == null) return null;
        String text = header.text();
        if (!text.regionMatches(true, 0, HEADING, 0, HEADING.length())) return null;
        String title = text.substring(HEADING.length()).trim();
        MediaKey wanted = new MediaKey(query);
        if (!wanted.matches(title)) return null;
        Element link = detail.getElementById("download_zh-CN");
        if (link == null || !"a".equals(link.normalName()) ||
                !link.hasAttr("href") || !"Download".equalsIgnoreCase(link.text().trim())) return null;
        URI target = safeUri(link.absUrl("href"), "srt");
        if (target == null) return null;
        String leaf = target.getPath().substring(target.getPath().lastIndexOf('/') + 1);
        String releaseName = leaf.replaceFirst("(?i)-(?:zh-CN|orig)\\.srt$", "");
        if (releaseName.equals(leaf) || !wanted.matches(releaseName)) return null;
        return new Track(title, page.url, target.toASCIIString());
    }

    private static String words(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replaceAll("\\p{M}+", "").replaceAll("['’]", "").replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Validate both the catalog's hrefs and every redirect, never requesting another origin. */
    private static URI safeUri(String raw, String kind) {
        try {
            // The public download anchors contain literal spaces and square brackets.
            URI uri = new URI(raw.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D"));
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null ||
                    uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443) ||
                    !("www.subtitlecat.com".equalsIgnoreCase(host) || "subtitlecat.com".equalsIgnoreCase(host))) return null;
            String path = uri.getPath();
            if ("search".equals(kind)) return ("/index.php".equals(path) || "/".equals(path)) ? uri : null;
            if (uri.getQuery() != null || !ASSET.matcher(path).matches() ||
                    !path.toLowerCase(Locale.ROOT).endsWith("." + kind) || path.matches("(?s).*\\p{Cntrl}.*")) return null;
            return uri;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static int remaining(long deadline) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消字幕搜索。");
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new SocketTimeoutException("搜索 Subtitle Cat 超时，请稍后重试。");
        return (int) millis;
    }

    private static HttpURLConnection open(URI uri, long deadline) throws IOException {
        HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
        http.setInstanceFollowRedirects(false);
        http.setConnectTimeout(Math.min(6500, remaining(deadline)));
        http.setReadTimeout(Math.min(6500, remaining(deadline)));
        http.setRequestProperty("User-Agent", "NasSubtitlesTV/1.3.1");
        return http;
    }

    /** A listed translation can outlive its SRT. Check its headers before offering it. */
    private static boolean hasFile(String raw, long deadline) throws IOException {
        URI uri = safeUri(raw, "srt");
        for (int redirects = 0; redirects <= 3 && uri != null; redirects++) {
            HttpURLConnection http = open(uri, deadline);
            try {
                http.setRequestMethod("HEAD");
                int status = http.getResponseCode();
                if (isRedirect(status)) {
                    uri = redirectTarget(uri, http.getHeaderField("Location"), "srt");
                    continue;
                }
                if (status == 404 || status == 410) return false;
                if (status == 405 || status == 501) return true; // GET-only host; validate the body on download.
                if (status == 429) throw new IOException("Subtitle Cat 请求次数已达上限，请稍后重试。");
                if (isTemporary(status)) {
                    throw new HttpRetryException("Subtitle Cat 的字幕文件暂时不可用。", status);
                }
                if (status != 200) throw new IOException("Subtitle Cat 暂时无法响应 (HTTP " + status + ")。");
                String type = http.getContentType();
                return http.getContentLengthLong() != 0 &&
                        (type == null || !type.toLowerCase(Locale.ROOT).contains("text/html"));
            } finally {
                http.disconnect();
            }
        }
        return false;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isTemporary(int status) {
        return status == 408 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static URI redirectTarget(URI uri, String location, String kind) {
        if (location == null) return null;
        try {
            return safeUri(uri.resolve(location.replace(" ", "%20")).toASCIIString(), kind);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Document readPage(String raw, boolean search, long deadline) throws IOException {
        String kind = search ? "search" : "html";
        URI uri = safeUri(raw, kind);
        for (int redirect = 0; redirect <= 3 && uri != null; redirect++) {
            HttpURLConnection http = open(uri, deadline);
            try {
                http.setRequestProperty("Accept", "text/html");
                int status = http.getResponseCode();
                if (isRedirect(status)) {
                    uri = redirectTarget(uri, http.getHeaderField("Location"), kind);
                    continue;
                }
                if (status == 404 || status == 410) return null;
                if (status == 429) throw new IOException("Subtitle Cat 请求次数已达上限，请稍后重试。");
                if (isTemporary(status)) {
                    throw new HttpRetryException("Subtitle Cat 的字幕页面暂时不可用。", status);
                }
                if (status != 200) throw new IOException("Subtitle Cat 暂时无法响应 (HTTP " + status + ")。");
                if (http.getContentLengthLong() > MAX_PAGE_BYTES) throw new IOException("Subtitle Cat 页面过大。");
                try (InputStream stream = http.getInputStream()) {
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = stream.read(buf)) != -1) {
                        remaining(deadline);
                        if (body.size() + read > MAX_PAGE_BYTES) throw new IOException("Subtitle Cat 页面过大。");
                        body.write(buf, 0, read);
                    }
                    return Jsoup.parse(new String(body.toByteArray(), StandardCharsets.UTF_8), uri.toASCIIString());
                }
            } finally {
                http.disconnect();
            }
        }
        throw new IOException("Subtitle Cat 返回了无效的目录地址。");
    }
}
