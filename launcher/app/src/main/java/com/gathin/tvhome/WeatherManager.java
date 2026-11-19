package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight weather fetcher powered by Open-Meteo (free, no API key required).
 * Resolves either a user-selected city (with exact coordinates) or approximate location by IP,
 * caches the result for 30 minutes, and formats a concise line for the Home clock header.
 */
final class WeatherManager {
    private static final String CACHE_PREFS = "weather_cache";
    private static final String KEY_CITY_QUERY = "city_query";
    private static final String KEY_CITY_LABEL = "city_label";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_TEMP_C = "temp_c";
    private static final String KEY_WMO_CODE = "wmo_code";
    private static final String KEY_IS_DAY = "is_day";
    private static final String KEY_FETCH_MS = "fetch_ms";
    private static final long REFRESH_TTL_MS = 30 * 60_000L;

    static final class CityOption {
        final String name;
        final String admin1;
        final String country;
        final double lat;
        final double lon;

        CityOption(String name, String admin1, String country, double lat, double lon) {
            this.name = name == null ? "" : name.trim();
            this.admin1 = admin1 == null ? "" : admin1.trim();
            this.country = country == null ? "" : country.trim();
            this.lat = lat;
            this.lon = lon;
        }

        /** Full disambiguated label for the search picker, e.g. "Palestina — São Paulo, Brasil". */
        String pickerLabel() {
            StringBuilder sb = new StringBuilder(name);
            if (!admin1.isEmpty() && !admin1.equalsIgnoreCase(name)) {
                sb.append(" \u2014 ").append(admin1);
                if (!country.isEmpty()) sb.append(", ").append(country);
            } else if (!country.isEmpty()) {
                sb.append(" \u2014 ").append(country);
            }
            return sb.toString();
        }

        /** Compact label saved and shown on Home, e.g. "Palestina, São Paulo". */
        String savedLabel() {
            if (!admin1.isEmpty() && !admin1.equalsIgnoreCase(name)) {
                return name + ", " + admin1;
            }
            if (!country.isEmpty()) {
                return name + ", " + country;
            }
            return name;
        }
    }

    interface Callback {
        void onWeather(String formatted);
    }

    interface SearchCallback {
        void onResults(List<CityOption> results);
    }

