package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private static final int[] IDLE_CLOCK_MINUTES = {5, 15, 30, 60, 0};
    private final TextView[] idleClockChoices = new TextView[IDLE_CLOCK_MINUTES.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView tmdbApiKeyChoice;
    private TextView fanartApiKeyChoice;
    private TextView tvdbApiKeyChoice;
    private TextView performanceModeChoice;
    private TextView dockBandChoice;
    private TextView dockGlassChoice;
    private TextView alwaysShowNamesChoice;
    private TextView showGreetingChoice;
    private TextView greetingNameChoice;
    private TextView showStatsChoice;
    private TextView showWeatherChoice;
    private TextView weatherCityChoice;
    private TextView ambientGlowChoice;
    private TextView appGlowChoice;
    private TextView libraryButtonChoice;
    private TextView showLibraryPillChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private int idleClockMinutes;
    private boolean animations;
    private boolean advancedEffects;
    private boolean dockBand;
    private boolean dockGlass;
    private boolean alwaysShowNames;
    private boolean showGreeting;
    private String greetingName;
    private boolean showStats;
    private boolean showWeather;
    private String weatherCity;
    private boolean ambientGlow;
    private boolean appGlow;
    private boolean libraryButton;
    private boolean showLibraryPill;
    private boolean performanceMode;

    private static int nearestDockOpacity(int value) {
        int best = DOCK_OPACITIES[0];
        int bestDiff = Math.abs(value - best);
        for (int candidate : DOCK_OPACITIES) {
            int diff = Math.abs(value - candidate);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        return best;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = nearestDockOpacity(config.dockOpacity);
        clockFormat = config.clockFormat;
        idleClockMinutes = config.homeIdleClockMinutes;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        dockBand = config.dockBand;
        dockGlass = config.dockGlass;
        alwaysShowNames = config.alwaysShowNames;
        showGreeting = config.showGreeting;
        greetingName = config.greetingName;
        showStats = config.showStats;
        showWeather = config.showWeather;
        weatherCity = config.weatherCity;
        ambientGlow = config.ambientGlow;
        appGlow = config.appGlow;
        libraryButton = config.libraryButton;
        showLibraryPill = config.showLibraryPill;
        performanceMode = choices.getBoolean(Config.PERFORMANCE_MODE_KEY, true);

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] idleClockIds = {R.id.idleClock5, R.id.idleClock15, R.id.idleClock30, R.id.idleClock60, R.id.idleClockOff};
        for (int i = 0; i < idleClockIds.length; i++) {
            final int value = IDLE_CLOCK_MINUTES[i];
            idleClockChoices[i] = findViewById(idleClockIds[i]);
            idleClockChoices[i].setOnClickListener(v -> {
                if (idleClockMinutes == value) return;
                idleClockMinutes = value;
                choices.edit().putInt(Config.IDLE_CLOCK_MINUTES_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
            reverseRow(idleClockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        tmdbApiKeyChoice = findViewById(R.id.tmdbApiKeyChoice);
        tmdbApiKeyChoice.setOnClickListener(v -> showApiKeyDialog(
                R.string.tmdb_api_key_label,
                R.string.tmdb_api_key_saved,
                Config.TMDB_API_KEY,
                SpotlightManager.DEFAULT_TMDB_API_KEY,
                SpotlightManager.getTmdbApiKey(this)));
        fanartApiKeyChoice = findViewById(R.id.fanartApiKeyChoice);
        fanartApiKeyChoice.setOnClickListener(v -> showApiKeyDialog(
                R.string.fanart_api_key_label,
                R.string.fanart_api_key_saved,
                Config.FANART_API_KEY,
                SpotlightManager.DEFAULT_FANART_API_KEY,
                SpotlightManager.getFanartApiKey(this)));
        tvdbApiKeyChoice = findViewById(R.id.tvdbApiKeyChoice);
        tvdbApiKeyChoice.setOnClickListener(v -> showApiKeyDialog(
                R.string.tvdb_api_key_label,
                R.string.tvdb_api_key_saved,
                Config.TVDB_API_KEY,
                SpotlightManager.DEFAULT_TVDB_API_KEY,
                SpotlightManager.getTvdbApiKey(this)));
        performanceModeChoice = findViewById(R.id.performanceModeChoice);
        performanceModeChoice.setOnClickListener(v -> {
            performanceMode = !performanceMode;
            choices.edit().putBoolean(Config.PERFORMANCE_MODE_KEY, performanceMode).apply();
            MainActivity.applyPerformanceMode(this);
            refresh();
        });
        dockBandChoice = findViewById(R.id.dockBandChoice);
        dockBandChoice.setOnClickListener(v -> {
            dockBand = !dockBand;
            choices.edit().putBoolean(Config.DOCK_BAND_KEY, dockBand).apply();
            changed();
            refresh();
        });
        dockGlassChoice = findViewById(R.id.dockGlassChoice);
        dockGlassChoice.setOnClickListener(v -> {
            dockGlass = !dockGlass;
            choices.edit().putBoolean(Config.DOCK_GLASS_KEY, dockGlass).apply();
            changed();
            refresh();
        });
        alwaysShowNamesChoice = findViewById(R.id.alwaysShowNamesChoice);
        alwaysShowNamesChoice.setOnClickListener(v -> {
            alwaysShowNames = !alwaysShowNames;
            choices.edit().putBoolean(Config.ALWAYS_SHOW_NAMES_KEY, alwaysShowNames).apply();
            changed();
            refresh();
        });
        showGreetingChoice = findViewById(R.id.showGreetingChoice);
        showGreetingChoice.setOnClickListener(v -> {
            showGreeting = !showGreeting;
            choices.edit().putBoolean(Config.SHOW_GREETING_KEY, showGreeting).apply();
            changed();
            refresh();
        });
        greetingNameChoice = findViewById(R.id.greetingNameChoice);
        greetingNameChoice.setOnClickListener(v -> showGreetingNameDialog());
        showStatsChoice = findViewById(R.id.showStatsChoice);
        showStatsChoice.setOnClickListener(v -> {
            showStats = !showStats;
            choices.edit().putBoolean(Config.SHOW_STATS_KEY, showStats).apply();
            changed();
            refresh();
        });
        showWeatherChoice = findViewById(R.id.showWeatherChoice);
        showWeatherChoice.setOnClickListener(v -> {
            showWeather = !showWeather;
            choices.edit().putBoolean(Config.SHOW_WEATHER_KEY, showWeather).apply();
            changed();
            refresh();
        });
        weatherCityChoice = findViewById(R.id.weatherCityChoice);
        weatherCityChoice.setOnClickListener(v -> showWeatherCityDialog());
        ambientGlowChoice = findViewById(R.id.ambientGlowChoice);
        ambientGlowChoice.setOnClickListener(v -> {
            ambientGlow = !ambientGlow;
            choices.edit().putBoolean(Config.AMBIENT_GLOW_KEY, ambientGlow).apply();
            changed();
            refresh();
        });
        appGlowChoice = findViewById(R.id.appGlowChoice);
        appGlowChoice.setOnClickListener(v -> {
            appGlow = !appGlow;
            choices.edit().putBoolean(Config.APP_GLOW_KEY, appGlow).apply();
            changed();
            refresh();
        });
        libraryButtonChoice = findViewById(R.id.libraryButtonChoice);
        libraryButtonChoice.setOnClickListener(v -> {
            libraryButton = !libraryButton;
            choices.edit().putBoolean(Config.LIBRARY_BUTTON_KEY, libraryButton).apply();
            changed();
            refresh();
        });
        showLibraryPillChoice = findViewById(R.id.showLibraryPillChoice);
        showLibraryPillChoice.setOnClickListener(v -> {
            showLibraryPill = !showLibraryPill;
            choices.edit().putBoolean(Config.SHOW_LIBRARY_PILL_KEY, showLibraryPill).apply();
            changed();
            refresh();
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY)
                    .remove(Config.IDLE_CLOCK_MINUTES_KEY).remove(OpenOnStart.KEY)
                    .remove(Config.DOCK_BAND_KEY).remove(Config.DOCK_GLASS_KEY)
                    .remove(Config.ALWAYS_SHOW_NAMES_KEY).remove(Config.SHOW_GREETING_KEY)
                    .remove(Config.GREETING_NAME_KEY).remove(Config.SHOW_STATS_KEY)
                    .remove(Config.SHOW_WEATHER_KEY).remove(Config.WEATHER_CITY_KEY)
                    .remove(Config.AMBIENT_GLOW_KEY).remove(Config.APP_GLOW_KEY)
                    .remove(Config.LIBRARY_BUTTON_KEY).remove(Config.SHOW_LIBRARY_PILL_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = nearestDockOpacity(current.dockOpacity);
            clockFormat = current.clockFormat;
            idleClockMinutes = current.homeIdleClockMinutes;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            dockBand = current.dockBand;
            dockGlass = current.dockGlass;
            alwaysShowNames = current.alwaysShowNames;
            showGreeting = current.showGreeting;
            greetingName = current.greetingName;
            showStats = current.showStats;
            showWeather = current.showWeather;
            weatherCity = current.weatherCity;
            ambientGlow = current.ambientGlow;
            appGlow = current.appGlow;
            libraryButton = current.libraryButton;
            showLibraryPill = current.showLibraryPill;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void showApiKeyDialog(int titleRes, int savedToastRes, String prefKey, String defaultKey, String currentKey) {
        float density = getResources().getDisplayMetrics().density;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(titleRes);
        final EditText input = new EditText(this);
        input.setText(currentKey);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        int pad = (int) (18 * density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);
        builder.setPositiveButton(R.string.save, (d, which) -> {
            String key = input.getText().toString().trim();
            if (key.isEmpty() || defaultKey.equals(key)) {
                choices.edit().remove(prefKey).apply();
            } else {
                choices.edit().putString(prefKey, key).apply();
            }
            changed();
            Toast.makeText(this, savedToastRes, Toast.LENGTH_SHORT).show();
            refresh();
        });
        builder.setNeutralButton(R.string.reset, (d, which) -> {
            choices.edit().remove(prefKey).apply();
            changed();
            Toast.makeText(this, savedToastRes, Toast.LENGTH_SHORT).show();
            refresh();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    private void showGreetingNameDialog() {
        float density = getResources().getDisplayMetrics().density;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.greeting_name_label);
        final EditText input = new EditText(this);
        input.setText(greetingName);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        int pad = (int) (18 * density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);
        builder.setPositiveButton(R.string.save, (d, which) -> {
            greetingName = input.getText().toString().trim();
            choices.edit().putString(Config.GREETING_NAME_KEY, greetingName).apply();
            changed();
            refresh();
        });
        builder.setNeutralButton(R.string.reset, (d, which) -> {
            choices.edit().remove(Config.GREETING_NAME_KEY).apply();
            greetingName = Config.load(this).greetingName;
            changed();
            refresh();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    private void showWeatherCityDialog() {
        float density = getResources().getDisplayMetrics().density;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.weather_city_label);
        final EditText input = new EditText(this);
        input.setText(weatherCity);
        input.setHint(R.string.weather_city_auto);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        int pad = (int) (18 * density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);
        builder.setPositiveButton(R.string.save, (d, which) -> {
            weatherCity = input.getText().toString().trim();
            if (weatherCity.isEmpty()) {
                choices.edit().remove(Config.WEATHER_CITY_KEY).apply();
            } else {
                choices.edit().putString(Config.WEATHER_CITY_KEY, weatherCity).apply();
            }
            changed();
            refresh();
        });
        builder.setNeutralButton(R.string.reset, (d, which) -> {
            choices.edit().remove(Config.WEATHER_CITY_KEY).apply();
            weatherCity = Config.load(this).weatherCity;
            changed();
            refresh();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        for (int i = 0; i < idleClockChoices.length; i++) {
            idleClockChoices[i].setSelected(idleClockMinutes == IDLE_CLOCK_MINUTES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        tmdbApiKeyChoice.setText(getString(R.string.tmdb_api_key_state,
                formatApiKeyState(SpotlightManager.getTmdbApiKey(this))));
        fanartApiKeyChoice.setText(getString(R.string.fanart_api_key_state,
                formatApiKeyState(SpotlightManager.getFanartApiKey(this))));
        tvdbApiKeyChoice.setText(getString(R.string.tvdb_api_key_state,
                formatApiKeyState(SpotlightManager.getTvdbApiKey(this))));
        performanceModeChoice.setText(getString(R.string.performance_mode_state,
                getString(performanceMode ? R.string.on : R.string.off)));
        dockBandChoice.setText(getString(R.string.dock_band_state,
                getString(dockBand ? R.string.on : R.string.off)));
        dockGlassChoice.setText(getString(R.string.dock_glass_state,
                getString(dockGlass ? R.string.on : R.string.off)));
        dockGlassChoice.setAlpha(dockBand ? 1f : 0.5f);
        alwaysShowNamesChoice.setText(getString(R.string.always_show_names_state,
                getString(alwaysShowNames ? R.string.on : R.string.off)));
        showGreetingChoice.setText(getString(R.string.show_greeting_state,
                getString(showGreeting ? R.string.on : R.string.off)));
        greetingNameChoice.setText(getString(R.string.greeting_name_state,
                greetingName.isEmpty() ? getString(R.string.off) : greetingName));
        greetingNameChoice.setAlpha(showGreeting ? 1f : 0.5f);
        showStatsChoice.setText(getString(R.string.show_stats_state,
                getString(showStats ? R.string.on : R.string.off)));
        showWeatherChoice.setText(getString(R.string.show_weather_state,
                getString(showWeather ? R.string.on : R.string.off)));
        weatherCityChoice.setText(getString(R.string.weather_city_state,
                weatherCity.isEmpty() ? getString(R.string.weather_city_auto) : weatherCity));
        weatherCityChoice.setAlpha(showWeather ? 1f : 0.5f);
        ambientGlowChoice.setText(getString(R.string.ambient_glow_state,
                getString(ambientGlow ? R.string.on : R.string.off)));
        appGlowChoice.setText(getString(R.string.app_glow_state,
                getString(appGlow ? R.string.on : R.string.off)));
        libraryButtonChoice.setText(getString(R.string.library_button_state,
                getString(libraryButton ? R.string.on : R.string.off)));
        showLibraryPillChoice.setText(getString(R.string.show_library_pill_state,
                getString(showLibraryPill ? R.string.on : R.string.off)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(Config.IDLE_CLOCK_MINUTES_KEY)
                || choices.contains(OpenOnStart.KEY)
                || choices.contains(Config.DOCK_BAND_KEY) || choices.contains(Config.DOCK_GLASS_KEY)
                || choices.contains(Config.ALWAYS_SHOW_NAMES_KEY) || choices.contains(Config.SHOW_GREETING_KEY)
                || choices.contains(Config.GREETING_NAME_KEY) || choices.contains(Config.SHOW_STATS_KEY)
                || choices.contains(Config.SHOW_WEATHER_KEY) || choices.contains(Config.WEATHER_CITY_KEY)
                || choices.contains(Config.AMBIENT_GLOW_KEY) || choices.contains(Config.APP_GLOW_KEY)
                || choices.contains(Config.LIBRARY_BUTTON_KEY) || choices.contains(Config.SHOW_LIBRARY_PILL_KEY);
    }

    private String formatApiKeyState(String key) {
        if (key == null || key.isEmpty()) return getString(R.string.api_key_not_set);
        if (key.length() <= 4) return "••••";
        return "••••••••" + key.substring(key.length() - 4);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
