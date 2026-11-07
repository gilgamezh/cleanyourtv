package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * gilgamezh fork: a "top shelf" of the Plex server's Continue watching and Recently added titles, between the clock
 * and the dock. Data comes straight from the Plex Media Server on the LAN (services.json); a card opens the title in
 * the Plex app, or the app itself when the deep link is refused. Nothing is shown while the server is unreachable.
 */
final class PlexShelf {
    private static final String TAG = "TvHome.Plex";
    private static final String PLEX_PKG = "com.plexapp.android";
    private static final int CARD_W = 184, CARD_H = 104;

    interface Host {
        /** Down from the shelf: back to the dock. */
        void focusDock();
        /** The shelf took focus: hide the dock's glass highlight and hero. */
        void onShelfFocused();
    }

    static final class Item {
        String row, ratingKey, title, subtitle, imagePath;
        long viewOffset, duration, addedAt;
        Bitmap image;
    }

    private final Context ctx;
    private final Services.Plex cfg;
    private final Host host;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout panel;
    private final TextView label;
    private final TextView focusedTitle;
    private final HorizontalScrollView scroller;
    private final LinearLayout row;
    private final List<Item> items = new ArrayList<>();
    private String machineId = "";
    private long lastFetch;
    private boolean fetching;
    private int lastFocusedIndex;
    private boolean closed;
    /** Hidden while focus is in the app grid below the dock (the grid scrolls up under the shelf). */
    private boolean suppressed = true;

    PlexShelf(Context ctx, FrameLayout root, Services.Plex cfg, Host host) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.host = host;
        dp = ctx.getResources().getDisplayMetrics().density;

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        label = new TextView(ctx);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTextColor(0xCCFFFFFF);
        label.setLetterSpacing(0.2f);
        label.setAllCaps(true);
        label.setShadowLayer(4 * dp, 0, dp, 0xAA000000);
        setFont(label, R.string.font_medium);
        // One line above the cards: the row name, then the focused title (no line under the row: the dock is there).
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        head.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroller = new HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setFillViewport(false);
        // Room for the focused card's scale-up at both ends and above/below.
        int pad = px(10);
        scroller.setPadding(pad, pad, px(60), pad);
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        scroller.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = -pad;
        sp.topMargin = px(2);
        panel.addView(scroller, sp);

        focusedTitle = new TextView(ctx);
        focusedTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        focusedTitle.setTextColor(Color.WHITE);
        focusedTitle.setSingleLine(true);
        focusedTitle.setEllipsize(TextUtils.TruncateAt.END);
        focusedTitle.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        focusedTitle.setAlpha(0f);
        setFont(focusedTitle, R.string.font_light);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = px(14);
        head.addView(focusedTitle, tp);

