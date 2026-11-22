package cn.nastv.subtitles;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveEntry;
import me.zhanghai.android.libarchive.ArchiveException;

/** Reads season packs in memory. Archive member names never become output paths. */
final class SeasonPack {
    static final int MAX_DOWNLOAD = 16 * 1024 * 1024;
    private static final int MAX_FILE = 4 * 1024 * 1024;
    private static final int MAX_TOTAL = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 512;
    private static final Charset GB = Charset.forName("GB18030");
    private static final String NUMBER = "[0-9零〇一二两三四五六七八九十百]{1,4}";
    private static final Pattern FULL = Pattern.compile("(?i)(?<![a-z0-9])(?:s(\\d{1,2})[. _-]*e(\\d{1,3})"
            + "|(\\d{1,2})x(\\d{1,3})|第\\s*(" + NUMBER + ")\\s*季[. _-]*第?\\s*(" + NUMBER + ")\\s*集)(?!\\d)");
    private static final Pattern EP_ONLY = Pattern.compile("(?i)(?<![a-z0-9])(?:(?:episode|ep|e)[ ._-]*(\\d{1,3})"
            + "|第\\s*(" + NUMBER + ")\\s*集)(?!\\d)");
    private static final Pattern SEASON = Pattern.compile("(?i)(?<![a-z0-9])(?:(?:season|s)[ ._-]*(\\d{1,2})"
            + "|第\\s*(" + NUMBER + ")\\s*季)(?![a-z0-9])");
    private static final Pattern EXTRA = Pattern.compile("(?i)^[. _]*(?:[-+&,至~～到][ ._]*(?:(?:s\\d{1,2})?e|第)?"
            + NUMBER + "(?![0-9a-z])|e\\d{1,3}(?!\\d)|第\\s*" + NUMBER + "\\s*集)");
    private static final Pattern GENERIC_DIR = Pattern.compile("(?i)(?:sub(?:title)?s?|srt|ass|字幕(?:包)?|中英双语|中英|简体中文|字幕下载|"
            + "简中|繁中|简体|繁体|english|eng?|zh(?:[-_.](?:cn|hans|tw|hant))?|chs|cht|中文|简英|简繁)");
    private static final Pattern SIMPLIFIED = Pattern.compile("(?i)简体|简中|简英|(?<![a-z])(?:zh[._-]?(?:cn|hans)|chs|sc|cn)(?![a-z])");
    private static final Pattern OTHER_SCRIPT = Pattern.compile("(?i)繁[体體中英]|正體|台湾|台灣|(?<![a-z])(?:zh[._-]?(?:tw|hk|hant)|cht|tc)(?![a-z])");
    private static final Pattern SUB_EXT = Pattern.compile("(?i).*\\.(srt|ass|ssa|vtt)$");
    private static final Pattern NUMBERED_FILE = Pattern.compile("(?i)^(\\d{1,3})(?:[. _-]+(?:chs|zh[._-]?(?:cn|hans)|简中|简体|简英|中英|双语))*\\.(?:srt|ass|ssa|vtt)$");
    private static final Pattern CUE_LINE = Pattern.compile("^\\s*(?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{1,3}\\s*-->.*");
    private static final String TRADITIONAL = "這麼們說還過會來時個為對發現讓從當裡裏學應該愛聽誰問難開關歡頭聲樣給認識謝覺實親見體無經進幾兩點嗎請話門與幫間沒東後";
    private static final String SIMPLIFIED_CHARS = "这么们说还过会来时个为对发现让从当里学应该爱听谁问难开关欢头声样给认识谢觉实亲见体无经进几两点吗请话门与帮间没东后";

    static final class Track {
        final String path, extension;
        final byte[] bytes;
        private final String series, episode;
        private final boolean inferredSeason;

        Track(String path, Identity id, SubtitleService.Downloaded sub) {
            this.path = path;
            series = id.show;
            episode = id.label();
            inferredSeason = id.inferredSeason;
            extension = sub.extension;
            bytes = sub.bytes;
        }
    }

    private static final class Identity {
        final int season, episode;
        final String show;
        final boolean inferredSeason;

        Identity(int season, int episode, String show, boolean inferredSeason) {
            this.season = season;
            this.episode = episode;
            this.show = show;
            this.inferredSeason = inferredSeason;
        }

        String label() {
            return String.format(Locale.ROOT, "S%02dE%02d", season, episode);
        }
    }

    private final int season;
    private final List<String> aliases;
    private final List<Track> tracks;
    private final Set<String> seriesInPack;

    private SeasonPack(int season, List<String> aliases, List<Track> tracks, Set<String> seriesInPack) {
        this.season = season;
        this.aliases = aliases;
        this.tracks = tracks;
        this.seriesInPack = seriesInPack;
    }

