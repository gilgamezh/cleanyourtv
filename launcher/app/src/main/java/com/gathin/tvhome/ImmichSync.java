package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps a small pool of photos from one Immich album in files/immich/ for the wallpaper rotation (gilgamezh fork).
 * Every refresh_hours it lists the album (timeline API, which covers every member of a shared album; random search
 * as a fallback), picks photos at random, downloads the "preview" rendition (~1440 px JPEG) of the new ones, deletes
 * the rest, and writes captions.txt with a PicPak-style caption per photo: "d/m/yyyy - City, State, CC".
 * Runs on one background thread; a failure keeps the photos already on the TV.
 */
final class ImmichSync {
    private static final String TAG = "TvHome.Immich";
    static final String DIR = "immich";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile boolean running;
    private static final String ID_RE = "[0-9a-fA-F-]{36}";

    interface Done { void onChanged(); }

    static File dir(Context ctx) {
        File base = Config.dir(ctx);
        return base == null ? null : new File(base, DIR);
    }

    /** Starts a refresh if one is due (or the pool or its captions are missing); calls done on the worker thread when files changed. */
    static void maybeSync(Context ctx, Services.Immich cfg, Done done) {
        if (cfg == null || running) return;
        File dir = dir(ctx);
        if (dir == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences("immich_sync", Context.MODE_PRIVATE);
        long last = prefs.getLong("last", 0);
        String key = cfg.albumId + "/" + cfg.photos + "/" + cfg.landscapeOnly;
        boolean configChanged = !key.equals(prefs.getString("key", ""));
        int have = WallpaperRotation.images(dir).size();
        boolean noCaptions = have > 0 && !new File(dir, WallpaperCaptions.FILE_NAME).isFile();
        boolean due = System.currentTimeMillis() - last > cfg.refreshHours * 3_600_000L;
        if (!due && !configChanged && !noCaptions && have > 0) return;
        running = true;
        worker.execute(() -> {
            try {
                boolean changed = sync(dir, cfg);
                prefs.edit().putLong("last", System.currentTimeMillis()).putString("key", key).apply();
                if (changed && done != null) done.onChanged();
            } catch (Exception e) {
                Log.w(TAG, "sync failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                running = false;
            }
        });
    }

    private static Map<String, String> auth(Services.Immich cfg) {
        Map<String, String> auth = new HashMap<>();
        auth.put("x-api-key", cfg.apiKey);
        return auth;
    }

    /** Every image in the album as {id, landscape?}; timeline first (all members' photos), random search if that fails. */
    private static List<String[]> listAlbum(Services.Immich cfg) {
        List<String[]> out = new ArrayList<>();
        Map<String, String> auth = auth(cfg);
        try {
            String q = "albumId=" + cfg.albumId + "&isTrashed=false";
            JSONArray buckets = new JSONArray(new String(Services.get(cfg.url + "/api/timeline/buckets?" + q, auth, 1024 * 1024),
                    StandardCharsets.UTF_8));
            for (int b = 0; b < buckets.length() && out.size() < 5000; b++) {
                String bucket = buckets.getJSONObject(b).optString("timeBucket");
                if (bucket.isEmpty()) continue;
                JSONObject cols = new JSONObject(new String(Services.get(cfg.url + "/api/timeline/bucket?" + q + "&timeBucket="
                        + URLEncoder.encode(bucket, "UTF-8"), auth, 8 * 1024 * 1024), StandardCharsets.UTF_8));
                JSONArray ids = cols.optJSONArray("id"), isImage = cols.optJSONArray("isImage"), ratio = cols.optJSONArray("ratio");
                if (ids == null) continue;
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i);
                    if (!id.matches(ID_RE) || (isImage != null && !isImage.optBoolean(i, true))) continue;
                    double r = ratio == null ? 0 : ratio.optDouble(i, 0);
                    out.add(new String[]{id, r == 0 || r >= 1 ? "L" : "P"});
                }
            }
            if (!out.isEmpty()) return out;
        } catch (Exception e) {
            Log.w(TAG, "timeline listing failed (" + e.getMessage() + "), using random search");
        }
        try {
            JSONObject body = new JSONObject();
            body.put("albumIds", new JSONArray().put(cfg.albumId));
            body.put("type", "IMAGE");
            body.put("size", 250);
            JSONArray found = new JSONArray(new String(Services.post(cfg.url + "/api/search/random", auth, body.toString(),
                    4 * 1024 * 1024), StandardCharsets.UTF_8));
            for (int i = 0; i < found.length(); i++) {
                JSONObject a = found.optJSONObject(i);
                if (a == null || a.optBoolean("isTrashed") || !a.optString("id").matches(ID_RE)) continue;
                int w = a.optInt("width", 0), h = a.optInt("height", 0);
                out.add(new String[]{a.optString("id"), w > 0 && h > 0 && w < h ? "P" : "L"});
            }
        } catch (Exception e) {
            Log.w(TAG, "random search failed: " + e.getMessage());
        }
        return out;
    }