    private final Context appCtx;
    private final SharedPreferences cache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weather-fetch");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    WeatherManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.cache = appCtx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
    }

    void close() {
        closed = true;
        executor.shutdownNow();
    }

    /** Returns the cached line immediately if it matches {@code cityQuery}, or null if none yet. */
    String cachedLine(String cityQuery) {
        String q = normalizeQuery(cityQuery);
        if (!q.equals(cache.getString(KEY_CITY_QUERY, null))) return null;
        if (!cache.contains(KEY_TEMP_C)) return null;
        int temp = Math.round(cache.getFloat(KEY_TEMP_C, 0f));
        int code = cache.getInt(KEY_WMO_CODE, 0);
        boolean isDay = cache.getBoolean(KEY_IS_DAY, true);
        String city = cache.getString(KEY_CITY_LABEL, "");
        return formatLine(temp, code, isDay, city, isPortuguese());
    }

    /** Searches up to 8 matching cities via Open-Meteo Geocoding on a background thread. */
    static void searchCities(Context ctx, String query, SearchCallback callback) {
        final String clean = query == null ? "" : query.trim();
        final Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
        final String lang = (loc != null && "pt".equalsIgnoreCase(loc.getLanguage())) ? "pt" : "en";
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<CityOption> list = new ArrayList<>();
            if (!clean.isEmpty()) {
                try {
                    String encoded = URLEncoder.encode(clean, "UTF-8");
                    String body = httpGet("https://geocoding-api.open-meteo.com/v1/search?name="
                            + encoded + "&count=8&language=" + lang + "&format=json");
                    if (body != null) {
                        JSONArray results = new JSONObject(body).optJSONArray("results");
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject o = results.getJSONObject(i);
                                list.add(new CityOption(
                                        o.optString("name", ""),
                                        o.optString("admin1", ""),
                                        o.optString("country", ""),
                                        o.getDouble("latitude"),
                                        o.getDouble("longitude")));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            main.post(() -> callback.onResults(list));
        }, "weather-city-search").start();
    }

    /** Delivers cached weather immediately (if matching) and refreshes in background when stale or city changed. */
    void refresh(String cityQuery, Callback callback) {
        if (closed) return;
        SharedPreferences settings = appCtx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        final boolean hasPinnedCoords = settings.contains(Config.WEATHER_LAT_KEY) && settings.contains(Config.WEATHER_LON_KEY);
        final double pinnedLat = hasPinnedCoords ? Double.longBitsToDouble(settings.getLong(Config.WEATHER_LAT_KEY, 0L)) : Double.NaN;
        final double pinnedLon = hasPinnedCoords ? Double.longBitsToDouble(settings.getLong(Config.WEATHER_LON_KEY, 0L)) : Double.NaN;
        String q = normalizeQuery(cityQuery);
        final String cacheKey = hasPinnedCoords ? (q + "@" + String.format(Locale.US, "%.4f,%.4f", pinnedLat, pinnedLon)) : q;

        String cached = cachedLine(cacheKey);
        if (cached != null) callback.onWeather(cached);

        long lastMs = cache.getLong(KEY_FETCH_MS, 0L);
        boolean sameQuery = cacheKey.equals(cache.getString(KEY_CITY_QUERY, null));
        if (sameQuery && cached != null && (System.currentTimeMillis() - lastMs) < REFRESH_TTL_MS) {
            return;
        }

        executor.execute(() -> {
            if (closed) return;
            try {
                double lat = pinnedLat;
                double lon = pinnedLon;
                String cityLabel = hasPinnedCoords ? q : "";

                if (Double.isNaN(lat) || Double.isNaN(lon)) {
                    if (sameQuery && cache.contains(KEY_LAT) && cache.contains(KEY_LON)) {
                        lat = Double.longBitsToDouble(cache.getLong(KEY_LAT, 0L));
                        lon = Double.longBitsToDouble(cache.getLong(KEY_LON, 0L));
                        cityLabel = cache.getString(KEY_CITY_LABEL, "");
                    }
                }

                if (Double.isNaN(lat) || Double.isNaN(lon)) {
                    Location loc = q.isEmpty() ? resolveByIp() : resolveByCity(q, isPortuguese() ? "pt" : "en");
                    if (loc == null) return;
                    lat = loc.lat;
                    lon = loc.lon;
                    cityLabel = loc.city;
                }

                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto",
                        lat, lon);
                String body = httpGet(url);
                if (body == null) return;

                JSONObject current = new JSONObject(body).optJSONObject("current");
                if (current == null || !current.has("temperature_2m")) return;

                float tempC = (float) current.optDouble("temperature_2m", 0.0);
                int wmo = current.optInt("weather_code", 0);
                boolean isDay = current.optInt("is_day", 1) == 1;

                cache.edit()
                        .putString(KEY_CITY_QUERY, cacheKey)
                        .putString(KEY_CITY_LABEL, cityLabel)
                        .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                        .putLong(KEY_LON, Double.doubleToRawLongBits(lon))
                        .putFloat(KEY_TEMP_C, tempC)
                        .putInt(KEY_WMO_CODE, wmo)
                        .putBoolean(KEY_IS_DAY, isDay)
                        .putLong(KEY_FETCH_MS, System.currentTimeMillis())
                        .apply();

                String line = formatLine(Math.round(tempC), wmo, isDay, cityLabel, isPortuguese());
                if (!closed) {
                    mainHandler.post(() -> {
                        if (!closed) callback.onWeather(line);
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }

    private boolean isPortuguese() {
        Locale loc = appCtx.getResources().getConfiguration().getLocales().get(0);
        return loc != null && "pt".equalsIgnoreCase(loc.getLanguage());
    }

    private static String normalizeQuery(String cityQuery) {
        return cityQuery == null ? "" : cityQuery.trim();
    }

    private static final class Location {
        final double lat, lon;
        final String city;
        Location(double lat, double lon, String city) {
            this.lat = lat;
            this.lon = lon;
            this.city = city == null ? "" : city.trim();
        }
    }

    private static Location resolveByCity(String city, String lang) {
        try {
            String encoded = URLEncoder.encode(city, "UTF-8");
            String body = httpGet("https://geocoding-api.open-meteo.com/v1/search?name="
                    + encoded + "&count=1&language=" + lang + "&format=json");
            if (body == null) return null;
            JSONArray results = new JSONObject(body).optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            JSONObject first = results.getJSONObject(0);
            return new Location(
                    first.getDouble("latitude"),
                    first.getDouble("longitude"),
                    first.optString("name", city));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Location resolveByIp() {
        try {
            String body = httpGet("https://ipwho.is/");
            if (body == null) return null;
            JSONObject root = new JSONObject(body);
            if (!root.optBoolean("success", false)) return null;
            return new Location(
                    root.getDouble("latitude"),
                    root.getDouble("longitude"),
                    root.optString("city", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "PureBoxTV/3.5");
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Formats a compact weather summary, e.g. "⛅ 23°C · Parcialmente nublado · São Paulo". */
    static String formatLine(int tempC, int wmoCode, boolean isDay, String city, boolean pt) {
        String icon = weatherIcon(wmoCode, isDay);
        String desc = weatherDescription(wmoCode, pt);
        StringBuilder sb = new StringBuilder();
        sb.append(icon).append(' ').append(tempC).append("°C · ").append(desc);
        if (city != null && !city.trim().isEmpty()) {
            sb.append(" · ").append(city.trim());
        }
        return sb.toString();
    }

    static String weatherIcon(int wmoCode, boolean isDay) {
        if (wmoCode == 0) return isDay ? "\u2600\uFE0F" : "\uD83C\uDF19";
        if (wmoCode <= 2) return isDay ? "\u26C5" : "\u2601\uFE0F";
        if (wmoCode == 3) return "\u2601\uFE0F";
        if (wmoCode == 45 || wmoCode == 48) return "\uD83C\uDF2B\uFE0F";
        if (wmoCode >= 51 && wmoCode <= 57) return "\uD83C\uDF26\uFE0F";
        if ((wmoCode >= 61 && wmoCode <= 67) || (wmoCode >= 80 && wmoCode <= 82)) return "\uD83C\uDF27\uFE0F";
        if ((wmoCode >= 71 && wmoCode <= 77) || (wmoCode >= 85 && wmoCode <= 86)) return "\u2744\uFE0F";
        if (wmoCode >= 95) return "\u26C8\uFE0F";
        return "\u2601\uFE0F";
    }

    static String weatherDescription(int wmoCode, boolean pt) {
        if (wmoCode == 0) return pt ? "Céu limpo" : "Clear";
        if (wmoCode <= 2) return pt ? "Parcialmente nublado" : "Partly cloudy";
        if (wmoCode == 3) return pt ? "Nublado" : "Overcast";
        if (wmoCode == 45 || wmoCode == 48) return pt ? "Neblina" : "Fog";
        if (wmoCode >= 51 && wmoCode <= 57) return pt ? "Garoa" : "Drizzle";
        if ((wmoCode >= 61 && wmoCode <= 67) || (wmoCode >= 80 && wmoCode <= 82)) return pt ? "Chuva" : "Rain";
        if ((wmoCode >= 71 && wmoCode <= 77) || (wmoCode >= 85 && wmoCode <= 86)) return pt ? "Neve" : "Snow";
        if (wmoCode >= 95) return pt ? "Tempestade" : "Thunderstorm";
        return pt ? "Nublado" : "Cloudy";
    }
}