        // Anchored above the dock (anchorAbove); until the grid has laid out, near the bottom.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.bottomMargin = px(140);
        // The panel starts 12dp left of the content so the first card's focus scale-up is not clipped by Home's root.
        lp.setMarginStart(px(48));
        panel.setPadding(px(12), 0, 0, 0);
        panel.setClipToPadding(false);
        root.addView(panel, lp);
    }

    boolean hasItems() { return !items.isEmpty(); }

    /** On screen and focusable from the dock (Plex or the shelf has focus, and it is not faded out). */
    boolean isOpen() { return !items.isEmpty() && !suppressed; }

    /** The panel's bottom sits gap dp above the dock's top edge (distance from the screen bottom in px). */
    void anchorAbove(int fromBottomPx) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int want = fromBottomPx + px(18);   // clear of the dock band and the focused card's scale-up
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want;
            panel.setLayoutParams(lp);
        }
    }

    /** Shown while focus is on the dock or the shelf, faded out while the grid below is in use. */
    void setShown(boolean shown) {
        if (suppressed == !shown) return;
        suppressed = !shown;
        if (items.isEmpty()) return;
        panel.animate().cancel();
        if (shown) {
            panel.setVisibility(View.VISIBLE);
            panel.animate().alpha(1f).setDuration(200).start();
        } else {
            panel.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                if (suppressed) panel.setVisibility(View.GONE);
            }).start();
        }
    }

    /** Up from the dock lands on the card focused last. */
    void focus() {
        if (!hasItems()) return;
        setShown(true);
        View v = row.getChildAt(Math.min(lastFocusedIndex, row.getChildCount() - 1));
        if (v != null) v.requestFocus();
    }

    void refreshIfStale() {
        if (fetching || closed) return;
        if (System.currentTimeMillis() - lastFetch < cfg.refreshMinutes * 60_000L && !items.isEmpty()) return;
        fetching = true;
        worker.execute(() -> {
            List<Item> fresh = null;
            try {
                fresh = fetch();
            } catch (Exception e) {
                Log.w(TAG, "fetch failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            List<Item> result = fresh;
            main.post(() -> {
                fetching = false;
                if (closed || result == null) return;
                lastFetch = System.currentTimeMillis();
                show(result);
            });
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    // ---- data ---------------------------------------------------------------------------------------

    private Map<String, String> auth() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Plex-Token", cfg.token);
        h.put("X-Plex-Client-Identifier", "gilgamezh-tv-launcher");
        h.put("X-Plex-Product", "gilgamezh TV launcher");
        return h;
    }

    private JSONObject getJson(String path) throws Exception {
        byte[] body = Services.get(cfg.url + path, auth(), 4 * 1024 * 1024);
        return new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONObject("MediaContainer");
    }

    private List<Item> fetch() throws Exception {
        if (machineId.isEmpty()) machineId = getJson("/identity").optString("machineIdentifier");
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String r : cfg.rows) {
            List<Item> part = "continue".equals(r) ? continueWatching() : "recent".equals(r) ? recentlyAdded() : Collections.emptyList();
            for (Item it : part) if (seen.add(it.ratingKey)) out.add(it);
        }
        for (Item it : out) {
            if (closed) break;
            it.image = image(it.imagePath);
        }
        Log.i(TAG, "shelf " + out.size() + " items");
        return out;
    }

    private List<Item> continueWatching() throws Exception {
        JSONArray md;
        try {
            JSONObject mc = getJson("/hubs/home/continueWatching?count=" + cfg.items);
            JSONArray hubs = mc.optJSONArray("Hub");
            md = hubs != null && hubs.length() > 0 ? hubs.getJSONObject(0).optJSONArray("Metadata") : null;
        } catch (Exception e) {
            md = null;
        }
        if (md == null) md = getJson("/library/onDeck?X-Plex-Container-Size=" + cfg.items).optJSONArray("Metadata");
        return parse(md, "Continue watching", cfg.items);
    }

    private List<Item> recentlyAdded() throws Exception {
        JSONArray dirs = getJson("/library/sections").optJSONArray("Directory");
        List<Item> all = new ArrayList<>();
        if (dirs == null) return all;
        for (int i = 0; i < dirs.length(); i++) {
            JSONObject d = dirs.getJSONObject(i);
            String type = d.optString("type");
            if (!"movie".equals(type) && !"show".equals(type)) continue;
            String key = d.optString("key");
            if (!key.matches("\\d+")) continue;
            all.addAll(parse(getJson("/library/sections/" + key + "/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size="
                    + cfg.items).optJSONArray("Metadata"), "Recently added", cfg.items));
        }
        Collections.sort(all, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return all.size() > cfg.items ? new ArrayList<>(all.subList(0, cfg.items)) : all;
    }

    private static List<Item> parse(JSONArray md, String row, int max) {
        List<Item> out = new ArrayList<>();
        if (md == null) return out;
        for (int i = 0; i < md.length() && out.size() < max; i++) {
            JSONObject m = md.optJSONObject(i);
            if (m == null) continue;
            String type = m.optString("type");
            Item it = new Item();
            it.row = row;
            it.ratingKey = m.optString("ratingKey");
            if (!it.ratingKey.matches("\\d+")) continue;
            it.viewOffset = m.optLong("viewOffset");
            it.duration = m.optLong("duration");
            it.addedAt = m.optLong("addedAt");
            switch (type) {
                case "episode":
                    it.title = m.optString("grandparentTitle");
                    it.subtitle = "S" + m.optInt("parentIndex") + " E" + m.optInt("index") + " · " + m.optString("title");
                    it.imagePath = first(m, "thumb", "grandparentArt", "art");
                    break;
                case "season":
                    it.title = m.optString("parentTitle");
                    it.subtitle = m.optString("title");
                    it.imagePath = first(m, "art", "parentArt", "thumb");
                    break;
                case "show":
                    it.title = m.optString("title");
                    it.subtitle = m.optString("year");
                    it.imagePath = first(m, "art", "thumb");
                    break;
                case "movie":
                    it.title = m.optString("title");
                    it.subtitle = m.has("year") ? String.valueOf(m.optInt("year")) : "";
                    it.imagePath = first(m, "art", "thumb");
                    break;
                default:
                    continue;   // music, photos, clips: not on the shelf
            }
            out.add(it);
        }
        return out;
    }

    private static String first(JSONObject m, String... keys) {
        for (String k : keys) {
            String v = m.optString(k);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private Bitmap image(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            String url = cfg.url + "/photo/:/transcode?width=" + px(CARD_W) + "&height=" + px(CARD_H)
                    + "&minSize=1&upscale=1&url=" + URLEncoder.encode(path, "UTF-8");
            byte[] bytes = Services.get(url, auth(), 2 * 1024 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- views --------------------------------------------------------------------------------------

    private void show(List<Item> fresh) {
        boolean hadFocus = row.hasFocus();
        for (Item it : items) if (it.image != null && !fresh.contains(it)) it.image.recycle();
        items.clear();
        items.addAll(fresh);
        row.removeAllViews();
        for (int i = 0; i < items.size(); i++) row.addView(card(items.get(i), i));
        panel.setVisibility(items.isEmpty() || suppressed ? View.GONE : View.VISIBLE);
        panel.setAlpha(suppressed ? 0f : 1f);
        if (!items.isEmpty()) label.setText(items.get(0).row);
        lastFocusedIndex = Math.min(lastFocusedIndex, Math.max(0, items.size() - 1));
        if (hadFocus) focus();
    }

    private View card(Item it, int index) {
        FrameLayout card = new FrameLayout(ctx);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        final float radius = 10 * dp;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadius(radius);
        card.setBackground(bg);

        ImageView image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (it.image != null) image.setImageBitmap(it.image);
        card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(56), Gravity.BOTTOM);
        card.addView(shade, sl);

        TextView name = new TextView(ctx);
        name.setText(it.title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        name.setTextColor(Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        setFont(name, R.string.font_medium);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        nl.leftMargin = px(10);
        nl.rightMargin = px(10);
        nl.bottomMargin = px(it.duration > 0 && it.viewOffset > 0 ? 11 : 8);
        card.addView(name, nl);

        if (it.duration > 0 && it.viewOffset > 0) {
            float done = Math.min(1f, it.viewOffset / (float) it.duration);
            View track = new View(ctx);
            track.setBackgroundColor(0x55FFFFFF);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3), Gravity.BOTTOM);
            card.addView(track, tl);
            View bar = new View(ctx);
            bar.setBackgroundColor(0xFFE5A00D);   // Plex amber
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Math.max(px(4), Math.round(px(CARD_W) * done)), px(3), Gravity.BOTTOM | Gravity.START);
            card.addView(bar, bl);
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(px(3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);

        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? 8 * dp : 0).setDuration(150).start();
            v.setForeground(has ? ring : null);
            if (has) {
                lastFocusedIndex = index;
                label.setText(it.row);
                focusedTitle.setText(it.subtitle == null || it.subtitle.isEmpty() ? it.title : it.title + "  ·  " + it.subtitle);
                focusedTitle.animate().alpha(1f).setDuration(150).start();
                host.onShelfFocused();
            } else {
                // Checked after the focus change settles: leaving the shelf hides the title and scrolls back to the
                // start, so the row never sits half-scrolled under the clock.
                row.post(() -> {
                    if (row.hasFocus()) return;
                    focusedTitle.animate().alpha(0f).setDuration(150).start();
                    if (!items.isEmpty()) label.setText(items.get(0).row);
                    scroller.smoothScrollTo(0, 0);
                });
            }
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                host.focusDock();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index == 0) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index == items.size() - 1) return true;
            return false;
        });
        card.setOnClickListener(v -> open(it));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H));
        lp.rightMargin = px(16);
        card.setLayoutParams(lp);
        return card;
    }

    private void open(Item it) {
        String uri = "plex://server://" + machineId + "/com.plexapp.plugins.library/library/metadata/" + it.ratingKey;
        Intent deep = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PLEX_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(deep);
            Log.i(TAG, "opened ratingKey " + it.ratingKey);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "deep link refused, opening Plex");
        }
        Intent app = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(PLEX_PKG);
        if (app != null) ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void setFont(TextView t, int fontRes) {
        try {
            t.setTypeface(UiFont.get(ctx, fontRes));
        } catch (Exception ignored) {
            // system font
        }
    }

    private int px(float v) { return Math.round(v * dp); }
}
