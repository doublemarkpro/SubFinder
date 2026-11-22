package cn.nastv.subtitles;

import android.content.SharedPreferences;
import android.net.Uri;

import java.util.Locale;

/** Remembers a successfully listed share and directory for each SMB endpoint and account. */
final class RecentFolders {
    private final SharedPreferences prefs;

    RecentFolders(SharedPreferences prefs) {
        this.prefs = prefs;
        if (!prefs.contains("pathHost") && !prefs.contains("path")) return;
        SharedPreferences.Editor edit = prefs.edit().remove("pathHost").remove("path");
        String path = prefs.getString("path", "");
        try {
            ShareAddress oldServer = ShareAddress.parse(prefs.getString("pathHost", ""), "");
            ShareAddress lastLogin = ShareAddress.parse(prefs.getString("host", ""), "");
            if (!path.isEmpty() && oldServer.endpoint().equalsIgnoreCase(lastLogin.endpoint())) {
                ShareAddress candidate = ShareAddress.parse(lastLogin.endpoint(), path);
                if (!candidate.share.isEmpty()) {
                    // 1.4 recorded only the opened share path and had no account on the record.
                    // Present it for confirmation on the landing page before enabling auto-resume.
                    edit.putString("suggested/" + identity(lastLogin, prefs.getString("user", "")), path);
                }
            }
        } catch (IllegalArgumentException ignored) {}
        edit.apply();
    }

    private static String identity(ShareAddress server, String account) {
        return Uri.encode(server.endpoint().toLowerCase(Locale.ROOT)) + "/" + Uri.encode(account.trim());
    }

    ShareAddress get(ShareAddress server, String account, boolean includeSuggestion) {
        if (server == null) return null;
        String key = identity(server, account);
        String path = prefs.getString("folder/" + key, "");
        if (path.isEmpty() && includeSuggestion) path = prefs.getString("suggested/" + key, "");
        if (path.isEmpty()) return null;
        try {
            ShareAddress address = ShareAddress.parse(server.endpoint(), path);
            return address.share.isEmpty() ? null : address;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    void remember(ShareAddress server, String account, String share, String directory) {
        if (server == null || share.isEmpty()) return;
        String key = identity(server, account);
        String path = share + (directory.isEmpty() ? "" : "/" + directory);
        if (path.equals(prefs.getString("folder/" + key, ""))) return;
        prefs.edit().putString("folder/" + key, path).remove("suggested/" + key).apply();
    }
}
