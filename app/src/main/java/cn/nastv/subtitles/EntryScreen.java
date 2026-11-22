package cn.nastv.subtitles;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class EntryScreen {
    private static final int PANEL = 0xff1c2721;
    private static final int PANEL_LINE = 0xff39483d;

    private EntryScreen() {}

    static TextView connection(TvUi ui, String recentPath, String endpoint, boolean canResume,
                               EditText host, EditText user, EditText password, CheckBox remember,
                               TextView status, Runnable resume, Runnable discover, Runnable connect) {
        boolean recent = recentPath != null && !recentPath.isEmpty();
        ScrollView scroll = new ScrollView(ui.a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        ui.fill(ui.body, scroll, -1);
        LinearLayout page = ui.col();
        scroll.addView(page);
        heading(ui, page, recent ? "我的片库" : "连接影片库");

        boolean stacked = ui.a.getResources().getConfiguration().screenWidthDp < 660;
        LinearLayout panels = stacked ? ui.col() : ui.row();
        panels.setGravity(Gravity.TOP);
        page.addView(panels, ui.size(-1, -2));

        LinearLayout hero = ui.col();
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff344b3d, 0xff25392f, 0xff1d2a23});
        surface.setCornerRadius(ui.dp(13));
        surface.setStroke(ui.dp(1), 0xff4b6050);
        hero.setBackground(surface);
        hero.setMinimumHeight(ui.dp(233));
        hero.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(hero, 23, 20);
        column(ui, panels, hero, stacked, 1f);
        if (!stacked) hero.getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        ui.space(panels, 13);
        LinearLayout settings = ui.col();
        settings.setBackground(ui.shape(PANEL, PANEL_LINE, 13));
        ui.pad(settings, 18, 14);
        column(ui, panels, settings, stacked, 1.14f);

        LinearLayout marker = ui.row();
        LinearLayout tile = ui.row();
        tile.setBackground(ui.shape(0xff2a3c31, 0xff61735e, 8));
        tile.setGravity(Gravity.CENTER);
        tile.addView(new TvUi.Glyph(ui.a, recent ? "folder" : "server", TvUi.AMBER), ui.size(22, 22));
        marker.addView(tile, ui.size(31, 31));
        ui.space(marker, 9);
        marker.addView(ui.text(recent ? "上次浏览" : "局域网设备", 14, TvUi.GOOD, true));
        hero.addView(marker);
        ui.space(hero, 17);
        String path = recent ? recentPath.replace('\\', '/') : "";
        int slash = path.lastIndexOf('/');
        String title = recent ? path.substring(slash + 1) : "发现 NAS";
        TextView directory = ui.text(title, 29, TvUi.PAPER, true);
        ui.clip(directory, 2);
        hero.addView(directory);
        ui.space(hero, 6);
        String location = recent ? (slash < 0 ? endpoint : endpoint + "  /  " + path.substring(0, slash).replace("/", "  /  "))
                : "电视与 NAS 连入同一网络，并在 NAS 上开启 SMB 文件共享。";
        TextView directoryInfo = ui.note(location);
        ui.clip(directoryInfo, 2);
        if (recent) {
            directoryInfo.setSingleLine();
            directoryInfo.setEllipsize(TextUtils.TruncateAt.START);
        }
        hero.addView(directoryInfo);
        ui.space(hero, 19);
        TextView primary = ui.button(recent ? (canResume ? "继续浏览" : "输入密码并继续") : "发现局域网 NAS",
                true, recent ? resume : discover);
        identify(primary);
        hero.addView(primary, ui.size(-2, 42));
        TextView discoverButton = primary;
        if (recent) {
            ui.space(hero, 9);
            discoverButton = ui.button("发现其他 NAS", false, discover);
            identify(discoverButton);
            hero.addView(discoverButton, ui.size(-2, 36));
            primary.setNextFocusDownId(discoverButton.getId());
            discoverButton.setNextFocusUpId(primary.getId());
        }

        settings.addView(ui.text(recent ? "连接设置" : "手动连接", 18, TvUi.PAPER, true));
        ui.space(settings, 9);
        TextView hostLabel = ui.text("NAS 地址", 13, TvUi.MUTED, false);
        settings.addView(hostLabel);
        ui.space(settings, 4);
        identify(host);
        hostLabel.setLabelFor(host.getId());
        settings.addView(host, ui.size(-1, 37));
        ui.space(settings, 8);
        LinearLayout accounts = ui.row();
        identify(user);
        identify(password);
        field(ui, accounts, "用户名", user);
        ui.space(accounts, 9);
        field(ui, accounts, "密码", password);
        settings.addView(accounts);
        ui.space(settings, 4);
        remember.setText("在此设备上记住密码");
        remember.setTextColor(TvUi.MUTED);
        remember.setTextSize(13);
        remember.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                new int[]{TvUi.AMBER, TvUi.MUTED}));
        remember.setBackground(ui.background(0, 0, TvUi.SURFACE, TvUi.FOCUS, 5));
        identify(remember);
        settings.addView(remember, ui.size(-2, 33));
        ui.space(settings, 4);
        TextView submit = ui.button(recent ? "连接 NAS" : "连接并选择共享", false, connect);
        identify(submit);
        submit.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                InputMethodManager ime = (InputMethodManager) ui.a.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (ime != null) ime.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        });
        settings.addView(submit, ui.size(-2, 39));
        ui.space(page, 8);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status, ui.size(-1, -2));

        if (!stacked) {
            primary.setNextFocusRightId(host.getId());
            discoverButton.setNextFocusRightId(host.getId());
            host.setNextFocusLeftId(primary.getId());
        }
        host.setNextFocusDownId(user.getId());
        user.setNextFocusRightId(password.getId());
        password.setNextFocusLeftId(user.getId());
        user.setNextFocusDownId(remember.getId());
        password.setNextFocusDownId(remember.getId());
        remember.setNextFocusDownId(submit.getId());
        submit.setNextFocusUpId(remember.getId());
        focusOnOpen(recent || host.length() == 0 ? primary : submit);
        return submit;
    }

    static void shares(TvUi ui, String endpoint, List<String> names, String error, int previous,
                       TvUi.OnPick pick, Runnable manual, Runnable refresh) {
        heading(ui, ui.body, "选择共享");
        boolean stacked = ui.a.getResources().getConfiguration().screenWidthDp < 660;
        LinearLayout columns = stacked ? ui.col() : ui.row();
        columns.setGravity(Gravity.TOP);
        ui.fill(ui.body, columns, -1);
        LinearLayout contents = ui.col();
        contents.setBackground(ui.shape(PANEL, PANEL_LINE, 12));
        ui.pad(contents, 12, 10);
        ui.fill(columns, contents, -1);

        LinearLayout line = ui.row();
        line.addView(new TvUi.Glyph(ui.a, "server", TvUi.GOOD), ui.size(21, 21));
        ui.space(line, 8);
        TextView server = ui.text(endpoint, 16, TvUi.PAPER, true);
        ui.clip(server, 1);
        ui.fill(line, server, -2);
        if (error == null) line.addView(ui.text(names.size() + " 个共享", 13, TvUi.MUTED, false));
        contents.addView(line, ui.size(-1, 28));
        ui.space(contents, 7);
        ui.rule(contents);
        ListView list = null;
        if (names.isEmpty()) {
            ui.space(contents, 12);
            contents.addView(ui.note(error == null ? "此账号没有列出共享，可手动输入共享路径。" : error));
        } else {
            List<TvUi.Item> rows = new ArrayList<>();
            for (String name : names) rows.add(new TvUi.Item(name, "共享文件夹", "folder"));
            list = ui.items(rows, pick);
            ui.fill(contents, list, -1);
        }
        ui.space(columns, 13);
        LinearLayout actions = ui.col();
        actions.setBackground(ui.shape(PANEL, PANEL_LINE, 12));
        ui.pad(actions, 16, 14);
        columns.addView(actions, ui.size(stacked ? -1 : 206, -2));
        actions.addView(ui.text("按路径打开", 18, TvUi.PAPER, true));
        ui.space(actions, 6);
        actions.addView(ui.note("输入共享名称，也可以直接指定它的子目录。"));
        ui.space(actions, 12);
        TextView enter = ui.button("手动打开共享", false, manual);
        identify(enter);
        actions.addView(enter, ui.size(-1, 38));
        ui.space(actions, 7);
        TextView reload = ui.button("刷新列表", false, refresh);
        identify(reload);
        actions.addView(reload, ui.size(-1, 36));
        if (list == null) {
            enter.requestFocus();
        } else {
            identify(list);
            if (!stacked) {
                list.setNextFocusRightId(enter.getId());
                enter.setNextFocusLeftId(list.getId());
                reload.setNextFocusLeftId(list.getId());
            }
            list.requestFocus();
            list.setSelection(Math.max(0, Math.min(previous, names.size() - 1)));
        }
    }

    private static void heading(TvUi ui, LinearLayout box, String title) {
        ui.space(box, 4);
        box.addView(ui.text(title, 27, TvUi.PAPER, true));
        ui.space(box, 12);
    }

    private static void field(TvUi ui, LinearLayout parent, String label, EditText input) {
        LinearLayout column = ui.col();
        TextView title = ui.text(label, 13, TvUi.MUTED, false);
        title.setLabelFor(input.getId());
        column.addView(title);
        ui.space(column, 4);
        column.addView(input, ui.size(-1, 37));
        ui.fill(parent, column, -2);
    }

    private static void column(TvUi ui, LinearLayout parent, View view, boolean stacked, float weight) {
        LinearLayout.LayoutParams params = ui.size(stacked ? -1 : 0, -2);
        if (!stacked) params.weight = weight;
        parent.addView(view, params);
    }

    private static void identify(View view) {
        if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
    }

    private static void focusOnOpen(View action) {
        action.setFocusableInTouchMode(true);
        action.setFocusedByDefault(true);
        action.requestFocus();
        action.post(() -> {
            if (action.isAttachedToWindow() && action.isShown()) action.requestFocus();
        });
    }
}
