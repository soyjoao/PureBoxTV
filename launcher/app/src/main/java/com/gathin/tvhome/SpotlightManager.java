package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SpotlightManager {
    private static final String TAG = "SpotlightManager";
    public static final String DEFAULT_TMDB_API_KEY = BuildConfig.DEFAULT_TMDB_API_KEY;
    public static final String DEFAULT_FANART_API_KEY = BuildConfig.DEFAULT_FANART_API_KEY;
    public static final String DEFAULT_TVDB_API_KEY = BuildConfig.DEFAULT_TVDB_API_KEY;
    public static final String KEY_TMDB_API = "tmdb_api_key";
    public static final String KEY_FANART_API = "fanart_api_key";
    public static final String KEY_TVDB_API = "tvdb_api_key";
    private static final String CACHE_FILE = "spotlights_v4.json";
    private static final String PREFS_NAME = "spotlight_prefs";
    private static final String KEY_DISMISSED = "dismissed_spotlights";
    public static final int MAX_ITEMS = 15;
    private static final int MAX_CANDIDATES = 45;
    private static final int MAX_BACKDROPS_PER_ITEM = 6;
    private static final long TRENDING_TTL_MS = 30 * 60 * 1000L; // 30 min cache for TMDB trending

    public static class Item {
        public String id;
        public String title;
        public String service;
        public int badgeColor;
        public String meta;
        public String overview;
        public String backdropUrl;
        public final List<String> backdropUrls = new ArrayList<>();
        public long tmdbId;
        public String mediaType;
        public String targetPackage;
        public String query;
        public String intentUri;
        public boolean isContinueWatching;
        public int remainingMin;
        public Bitmap bitmap;
        public String loadedBitmapUrl;
        public int cycleOffset = 0;

        public Item(String id, String title, String service, int badgeColor, String meta,
                    String overview, String backdropUrl, String targetPackage, String query,
                    String intentUri, boolean isContinueWatching, int remainingMin) {
            this.id = id;
            this.title = title;
            this.service = service;
            this.badgeColor = badgeColor;
            this.meta = meta;
            this.overview = overview != null ? overview : "";
            this.backdropUrl = upgradeTmdbBackdropSize(backdropUrl);
            if (this.backdropUrl != null && !this.backdropUrl.isEmpty() && !isLowQualityOrLocalUri(this.backdropUrl)) {
                this.backdropUrls.add(this.backdropUrl);
            }
            this.targetPackage = targetPackage;
            this.query = query;
            this.intentUri = intentUri != null ? intentUri : "";
            this.isContinueWatching = isContinueWatching;
            this.remainingMin = remainingMin;
        }

        public void addBackdropCandidate(String url) {
            if (url == null || url.isEmpty() || isLowQualityOrLocalUri(url)) return;
            String up = upgradeTmdbBackdropSize(url);
            if (!backdropUrls.contains(up) && backdropUrls.size() < MAX_BACKDROPS_PER_ITEM) {
                backdropUrls.add(up);
            }
            if (backdropUrl == null || backdropUrl.isEmpty() || isLowQualityOrLocalUri(backdropUrl)) {
                backdropUrl = up;
            }
        }

        public String getBackdropForCycle(int cycle) {
            if (!backdropUrls.isEmpty()) {
                return backdropUrls.get(Math.abs(cycle + cycleOffset) % backdropUrls.size());
            }
            return backdropUrl;
        }
    }

    public interface Callback {
        void onSpotlightsLoaded(List<Item> items);
        void onBackdropLoaded(int index, Bitmap bitmap);
    }

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioTimeoutExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotlight-io");
        t.setDaemon(true);
        return t;
    });
    private final List<Item> items = new ArrayList<>();
    private final List<Item> allCandidates = new ArrayList<>();
    private final List<Item> cachedTrendingPool = new ArrayList<>();
    private long lastTrendingFetchMs = 0L;
    private String lastUsedApiKey = "";
    private String lastFilterSignature = "";
    private String lastLocalWatchSignature = "";
    private String tvdbBearerToken = null;
    private int backdropCycle = 0;
    private volatile int activeIndex = 0;
    private final Map<String, String[]> tmdbEnrichCache = new HashMap<>();
    private final Map<String, List<String>> multiBackdropCache = new HashMap<>();
    private final File cacheDir;
    private final int backdropReqW;
    private final int backdropReqH;

    public SpotlightManager(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        android.util.DisplayMetrics dm = this.context.getResources().getDisplayMetrics();
        this.backdropReqW = Math.min(1920, Math.max(640, dm.widthPixels));
        this.backdropReqH = Math.min(1080, Math.max(360, dm.heightPixels));
        File ext = context.getExternalFilesDir(null);
        this.cacheDir = new File(ext != null ? ext : context.getFilesDir(), "spotlight_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public static String getTmdbApiKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String custom = prefs.getString(KEY_TMDB_API, null);
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        return DEFAULT_TMDB_API_KEY;
    }

    public static String getFanartApiKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String custom = prefs.getString(KEY_FANART_API, null);
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        return DEFAULT_FANART_API_KEY;
    }

    public static String getTvdbApiKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String custom = prefs.getString(KEY_TVDB_API, null);
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        return DEFAULT_TVDB_API_KEY;
    }

    private String apiKey() {
        return getTmdbApiKey(context);
    }

    private String fanartKey() {
        return getFanartApiKey(context);
    }

    private String tvdbKey() {
        return getTvdbApiKey(context);
    }

    private static boolean isInSlidingWindow(int index, int size, int center) {
        if (size <= 3) return true;
        int prev = (center - 1 + size) % size;
        int next = (center + 1) % size;
        return index == center || index == prev || index == next;
    }

    /**
     * Updates the active carousel index and keeps only a 3-item sliding window (prev, current, next)
     * decoded in RAM, dropping bitmaps outside the window and pre-decoding the neighbors from local disk.
     */
    public void setActiveIndex(final int index) {
        this.activeIndex = index;
        synchronized (items) {
            int size = items.size();
            for (int i = 0; i < size; i++) {
                if (!isInSlidingWindow(i, size, index)) {
                    Item it = items.get(i);
                    it.bitmap = null;
                    it.loadedBitmapUrl = null;
                }
            }
        }
        executor.execute(() -> loadWindowBitmaps(index));
    }

    /**
     * Releases decoded bitmaps when Home goes to background or under memory pressure.
     * @param keepCurrentOnly true keeps only activeIndex's bitmap so returning to Home has no blank frame.
     */
    public void trimMemory(boolean keepCurrentOnly) {
        synchronized (items) {
            int cur = this.activeIndex;
            for (int i = 0; i < items.size(); i++) {
                if (!keepCurrentOnly || i != cur) {
                    Item it = items.get(i);
                    it.bitmap = null;
                    it.loadedBitmapUrl = null;
                }
            }
            for (Item c : allCandidates) {
                if (!items.contains(c)) {
                    c.bitmap = null;
                    c.loadedBitmapUrl = null;
                }
            }
        }
    }

    private void loadWindowBitmaps(int centerIndex) {
        List<Item> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        int size = snapshot.size();
        if (size == 0) return;
        int center = ((centerIndex % size) + size) % size;
        if (center != this.activeIndex) return;
        int next = (center + 1) % size;
        int prev = (center - 1 + size) % size;
        int[] window = size == 1 ? new int[]{center}
                : size == 2 ? new int[]{center, next}
                : new int[]{center, next, prev};

        final int cycle = this.backdropCycle;
        for (int idx : window) {
            if (center != this.activeIndex) return;
            Item item = snapshot.get(idx);
            String targetUrl = item.getBackdropForCycle(cycle);
            if (targetUrl == null || targetUrl.isEmpty()) continue;
            if (item.bitmap != null && targetUrl.equals(item.loadedBitmapUrl)) continue;
            Bitmap bmp = loadOrDownloadBackdrop(item, targetUrl);
            if (bmp != null) {
                synchronized (items) {
                    if (isInSlidingWindow(idx, items.size(), this.activeIndex)) {
                        item.bitmap = bmp;
                        item.loadedBitmapUrl = targetUrl;
                        item.backdropUrl = targetUrl;
                    }
                }
                final int postIdx = idx;
                final Bitmap readyBmp = bmp;
                mainHandler.post(() -> callback.onBackdropLoaded(postIdx, readyBmp));
            }
        }
    }

    /**
     * Advances to the next backdrop rotation cycle (called when the carousel completes a full round)
     * and reloads the active window's bitmaps from the next backdrop URL in their pool.
     */
    public void advanceBackdropCycle() {
        backdropCycle++;
        executor.execute(() -> loadWindowBitmaps(this.activeIndex));
    }

    /**
     * Advances the backdrop of a specific item index to its next available cover art for its next pass,
     * and releases its old Bitmap from RAM if it is outside the 3-item sliding window.
     */
    public void rotateBackdropForIndex(final int index) {
        executor.execute(() -> {
            Item item;
            boolean inWindow;
            synchronized (items) {
                if (index < 0 || index >= items.size()) return;
                item = items.get(index);
                item.cycleOffset++;
                inWindow = isInSlidingWindow(index, items.size(), this.activeIndex);
                if (!inWindow) {
                    item.bitmap = null;
                    item.loadedBitmapUrl = null;
                }
            }
            if (item.backdropUrls.size() <= 1) return;
            String nextUrl = item.getBackdropForCycle(this.backdropCycle);
            item.backdropUrl = nextUrl;
            // Ensure file is cached on disk, and only decode into RAM if inside the 3-item sliding window
            if (inWindow) {
                Bitmap bmp = loadOrDownloadBackdrop(item, nextUrl);
                if (bmp != null) {
                    item.bitmap = bmp;
                    item.loadedBitmapUrl = nextUrl;
                    mainHandler.post(() -> callback.onBackdropLoaded(index, bmp));
                }
            } else if (nextUrl != null && nextUrl.startsWith("http")) {
                File localFile = new File(cacheDir, "bg_hd_" + Math.abs(nextUrl.hashCode()) + ".jpg");
                if (!localFile.exists() || localFile.length() == 0) {
                    try { downloadFile(nextUrl, localFile); } catch (Exception ignored) {}
                }
            }
        });
    }

    public List<Item> getItems() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    public void startLoading() {
        executor.execute(() -> {
            Set<String> dismissed = getDismissedSet();

            // 1. Load initial fallback/cached items so UI displays immediately
            List<Item> cached = filterAllowed(loadFromDiskCache(), dismissed);
            if (cached.isEmpty()) {
                cached = filterAllowed(getDefaultItems(), dismissed);
            }
            setCandidatesAndPublish(cached);
            downloadBackdrops();

            // 2. Fetch fresh items (Watch Next + App Preview Channels + TMDB pt-BR)
            try {
                List<Item> fresh = fetchFreshCandidates(dismissed);
                if (!fresh.isEmpty()) {
                    setCandidatesAndPublish(fresh);
                    saveToDiskCache(fresh);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch fresh spotlights: " + e.getMessage());
            }

            // 3. Download/decode backdrops for active items
            downloadBackdrops();
        });
    }

    /**
     * Refreshes Continue Watching items when returning to Home so progress/remaining time stays up to date.
     * Skips full rebuild and disk writes when local Watch Next / Preview Channel state has not changed.
     */
    public void refreshContinueWatching() {
        executor.execute(() -> {
            try {
                Set<String> dismissed = getDismissedSet();
                List<Item> continueItems = new ArrayList<>();
                List<Item> appChannelItems = new ArrayList<>();
                fetchWatchNextPrograms(continueItems, dismissed);
                fetchPreviewChannelPrograms(continueItems, appChannelItems, dismissed);

                String filterSig = currentFilterSignature();
                StringBuilder sigBuilder = new StringBuilder();
                sigBuilder.append(filterSig).append('|');
                for (Item it : continueItems) {
                    sigBuilder.append(it.id).append(':').append(it.remainingMin).append(';');
                }
                for (Item it : appChannelItems) {
                    sigBuilder.append(it.id).append(':').append(it.remainingMin).append(';');
                }
                String sig = sigBuilder.toString();
                long now = android.os.SystemClock.elapsedRealtime();
                boolean trendingValid = !cachedTrendingPool.isEmpty()
                        && apiKey().equals(lastUsedApiKey)
                        && filterSig.equals(lastFilterSignature)
                        && (now - lastTrendingFetchMs) < TRENDING_TTL_MS;

                boolean hasActive;
                synchronized (items) {
                    hasActive = !items.isEmpty();
                }

                if (hasActive && trendingValid && sig.equals(lastLocalWatchSignature)) {
                    // Nothing changed in Watch Next or Preview Channels; just ensure 3-item window bitmaps are ready
                    loadWindowBitmaps(this.activeIndex);
                    return;
                }
                lastLocalWatchSignature = sig;

                List<Item> fresh = fetchFreshCandidates(dismissed);
                if (!fresh.isEmpty()) {
                    setCandidatesAndPublish(fresh);
                    saveToDiskCache(fresh);
                    downloadBackdrops();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void setCandidatesAndPublish(List<Item> candidates) {
        List<Item> activeList;
        synchronized (items) {
            // Preserve already-decoded bitmaps only for matching IDs or backdrop URLs
            Map<String, Bitmap> existingById = new HashMap<>();
            Map<String, Bitmap> existingByUrl = new HashMap<>();
            for (Item old : items) {
                if (old.bitmap != null) {
                    if (old.id != null) existingById.put(old.id, old.bitmap);
                    if (old.loadedBitmapUrl != null && !old.loadedBitmapUrl.isEmpty()) {
                        existingByUrl.put(old.loadedBitmapUrl, old.bitmap);
                    }
                }
            }
            allCandidates.clear();
            allCandidates.addAll(candidates);

            items.clear();
            for (int i = 0; i < allCandidates.size() && items.size() < MAX_ITEMS; i++) {
                items.add(allCandidates.get(i));
            }
            int size = items.size();
            int center = size > 0 ? ((this.activeIndex % size) + size) % size : 0;
            for (int i = 0; i < size; i++) {
                Item c = items.get(i);
                if (isInSlidingWindow(i, size, center)) {
                    if (c.bitmap == null) {
                        if (c.id != null) c.bitmap = existingById.get(c.id);
                        if (c.bitmap == null && c.backdropUrl != null) c.bitmap = existingByUrl.get(c.backdropUrl);
                    }
                } else {
                    c.bitmap = null;
                    c.loadedBitmapUrl = null;
                }
            }
            activeList = new ArrayList<>(items);
        }
        mainHandler.post(() -> callback.onSpotlightsLoaded(activeList));
    }

    /**
     * Removes the item at the given index, records it as dismissed in SharedPreferences,
     * and fills its slot with the next available attraction from the candidate pool.
     */
    public Item removeItemAt(int index) {
        Item removed;
        List<Item> updatedActive;
        synchronized (items) {
            if (index < 0 || index >= items.size()) return null;
            removed = items.remove(index);
            markDismissed(removed);
            allCandidates.remove(removed);

            Set<String> dismissed = getDismissedSet();
            Set<String> currentIds = new HashSet<>();
            Set<String> currentTitles = new HashSet<>();
            for (Item it : items) {
                currentIds.add(it.id);
                currentTitles.add(norm(it.title));
            }

            // Fill slot from remaining allCandidates
            for (Item candidate : allCandidates) {
                if (items.size() >= MAX_ITEMS) break;
                if (currentIds.contains(candidate.id)) continue;
                if (currentTitles.contains(norm(candidate.title))) continue;
                if (isDismissed(dismissed, candidate.id, candidate.title)) continue;
                items.add(candidate);
                currentIds.add(candidate.id);
                currentTitles.add(norm(candidate.title));
            }

            // If still fewer than MAX_ITEMS, check default items as backup
            if (items.size() < MAX_ITEMS) {
                for (Item def : getDefaultItems()) {
                    if (items.size() >= MAX_ITEMS) break;
                    if (currentIds.contains(def.id)) continue;
                    if (currentTitles.contains(norm(def.title))) continue;
                    if (isDismissed(dismissed, def.id, def.title)) continue;
                    items.add(def);
                    allCandidates.add(def);
                    currentIds.add(def.id);
                    currentTitles.add(norm(def.title));
                }
            }

            updatedActive = new ArrayList<>(items);
        }

        final Item toDelete = removed;
        final List<Item> toSave = new ArrayList<>(allCandidates);
        executor.execute(() -> {
            deleteItemCachedFiles(toDelete);
            saveToDiskCache(toSave);
            downloadBackdrops();
        });

        return removed;
    }

    private void deleteItemCachedFiles(Item item) {
        if (item == null) return;
        item.bitmap = null;
        Set<String> urls = new HashSet<>(item.backdropUrls);
        if (item.backdropUrl != null && !item.backdropUrl.isEmpty()) {
            urls.add(item.backdropUrl);
        }
        for (String u : urls) {
            if (u == null || u.isEmpty()) continue;
            File hdFile = new File(cacheDir, "bg_hd_" + Math.abs(u.hashCode()) + ".jpg");
            if (hdFile.exists()) hdFile.delete();
            File legacyFile = new File(cacheDir, "bg_" + Math.abs((item.id + "_" + u).hashCode()) + ".jpg");
            if (legacyFile.exists()) legacyFile.delete();
        }
    }

    private void pruneUnusedCacheFiles(List<Item> activeItems) {
        if (cacheDir == null || !cacheDir.isDirectory()) return;
        Set<String> keepFiles = new HashSet<>();
        keepFiles.add(CACHE_FILE);
        for (Item item : activeItems) {
            if (item.backdropUrl != null && !item.backdropUrl.isEmpty()) {
                keepFiles.add("bg_hd_" + Math.abs(item.backdropUrl.hashCode()) + ".jpg");
            }
            for (String u : item.backdropUrls) {
                if (u != null && !u.isEmpty()) {
                    keepFiles.add("bg_hd_" + Math.abs(u.hashCode()) + ".jpg");
                }
            }
        }
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!keepFiles.contains(name)) {
                f.delete();
            }
        }
    }

    private Set<String> getDismissedSet() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY_DISMISSED, Collections.emptySet());
        return new HashSet<>(saved != null ? saved : Collections.emptySet());
    }

    private void markDismissed(Item item) {
        if (item == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> set = getDismissedSet();
        if (item.id != null && !item.id.isEmpty()) {
            set.add(item.id);
        }
        String normTitle = norm(item.title);
        if (!normTitle.isEmpty()) {
            set.add("title:" + normTitle);
        }
        prefs.edit().putStringSet(KEY_DISMISSED, set).apply();
    }

    private static boolean isDismissed(Set<String> dismissed, String id, String title) {
        if (dismissed == null || dismissed.isEmpty()) return false;
        if (id != null && dismissed.contains(id)) return true;
        String nt = norm(title);
        return !nt.isEmpty() && dismissed.contains("title:" + nt);
    }

    private static List<Item> filterDismissed(List<Item> input, Set<String> dismissed) {
        List<Item> out = new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();
        for (Item it : input) {
            if (isDismissed(dismissed, it.id, it.title)) continue;
            String nt = norm(it.title);
            if (!nt.isEmpty() && !seenTitles.add(nt)) continue;
            out.add(it);
        }
        return out;
    }

    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("[\\p{InCombiningDiacriticalMarks}]+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    public static String formatRemainingPtBr(int remainingMin) {
        if (remainingMin <= 0) return "";
        if (remainingMin >= 60) {
            int h = remainingMin / 60;
            int m = remainingMin % 60;
            if (m > 0) {
                return "Faltam " + h + " h " + m + " min";
            } else {
                return "Faltam " + h + " h";
            }
        }
        return "Faltam " + remainingMin + " min";
    }

    private static String upgradeTmdbBackdropSize(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        String clean = uri.replace("?v=horizontal_fix", "");
        if (clean.contains("image.tmdb.org/t/p/")) {
            clean = clean.replace("/t/p/w500/", "/t/p/original/")
                    .replace("/t/p/w780/", "/t/p/original/")
                    .replace("/t/p/w1280/", "/t/p/original/");
        }
        return clean;
    }

    private static boolean isLowQualityOrLocalUri(String uri) {
        if (isLocalOrUntrustedUri(uri)) return true;
        return uri.contains("/w500/")
                || uri.contains("/w300/")
                || uri.contains("/w780/")
                || uri.contains("m.media-amazon.com")
                || uri.contains("_ZA")
                || uri.contains("pv-target-images")
                || uri.contains("episodes.metahub.space")
                || uri.contains("media.kitsu.app");
    }

    private static boolean isLocalOrUntrustedUri(String uri) {
        if (uri == null || uri.isEmpty()) return true;
        return uri.contains("192.168.")
                || uri.contains("10.")
                || uri.contains("172.16.")
                || uri.contains("127.0.0.1")
                || uri.contains("localhost")
                || uri.contains(":8096")
                || uri.startsWith("content://org.jellyfin");
    }

    private String fetchTmdbBackdropUrl(String title) {
        if (title == null || title.isEmpty()) return null;
        try {
            String clean = title.replaceAll("\\s*[-–:]\\s*S\\d+.*$", "").trim();
            String cacheKey = norm(clean);
            if (!cacheKey.isEmpty() && tmdbEnrichCache.containsKey(cacheKey)) {
                String cachedBp = tmdbEnrichCache.get(cacheKey)[1];
                if (cachedBp != null && !cachedBp.isEmpty()) return cachedBp;
            }
            String key = apiKey();
            if (key == null || key.isEmpty()) return null;
            String encoded = URLEncoder.encode(clean, "UTF-8");
            String urlStr = "https://api.themoviedb.org/3/search/multi?api_key="
                    + key + "&language=pt-BR&query=" + encoded;
            String json = httpGet(urlStr);
            if (json != null) {
                JSONObject root = new JSONObject(json);
                JSONArray results = root.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    for (int j = 0; j < results.length(); j++) {
                        JSONObject obj = results.getJSONObject(j);
                        String mediaType = obj.optString("media_type", "movie");
                        if ("person".equals(mediaType)) continue;
                        String bp = obj.optString("backdrop_path", "");
                        if (bp != null && !bp.isEmpty() && !"null".equals(bp)) {
                            String fullUrl = "https://image.tmdb.org/t/p/original" + bp;
                            String ov = obj.optString("overview", "").trim();
                            if (!cacheKey.isEmpty()) tmdbEnrichCache.put(cacheKey, new String[]{ov, fullUrl});
                            return fullUrl;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Bitmap loadOrDownloadBackdrop(Item item, String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) return null;
        File localFile = new File(cacheDir, "bg_hd_" + Math.abs(targetUrl.hashCode()) + ".jpg");
        if (localFile.exists() && localFile.length() > 0) {
            Bitmap bmp = decodeBackdropBitmap(localFile.getAbsolutePath(), backdropReqW, backdropReqH);
            if (bmp != null) return bmp;
        }
        try {
            if (targetUrl.startsWith("http")) {
                downloadFile(targetUrl, localFile);
            } else if (targetUrl.startsWith("content://") || targetUrl.startsWith("file://")) {
                copyContentUriWithTimeout(Uri.parse(targetUrl), localFile);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed loading backdrop " + targetUrl + ": " + e.getMessage());
        }
        if (localFile.exists() && localFile.length() > 0) {
            return decodeBackdropBitmap(localFile.getAbsolutePath(), backdropReqW, backdropReqH);
        }
        return null;
    }

    private void ensureBackdropFileOnDisk(String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) return;
        File localFile = new File(cacheDir, "bg_hd_" + Math.abs(targetUrl.hashCode()) + ".jpg");
        if (localFile.exists() && localFile.length() > 0) return;
        try {
            if (targetUrl.startsWith("http")) {
                downloadFile(targetUrl, localFile);
            } else if (targetUrl.startsWith("content://") || targetUrl.startsWith("file://")) {
                copyContentUriWithTimeout(Uri.parse(targetUrl), localFile);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed caching backdrop " + targetUrl + ": " + e.getMessage());
        }
    }

    private void downloadBackdrops() {
        List<Item> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        int size = snapshot.size();
        if (size == 0) return;
        final int cycle = this.backdropCycle;
        final int center = ((this.activeIndex % size) + size) % size;

        // Pass 1: Decode Bitmaps ONLY for the 3-item sliding window (current, next, prev) so RAM stays low,
        // while ensuring the primary backdrop file is cached on disk for all 15 items.
        for (int i = 0; i < size; i++) {
            Item item = snapshot.get(i);
            if (item.backdropUrl == null || item.backdropUrl.isEmpty() || isLowQualityOrLocalUri(item.backdropUrl)) {
                String tmdbUrl = fetchTmdbBackdropUrl(item.title);
                if (tmdbUrl != null) {
                    item.addBackdropCandidate(tmdbUrl);
                }
            }

            String targetUrl = item.getBackdropForCycle(cycle);
            if (targetUrl == null || targetUrl.isEmpty()) continue;

            if (isInSlidingWindow(i, size, center)) {
                if (item.bitmap != null && targetUrl.equals(item.loadedBitmapUrl)) continue;
                Bitmap bmp = loadOrDownloadBackdrop(item, targetUrl);
                if (bmp == null) {
                    String fallbackUrl = fetchTmdbBackdropUrl(item.title);
                    if (fallbackUrl != null && !fallbackUrl.equals(targetUrl)) {
                        item.addBackdropCandidate(fallbackUrl);
                        targetUrl = fallbackUrl;
                        bmp = loadOrDownloadBackdrop(item, targetUrl);
                    }
                }
                if (bmp != null) {
                    item.bitmap = bmp;
                    item.loadedBitmapUrl = targetUrl;
                    item.backdropUrl = targetUrl;
                    final int idx = i;
                    final Bitmap readyBmp = bmp;
                    mainHandler.post(() -> callback.onBackdropLoaded(idx, readyBmp));
                }
            } else {
                // Outside the 3-item window: keep Bitmap out of RAM, only cache file on disk
                item.bitmap = null;
                item.loadedBitmapUrl = null;
                ensureBackdropFileOnDisk(targetUrl);
            }
        }

        // Pass 2: Enrich each active item with multi-source 1080p backdrops (TMDB /images + Fanart.tv + TheTVDB)
        // and pre-download their files to disk cache so subsequent carousel rounds switch covers immediately.
        boolean anyEnriched = false;
        for (int i = 0; i < snapshot.size(); i++) {
            Item item = snapshot.get(i);
            if (item.backdropUrls.size() < 3) {
                int before = item.backdropUrls.size();
                populateMultiSourceBackdrops(item);
                if (item.backdropUrls.size() > before) {
                    anyEnriched = true;
                }
            }
            // Pre-cache all alternate backdrop files on disk (up to MAX_BACKDROPS_PER_ITEM) without decoding all into RAM
            for (int b = 0; b < item.backdropUrls.size(); b++) {
                String altUrl = item.backdropUrls.get(b);
                if (altUrl != null && altUrl.startsWith("http")) {
                    File altFile = new File(cacheDir, "bg_hd_" + Math.abs(altUrl.hashCode()) + ".jpg");
                    if (!altFile.exists() || altFile.length() == 0) {
                        try {
                            downloadFile(altUrl, altFile);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        if (anyEnriched) {
            synchronized (items) {
                saveToDiskCache(new ArrayList<>(allCandidates));
            }
        }
        pruneUnusedCacheFiles(snapshot);
    }

    /**
     * Collects up to MAX_BACKDROPS_PER_ITEM distinct Full HD 16:9 backdrops for an item from:
     * 1. TMDB (/3/{movie|tv}/{id}/images)
     * 2. Fanart.tv (/v3/movies/{tmdbId} or /v3/tv/{tvdbId})
     * 3. TheTVDB v4 (/v4/series/{tvdbId}/artworks?type=3)
     */
    private void populateMultiSourceBackdrops(Item item) {
        if (item == null || item.title == null || item.title.isEmpty()) return;
        String cacheKey = norm(item.title);
        if (!cacheKey.isEmpty() && multiBackdropCache.containsKey(cacheKey)) {
            for (String u : multiBackdropCache.get(cacheKey)) {
                item.addBackdropCandidate(u);
            }
            return;
        }

        try {
            // Ensure we have tmdbId and mediaType
            if (item.tmdbId <= 0 || item.mediaType == null || item.mediaType.isEmpty()) {
                resolveTmdbIdentity(item);
            }
            if (item.tmdbId <= 0) return;

            String tKey = apiKey();
            if (tKey == null || tKey.isEmpty()) return;

            String type = "tv".equals(item.mediaType) ? "tv" : "movie";
            List<String> tmdbList = new ArrayList<>();
            List<String> fanartList = new ArrayList<>();
            List<String> tvdbList = new ArrayList<>();

            // 1. TMDB /images (1080p+ clean textless backdrops only: include_image_language=null)
            try {
                String imgUrl = "https://api.themoviedb.org/3/" + type + "/" + item.tmdbId
                        + "/images?api_key=" + tKey + "&include_image_language=null";
                String json = httpGet(imgUrl);
                if (json != null) {
                    JSONObject root = new JSONObject(json);
                    JSONArray backdrops = root.optJSONArray("backdrops");
                    if (backdrops != null) {
                        for (int i = 0; i < backdrops.length() && tmdbList.size() < 4; i++) {
                            JSONObject b = backdrops.getJSONObject(i);
                            int w = b.optInt("width", 0);
                            String fp = b.optString("file_path", "");
                            if (w >= 1280 && fp != null && !fp.isEmpty() && !"null".equals(fp)) {
                                tmdbList.add("https://image.tmdb.org/t/p/original" + fp);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 2. Fanart.tv + 3. TheTVDB v4
            long tvdbId = 0;
            if ("tv".equals(type)) {
                try {
                    String extJson = httpGet("https://api.themoviedb.org/3/tv/" + item.tmdbId
                            + "/external_ids?api_key=" + apiKey());
                    if (extJson != null) {
                        tvdbId = new JSONObject(extJson).optLong("tvdb_id", 0);
                    }
                } catch (Exception ignored) {}
            }

            String fKey = fanartKey();
            if (fKey != null && !fKey.isEmpty()) {
                try {
                    String fanartUrl = "movie".equals(type)
                            ? ("https://webservice.fanart.tv/v3/movies/" + item.tmdbId + "?api_key=" + fKey)
                            : (tvdbId > 0 ? ("https://webservice.fanart.tv/v3/tv/" + tvdbId + "?api_key=" + fKey) : null);
                    if (fanartUrl != null) {
                        String fJson = httpGet(fanartUrl);
                        if (fJson != null) {
                            JSONObject fRoot = new JSONObject(fJson);
                            String arrKey = "movie".equals(type) ? "moviebackground" : "showbackground";
                            JSONArray bgArr = fRoot.optJSONArray(arrKey);
                            if (bgArr != null) {
                                for (int i = 0; i < bgArr.length() && fanartList.size() < 3; i++) {
                                    String u = bgArr.getJSONObject(i).optString("url", "");
                                    if (!u.isEmpty()) fanartList.add(u);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (tvdbId > 0) {
                try {
                    String token = ensureTvdbToken();
                    if (token != null && !token.isEmpty()) {
                        String tvdbJson = httpGetWithBearer(
                                "https://api4.thetvdb.com/v4/series/" + tvdbId + "/artworks?type=3", token);
                        if (tvdbJson != null) {
                            JSONObject data = new JSONObject(tvdbJson).optJSONObject("data");
                            JSONArray arts = data != null ? data.optJSONArray("artworks") : null;
                            if (arts != null) {
                                for (int i = 0; i < arts.length() && tvdbList.size() < 3; i++) {
                                    String img = arts.getJSONObject(i).optString("image", "");
                                    if (!img.isEmpty()) tvdbList.add(img);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Interleave Fanart.tv, TheTVDB, and TMDB so each carousel round shows a fresh style of cover art
            int max = Math.max(tmdbList.size(), Math.max(fanartList.size(), tvdbList.size()));
            for (int i = 0; i < max; i++) {
                if (i < fanartList.size()) item.addBackdropCandidate(fanartList.get(i));
                if (i < tvdbList.size()) item.addBackdropCandidate(tvdbList.get(i));
                if (i < tmdbList.size()) item.addBackdropCandidate(tmdbList.get(i));
            }

            if (!cacheKey.isEmpty() && !item.backdropUrls.isEmpty()) {
                multiBackdropCache.put(cacheKey, new ArrayList<>(item.backdropUrls));
            }
        } catch (Exception ignored) {}
    }

    private void resolveTmdbIdentity(Item item) {
        if (item.id != null && item.id.startsWith("tmdb_")) {
            // Format: tmdb_movie_12345 or tmdb_tv_12345 or tmdb_12345
            String[] parts = item.id.split("_");
            if (parts.length == 3) {
                item.mediaType = parts[1];
                try { item.tmdbId = Long.parseLong(parts[2]); } catch (Exception ignored) {}
                if (item.tmdbId > 0) return;
            }
        }
        String key = apiKey();
        if (key == null || key.isEmpty()) return;
        try {
            String clean = item.title.replaceAll("\\s*[-–:]\\s*S\\d+.*$", "").trim();
            String encoded = URLEncoder.encode(clean, "UTF-8");
            String json = httpGet("https://api.themoviedb.org/3/search/multi?api_key="
                    + key + "&language=pt-BR&query=" + encoded);
            if (json == null) return;
            JSONArray results = new JSONObject(json).optJSONArray("results");
            if (results == null) return;
            for (int i = 0; i < results.length(); i++) {
                JSONObject obj = results.getJSONObject(i);
                String mt = obj.optString("media_type", "movie");
                if ("person".equals(mt)) continue;
                item.tmdbId = obj.optLong("id", 0);
                item.mediaType = mt;
                String bp = obj.optString("backdrop_path", "");
                if (bp != null && !bp.isEmpty() && !"null".equals(bp)) {
                    item.addBackdropCandidate("https://image.tmdb.org/t/p/original" + bp);
                }
                break;
            }
        } catch (Exception ignored) {}
    }

    private synchronized String ensureTvdbToken() {
        if (tvdbBearerToken != null && !tvdbBearerToken.isEmpty()) return tvdbBearerToken;
        String key = tvdbKey();
        if (key == null || key.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api4.thetvdb.com/v4/login").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            byte[] body = ("{\"apikey\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
            try ( java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JSONObject root = new JSONObject(sb.toString());
                    JSONObject data = root.optJSONObject("data");
                    if (data != null) {
                        tvdbBearerToken = data.optString("token", null);
                        return tvdbBearerToken;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String httpGetWithBearer(String urlStr, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        if (conn.getResponseCode() == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }
        return null;
    }

    private void copyContentUriWithTimeout(Uri uri, File dest) {
        java.util.concurrent.Future<?> future = ioTimeoutExecutor.submit(() -> {
            try {
                copyContentUri(uri, dest);
            } catch (Exception e) {
                Log.w(TAG, "copyContentUri error: " + e.getMessage());
            }
        });
        try {
            future.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            Log.w(TAG, "copyContentUri timed out for " + uri);
        }
    }

    private void copyContentUri(Uri uri, File dest) throws Exception {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) return;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (tmp.exists() && tmp.length() > 0) {
            if (dest.exists()) dest.delete();
            tmp.renameTo(dest);
        }
    }

    private List<Item> fetchFreshCandidates(Set<String> dismissed) {
        List<Item> result = new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();

        // A. Fetch Continue Watching from Android TV Watch Next (Nuvio, Stremio, Prime, etc.)
        List<Item> continueItems = new ArrayList<>();
        if (isWatchNextEnabled(context)) {
            fetchWatchNextPrograms(continueItems, dismissed);
        }

        // B. Fetch Continue Watching & App Channel Programs from Android TV Preview Channels
        List<Item> appChannelItems = new ArrayList<>();
        fetchPreviewChannelPrograms(continueItems, appChannelItems, dismissed);

        // Enrich Continue Watching items (up to 6) with TMDB pt-BR synopsis & 16:9 backdrop if needed
        for (Item cw : continueItems) {
            if (result.size() >= 6) break;
            if (!isWatchNextEnabled(context) || !isServiceEnabled(context, cw.service)) continue;
            if (isRawSlugOrInvalidTitle(cw.title)) continue;
            String nt = norm(cw.title);
            if (nt.isEmpty() || !seenTitles.add(nt)) continue;
            enrichItemWithTmdbPtBr(cw);
            if (cw.backdropUrl == null || cw.backdropUrl.isEmpty()) continue;
            result.add(cw);
        }

        // Add up to 5 app channel items (like Prime Video's channel) enriched with pt-BR if needed
        for (Item appItem : appChannelItems) {
            if (result.size() >= 10) break;
            if (!isServiceEnabled(context, appItem.service)) continue;
            if (isRawSlugOrInvalidTitle(appItem.title)) continue;
            String nt = norm(appItem.title);
            if (nt.isEmpty() || !seenTitles.add(nt)) continue;
            if (appItem.overview == null || appItem.overview.isEmpty() || isLowQualityOrLocalUri(appItem.backdropUrl)) {
                enrichItemWithTmdbPtBr(appItem);
            }
            if (appItem.backdropUrl == null || appItem.backdropUrl.isEmpty()) continue;
            result.add(appItem);
        }

        // Query installed & enabled streaming services once per candidate refresh instead of per item
        List<ProviderSpec> installedServices = queryInstalledStreamingServices();
        String filterSig = currentFilterSignature();

        // C. Fetch Provider-Verified Movies & Series from TMDB in pt-BR (watch_region=BR)
        String currentApiKey = apiKey();
        if (currentApiKey == null || currentApiKey.isEmpty() || installedServices.isEmpty()) {
            cachedTrendingPool.clear();
            lastUsedApiKey = "";
            lastFilterSignature = filterSig;
        } else {
            long now = android.os.SystemClock.elapsedRealtime();
            boolean useTrendingCache = !cachedTrendingPool.isEmpty()
                    && currentApiKey.equals(lastUsedApiKey)
                    && filterSig.equals(lastFilterSignature)
                    && (now - lastTrendingFetchMs) < TRENDING_TTL_MS;

            if (!useTrendingCache) {
                List<Item> fetchedTrending = fetchVerifiedTmdbCatalog(currentApiKey, installedServices);
                cachedTrendingPool.clear();
                if (!fetchedTrending.isEmpty()) {
                    cachedTrendingPool.addAll(fetchedTrending);
                }
                lastTrendingFetchMs = now;
                lastUsedApiKey = currentApiKey;
                lastFilterSignature = filterSig;
            }
        }

        for (Item t : cachedTrendingPool) {
            if (result.size() >= MAX_CANDIDATES) break;
            if (!isServiceEnabled(context, t.service)) continue;
            if (isDismissed(dismissed, t.id, t.title)) continue;
            String nt = norm(t.title);
            if (nt.isEmpty() || !seenTitles.add(nt)) continue;
            result.add(t);
        }

        if (result.isEmpty()) return filterAllowed(getDefaultItems(), dismissed);
        return result;
    }

    private List<Item> fetchVerifiedTmdbCatalog(String currentApiKey, List<ProviderSpec> services) {
        List<List<Item>> buckets = new ArrayList<>();
        Set<String> globalSeen = new HashSet<>();
        String todayIso = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());

        // 1. For each installed subscription streaming service (Netflix, Prime Video, Max, Disney+),
        // query TMDB's regional catalog (watch_region=BR, flatrate) for both Movies and TV Series.
        for (ProviderSpec spec : services) {
            if (spec.tmdbProviderId > 0) {
                List<Item> providerBucket = new ArrayList<>();
                List<Item> movies = fetchTmdbDiscoverList(currentApiKey, "movie", spec, todayIso, 8);
                List<Item> series = fetchTmdbDiscoverList(currentApiKey, "tv", spec, todayIso, 8);
                int max = Math.max(movies.size(), series.size());
                for (int i = 0; i < max; i++) {
                    if (i < movies.size()) providerBucket.add(movies.get(i));
                    if (i < series.size()) providerBucket.add(series.get(i));
                }
                if (!providerBucket.isEmpty()) {
                    buckets.add(providerBucket);
                }
            } else {
                // Aggregator service like Nuvio or Stremio: fetch actually released trending titles
                List<Item> trendingBucket = fetchTmdbTrendingForAggregator(currentApiKey, spec, todayIso, 15);
                if (!trendingBucket.isEmpty()) {
                    buckets.add(trendingBucket);
                }
            }
        }

        // Interleave items across all installed provider buckets so the carousel alternates services & Movies/Series
        List<Item> interleaved = new ArrayList<>();
        boolean addedAny = true;
        int idx = 0;
        while (addedAny && interleaved.size() < MAX_CANDIDATES) {
            addedAny = false;
            for (List<Item> bucket : buckets) {
                if (idx < bucket.size()) {
                    addedAny = true;
                    Item candidate = bucket.get(idx);
                    String nt = norm(candidate.title);
                    if (!nt.isEmpty() && globalSeen.add(nt)) {
                        interleaved.add(candidate);
                        if (interleaved.size() >= MAX_CANDIDATES) break;
                    }
                }
            }
            idx++;
        }
        return interleaved;
    }

    private List<Item> fetchTmdbDiscoverList(String apiKey, String mediaType, ProviderSpec spec, String todayIso, int limit) {
        List<Item> out = new ArrayList<>();
        try {
            String dateFilter = "tv".equals(mediaType)
                    ? "&first_air_date.lte=" + todayIso
                    : "& primary_release_date.lte=".replace(" ", "") + todayIso;
            String urlStr = "https://api.themoviedb.org/3/discover/" + mediaType
                    + "?api_key=" + apiKey
                    + "&language=pt-BR&watch_region=BR"
                    + "&with_watch_providers=" + spec.tmdbProviderId
                    + "&with_watch_monetization_types=flatrate"
                    + "&sort_by=popularity.desc&vote_count.gte=25"
                    + dateFilter + "&page=1";
            String json = httpGet(urlStr);
            if (json == null) return out;
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("results");
            if (arr == null) return out;
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                Item item = parseTmdbJsonItem(arr.getJSONObject(i), mediaType, spec, todayIso);
                if (item != null) out.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB discover error (" + spec.service + " " + mediaType + "): " + e.getMessage());
        }
        return out;
    }

    private List<Item> fetchTmdbTrendingForAggregator(String apiKey, ProviderSpec spec, String todayIso, int limit) {
        List<Item> out = new ArrayList<>();
        try {
            String urlStr = "https://api.themoviedb.org/3/trending/all/week?api_key="
                    + apiKey + "&language=pt-BR&page=1";
            String json = httpGet(urlStr);
            if (json == null) return out;
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("results");
            if (arr == null) return out;
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                JSONObject obj = arr.getJSONObject(i);
                String mediaType = obj.optString("media_type", "movie");
                if ("person".equals(mediaType)) continue;
                if (obj.optInt("vote_count", 0) < 30) continue; // Skip unreleased/unrated announcements
                Item item = parseTmdbJsonItem(obj, mediaType, spec, todayIso);
                if (item != null) out.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB aggregator trending error: " + e.getMessage());
        }
        return out;
    }

    private Item parseTmdbJsonItem(JSONObject obj, String mediaType, ProviderSpec spec, String todayIso) {
        String title = obj.optString("title");
        if (title == null || title.isEmpty()) title = obj.optString("name", "");
        if (title == null || title.isEmpty()) return null;

        String dateStr = obj.optString("release_date");
        if (dateStr == null || dateStr.isEmpty()) dateStr = obj.optString("first_air_date", "");
        if (dateStr != null && dateStr.length() >= 10 && dateStr.compareTo(todayIso) > 0) {
            return null; // Unreleased future title
        }

        String overview = obj.optString("overview", "").trim();
        if (overview.isEmpty()) return null;

        String backdropPath = obj.optString("backdrop_path", "");
        if (backdropPath == null || backdropPath.isEmpty() || "null".equals(backdropPath)) return null;
        String backdropUrl = "https://image.tmdb.org/t/p/original" + backdropPath;

        long tmdbId = obj.optLong("id", 0);
        String id = "tmdb_" + mediaType + "_" + tmdbId;
        String typeStr = "tv".equals(mediaType) ? "Série" : "Filme";
        String year = (dateStr != null && dateStr.length() >= 4) ? dateStr.substring(0, 4) : "";

        double rating = obj.optDouble("vote_average", 0.0);
        String ratingStr = rating > 0 ? String.format(Locale.getDefault(), "⭐ %.1f", rating) : "";
        String meta = year + (year.isEmpty() ? "" : " • ") + typeStr + (ratingStr.isEmpty() ? "" : " • " + ratingStr);

        Item created = new Item(
                id,
                title,
                spec.service,
                badgeColorForService(spec.service),
                meta,
                overview,
                backdropUrl,
                spec.pkg,
                title,
                "",
                false,
                0
        );
        created.tmdbId = tmdbId;
        created.mediaType = mediaType;
        return created;
    }

    private static boolean isRawSlugOrInvalidTitle(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("mdn:") || t.startsWith("kitsu:") || t.contains("://")) return true;
        if (t.matches("^tt\\d+$")) return true;
        // A long string with no spaces and multiple hyphens/colons is an internal addon ID/slug
        return !t.contains(" ") && t.length() > 28 && (t.contains("-") || t.contains(":"));
    }

    private void fetchWatchNextPrograms(List<Item> outContinue, Set<String> dismissed) {
        Cursor c = null;
        try {
            Uri uri = Uri.parse("content://android.media.tv/watch_next_program");
            c = context.getContentResolver().query(uri, null, null, null, "last_engagement_time_utc_millis DESC");
            if (c == null) return;
            PackageManager pm = context.getPackageManager();
            while (c.moveToNext()) {
                long browsable = getCursorLong(c, "browsable", 1L);
                if (browsable == 0L) continue;
                int watchNextType = (int) getCursorLong(c, "watch_next_type", 0L);
                if (watchNextType == 3) continue; // Skip pure watchlist without progress

                String pkg = getCursorStr(c, "package_name");
                if (pkg.isEmpty() || pkg.equals(context.getPackageName()) || isAudioOnlyPackage(pkg) || !isInstalled(pm, pkg)) continue;

                String title = getCursorStr(c, "title");
                String art = getCursorArt(c);
                if (title.isEmpty()) continue;

                long rowId = getCursorLong(c, "_id", 0L);
                String id = "wn|" + pkg + "|" + rowId;
                if (isDismissed(dismissed, id, title)) continue;

                long pos = getCursorLong(c, "last_playback_position_millis", 0L);
                long dur = getCursorLong(c, "duration_millis", 0L);
                if (dur == 100L || dur == 1000L || dur == 10000L || dur == 100000L) dur = 0L;

                int remainingMin = 0;
                if (dur > 0L && pos > 0L && pos <= dur) {
                    float progress = (float) pos / (float) dur;
                    if (progress >= 0.95f) continue; // Already finished
                    if (dur >= 600000L && (dur - pos) < 120000L) continue; // Less than 2 min left
                    if (dur >= 60000L) {
                        remainingMin = (int) Math.max(1L, (dur - pos) / 60000L);
                    }
                }

                String serviceBadge = serviceNameForPackage(pkg);
                if (!isServiceEnabled(context, serviceBadge)) continue;
                String servicePretty = prettyServiceName(pkg);
                String remStr = formatRemainingPtBr(remainingMin);
                String meta = !remStr.isEmpty()
                        ? (servicePretty + " • " + remStr)
                        : (servicePretty + " • Continuar assistindo");

                String overview = getCursorEpisodeOrDesc(c);
                String intentUri = getCursorStr(c, "intent_uri");

                outContinue.add(new Item(
                        id,
                        title,
                        serviceBadge,
                        badgeColorForService(serviceBadge),
                        meta,
                        overview,
                        art,
                        pkg,
                        title,
                        intentUri,
                        true,
                        remainingMin
                ));
            }
        } catch (Exception e) {
            Log.w(TAG, "WatchNext query error: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
    }

    private void fetchPreviewChannelPrograms(List<Item> outContinue, List<Item> outAppChannels, Set<String> dismissed) {
        Cursor chCursor = null;
        Cursor progCursor = null;
        try {
            PackageManager pm = context.getPackageManager();
            Map<Long, String> channelPkgMap = new HashMap<>();
            Set<Long> resumeChannelIds = new HashSet<>();

            Uri channelsUri = Uri.parse("content://android.media.tv/channel");
            chCursor = context.getContentResolver().query(channelsUri, null, null, null, null);
            if (chCursor != null) {
                while (chCursor.moveToNext()) {
                    String type = getCursorStr(chCursor, "type");
                    if (!"TYPE_PREVIEW".equals(type)) continue;
                    String pkg = getCursorStr(chCursor, "package_name");
                    if (pkg.isEmpty() || pkg.equals(context.getPackageName()) || isAudioOnlyPackage(pkg) || !isInstalled(pm, pkg)) continue;
                    long chId = getCursorLong(chCursor, "_id", -1L);
                    if (chId < 0) continue;
                    String displayName = getCursorStr(chCursor, "display_name");
                    if (isResumeChannelName(displayName)) {
                        resumeChannelIds.add(chId);
                        channelPkgMap.put(chId, pkg);
                    } else if (getCursorLong(chCursor, "browsable", 1L) != 0L) {
                        channelPkgMap.put(chId, pkg);
                    }
                }
            }
            if (channelPkgMap.isEmpty()) return;

            Uri progUri = Uri.parse("content://android.media.tv/preview_program");
            progCursor = context.getContentResolver().query(progUri, null, null, null, null);
            if (progCursor == null) return;

            Map<String, Integer> perAppCount = new HashMap<>();
            while (progCursor.moveToNext()) {
                long chId = getCursorLong(progCursor, "channel_id", -1L);
                String pkg = channelPkgMap.get(chId);
                if (pkg == null) continue;
                if (getCursorLong(progCursor, "browsable", 1L) == 0L) continue;

                String title = getCursorStr(progCursor, "title");
                String art = getCursorArt(progCursor);
                if (title.isEmpty() || art.isEmpty()) continue;

                long rowId = getCursorLong(progCursor, "_id", 0L);
                String id = "pp|" + pkg + "|" + rowId;
                if (isDismissed(dismissed, id, title)) continue;

                long pos = getCursorLong(progCursor, "last_playback_position_millis", 0L);
                long dur = getCursorLong(progCursor, "duration_millis", 0L);
                int remainingMin = 0;
                boolean hasProgress = false;
                if (dur >= 60000L && pos > 0L && pos < dur) {
                    float progress = (float) pos / (float) dur;
                    if (progress >= 0.95f) continue;
                    remainingMin = (int) Math.max(1L, (dur - pos) / 60000L);
                    hasProgress = true;
                }

                boolean isResume = resumeChannelIds.contains(chId) || hasProgress;
                if (isResume && !isWatchNextEnabled(context)) continue;
                String serviceBadge = serviceNameForPackage(pkg);
                if (!isServiceEnabled(context, serviceBadge)) continue;
                String servicePretty = prettyServiceName(pkg);
                String overview = getCursorEpisodeOrDesc(progCursor);
                String intentUri = getCursorStr(progCursor, "intent_uri");

                if (isResume) {
                    String remStr = formatRemainingPtBr(remainingMin);
                    String meta = !remStr.isEmpty()
                            ? (servicePretty + " • " + remStr)
                            : (servicePretty + " • Continuar assistindo");
                    outContinue.add(new Item(
                            id,
                            title,
                            serviceBadge,
                            badgeColorForService(serviceBadge),
                            meta,
                            overview,
                            art,
                            pkg,
                            title,
                            intentUri,
                            true,
                            remainingMin
                    ));
                } else {
                    int count = perAppCount.containsKey(pkg) ? perAppCount.get(pkg) : 0;
                    if (count < 3) {
                        perAppCount.put(pkg, count + 1);
                        outAppChannels.add(new Item(
                                id,
                                title,
                                serviceBadge,
                                badgeColorForService(serviceBadge),
                                servicePretty,
                                overview,
                                art,
                                pkg,
                                title,
                                intentUri,
                                false,
                                0
                        ));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Preview channels query error: " + e.getMessage());
        } finally {
            if (chCursor != null) try { chCursor.close(); } catch (Exception ignored) {}
            if (progCursor != null) try { progCursor.close(); } catch (Exception ignored) {}
        }
    }

    private void enrichItemWithTmdbPtBr(Item item) {
        if (item == null || item.title == null || item.title.isEmpty()) return;
        try {
            // Strip episode suffix if present (e.g. "Duna - S1E2")
            String cleanTitle = item.title.replaceAll("\\s*[-–:]\\s*S\\d+.*$", "").trim();
            String cacheKey = norm(cleanTitle);
            if (!cacheKey.isEmpty() && tmdbEnrichCache.containsKey(cacheKey)) {
                String[] cached = tmdbEnrichCache.get(cacheKey);
                if (cached[0] != null && !cached[0].isEmpty()
                        && (item.overview == null || item.overview.isEmpty() || item.overview.length() < 25)) {
                    item.overview = cached[0];
                }
                if (cached[1] != null && !cached[1].isEmpty()
                        && (item.backdropUrl == null || item.backdropUrl.isEmpty() || isLocalOrUntrustedUri(item.backdropUrl))) {
                    item.backdropUrl = cached[1];
                }
                return;
            }

            String key = apiKey();
            if (key == null || key.isEmpty()) return;
            String encoded = URLEncoder.encode(cleanTitle, "UTF-8");
            String urlStr = "https://api.themoviedb.org/3/search/multi?api_key="
                    + key + "&language=pt-BR&query=" + encoded;
            String json = httpGet(urlStr);
            if (json == null) return;

            JSONObject root = new JSONObject(json);
            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) return;

            for (int i = 0; i < results.length(); i++) {
                JSONObject obj = results.getJSONObject(i);
                String mediaType = obj.optString("media_type", "movie");
                if ("person".equals(mediaType)) continue;

                item.tmdbId = obj.optLong("id", 0);
                item.mediaType = mediaType;

                String overview = obj.optString("overview", "").trim();
                if (!overview.isEmpty() && (item.overview == null || item.overview.isEmpty() || item.overview.length() < 25)) {
                    item.overview = overview;
                }

                String tmdbBackdrop = "";
                String backdropPath = obj.optString("backdrop_path", "");
                if (backdropPath != null && !backdropPath.isEmpty() && !"null".equals(backdropPath)) {
                    tmdbBackdrop = "https://image.tmdb.org/t/p/original" + backdropPath;
                    item.addBackdropCandidate(tmdbBackdrop);
                    if (item.backdropUrl == null || item.backdropUrl.isEmpty() || isLowQualityOrLocalUri(item.backdropUrl)) {
                        item.backdropUrl = tmdbBackdrop;
                    }
                }
                if (!cacheKey.isEmpty()) {
                    tmdbEnrichCache.put(cacheKey, new String[]{overview, tmdbBackdrop});
                }
                break;
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isResumeChannelName(String name) {
        if (name == null) return false;
        String s = name.toLowerCase(Locale.ROOT);
        return s.contains("continue watching")
                || (s.contains("continue") && s.contains("watch"))
                || s.startsWith("resume")
                || s.contains("recently watched")
                || s.contains("watch next")
                || s.contains("continuar assistindo")
                || s.contains("continue assistindo")
                || s.startsWith("continuar");
    }

    private static String getCursorStr(Cursor c, String col) {
        try {
            int idx = c.getColumnIndex(col);
            if (idx >= 0 && !c.isNull(idx)) {
                String v = c.getString(idx);
                return v != null ? v.trim() : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static long getCursorLong(Cursor c, String col, long def) {
        try {
            int idx = c.getColumnIndex(col);
            if (idx >= 0 && !c.isNull(idx)) {
                return c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static String getCursorArt(Cursor c) {
        String poster = getCursorStr(c, "poster_art_uri");
        if (!poster.isEmpty()) return poster;
        return getCursorStr(c, "thumbnail_uri");
    }

    private static String getCursorEpisodeOrDesc(Cursor c) {
        String sNum = getCursorStr(c, "season_display_number");
        String eNum = getCursorStr(c, "episode_display_number");
        String eTitle = getCursorStr(c, "episode_title");
        String desc = getCursorStr(c, "short_description");
        if (desc.isEmpty()) desc = getCursorStr(c, "long_description");

        StringBuilder ep = new StringBuilder();
        if (!sNum.isEmpty() && !eNum.isEmpty()) {
            ep.append("T").append(sNum).append(" · E").append(eNum);
        } else if (!eNum.isEmpty()) {
            ep.append("E").append(eNum);
        }
        if (!eTitle.isEmpty()) {
            if (ep.length() > 0) ep.append(" — ");
            ep.append(eTitle);
        }
        if (!desc.isEmpty()) {
            if (ep.length() > 0) ep.append(" • ");
            ep.append(desc);
        }
        return ep.toString();
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        if (conn.getResponseCode() == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }
        return null;
    }

    private static final class ProviderSpec {
        final String service;
        final String pkg;
        final int tmdbProviderId;

        ProviderSpec(String service, String pkg, int tmdbProviderId) {
            this.service = service;
            this.pkg = pkg;
            this.tmdbProviderId = tmdbProviderId;
        }
    }

    /** Represents an installed video/streaming app detected for Spotlight filtering in Settings. */
    public static final class ServiceOption {
        public final String key;
        public final String label;

        public ServiceOption(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public static String normalizeServiceKey(String service) {
        if (service == null) return "";
        String norm = service.trim().toUpperCase(Locale.ROOT);
        if ("MAX".equals(norm)) return "HBO MAX";
        return norm;
    }

    public static boolean isWatchNextEnabled(Context context) {
        if (context == null) return true;
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Config.SPOTLIGHT_SHOW_WATCH_NEXT_KEY, true);
    }

    public static Set<String> getDisabledServices(Context context) {
        Set<String> out = new HashSet<>();
        if (context == null) return out;
        Set<String> raw = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getStringSet(Config.SPOTLIGHT_DISABLED_SERVICES_KEY, null);
        if (raw != null) {
            for (String s : raw) {
                String k = normalizeServiceKey(s);
                if (!k.isEmpty()) out.add(k);
            }
        }
        return out;
    }

    public static boolean isServiceEnabled(Context context, String service) {
        String key = normalizeServiceKey(service);
        if (key.isEmpty()) return true;
        return !getDisabledServices(context).contains(key);
    }

    private String currentFilterSignature() {
        List<String> disabled = new ArrayList<>(getDisabledServices(context));
        Collections.sort(disabled);
        StringBuilder sb = new StringBuilder();
        sb.append(isWatchNextEnabled(context) ? "wn1" : "wn0").append(':');
        for (String d : disabled) sb.append(d).append(',');
        return sb.toString();
    }

    private List<Item> filterAllowed(List<Item> input, Set<String> dismissed) {
        boolean allowWatchNext = isWatchNextEnabled(context);
        Set<String> disabled = getDisabledServices(context);
        List<Item> out = new ArrayList<>();
        for (Item it : input) {
            if (isDismissed(dismissed, it.id, it.title)) continue;
            if (it.isContinueWatching && !allowWatchNext) continue;
            if (disabled.contains(normalizeServiceKey(it.service))) continue;
            out.add(it);
        }
        return out;
    }

    /**
     * Automatically detects installed video/streaming apps on the TVBox (known streaming apps +
     * any installed app publishing Android TV Watch Next or Preview Channels).
     */
    public static List<ServiceOption> detectInstalledVideoServices(Context context) {
        List<ServiceOption> found = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        PackageManager pm = context.getPackageManager();

        String[][] known = new String[][]{
                {"NUVIO", "Nuvio", "com.nuvio.tv"},
                {"STREMIO", "Stremio", "com.stremio.one"},
                {"NETFLIX", "Netflix", "com.netflix.mediaclient", "com.netflix.ninja"},
                {"DISNEY+", "Disney+", "com.disney.disneyplus"},
                {"HBO MAX", "HBO Max", "com.wbd.stream", "com.hbo.hbonow"},
                {"PRIME VIDEO", "Prime Video", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
                {"APPLE TV+", "Apple TV+", "com.apple.atve.androidtv.appletv"},
                {"PARAMOUNT+", "Paramount+", "com.cbs.ott"},
                {"GLOBOPLAY", "Globoplay", "com.globo.globotv"},
                {"CRUNCHYROLL", "Crunchyroll", "com.crunchyroll.crunchyroid"},
                {"JELLYFIN", "Jellyfin", "org.jellyfin.androidtv"},
                {"PLEX", "Plex", "com.plexapp.android"},
                {"KODI", "Kodi", "org.xbmc.kodi"},
                {"YOUTUBE", "YouTube", "com.google.android.youtube.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv"}
        };
        for (String[] entry : known) {
            boolean installed = false;
            for (int i = 2; i < entry.length; i++) {
                if (isInstalled(pm, entry[i])) {
                    installed = true;
                    break;
                }
            }
            if (installed && seenKeys.add(entry[0])) {
                found.add(new ServiceOption(entry[0], entry[1]));
            }
        }

        // Also detect any other installed app publishing Android TV Preview Channels or Watch Next
        String[] uris = new String[]{
                "content://android.media.tv/channel",
                "content://android.media.tv/watch_next_program"
        };
        for (String uriStr : uris) {
            Cursor c = null;
            try {
                c = context.getContentResolver().query(Uri.parse(uriStr), new String[]{"package_name"}, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String pkg = getCursorStr(c, "package_name");
                    if (pkg.isEmpty() || pkg.equals(context.getPackageName()) || isAudioOnlyPackage(pkg) || !isInstalled(pm, pkg)) continue;
                    String key = serviceNameForPackage(pkg);
                    String label = prettyServiceName(pkg);
                    if ("TV".equals(key)) {
                        try {
                            CharSequence appLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                            if (appLabel != null && appLabel.length() > 0) {
                                label = appLabel.toString().trim();
                                key = normalizeServiceKey(label);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (!key.isEmpty() && !"TV".equals(key) && seenKeys.add(key)) {
                        found.add(new ServiceOption(key, label));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { c.close(); } catch (Exception ignored) {}
            }
        }
        return found;
    }

    /**
     * Returns streaming apps installed & enabled on the TVBox with their official TMDB Brazil provider IDs.
     * Note: Jellyfin is a personal media server and is intentionally excluded here so TMDB catalog
     * items are never falsely labeled as available on Jellyfin; Jellyfin items come exclusively
     * from Jellyfin's own Android TV Watch Next / Preview Channels.
     */
    private List<ProviderSpec> queryInstalledStreamingServices() {
        PackageManager pm = context.getPackageManager();
        List<ProviderSpec> available = new ArrayList<>();
        if (isServiceEnabled(context, "NETFLIX")) {
            if (isInstalled(pm, "com.netflix.mediaclient")) available.add(new ProviderSpec("NETFLIX", "com.netflix.mediaclient", 8));
            else if (isInstalled(pm, "com.netflix.ninja")) available.add(new ProviderSpec("NETFLIX", "com.netflix.ninja", 8));
        }
        if (isServiceEnabled(context, "PRIME VIDEO")) {
            if (isInstalled(pm, "com.amazon.amazonvideo.livingroom")) available.add(new ProviderSpec("PRIME VIDEO", "com.amazon.amazonvideo.livingroom", 119));
            else if (isInstalled(pm, "com.amazon.avod.thirdpartyclient")) available.add(new ProviderSpec("PRIME VIDEO", "com.amazon.avod.thirdpartyclient", 119));
        }
        if (isServiceEnabled(context, "HBO MAX")) {
            if (isInstalled(pm, "com.wbd.stream")) available.add(new ProviderSpec("HBO MAX", "com.wbd.stream", 1899));
            else if (isInstalled(pm, "com.hbo.hbonow")) available.add(new ProviderSpec("HBO MAX", "com.hbo.hbonow", 1899));
        }
        if (isServiceEnabled(context, "DISNEY+") && isInstalled(pm, "com.disney.disneyplus")) {
            available.add(new ProviderSpec("DISNEY+", "com.disney.disneyplus", 337));
        }
        if (isServiceEnabled(context, "APPLE TV+") && isInstalled(pm, "com.apple.atve.androidtv.appletv")) {
            available.add(new ProviderSpec("APPLE TV+", "com.apple.atve.androidtv.appletv", 350));
        }
        if (isServiceEnabled(context, "PARAMOUNT+") && isInstalled(pm, "com.cbs.ott")) {
            available.add(new ProviderSpec("PARAMOUNT+", "com.cbs.ott", 531));
        }
        if (isServiceEnabled(context, "GLOBOPLAY") && isInstalled(pm, "com.globo.globotv")) {
            available.add(new ProviderSpec("GLOBOPLAY", "com.globo.globotv", 307));
        }
        if (isServiceEnabled(context, "CRUNCHYROLL") && isInstalled(pm, "com.crunchyroll.crunchyroid")) {
            available.add(new ProviderSpec("CRUNCHYROLL", "com.crunchyroll.crunchyroid", 283));
        }
        if (isServiceEnabled(context, "NUVIO") && isInstalled(pm, "com.nuvio.tv")) {
            available.add(new ProviderSpec("NUVIO", "com.nuvio.tv", 0));
        }
        if (isServiceEnabled(context, "STREMIO") && isInstalled(pm, "com.stremio.one")) {
            available.add(new ProviderSpec("STREMIO", "com.stremio.one", 0));
        }
        return available;
    }

    private static boolean isAudioOnlyPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("spotify")
                || p.contains("music")
                || p.contains("deezer")
                || p.contains("tidal")
                || p.contains("pandora")
                || p.contains("soundcloud")
                || p.contains("tunein")
                || p.contains("audible")
                || p.contains("iheart");
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String serviceNameForPackage(String pkg) {
        if (pkg == null) return "TV";
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("netflix")) return "NETFLIX";
        if (p.contains("amazon") || p.contains("avod")) return "PRIME VIDEO";
        if (p.contains("nuvio")) return "NUVIO";
        if (p.contains("stremio")) return "STREMIO";
        if (p.contains("jellyfin")) return "JELLYFIN";
        if (p.contains("disney")) return "DISNEY+";
        if (p.contains("wbd") || p.contains("max") || p.contains("hbo")) return "HBO MAX";
        if (p.contains("appletv") || p.contains("atve")) return "APPLE TV+";
        if (p.contains("cbs.ott") || p.contains("paramount")) return "PARAMOUNT+";
        if (p.contains("globo")) return "GLOBOPLAY";
        if (p.contains("crunchyroll")) return "CRUNCHYROLL";
        if (p.contains("plex")) return "PLEX";
        if (p.contains("kodi")) return "KODI";
        if (p.contains("youtube") || p.contains("smarttube")) return "YOUTUBE";
        return "TV";
    }

    private static String prettyServiceName(String pkg) {
        if (pkg == null) return "TV";
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("netflix")) return "Netflix";
        if (p.contains("amazon") || p.contains("avod")) return "Prime Video";
        if (p.contains("nuvio")) return "Nuvio";
        if (p.contains("stremio")) return "Stremio";
        if (p.contains("jellyfin")) return "Jellyfin";
        if (p.contains("disney")) return "Disney+";
        if (p.contains("wbd") || p.contains("max") || p.contains("hbo")) return "HBO Max";
        if (p.contains("appletv") || p.contains("atve")) return "Apple TV+";
        if (p.contains("cbs.ott") || p.contains("paramount")) return "Paramount+";
        if (p.contains("globo")) return "Globoplay";
        if (p.contains("crunchyroll")) return "Crunchyroll";
        if (p.contains("plex")) return "Plex";
        if (p.contains("kodi")) return "Kodi";
        if (p.contains("youtube") || p.contains("smarttube")) return "YouTube";
        return "TV";
    }

    private static int badgeColorForService(String service) {
        String s = normalizeServiceKey(service);
        if ("NETFLIX".equals(s)) return 0xFFE50914;
        if ("PRIME VIDEO".equals(s)) return 0xFF0082DC;
        if ("NUVIO".equals(s)) return 0xFF6E28E6;
        if ("STREMIO".equals(s)) return 0xFF5E35B1;
        if ("DISNEY+".equals(s)) return 0xFF113CCF;
        if ("HBO MAX".equals(s)) return 0xFF002BE7;
        if ("APPLE TV+".equals(s)) return 0xFF2C2C2E;
        if ("PARAMOUNT+".equals(s)) return 0xFF0064FF;
        if ("GLOBOPLAY".equals(s)) return 0xFFFB0234;
        if ("CRUNCHYROLL".equals(s)) return 0xFFF47521;
        if ("PLEX".equals(s)) return 0xFFE5A00D;
        if ("YOUTUBE".equals(s)) return 0xFFFF0000;
        return 0xFF37474F;
    }

    private static String packageForService(String service) {
        String s = normalizeServiceKey(service);
        if ("NETFLIX".equals(s)) return "com.netflix.mediaclient";
        if ("PRIME VIDEO".equals(s)) return "com.amazon.amazonvideo.livingroom";
        if ("NUVIO".equals(s)) return "com.nuvio.tv";
        if ("STREMIO".equals(s)) return "com.stremio.one";
        if ("DISNEY+".equals(s)) return "com.disney.disneyplus";
        if ("HBO MAX".equals(s)) return "com.wbd.stream";
        if ("APPLE TV+".equals(s)) return "com.apple.atve.androidtv.appletv";
        if ("PARAMOUNT+".equals(s)) return "com.cbs.ott";
        if ("GLOBOPLAY".equals(s)) return "com.globo.globotv";
        if ("CRUNCHYROLL".equals(s)) return "com.crunchyroll.crunchyroid";
        if ("JELLYFIN".equals(s)) return "org.jellyfin.androidtv";
        if ("PLEX".equals(s)) return "com.plexapp.android";
        return null;
    }

    private List<Item> getDefaultItems() {
        List<Item> defaults = new ArrayList<>();
        defaults.add(new Item("def_1", "Duna", "NUVIO", 0xFF6E28E6, "Nuvio • Faltam 2 h 29 min",
                "Paul Atreides é um jovem brilhante, dono de um destino além de sua compreensão. Ele deve viajar para o planeta mais perigoso do universo para garantir o futuro de seu povo.",
                "https://image.tmdb.org/t/p/w1280/jYEW5xZkZk2WTrdbMGAPFuBqbDc.jpg", "com.nuvio.tv", "Duna", "", true, 149));
        defaults.add(new Item("def_2", "Black Doves", "NETFLIX", 0xFFE50914, "2024 • Série • ⭐ 7,2",
                "Quando uma espiã infiltrada descobre que seu amante foi assassinado, ela conta com a ajuda de um velho amigo assassino para encontrar a verdade e buscar vingança.",
                "https://image.tmdb.org/t/p/w1280/qu9epTZh29fGUkY7ZYc9PVsv0Ig.jpg", "com.netflix.mediaclient", "Black Doves", "", false, 0));
        defaults.add(new Item("def_3", "Fallout", "PRIME VIDEO", 0xFF0082DC, "2024 • Série • ⭐ 8,4",
                "Duzentos anos após o apocalipse, os gentis habitantes de abrigos nucleares de luxo são forçados a retornar à paisagem infernal irradiada que seus ancestrais deixaram para trás.",
                "https://image.tmdb.org/t/p/w1280/coaPCIqQBPUZsOnJcWZxhaORcDT.jpg", "com.amazon.amazonvideo.livingroom", "Fallout", "", false, 0));
        defaults.add(new Item("def_4", "Coração Selvagem", "NETFLIX", 0xFFE50914, "2024 • Filme • ⭐ 7,3",
                "Em meio a um território implacável, um ex-combatente precisa enfrentar seu próprio passado para proteger uma família inocente de um cartel perigoso.",
                "https://image.tmdb.org/t/p/w1280/6uVRPFflyJpo8Cx872olmlGTWla.jpg", "com.netflix.mediaclient", "Coração Selvagem", "", false, 0));
        defaults.add(new Item("def_5", "O Senhor dos Anéis: Os Anéis de Poder", "PRIME VIDEO", 0xFF0082DC, "2022 • Série • ⭐ 7,3",
                "Tendo início em uma época de relativa paz, a série acompanha um grupo de personagens que enfrenta o ressurgimento do mal na Terra-média.",
                "https://image.tmdb.org/t/p/w1280/uVOcz7JBOuZxsx9IYpx6Ah174LX.jpg", "com.amazon.amazonvideo.livingroom", "O Senhor dos Anéis: Os Anéis de Poder", "", false, 0));
        defaults.add(new Item("def_6", "Godzilla Minus One", "NETFLIX", 0xFFE50914, "2023 • Filme • ⭐ 8,0",
                "No Japão pós-guerra devastado, surge um novo terror gigantesco que força a população sobrevivente a se unir em uma luta desesperada pela própria existência.",
                "https://image.tmdb.org/t/p/w1280/oqrYIDH4tvfEzPe1yqT4jzmAWHQ.jpg", "com.netflix.mediaclient", "Godzilla Minus One", "", false, 0));
        defaults.add(new Item("def_7", "A Casa do Dragão", "HBO MAX", 0xFF002BE7, "2022 • Série • ⭐ 8,4",
                "A história da Casa Targaryen, 200 anos antes dos eventos de Game of Thrones, marcada pela guerra civil conhecida como a Dança dos Dragões.",
                "https://image.tmdb.org/t/p/w1280/etj8E2o0Bud0HkONVQPjyCkIvpv.jpg", "com.wbd.stream", "A Casa do Dragão", "", false, 0));
        defaults.add(new Item("def_8", "Resident Evil", "NETFLIX", 0xFFE50914, "2022 • Série • ⭐ 7,5",
                "Décadas após a descoberta do T-vírus, uma nova epidemia global revela os segredos mais sombrios da corporação Umbrella e de seus fundadores.",
                "https://image.tmdb.org/t/p/w1280/3icyRAqgakNcQn6aDVz9libFmBA.jpg", "com.netflix.mediaclient", "Resident Evil", "", false, 0));
        defaults.add(new Item("def_9", "The Boys", "PRIME VIDEO", 0xFF0082DC, "2019 • Série • ⭐ 8,5",
                "Um grupo de vigilantes se propõe a derrubar super-heróis corruptos que abusam de seus superpoderes em vez de usá-los para o bem.",
                "https://image.tmdb.org/t/p/w1280/7cqKGQMnNabzOpi7qaIgZvQ7NGV.jpg", "com.amazon.amazonvideo.livingroom", "The Boys", "", false, 0));
        defaults.add(new Item("def_10", "Pinguim", "HBO MAX", 0xFF002BE7, "2024 • Série • ⭐ 8,5",
                "Após os eventos em Gotham, Oswald Cobblepot inicia sua ascensão implacável pelo submundo do crime da cidade.",
                "https://image.tmdb.org/t/p/w1280/9faGSFi5jam6pDWGNd0p8JcJgXQ.jpg", "com.wbd.stream", "Pinguim", "", false, 0));
        defaults.add(new Item("def_11", "Oppenheimer", "PRIME VIDEO", 0xFF0082DC, "2023 • Filme • ⭐ 8,1",
                "A história do físico americano J. Robert Oppenheimer, seu papel no Projeto Manhattan e no desenvolvimento da bomba atômica durante a Segunda Guerra Mundial.",
                "https://image.tmdb.org/t/p/w1280/fm6KqXpk3M2HVveHwCrBSSBaO0V.jpg", "com.amazon.amazonvideo.livingroom", "Oppenheimer", "", false, 0));
        defaults.add(new Item("def_12", "Ruptura", "NUVIO", 0xFF6E28E6, "2022 • Série • ⭐ 8,4",
                "Mark lidera uma equipe de funcionários de escritório cujas memórias foram cirurgicamente divididas entre a vida no trabalho e a vida pessoal.",
                "https://image.tmdb.org/t/p/w1280/ixgFmf1X59PUZam2qbAfskx2gQr.jpg", "com.nuvio.tv", "Ruptura", "", false, 0));
        defaults.add(new Item("def_13", "The Last of Us", "HBO MAX", 0xFF002BE7, "2023 • Série • ⭐ 8,6",
                "Vinte anos após a destruição da civilização moderna, Joel é contratado para tirar Ellie de uma zona de quarentena opressiva.",
                "https://image.tmdb.org/t/p/w1280/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg", "com.wbd.stream", "The Last of Us", "", false, 0));
        defaults.add(new Item("def_14", "O Problema dos 3 Corpos", "NETFLIX", 0xFFE50914, "2024 • Série • ⭐ 7,5",
                "Uma decisão tomada na China nos anos 1960 ecoa através do espaço e do tempo até um grupo de cientistas brilhantes no presente.",
                "https://image.tmdb.org/t/p/w1280/ciizJ9Okzt9tBBGK7Q3T14LFT2j.jpg", "com.netflix.mediaclient", "O Problema dos 3 Corpos", "", false, 0));
        defaults.add(new Item("def_15", "Xógum: A Gloriosa Saga do Japão", "DISNEY+", 0xFF113CCF, "2024 • Série • ⭐ 8,5",
                "No Japão do ano 1600, o Senhor Yoshii Toranaga luta pela própria vida enquanto seus inimigos no Conselho de Regentes se unem contra ele.",
                "https://image.tmdb.org/t/p/w1280/5zmiBoMzeeVdQ62no55JOJMY498.jpg", "com.disney.disneyplus", "Xógum", "", false, 0));
        return defaults;
    }

    private void saveToDiskCache(List<Item> saveItems) {
        try {
            JSONArray arr = new JSONArray();
            for (Item item : saveItems) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.id);
                obj.put("title", item.title);
                obj.put("service", item.service);
                obj.put("badgeColor", item.badgeColor);
                obj.put("meta", item.meta);
                obj.put("overview", item.overview);
                obj.put("backdropUrl", item.backdropUrl);
                obj.put("tmdbId", item.tmdbId);
                obj.put("mediaType", item.mediaType != null ? item.mediaType : "");
                if (!item.backdropUrls.isEmpty()) {
                    JSONArray bArr = new JSONArray();
                    for (String u : item.backdropUrls) bArr.put(u);
                    obj.put("backdropUrls", bArr);
                }
                obj.put("targetPackage", item.targetPackage);
                obj.put("query", item.query);
                obj.put("intentUri", item.intentUri);
                obj.put("isContinueWatching", item.isContinueWatching);
                obj.put("remainingMin", item.remainingMin);
                arr.put(obj);
            }
            File f = new File(cacheDir, CACHE_FILE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private List<Item> loadFromDiskCache() {
        List<Item> list = new ArrayList<>();
        File f = new File(cacheDir, CACHE_FILE);
        if (!f.exists() || f.length() == 0) return list;
        try (InputStream is = new java.io.FileInputStream(f)) {
            byte[] bytes = new byte[(int) f.length()];
            int read = is.read(bytes);
            if (read <= 0) return list;
            JSONArray arr = new JSONArray(new String(bytes, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String cachedService = normalizeServiceKey(obj.optString("service"));
                Item item = new Item(
                        obj.optString("id"),
                        obj.optString("title"),
                        cachedService,
                        obj.optInt("badgeColor"),
                        obj.optString("meta"),
                        obj.optString("overview", ""),
                        obj.optString("backdropUrl"),
                        obj.optString("targetPackage"),
                        obj.optString("query"),
                        obj.optString("intentUri", ""),
                        obj.optBoolean("isContinueWatching"),
                        obj.optInt("remainingMin", 0)
                );
                item.tmdbId = obj.optLong("tmdbId", 0);
                item.mediaType = obj.optString("mediaType", "");
                JSONArray bArr = obj.optJSONArray("backdropUrls");
                if (bArr != null) {
                    for (int b = 0; b < bArr.length(); b++) {
                        item.addBackdropCandidate(bArr.optString(b, ""));
                    }
                }
                list.add(item);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static void downloadFile(String urlStr, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        if (conn.getResponseCode() == 200) {
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (tmp.exists() && tmp.length() > 0) {
                if (dest.exists()) dest.delete();
                tmp.renameTo(dest);
            }
        }
    }

    public static Bitmap decodeBackdropBitmap(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = calculateInSampleSize(opts, reqW, reqH);
        opts.inSampleSize = sample;
        int sampledW = opts.outWidth / Math.max(1, sample);
        int sampledH = opts.outHeight / Math.max(1, sample);
        // Scale down directly during native decode when the image exceeds the display size (e.g. 1080p on 720p TV)
        if (sampledW > reqW && sampledH > reqH && reqW > 0 && reqH > 0) {
            opts.inScaled = true;
            opts.inDensity = sampledW;
            opts.inTargetDensity = reqW;
        }
        opts.inJustDecodeBounds = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            opts.inPreferredConfig = Bitmap.Config.HARDWARE;
        } else {
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        }
        return BitmapFactory.decodeFile(path, opts);
    }

    public static Bitmap decodeSampledBitmap(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
        opts.inJustDecodeBounds = false;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, opts);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        // Downsample any 2K/4K (2560x1440 or 3840x2160) source image so it fits within 1080p memory budget
        while ((width / inSampleSize) > (reqWidth * 1.25f) || (height / inSampleSize) > (reqHeight * 1.25f)) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    public void launchItem(Item item) {
        if (item == null) return;
        PackageManager pm = context.getPackageManager();

        // 1. If we have a direct deep-link intentUri from Watch Next / Preview Channel, launch it directly to resume!
        if (item.intentUri != null && !item.intentUri.isEmpty()) {
            try {
                Intent deep;
                if (item.intentUri.startsWith("intent:")) {
                    deep = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                } else {
                    deep = new Intent(Intent.ACTION_VIEW, Uri.parse(item.intentUri));
                }
                deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (deep.getPackage() == null && deep.getComponent() == null
                        && item.targetPackage != null && !item.targetPackage.isEmpty()) {
                    deep.setPackage(item.targetPackage);
                }
                try {
                    context.startActivity(deep);
                    return;
                } catch (Exception e1) {
                    deep.setPackage(null);
                    context.startActivity(deep);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Try launching Leanback or standard launch intent for targetPackage
        if (item.targetPackage != null && !item.targetPackage.isEmpty()) {
            try {
                Intent intent = pm.getLeanbackLaunchIntentForPackage(item.targetPackage);
                if (intent == null) intent = pm.getLaunchIntentForPackage(item.targetPackage);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Fallback search
        try {
            Intent searchIntent = new Intent(Intent.ACTION_SEARCH);
            searchIntent.putExtra("query", item.title);
            searchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(searchIntent);
        } catch (Exception e) {
            Log.w(TAG, "Cannot launch search: " + e.getMessage());
        }
    }
}
