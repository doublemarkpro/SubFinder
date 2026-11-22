package cn.nastv.subtitles;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** A process-wide, serial download queue. The browser and the queue own separate SMB sessions. */
final class BatchQueue {
    enum State {
        QUEUED, SEARCHING, FALLBACK, DOWNLOADING, SAVING, CHOICE, MISSING, FAILED, SAVED, SKIPPED;

        boolean working() {
            return this == SEARCHING || this == FALLBACK || this == DOWNLOADING || this == SAVING;
        }

        boolean unfinished() {
            return this == QUEUED || working() || this == CHOICE;
        }
    }

    private static BatchQueue instance;

    static synchronized BatchQueue get(Context context) {
        if (instance == null) instance = new BatchQueue(context.getApplicationContext());
        return instance;
    }

    private static final class Login {
        final String host, domain, user, password, identity;
        final int port;

        Login(String host, int port, String domain, String user, String password) {
            this.host = host;
            this.port = port;
            this.domain = domain;
            this.user = user;
            this.password = password;
            // Encode components separately. IPv6 colons and account delimiters remain unambiguous.
            identity = new JSONArray().put(host.toLowerCase(Locale.ROOT)).put(port)
                    .put(domain.toLowerCase(Locale.ROOT)).put(user).toString();
        }
    }

    private static final class Job {
        final String id, identity, share, path, originalQuery;
        final long bytes;
        String query;
        State state = State.QUEUED;
        String message = "", picked = "";
        boolean manual;
        List<SubtitleService.Result> options = Collections.emptyList();
        SubtitleService.Result selected;

        Job(String identity, String share, NasStore.Entry entry, String query) {
            id = UUID.randomUUID().toString();
            this.identity = identity;
            this.share = share;
            path = entry.path;
            bytes = entry.size;
            this.query = query;
            originalQuery = query;
        }

        Job(JSONObject data) throws JSONException {
            id = data.getString("id");
            identity = data.getString("identity");
            share = data.getString("share");
            path = data.getString("path");
            bytes = data.getLong("size");
            query = data.getString("query");
            originalQuery = data.optString("originalQuery", query);
            state = State.valueOf(data.getString("state"));
            manual = data.optBoolean("manual");
            picked = data.optString("picked");
            message = data.optString("message");
            // Re-search volatile candidates after a restart, retaining any explicit version choice.
            if (state.working() || state == State.CHOICE) state = State.QUEUED;
        }

        JSONObject encode() throws JSONException {
            return new JSONObject().put("id", id).put("identity", identity).put("share", share)
                    .put("path", path).put("size", bytes).put("query", query).put("originalQuery", originalQuery)
                    .put("state", state.name())
                    .put("manual", manual).put("picked", picked).put("message", message);
        }
    }

    static final class Row {
        final String id, name, share, path, message, query, originalQuery;
        final State state;
        final List<SubtitleService.Result> options;

        Row(Job job) {
            id = job.id;
            name = basename(job.path);
            share = job.share;
            path = job.path;
            state = job.state;
            message = job.message;
            query = job.query;
            originalQuery = job.originalQuery;
            options = job.options;
        }
    }

    private final List<Job> jobs = new ArrayList<>();
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SubtitleService service = new SubtitleService();
    private Runnable listener;
    private Login login;
    private boolean draining;