    static SeasonPack read(File archive, int season, List<String> aliases) throws IOException {
        return read(archive, season, aliases, false);
    }

    static SeasonPack read(File archive, int season, List<String> aliases, boolean requireExplicitSeason) throws IOException {
        if (season < 0 || season > 99) throw new IOException("无法确定影片的季号。");
        if (!archive.isFile() || archive.length() == 0 || archive.length() > MAX_DOWNLOAD)
            throw new IOException("字幕包为空或超过 16 MB。");
        byte[] signature = new byte[8];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new FileInputStream(archive))) {
            if (archive.length() < 8) throw new IOException("字幕包不完整。");
            in.readFully(signature);
        }
        Reader members;
        if (signature[0] == 'P' && signature[1] == 'K') {
            members = readZip(archive, season);
        } else if ((signature[0] == 'R' && signature[1] == 'a' && signature[2] == 'r' && signature[3] == '!'
                && signature[4] == 0x1a && signature[5] == 7)
                || (signature[0] == '7' && signature[1] == 'z' && signature[2] == (byte)0xbc && signature[3] == (byte)0xaf
                && signature[4] == 0x27 && signature[5] == 0x1c)) {
            members = readNative(archive, season);
        } else {
            throw new IOException("请选择 ZIP、RAR 或 7z 格式的整季字幕包。");
        }
        if (requireExplicitSeason || members.otherSeason) members.tracks.removeIf(track -> track.inferredSeason);
        return new SeasonPack(season, new ArrayList<>(aliases), members.tracks, members.series);
    }

    List<String> availableSeries() {
        Set<String> names = new LinkedHashSet<>();
        for (Track track : tracks) if (!track.series.isEmpty()) names.add(track.series);
        return new ArrayList<>(names);
    }

    SeasonPack withAlias(String series) {
        if (!availableSeries().contains(series)) return this;
        List<String> updated = new ArrayList<>(aliases);
        updated.add(series);
        return new SeasonPack(season, updated, tracks, seriesInPack);
    }

    List<Track> choices(String videoQuery) {
        Identity video = identity(videoQuery, -1);
        if (video == null || video.season != season || aliases.isEmpty()
                || (!video.show.isEmpty() && !known(video.show))) return Collections.emptyList();
        boolean mixed = false;
        for (String series : seriesInPack) if (!known(series)) mixed = true;
        List<Track> matches = new ArrayList<>();
        String label = video.label();
        for (Track track : tracks) {
            if (label.equals(track.episode) && (track.series.isEmpty() ? !mixed : known(track.series))) matches.add(track);
        }
        return matches;
    }

    private boolean known(String series) {
        String name = key(series);
        for (String alias : aliases) if (alias != null && !name.isEmpty() && name.equals(key(alias))) return true;
        return false;
    }

    private static String key(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static String episodeOf(String value) {
        Identity id = identity(value, -1);
        return id == null ? "" : id.label();
    }

    static String showOf(String value) {
        Identity id = identity(value, -1);
        return id == null ? "" : id.show;
    }

    static int seasonOf(String value) {
        Identity id = identity(value, -1);
        return id == null ? -1 : id.season;
    }

    /** Also understands a season named in a pack or parent directory without an episode. */
    static int declaredSeason(String label) {
        if (label == null) return -1;
        Matcher full = FULL.matcher(label);
        Matcher plain = SEASON.matcher(label);
        int result = -1;
        while (full.find()) {
            int value = number(full.group(full.group(1) != null ? 1 : full.group(3) != null ? 3 : 5));
            if (result >= 0 && value != result) return -2;
            result = value;
        }
        while (plain.find()) {
            int value = number(plain.group(1) == null ? plain.group(2) : plain.group(1));
            if (result >= 0 && value != result) return -2;
            result = value;
        }
        return result;
    }

    private static int number(String value) {
        if (value == null) return -1;
        if (value.matches("[0-9]+")) return Integer.parseInt(value);
        int result = 0, digit = 0;
        for (char c : value.replace('〇','零').replace('两','二').toCharArray()) {
            if (c == '十' || c == '百') {
                result += (digit == 0 ? 1 : digit) * (c == '十' ? 10 : 100);
                digit = 0;
            } else {
                digit = "零一二三四五六七八九".indexOf(c);
                if (digit < 0) return -1;
            }
        }
        return result + digit;
    }

    private static String clean(String value) {
        return value.replaceAll("[._\\[\\]()（）]+", " ").replaceAll("^[\\s–—-]+|[\\s–—-]+$", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static Identity identity(String value, int contextSeason) {
        if (value == null || value.length() > 1024) return null;
        String[] parts = value.replace('\\','/').split("/");
        int season = -1, episode = -1;
        String show = "";
        for (String part : parts) {
            Matcher m = FULL.matcher(part);
            if (m.find()) {
                int group = m.group(1) != null ? 1 : m.group(3) != null ? 3 : 5;
                int s = number(m.group(group)), e = number(m.group(group + 1));
                if ((season >= 0 && s != season) || (episode >= 0 && e != episode) || EXTRA.matcher(part.substring(m.end())).find()) return null;
                season = s;
                episode = e;
                String prefix = clean(part.substring(0, m.start()));
                if (!prefix.isEmpty()) show = prefix;
                if (m.find()) return null;
                continue;
            }
            Matcher sm = SEASON.matcher(part);
            while (sm.find()) {
                int s = number(sm.group(1) != null ? sm.group(1) : sm.group(2));
                if (season >= 0 && s != season) return null;
                season = s;
                String prefix = clean(part.substring(0, sm.start()));
                if (!prefix.isEmpty()) show = prefix;
            }
            Matcher em = EP_ONLY.matcher(part);
            if (em.find()) {
                int e = number(em.group(1) != null ? em.group(1) : em.group(2));
                if ((episode >= 0 && e != episode) || EXTRA.matcher(part.substring(em.end())).find()) return null;
                episode = e;
                String prefix = clean(part.substring(0, em.start()));
                if (!prefix.isEmpty() && !SEASON.matcher(prefix).find()) show = prefix;
                if (em.find()) return null;
            }
        }
        if (episode < 0 && parts.length > 0) {
            Matcher digits = NUMBERED_FILE.matcher(parts[parts.length-1]);
            if (digits.matches()) episode = number(digits.group(1));
        }
        boolean inferred = season < 0;
        if (inferred) season = contextSeason;
        if (season < 0 || season > 99 || episode < 0 || episode > 999) return null;
        if (show.isEmpty()) {
            for (int i = parts.length-2; i >= 0; i--) {
                String dir = clean(parts[i]);
                if (dir.isEmpty() || GENERIC_DIR.matcher(dir).matches() || SEASON.matcher(dir).find()
                        || FULL.matcher(dir).find() || EP_ONLY.matcher(dir).find()) continue;
                show = dir;
                break;
            }
        }
        return new Identity(season, episode, show, inferred);
    }

    private static final class Reader {
        final int season;
        final List<Track> tracks = new ArrayList<>();
        final Set<String> series = new LinkedHashSet<>();
        boolean otherSeason;
        int entries;
        long total, retained;

        Reader(int season) { this.season = season; }

        Identity inspect(String path, long size, boolean regular) throws IOException {
            if (++entries > MAX_ENTRIES) throw new IOException("字幕包内超过 512 项。");
            if (path != null && path.length() <= 1024) {
                int marked = declaredSeason(path);
                if (marked == -2 || (marked >= 0 && marked != season)) otherSeason = true;
            }
            if (!regular) return null;
            if (size < 0 || size > MAX_TOTAL - total) throw new IOException("字幕包解压后超过 32 MB，或未标明文件长度。");
            total += size;
            if (path == null || path.length() > 1024 || path.startsWith("/") || path.startsWith("\\")
                    || path.contains(":") || path.matches(".*[\\p{Cntrl}].*")) return null;
            String[] parts = path.replace('\\','/').split("/");
            for (String part : parts) if (part.isEmpty() || (part.startsWith(".") && !part.equals(".")) || part.equals("__MACOSX")) return null;
            if (!SUB_EXT.matcher(path).matches()) return null;
            Identity id = identity(path, season);
            if (id == null || id.season != season) return null;
            if (!id.show.isEmpty()) series.add(id.show);
            if (otherLanguage(parts)) return null;
            if (size > MAX_FILE) throw new IOException("字幕包内单份字幕超过 4 MB。");
            return id;
        }

        void add(String path, Identity id, byte[] data) throws IOException {
            String text = decode(data);
            if (text == null || !chineseDialogue(text)) return;
            SubtitleService.Downloaded parsed;
            try { parsed = SubtitleService.parseText(text, false); }
            catch (IOException invalid) { return; }
            retained += parsed.bytes.length;
            if (retained > MAX_TOTAL) throw new IOException("字幕包中的文本过大。");
            tracks.add(new Track(path, id, parsed));
        }
    }

    private static boolean otherLanguage(String[] parts) {
        for (int i = parts.length-1; i >= 0; i--) {
            if (OTHER_SCRIPT.matcher(parts[i]).find()) return true;
            if (SIMPLIFIED.matcher(parts[i]).find()) return false;
        }
        return false;
    }

    private static String decode(byte[] data) {
        try {
            if (data.length >= 2 && ((data[0] == (byte)0xff && data[1] == (byte)0xfe)
                    || (data[0] == (byte)0xfe && data[1] == (byte)0xff)))
                return StandardCharsets.UTF_16.newDecoder().decode(ByteBuffer.wrap(data)).toString();
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException utf) {
            try { return GB.newDecoder().decode(ByteBuffer.wrap(data)).toString(); }
            catch (CharacterCodingException invalid) { return null; }
        }
    }

    private static boolean chineseDialogue(String text) {
        int han = 0, traditional = 0, simple = 0, kana = 0, chineseCues = 0, cues = 0;
        boolean inCue = false, counted = false;
        try (java.io.BufferedReader lines = new java.io.BufferedReader(new java.io.StringReader(text))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("Dialogue:")) {
                    String[] fields = line.split(",",10);
                    if (fields.length < 10) continue;
                    line = fields[9];
                    inCue = true;
                    counted = false;
                    cues++;
                } else if (CUE_LINE.matcher(line).matches()) {
                    inCue = true;
                    counted = false;
                    cues++;
                    continue;
                } else if (line.trim().isEmpty()) {
                    inCue = false;
                    continue;
                }
                if (!inCue) continue;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c >= '\u3040' && c <= '\u30ff') kana++;
                    if (c < '\u3400' || c > '\u9fff') continue;
                    han++;
                    if (!counted) { chineseCues++; counted = true; }
                    if (TRADITIONAL.indexOf(c) >= 0) traditional++;
                    if (SIMPLIFIED_CHARS.indexOf(c) >= 0) simple++;
                }
            }
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        return han >= 3 && chineseCues * 4 >= cues && kana * 2 < han && (traditional < 2 || traditional <= simple);
    }

    private static Reader readZip(File file, int season) throws IOException {
        try { return readZip(file, season, StandardCharsets.UTF_8); }
        catch (java.util.zip.ZipException | IllegalArgumentException utf8Path) {
            try { return readZip(file, season, GB); }
            catch (IllegalArgumentException invalid) { throw new IOException("ZIP 文件名编码无法读取。", invalid); }
        }
    }

    private static Reader readZip(File file, int season, Charset charset) throws IOException {
        Reader reader = new Reader(season);
        try (ZipFile zip = new ZipFile(file, charset)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[16384];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                Identity id = reader.inspect(path, entry.getSize(), !entry.isDirectory());
                if (id == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > entry.getSize()) throw new IOException("压缩包内字幕长度不一致。");
                        crc.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                    if (out.size() != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("ZIP 字幕校验失败。");
                    reader.add(path, id, out.toByteArray());
                }
            }
        }
        return reader;
    }

    private static Reader readNative(File file, int season) throws IOException {
        Reader reader = new Reader(season);
        long archive = Archive.readNew();
        try {
            Archive.setCharset(archive, "UTF-8".getBytes(StandardCharsets.US_ASCII));
            Archive.readSupportFormatRar(archive);
            Archive.readSupportFormatRar5(archive);
            Archive.readSupportFormat7zip(archive);
            Archive.readOpenFileName(archive, file.getAbsolutePath().getBytes(StandardCharsets.UTF_8), 16384);
            long entry;
            while ((entry = Archive.readNextHeader(archive)) != 0) {
                if (ArchiveEntry.isEncrypted(entry)) throw new IOException("此字幕包已加密，请选择未加密的字幕包。");
                String path = ArchiveEntry.pathnameUtf8(entry);
                if (path == null) {
                    byte[] raw = ArchiveEntry.pathname(entry);
                    if (raw != null) path = decode(raw);
                }
                boolean regular = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
                        && ArchiveEntry.hardlink(entry) == null && ArchiveEntry.symlink(entry) == null;
                long size = ArchiveEntry.sizeIsSet(entry) ? ArchiveEntry.size(entry) : -1;
                Identity id = reader.inspect(path, size, regular);
                if (id == null) { Archive.readDataSkip(archive); continue; }
                byte[] data = new byte[(int)size];
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    int before = buffer.position();
                    Archive.readData(archive, buffer);
                    if (before == buffer.position()) throw new IOException("字幕包中的文件不完整。");
                }
                ByteBuffer tail = ByteBuffer.allocate(1);
                Archive.readData(archive, tail);
                if (tail.position() != 0) throw new IOException("压缩包内字幕长度不一致。");
                reader.add(path, id, data);
            }
        } catch (ArchiveException failure) {
            throw new IOException("无法读取 RAR 或 7z 字幕包：" + failure.getMessage(), failure);
        } finally {
            Archive.free(archive);
        }
        return reader;
    }
}