    private static boolean sync(File dir, Services.Immich cfg) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        List<String[]> album = listAlbum(cfg);
        Collections.shuffle(album);
        Set<String> wanted = new LinkedHashSet<>();
        int portrait = 0;
        for (String[] a : album) {
            if ("P".equals(a[1])) { portrait++; if (cfg.landscapeOnly) continue; }
            if (wanted.size() < cfg.photos) wanted.add(a[0]);
        }
        // An album of mostly portrait photos still gets a wallpaper.
        for (String[] a : album) if (wanted.size() < Math.min(cfg.photos, 5)) wanted.add(a[0]);
        if (wanted.isEmpty()) { Log.w(TAG, "album returned no usable images"); return false; }

        Set<String> present = new HashSet<>();
        File[] existing = dir.listFiles();
        if (existing != null) for (File f : existing) present.add(f.getName());
        Map<String, String> captions = new HashMap<>(WallpaperCaptions.read(dir));

        boolean changed = false;
        int fetched = 0;
        Map<String, String> auth = auth(cfg);
        for (String id : wanted) {
            String name = id + ".jpg";
            if (!present.contains(name)) {
                byte[] jpg = Services.get(cfg.url + "/api/assets/" + id + "/thumbnail?size=preview", auth, 12 * 1024 * 1024);
                File tmp = new File(dir, name + ".part");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(jpg); }
                if (tmp.renameTo(new File(dir, name))) { changed = true; fetched++; }
            }
            if (!captions.containsKey(name)) {
                try {
                    String c = caption(new JSONObject(new String(Services.get(cfg.url + "/api/assets/" + id, auth, 512 * 1024),
                            StandardCharsets.UTF_8)));
                    if (!c.isEmpty()) { captions.put(name, c); changed = true; }
                } catch (Exception e) {
                    Log.w(TAG, "no details for one asset: " + e.getMessage());
                }
            }
        }
        int removed = 0;
        if (existing != null) for (File f : existing) {
            String n = f.getName();
            if (n.endsWith(".part") || (n.endsWith(".jpg") && !wanted.contains(n.substring(0, n.length() - 4)))) {
                if (f.delete()) { changed = true; removed++; }
            }
        }
        // captions.txt: one "file<TAB>caption" per photo in the pool (WallpaperCaptions' format).
        StringBuilder txt = new StringBuilder("# Immich captions, written by ImmichSync\n");
        for (String id : wanted) {
            String c = captions.get(id + ".jpg");
            if (c != null) txt.append(id).append(".jpg\t").append(c.replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        File tmp = new File(dir, WallpaperCaptions.FILE_NAME + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(txt.toString().getBytes(StandardCharsets.UTF_8)); }
        tmp.renameTo(new File(dir, WallpaperCaptions.FILE_NAME));

        Log.i(TAG, "album " + album.size() + " images (" + portrait + " portrait), pool " + wanted.size()
                + " (+" + fetched + " -" + removed + ")");
        return changed;
    }

    /** PicPak's short caption: local date as d/m/yyyy, then City, State, ISO country code; missing parts left out. */
    static String caption(JSONObject asset) {
        StringBuilder out = new StringBuilder();
        String local = asset.optString("localDateTime");     // e.g. 2024-07-14T18:32:10.000Z (already local time)
        if (local.length() >= 10 && local.charAt(4) == '-' && local.charAt(7) == '-') {
            try {
                int y = Integer.parseInt(local.substring(0, 4));
                int m = Integer.parseInt(local.substring(5, 7));
                int d = Integer.parseInt(local.substring(8, 10));
                out.append(d).append('/').append(m).append('/').append(y);
            } catch (NumberFormatException ignored) {
                // no date
            }
        }
        JSONObject exif = asset.optJSONObject("exifInfo");
        List<String> place = new ArrayList<>();
        if (exif != null) {
            for (String k : new String[]{"city", "state"}) {
                String v = exif.optString(k, "").trim();
                if (!v.isEmpty() && !"null".equals(v) && !place.contains(v)) place.add(v);
            }
            String cc = countryCode(exif.optString("country", "").trim());
            if (!cc.isEmpty()) place.add(cc);
        }
        if (!place.isEmpty()) {
            if (out.length() > 0) out.append(" - ");
            out.append(String.join(", ", place));
        }
        return out.toString();
    }

    private static Map<String, String> countries;

    /** "Netherlands" -> "NL" via the JVM's English country names; unknown names are kept as they are. */
    static synchronized String countryCode(String name) {
        if (name.isEmpty() || "null".equals(name)) return "";
        if (countries == null) {
            countries = new HashMap<>();
            for (String iso : Locale.getISOCountries()) {
                countries.put(new Locale("", iso).getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT), iso);
            }
            // Names Immich's reverse geocoder uses that differ from the JVM's.
            countries.put("united states of america", "US");
            countries.put("united states", "US");
            countries.put("united kingdom", "GB");
            countries.put("russia", "RU");
            countries.put("south korea", "KR");
            countries.put("czechia", "CZ");
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("the ")) key = key.substring(4);   // "The Netherlands"
        String cc = countries.get(key);
        return cc != null ? cc : name;
    }

    /** The pool, in a stable order, for the wallpaper rotation. */
    static List<File> images(Context ctx) {
        File dir = dir(ctx);
        if (dir == null) return Collections.emptyList();
        return WallpaperRotation.images(dir);
    }
}
