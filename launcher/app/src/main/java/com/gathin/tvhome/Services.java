package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Self-hosted services the home screen talks to (gilgamezh fork): Immich for the wallpaper, Plex for the shelf.
 * Settings live in services.json in the launcher's own folder on the TV (written by tools/services-setup.sh);
 * no file, or a section missing, means that feature is off and the launcher stays fully offline.
 */
final class Services {
    static final String FILE = "services.json";
    private static final String TAG = "TvHome.Services";

    static final class Immich {
        final String url, apiKey, albumId;
        final int photos, refreshHours, rotationMinutes, dim;
        final boolean landscapeOnly;
        /** Show the whole photo, letterboxed, instead of cropping it to fill the screen. */
        final boolean fit;

        Immich(JSONObject o) {
            url = trimSlash(o.optString("url"));
            apiKey = o.optString("api_key");
            albumId = o.optString("album_id");
            photos = clamp(o.optInt("photos", 40), 1, 100);
            refreshHours = clamp(o.optInt("refresh_hours", 6), 1, 168);
            rotationMinutes = clamp(o.optInt("rotation_minutes", 3), 1, 1440);
            dim = clamp(o.optInt("dim", 25), 0, 90);
            landscapeOnly = o.optBoolean("landscape_only", true);
            fit = o.optBoolean("fit", true);
        }

        boolean valid() { return !url.isEmpty() && !apiKey.isEmpty() && !albumId.isEmpty(); }
    }

    static final class Plex {
        final String url, token;
        final List<String> rows = new ArrayList<>();
        final int items, refreshMinutes;

        Plex(JSONObject o) {
            url = trimSlash(o.optString("url"));
            token = o.optString("token");
            JSONArray r = o.optJSONArray("rows");
            if (r == null) { rows.add("continue"); rows.add("recent"); }
            else for (int i = 0; i < r.length(); i++) rows.add(r.optString(i));
            items = clamp(o.optInt("items", 12), 1, 40);
            refreshMinutes = clamp(o.optInt("refresh_minutes", 5), 1, 1440);
        }

        boolean valid() { return !url.isEmpty() && !token.isEmpty() && !rows.isEmpty(); }
    }

    final Immich immich;
    final Plex plex;

    private Services(Immich immich, Plex plex) {
        this.immich = immich;
        this.plex = plex;
    }

    static Services load(Context ctx) {
        File dir = Config.dir(ctx);
        File file = dir == null ? null : new File(dir, FILE);
        if (file == null || !file.isFile()) return new Services(null, null);
        try (InputStream in = new FileInputStream(file)) {
            JSONObject json = new JSONObject(new String(readAll(in, 64 * 1024), StandardCharsets.UTF_8));
            JSONObject i = json.optJSONObject("immich"), p = json.optJSONObject("plex");
            Immich immich = i == null ? null : new Immich(i);
            Plex plex = p == null ? null : new Plex(p);
            return new Services(immich != null && immich.valid() ? immich : null, plex != null && plex.valid() ? plex : null);
        } catch (Exception e) {
            Log.w(TAG, "services.json unreadable: " + e.getClass().getSimpleName());
            return new Services(null, null);
        }
    }

    // ---- tiny HTTP helper: bounded sizes and timeouts, never logs headers or URLs with secrets ----

    static byte[] get(String url, Map<String, String> headers, int maxBytes) throws IOException {
        return request("GET", url, headers, null, maxBytes);
    }

    static byte[] post(String url, Map<String, String> headers, String jsonBody, int maxBytes) throws IOException {
        return request("POST", url, headers, jsonBody, maxBytes);
    }

    private static byte[] request(String method, String url, Map<String, String> headers, String body, int maxBytes)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "gilgamezh-tv-launcher");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) { return readAll(in, maxBytes); }
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("response over " + max + " bytes");
        }
        return out.toByteArray();
    }

    private static String trimSlash(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
