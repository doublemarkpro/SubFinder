package cn.nastv.subtitles;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QueueScreen {
    private static final int WARN = 0xffe6a994;
    private static final Pattern EPISODE = Pattern.compile("(?i)(?<![a-z0-9])(s\\d{1,2}e\\d{1,3}(?:e\\d{1,3})*)(?!\\d)");
    private final Activity activity;
    private final TvUi ui;
    private final BatchQueue queue;
    private final java.util.function.Consumer<BatchQueue.Row> openPack;
    private final LinearLayout root;
    private final TextView summary, count, empty;
    private final ProgressBar progress;
    private final ListView tasks;
    private final TaskAdapter adapter = new TaskAdapter();
    private List<BatchQueue.Row> rows = Collections.emptyList();
    private Dialog modal;

    QueueScreen(Activity activity, TvUi ui, BatchQueue queue, java.util.function.Consumer<BatchQueue.Row> openPack) {
        this.activity = activity;
        this.ui = ui;
        this.queue = queue;
        this.openPack = openPack;
        root = ui.col();
        LinearLayout context = ui.row();
        context.addView(new TvUi.Glyph(activity,"caption",TvUi.AMBER),ui.size(16,16));
        ui.space(context,6);
        context.addView(ui.text("简体中文字幕",12,TvUi.MUTED,false));
        root.addView(context);
        ui.space(root,4);
        root.addView(ui.text("下载队列",28,TvUi.PAPER,true));
        ui.space(root,10);
        LinearLayout bar = ui.row();
        summary = ui.text("",13,TvUi.MUTED,false);
        ui.clip(summary,2);
        ui.fill(bar,summary,-2);
        count = ui.text("",12,TvUi.MUTED,false);
        count.setTypeface(Typeface.MONOSPACE);
        ui.space(bar,6);
        bar.addView(count);
        root.addView(bar);
        ui.space(root,6);
        progress = new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setProgressTintList(ColorStateList.valueOf(TvUi.GOOD));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(TvUi.LINE));
        root.addView(progress,ui.size(-1,5));
        ui.space(root,7);
        empty = ui.note("");
        root.addView(empty);
        tasks = ui.list();
        tasks.setAdapter(adapter);
        tasks.setOnItemClickListener((parent,view,index,id) -> open(rows.get(index)));
        ui.fill(root,tasks,-1);
        refresh();
        if (!rows.isEmpty()) tasks.requestFocus();
    }

    View view() { return root; }

    boolean back() {
        if (modal == null || !modal.isShowing()) return false;
        modal.dismiss();
        return true;
    }

    void refresh() {
        rows = queue.rows();
        adapter.notifyDataSetChanged();
        int saved = 0, skipped = 0, choices = 0, missing = 0, failed = 0, waiting = 0;
        for (BatchQueue.Row row : rows) {
            switch (row.state) {
                case SAVED: saved++; break;
                case SKIPPED: skipped++; break;
                case CHOICE: choices++; break;
                case MISSING: missing++; break;
                case FAILED: failed++; break;
                default: waiting++;
            }
        }
        StringBuilder status = new StringBuilder();
        append(status, waiting, "待处理");
        append(status, saved, "已保存");
        append(status, skipped, "已跳过");
        append(status, choices, "待选版本");
        append(status, missing, "未找到");
        append(status, failed, "失败");
        int processed = rows.size() - waiting;
        summary.setText(status.length() == 0 ? "队列为空" : status.toString());
        count.setText(rows.isEmpty() ? "" : processed + " / " + rows.size());
        progress.setMax(Math.max(1,rows.size()));
        progress.setProgress(processed);
        progress.setContentDescription("已处理 " + processed + " 项，共 " + rows.size() + " 项");
        tasks.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (rows.isEmpty()) empty.setText(queue.authenticated()
                ? "在影片目录中勾选影片，选择「下载所选」后即可查看进度。"
                : "连接 NAS 后可查看并继续该账户的下载任务。");
    }

    private static void append(StringBuilder text, int n, String label) {
        if (n == 0) return;
        if (text.length() > 0) text.append("   ·   ");
        text.append(label).append(' ').append(n);
    }

    private static String stateLabel(BatchQueue.State state) {
        switch (state) {
            case QUEUED: return "排队中";
            case SEARCHING: return "正在查询";
            case FALLBACK: return "备用源查询";
            case DOWNLOADING: return "下载中";
            case SAVING: return "写入中";
            case CHOICE: return "选版本 ›";
            case MISSING: case FAILED: return "查看原因 ›";
            case SAVED: return "已保存";
            case SKIPPED: return "已跳过";
            default: throw new IllegalArgumentException();
        }
    }

    private class TaskAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return rows.get(position).id.hashCode(); }
        @Override public View getView(int position, View reuse, ViewGroup parent) {
            TaskView view = reuse == null ? new TaskView() : (TaskView) reuse;
            view.bind(rows.get(position));
            return view;
        }
    }

    private class TaskView extends LinearLayout {
        final TextView dot, title, detail, action;

        TaskView() {
            super(activity);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new AbsListView.LayoutParams(-1,ui.dp(53)));
            ui.pad(this,7,0);
            dot = ui.text("",17,TvUi.MUTED,true);
            dot.setGravity(Gravity.CENTER);
            addView(dot,ui.size(20,25));
            ui.space(this,7);
            LinearLayout lines = ui.col();
            title = ui.text("",15,TvUi.PAPER,true);
            ui.clip(title,1);
            lines.addView(title);
            ui.space(lines,3);
            detail = ui.text("",12,TvUi.MUTED,false);
            ui.clip(detail,1);
            lines.addView(detail);
            ui.fill(this,lines,-2);
            ui.space(this,6);
            action = ui.text("",12,TvUi.MUTED,true);
            action.setGravity(Gravity.CENTER);
            ui.pad(action,7,0);
            addView(action,ui.size(-2,27));
        }

        void bind(BatchQueue.Row row) {
            BatchQueue.State state = row.state;
            int color = TvUi.MUTED;
            String mark = "○";
            if (state == BatchQueue.State.SAVED || state == BatchQueue.State.SKIPPED) {
                color = TvUi.GOOD;
                mark = "✓";
            } else if (state == BatchQueue.State.FAILED || state == BatchQueue.State.MISSING) {
                color = WARN;
                mark = state == BatchQueue.State.FAILED ? "!" : "−";
            } else if (state == BatchQueue.State.CHOICE || state.working()) {
                color = TvUi.AMBER;
                mark = state == BatchQueue.State.CHOICE ? "≡" : "◉";
            }
            dot.setText(mark);
            dot.setTextColor(color);
            String name = row.name;
            int dotAt = name.lastIndexOf('.');
            if (dotAt > 0) name = name.substring(0,dotAt);
            Matcher ep = EPISODE.matcher(name);
            if (ep.find()) {
                String episode = ep.group(1).toUpperCase(Locale.ROOT);
                String series = (name.substring(0,ep.start()) + " " + name.substring(ep.end()))
                        .replaceAll("[._ ]+"," ").trim();
                title.setText(series.isEmpty() ? episode : episode + "   " + series);
            } else title.setText(name);
            String extra = row.message.replace('\n',' ');
            if (state == BatchQueue.State.MISSING || state == BatchQueue.State.FAILED)
                extra = (state == BatchQueue.State.MISSING ? "未找到" : "失败") + "  ·  " + extra;
            if (state == BatchQueue.State.QUEUED) extra = "等待处理";
            detail.setText(extra);
            action.setText(state==BatchQueue.State.MISSING && openPack!=null && PackFlow.supports(row.query,row.path)
                    ? "选整季包 ›" : stateLabel(state));
            action.setTextColor(color);
            boolean actionable = state == BatchQueue.State.CHOICE || state == BatchQueue.State.FAILED || state == BatchQueue.State.MISSING;
            action.setBackground(actionable ? ui.shape(TvUi.SURFACE,TvUi.LINE,5) : null);
        }
    }

    private void open(BatchQueue.Row row) {
        if (row.state == BatchQueue.State.CHOICE) {
            chooseVersion(row);
        } else if (row.state == BatchQueue.State.MISSING || row.state == BatchQueue.State.FAILED) {
            issueDetails(row);
        } else {
            LinearLayout content = panel();
            content.addView(ui.text(stateLabel(row.state),20,TvUi.PAPER,true));
            ui.space(content,10);
            content.addView(ui.note(row.share + "/" + row.path));
            if (!row.message.isEmpty()) {
                ui.space(content,10);
                content.addView(ui.note(row.message));
            }
            ui.space(content,14);
            TextView done = ui.button("返回队列",false,() -> back());
            content.addView(done,ui.size(-2,37));
            display(content,false);
            done.requestFocus();
        }
    }

    private void issueDetails(BatchQueue.Row row) {
        LinearLayout content = panel();
        content.addView(ui.text(row.state == BatchQueue.State.MISSING ? "未找到匹配的简中字幕" : "任务失败",
                22,TvUi.PAPER,true));
        ui.space(content,10);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout details = ui.col();
        details.addView(ui.text(row.message,16,TvUi.PAPER,false));
        ui.space(details,13);
        details.addView(ui.note("当前搜索词"));
        details.addView(ui.text(row.query,16,TvUi.PAPER,false));
        if (!row.query.equals(row.originalQuery)) {
            ui.space(details,7);
            details.addView(ui.note("最初的搜索词"));
            details.addView(ui.text(row.originalQuery,15,TvUi.PAPER,false));
        }
        ui.space(details,10);
        details.addView(ui.note(row.share + "/" + row.path));
        scroll.addView(details);
        ui.fill(content,scroll,-1);
        ui.space(content,12);
        TextView seasonPack = null;
        if (openPack != null && PackFlow.supports(row.query,row.path)) {
            seasonPack = ui.button("去 SubHD 选整季字幕包",true,() -> { back(); openPack.accept(row); });
            content.addView(seasonPack,ui.size(-2,38));
            ui.space(content,8);
        }
        LinearLayout actions = ui.row();
        TextView retry = ui.button("重新查找",seasonPack==null,() -> {
            queue.retry(row.id);
            back();
        });
        actions.addView(retry,ui.size(-2,38));
        ui.space(actions,8);
        actions.addView(ui.button("修改搜索词并重试",false,() -> {
            back();
            editQuery(row);
        }),ui.size(-2,38));
        ui.space(actions,8);
        actions.addView(ui.button("返回队列",false,() -> back()),ui.size(-2,38));
        content.addView(actions);
        display(content,true);
        (seasonPack==null?retry:seasonPack).requestFocus();
    }

    private void editQuery(BatchQueue.Row row) {
        LinearLayout content = panel();
        content.addView(ui.text("修改搜索词并重试",22,TvUi.PAPER,true));
        ui.space(content,7);
        TextView filename = ui.note(row.name);
        ui.clip(filename,2);
        content.addView(filename);
        ui.space(content,10);
        EditText query = ui.edit("剧名和集号、片名或 IMDb 编号",row.query,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(query,ui.size(-1,42));
        ui.space(content,12);
        LinearLayout actions = ui.row();
        TextView retry = ui.button("重新查找",true,() -> {
            try {
                queue.retry(row.id,query.getText().toString());
                back();
            } catch (IllegalArgumentException e) {
                query.requestFocus();
                query.setError(e.getMessage());
            }
        });
        actions.addView(retry,ui.size(-2,38));
        ui.space(actions,8);
        actions.addView(ui.button("取消",false,() -> {
            back();
            issueDetails(row);
        }),ui.size(-2,38));
        content.addView(actions);
        query.setOnEditorActionListener((v,id,event) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            retry.performClick();
            return true;
        });
        display(content,false);
        query.requestFocus();
    }

    private LinearLayout panel() {
        LinearLayout content = ui.col();
        content.setBackground(ui.shape(TvUi.SURFACE,TvUi.LINE,10));
        ui.pad(content,21,15);
        return content;
    }

    private void chooseVersion(BatchQueue.Row row) {
        if (row.options.isEmpty()) return;
        LinearLayout content = panel();
        LinearLayout header = ui.row();
        ui.fill(header,ui.text("选择字幕版本",23,TvUi.PAPER,true),-2);
        header.addView(ui.button("返回队列",false,() -> back()),ui.size(-2,34));
        content.addView(header);
        ui.space(content,6);
        TextView film = ui.note(row.name);
        ui.clip(film,2);
        content.addView(film);
        ui.space(content,8);
        ListView list = ui.list();
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return row.options.size(); }
            @Override public Object getItem(int pos) { return row.options.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View reuse, ViewGroup parent) {
                SubtitleService.Result result = row.options.get(pos);
                LinearLayout item = ui.col();
                item.setGravity(Gravity.CENTER_VERTICAL);
                ui.pad(item,9,6);
                TextView name = ui.text(result.title,15,TvUi.PAPER,true);
                ui.clip(name,2);
                item.addView(name);
                ui.space(item,2);
                TextView meta = ui.text(result.source.label + "  ·  " + result.detail,12,TvUi.MUTED,false);
                ui.clip(meta,1);
                item.addView(meta);
                return item;
            }
        });
        list.setOnItemClickListener((parent,view,index,id) -> {
            queue.choose(row.id,row.options.get(index));
            back();
        });
        ui.fill(content,list,-1);
        ui.space(content,6);
        TextView selected = ui.text(row.options.get(0).title,13,TvUi.AMBER,false);
        selected.setMaxLines(3);
        selected.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(selected);
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int index,long id) {
                selected.setText(row.options.get(index).title);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        display(content,true);
        list.requestFocus();
        list.setSelection(0);
    }

    private void display(LinearLayout content, boolean tall) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        window.setLayout(Math.min(ui.dp(660), (int)(width * .86f)),tall ? (int)(height * .79f) : -2);
        modal = dialog;
        dialog.setOnDismissListener(d -> { if (modal == dialog) modal = null; });
        dialog.show();
    }
}