    private BatchQueue(Context context) {
        prefs = context.getSharedPreferences("batch_queue", Context.MODE_PRIVATE);
        try {
            JSONArray saved = new JSONArray(prefs.getString("jobs", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                try { jobs.add(new Job(saved.getJSONObject(i))); }
                catch (JSONException | IllegalArgumentException ignored) { /* Ignore one damaged record. */ }
            }
        } catch (JSONException ignored) { /* No recoverable records. */ }
    }

    synchronized void setListener(Runnable callback) { listener = callback; }
    synchronized void removeListener(Runnable callback) {
        if (listener == callback) listener = null;
    }

    private final Runnable refresh = () -> {
        Runnable callback;
        synchronized (this) { callback = listener; }
        if (callback != null) callback.run();
    };

    // Called under the queue lock. A burst of changes only redraws the latest state.
    private void changed(boolean save) {
        if (save) {
            JSONArray records = new JSONArray();
            try { for (Job job : jobs) records.put(job.encode()); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            prefs.edit().putString("jobs", records.toString()).apply();
        }
        main.removeCallbacks(refresh);
        main.post(refresh);
    }

    synchronized void connected(String host, int port, String domain, String user, String password) {
        login = new Login(host, port, domain, user, password);
        startWorker();
        changed(false);
    }

    synchronized void disconnect() {
        login = null;
        changed(false);
    }

    synchronized boolean authenticated() { return login != null; }

    synchronized String identity() { return login == null ? "" : login.identity; }

    synchronized boolean processing(String identity, String share, String path) {
        for (Job job : jobs) {
            if (job.identity.equals(identity) && job.share.equalsIgnoreCase(share) && job.path.equals(path))
                return job.state == State.QUEUED || job.state.working();
        }
        return false;
    }

    synchronized void recordPackSave(String identity, String share, NasStore.Entry video, String query, String message) {
        for (Job job : jobs) {
            if (job.identity.equals(identity) && job.share.equalsIgnoreCase(share) && job.path.equals(video.path)) {
                if (!job.state.working() && job.state != State.QUEUED) {
                    job.options = Collections.emptyList();
                    finish(job, State.SAVED, message);
                }
                return;
            }
        }
        Job job = new Job(identity, share, video, query);
        jobs.add(job);
        finish(job, State.SAVED, message);
    }

    private boolean visible(Job job) {
        return login != null && login.identity.equals(job.identity);
    }

    synchronized List<Row> rows() {
        List<Row> result = new ArrayList<>();
        for (Job job : jobs) if (visible(job)) result.add(new Row(job));
        return result;
    }

    synchronized int pendingCount() {
        int n = 0;
        for (Job job : jobs) if (visible(job) && job.state.unfinished()) n++;
        return n;
    }

    synchronized int size() {
        int n = 0;
        for (Job job : jobs) if (visible(job)) n++;
        return n;
    }

    synchronized Set<String> unfinishedPaths(String share) {
        Set<String> paths = new HashSet<>();
        for (Job job : jobs) {
            if (visible(job) && job.share.equalsIgnoreCase(share) && job.state.unfinished()) paths.add(job.path);
        }
        return paths;
    }

    synchronized boolean onlyFolder(String share, String directory) {
        boolean any = false;
        for (Job job : jobs) {
            if (!visible(job)) continue;
            if (!job.share.equalsIgnoreCase(share) || !parent(job.path).equals(directory)) return false;
            any = true;
        }
        return any;
    }

    synchronized int enqueue(String share, List<NasStore.Entry> entries, List<String> queries) {
        if (login == null || share == null || share.isEmpty()) return 0;
        if (entries.size() != queries.size()) throw new IllegalArgumentException("缺少影片搜索词");
        int added = 0;
        for (int i = 0; i < entries.size(); i++) {
            NasStore.Entry entry = entries.get(i);
            if (entry.directory) continue;
            Job existing = null;
            for (Job job : jobs) {
                if (visible(job) && job.share.equalsIgnoreCase(share) && job.path.equals(entry.path)) {
                    existing = job;
                    break;
                }
            }
            if (existing != null && existing.state.unfinished()) continue;
            // A new explicit request can target a replaced video or a previously deleted sidecar.
            if (existing != null) jobs.remove(existing);
            jobs.add(new Job(login.identity, share, entry, queries.get(i)));
            added++;
        }
        if (added > 0) {
            changed(true);
            startWorker();
        }
        return added;
    }

    private Job find(String id) {
        for (Job job : jobs) if (visible(job) && job.id.equals(id)) return job;
        return null;
    }

    synchronized void retry(String id) {
        retry(id, null);
    }

    synchronized void retry(String id, String editedQuery) {
        Job job = find(id);
        if (job == null || (job.state != State.MISSING && job.state != State.FAILED)) return;
        if (editedQuery != null) {
            String query = editedQuery.trim();
            if (query.length() > 300) throw new IllegalArgumentException("搜索词最多 300 个字符。");
            String title = SubtitleService.suggestQuery(query)
                    .replaceFirst("(?:^|\\s)S\\d{2}E\\d{2,3}(?:-E\\d{2,3})?$", "");
            if (!Pattern.compile("[\\p{L}\\p{N}]").matcher(title).find())
                throw new IllegalArgumentException("请输入片名、剧名或 IMDb 编号。");
            if (!query.equals(job.query)) {
                job.query = query;
                job.manual = false;
                job.picked = "";
                job.options = Collections.emptyList();
                job.selected = null;
            }
        }
        job.state = State.QUEUED;
        job.message = "";
        changed(true);
        startWorker();
    }

    synchronized void choose(String id, SubtitleService.Result option) {
        Job job = find(id);
        if (job == null || job.state != State.CHOICE || !job.options.contains(option)) return;
        job.manual = true;
        job.selected = option;
        job.picked = signature(option);
        job.state = State.QUEUED;
        job.options = Collections.emptyList();
        changed(true);
        startWorker();
    }

    private void startWorker() {
        if (!draining && next() != null) {
            draining = true;
            worker.execute(this::drain);
        }
    }

    private Job next() {
        for (Job job : jobs) if (visible(job) && job.state == State.QUEUED) return job;
        return null;
    }

    private void drain() {
        NasStore store = null;
        Login active = null;
        String share = "";
        try {
            while (true) {
                Job job;
                Login account;
                synchronized (this) {
                    job = next();
                    if (job == null) return;
                    account = login;
                    phase(job, State.SEARCHING, "正在检查影片目录");
                }
                try {
                    if (account != active) {
                        close(store);
                        store = null;
                        active = account;
                        share = "";
                    }
                    if (store == null) store = NasStore.login(account.host, account.port, account.domain, account.user, account.password);
                    if (!job.share.equals(share)) {
                        store.selectShare(job.share);
                        share = job.share;
                    }
                    process(store, job);
                } catch (SubtitleService.WrongEpisode wrong) {
                    finish(job, State.MISSING, wrong.getMessage());
                } catch (Exception error) {
                    String detail = error.getLocalizedMessage();
                    finish(job, State.FAILED, detail == null || detail.isEmpty() ? "请检查网络和 NAS 后重试" : detail);
                    // The next episode gets a fresh session after a possible SMB disconnect.
                    close(store);
                    store = null;
                    share = "";
                }
            }
        } finally {
            close(store);
            synchronized (this) {
                draining = false;
                startWorker();
            }
        }
    }

    private static void close(NasStore store) {
        if (store != null) {
            try { store.close(); } catch (RuntimeException ignored) { /* Already disconnected. */ }
        }
    }

    private void process(NasStore store, Job job) throws IOException {
        if (skipExisting(store, job)) return;
        String name = basename(job.path);
        SubtitleService.Result selected = job.selected;
        if (selected == null) {
            phase(job, State.SEARCHING, "The Subtitle DB");
            List<SubtitleService.Result> options = service.searchBatch(job.query, name,
                    unavailable -> phase(job, State.FALLBACK, "Subtitle Cat"));
            if (options.isEmpty()) {
                finish(job, State.MISSING, "两个来源均未找到匹配的简中字幕");
                return;
            }
            selected = resolve(name, options, job.manual, job.picked);
            if (selected == null) {
                synchronized (this) {
                    job.manual = true;
                    job.options = Collections.unmodifiableList(new ArrayList<>(options));
                    finish(job, State.CHOICE, options.size() + " 个候选版本，需核对片源");
                }
                return;
            }
        }
        phase(job, State.DOWNLOADING, selected.source.label);
        SubtitleService.Downloaded track = service.download(selected);
        if (skipExisting(store, job)) return;
        phase(job, State.SAVING, selected.source.label);
        String path = store.saveBatchSubtitle(job.path, track.extension, track.bytes);
        if (path == null) finish(job, State.SKIPPED, "已有同名简中字幕");
        else finish(job, State.SAVED, selected.source.label + "  ·  " + basename(path));
    }

    private boolean skipExisting(NasStore store, Job job) throws IOException {
        String name = basename(job.path);
        List<NasStore.Entry> files = store.list(parent(job.path));
        boolean found = false;
        for (NasStore.Entry file : files) {
            if (file.directory || !file.name.equals(name)) continue;
            if (file.size != job.bytes) throw new IOException("影片已被替换，请返回目录重新选择");
            found = true;
            break;
        }
        if (!found) throw new IOException("影片已移动或删除，请刷新目录");
        if (!simplifiedPaths(files).contains(job.path)) return false;
        finish(job, State.SKIPPED, "目录中已有简中字幕");
        return true;
    }

    private synchronized void phase(Job job, State state, String message) {
        job.state = state;
        job.message = message;
        changed(false);
    }

    private synchronized void finish(Job job, State state, String message) {
        job.state = state;
        job.message = message;
        // Renew a possibly expired download URL on retry while retaining the user's release choice.
        if (state == State.FAILED) job.selected = null;
        if (state == State.SAVED || state == State.SKIPPED) {
            job.selected = null;
            job.picked = "";
        }
        changed(true);
    }

    private static String signature(SubtitleService.Result result) {
        return new JSONArray().put(result.source.name()).put(result.title).put(result.detail).put(result.extension).toString();
    }

    static SubtitleService.Result resolve(String filename, List<SubtitleService.Result> options, boolean manual, String picked) {
        if (!manual && options.size() == 1 && options.get(0).canAutoSave) return options.get(0);
        String wanted = normalizedRelease(stem(filename));
        SubtitleService.Result match = null;
        for (SubtitleService.Result result : options) {
            boolean fits = manual ? signature(result).equals(picked)
                    : result.canAutoSave && normalizedRelease(result.title).equals(wanted);
            if (!fits) continue;
            if (match != null) return null;
            match = result;
        }
        return match;
    }

    private static String normalizedRelease(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[\\s._-]+", " ").trim();
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String basename(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static final Pattern SUB_EXT = Pattern.compile("(?i)srt|ass|ssa|vtt|sub|sup|idx");
    private static final Pattern SIMPLE = Pattern.compile(
            "(?i)(?:(?:zh|chi|zho)[-_ .]?(?:cn|hans(?:[-_ .]?(?:cn|sg|my))?|sg)|chs(?:eng)?|engchs|zhs|sc|gb(?:k|2312)|简体(?:中文|双语)?|简中(?:英)?|中简|简英)");
    private static final Pattern OTHER_TAG = Pattern.compile(
            "(?i)(?:[0-9]{1,3}|forced|default|sdh|hi|cc|signs|foreign|en(?:g|glish)?|英文|双语|zh|chi|chinese|bilingual|utf8)");

    static boolean hasSimplified(String videoName, List<NasStore.Entry> files) {
        for (String path : simplifiedPaths(files)) if (basename(path).equals(videoName)) return true;
        return false;
    }

    static Set<String> simplifiedPaths(List<NasStore.Entry> listing) {
        return simplifiedPaths(listing, null);
    }

    private static final Pattern SUBTITLE_SUFFIX = Pattern.compile(
            "(?i)(?:[. _-]+(?:[a-z]{2,3}(?:[-_][a-z0-9]{2,8})*|forced|default|sdh|[0-9]{1,3}))*");

    static Set<String> simplifiedPaths(List<NasStore.Entry> listing, Set<String> otherTracks) {
        Map<String,List<NasStore.Entry>> stems = new HashMap<>();
        List<String> sidecars = new ArrayList<>();
        for (NasStore.Entry file : listing) {
            if (file.directory) continue;
            int dot = file.name.lastIndexOf('.');
            if (dot < 0) continue;
            if (SUB_EXT.matcher(file.name.substring(dot + 1)).matches()) sidecars.add(file.name);
            else if (file.isVideo()) stems.computeIfAbsent(stem(file.name).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(file);
        }
        Set<String> paths = new HashSet<>();
        for (String sidecar : sidecars) {
            String lower = sidecar.toLowerCase(Locale.ROOT);
            // Longest video stem wins if Film.mkv and Film.2.mkv are both present.
            int dot = lower.lastIndexOf('.');
            for (int end = dot; end > 0; end--) {
                if (". _-[(".indexOf(lower.charAt(end)) < 0) continue;
                List<NasStore.Entry> matches = stems.get(lower.substring(0, end));
                if (matches == null) continue;
                if (isSimplifiedSidecar(matches.get(0).name, sidecar)) {
                    for (NasStore.Entry file : matches) paths.add(file.path);
                } else if (SUBTITLE_SUFFIX.matcher(lower.substring(end, dot)).matches()) {
                    if (otherTracks != null) for (NasStore.Entry file : matches) otherTracks.add(file.path);
                } else continue;
                break;
            }
        }
        return paths;
    }

    static boolean isSimplifiedSidecar(String video, String sidecar) {
        int dot = sidecar.lastIndexOf('.');
        if (dot < 0 || !SUB_EXT.matcher(sidecar.substring(dot + 1)).matches()) return false;
        String base = stem(video);
        if (dot <= base.length() || !sidecar.regionMatches(true, 0, base, 0, base.length())) return false;
        String suffix = sidecar.substring(base.length(), dot);
        if (". _-[(".indexOf(suffix.charAt(0)) < 0) return false;
        // Remove a recognized simplified-language tag; every remaining component must describe a subtitle.
        String labels = suffix.toLowerCase(Locale.ROOT).replaceAll("[\\[\\]()&+]", ".");
        java.util.regex.Matcher tag = SIMPLE.matcher(labels);
        while (tag.find()) {
            String before = labels.substring(0, tag.start());
            String after = labels.substring(tag.end());
            if (!before.isEmpty() && !separator(before.charAt(before.length() - 1))) continue;
            if (!after.isEmpty() && !separator(after.charAt(0))) continue;
            boolean valid = true;
            for (String token : (before + "." + after).split("[. _-]+")) {
                if (!token.isEmpty() && !OTHER_TAG.matcher(token).matches()) { valid = false; break; }
            }
            if (valid) return true;
        }
        return false;
    }

    private static boolean separator(char c) { return ". _-".indexOf(c) >= 0; }
}
