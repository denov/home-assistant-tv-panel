package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Home Assistant address and token, in the app's own storage (backups are off). They come from pairing with the
 * TV Panel integration in Home Assistant (PairServer), or as config.json pushed by install.sh through ConfigProvider,
 * which is read once and deleted, with the solar screen's panel layout. Everything else is set in Home
 * Assistant itself: see README.md.
 */
final class Config {
    static final String PUSHED = "config.json";

    final String url;
    final String token;

    Config(String url, String token) {
        this.url = url == null ? "" : url;
        this.token = token == null ? "" : token;
    }

    boolean complete() {
        return !url.isEmpty() && !token.isEmpty();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("server", Context.MODE_PRIVATE);
    }

    static Config load(Context c) {
        SharedPreferences p = prefs(c);
        return new Config(p.getString("url", ""), p.getString("token", ""));
    }

    /** Paired by the Home Assistant integration (PairServer): the address and the TV's own token. */
    static void save(Context c, String url, String token) {
        prefs(c).edit().putString("url", normalizeUrl(url)).putString("token", token.trim()).apply();
        WidgetProvider.invalidate();
        SettingsSource.invalidate();
    }

    /** The owner's panel layout for the solar screen (config.json's "solar"), or "" for rows of panels. */
    static String solarLayout(Context c) {
        return prefs(c).getString("solar", "");
    }

    /** "http://host:8123/" and "host:8123" both become "http://host:8123". */
    static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** A pushed file that doesn't read this soon after it changed may still be being written. */
    static final long WRITE_GRACE_MS = 10_000;

    static boolean mayBeWriting(long modifiedMs, long nowMs) {
        return nowMs - modifiedMs <= WRITE_GRACE_MS;
    }

    /**
     * Takes a pushed config.json ({"url": ..., "token": ...}) if there is one, and deletes it. One that doesn't read is
     * kept while it may still be being written: the status screen can look before install.sh's write is done, and the
     * import that runs when the write closes (ConfigProvider) then finds it.
     */
    static boolean importPushed(Context c) {
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, PUSHED);
        if (f == null || !f.isFile()) return false;
        boolean done = true;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            SharedPreferences.Editor e = prefs(c).edit();
            if (o.has("url")) e.putString("url", normalizeUrl(o.optString("url")));
            if (o.has("token")) e.putString("token", o.optString("token").trim());
            if (o.has("solar")) e.putString("solar", o.optJSONObject("solar") == null ? "" : o.optJSONObject("solar").toString());
            e.apply();
            WidgetProvider.invalidate();
            SettingsSource.invalidate();
            return true;
        } catch (Exception e) {
            done = !mayBeWriting(f.lastModified(), System.currentTimeMillis());
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            if (done) f.delete();
        }
    }
}
