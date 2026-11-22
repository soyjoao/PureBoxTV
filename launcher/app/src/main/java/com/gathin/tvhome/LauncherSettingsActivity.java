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
    private TextView spotlightEnabledChoice;
    private TextView spotlightWatchNextChoice;
    private TextView spotlightServicesChoice;
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
    private TextView sectionTitle;
    private View settingsDockPanel;
    private android.widget.ScrollView settingsScroll;
    private TextView[] navItems;
    private View[] categoryPanels;
    private final int[] categoryTitleRes = {
            R.string.settings_cat_appearance,
            R.string.settings_cat_background,
            R.string.settings_cat_clock_weather,
            R.string.settings_cat_system,
            R.string.settings_cat_general
    };
    private int activeCategory = 0;
    private View focusedSettingsRow;
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
    private boolean spotlightEnabled;
    private boolean spotlightWatchNext;
    private List<SpotlightManager.ServiceOption> detectedVideoServices = new ArrayList<>();
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
        View reorderRow = findViewById(R.id.reorderRow);
        if (reorderRow != null) reorderRow.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
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
        spotlightEnabled = config.spotlightEnabled;
        spotlightWatchNext = SpotlightManager.isWatchNextEnabled(this);
        detectedVideoServices = SpotlightManager.detectInstalledVideoServices(this);
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
        spotlightEnabledChoice = findViewById(R.id.spotlightEnabledChoice);
        spotlightEnabledChoice.setOnClickListener(v -> {
            spotlightEnabled = !spotlightEnabled;
            choices.edit().putBoolean(Config.SPOTLIGHT_ENABLED_KEY, spotlightEnabled).apply();
            changed();
            refresh();
        });
        spotlightWatchNextChoice = findViewById(R.id.spotlightWatchNextChoice);
        spotlightWatchNextChoice.setOnClickListener(v -> {
            spotlightWatchNext = !spotlightWatchNext;
            choices.edit().putBoolean(Config.SPOTLIGHT_SHOW_WATCH_NEXT_KEY, spotlightWatchNext).apply();
            changed();
            refresh();
        });
        spotlightServicesChoice = findViewById(R.id.spotlightServicesChoice);
        spotlightServicesChoice.setOnClickListener(v -> showSpotlightServicesDialog());
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
                new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog)
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
        View openOnStartRow = findViewById(R.id.openOnStartRow);
        if (config.openOnStartChoice) {
            if (openOnStartRow != null) openOnStartRow.setVisibility(View.VISIBLE);
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
                    .remove(Config.WEATHER_LAT_KEY).remove(Config.WEATHER_LON_KEY)
                    .remove(Config.SPOTLIGHT_ENABLED_KEY)
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
            spotlightEnabled = current.spotlightEnabled;
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

        sectionTitle = findViewById(R.id.sectionTitle);
        settingsDockPanel = findViewById(R.id.settingsDockPanel);
        settingsScroll = findViewById(R.id.settingsScroll);
        TextView versionView = findViewById(R.id.settingsVersion);
        if (versionView != null) versionView.setText("PureBoxTV v" + BuildConfig.VERSION_NAME);

        navItems = new TextView[] {
                findViewById(R.id.navAppearance),
                findViewById(R.id.navBackground),
                findViewById(R.id.navClockWeather),
                findViewById(R.id.navSystem),
                findViewById(R.id.navGeneral)
        };
        categoryPanels = new View[] {
                findViewById(R.id.panelAppearance),
                findViewById(R.id.panelBackground),
                findViewById(R.id.panelClockWeather),
                findViewById(R.id.panelSystem),
                findViewById(R.id.panelGeneral)
        };
        for (int i = 0; i < navItems.length; i++) {
            final int index = i;
            navItems[i].setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) selectCategory(index);
            });
            navItems[i].setOnClickListener(v -> {
                selectCategory(index);
                focusFirstInPanel(categoryPanels[index]);
            });
            attachRowFocusHighlight(categoryPanels[i]);
        }

        refresh();
        selectCategory(0);
        navItems[0].requestFocus();
    }

    /** Highlights the whole inline row and subtly scales the focused pill so the remote cursor is unmistakable. */
    private void attachRowFocusHighlight(View panel) {
        if (!(panel instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) panel;
        for (int i = 0; i < group.getChildCount(); i++) {
            View row = group.getChildAt(i);
            if (!(row instanceof android.view.ViewGroup)) continue;
            bindFocusInsideRow(row, (android.view.ViewGroup) row);
        }
    }

    private void bindFocusInsideRow(final View row, android.view.ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.isFocusable()) {
                child.setOnFocusChangeListener((v, hasFocus) -> {
                    v.setScaleX(hasFocus ? 1.03f : 1.0f);
                    v.setScaleY(hasFocus ? 1.03f : 1.0f);
                    if (hasFocus) {
                        if (focusedSettingsRow != null && focusedSettingsRow != row) {
                            focusedSettingsRow.setBackground(null);
                        }
                        focusedSettingsRow = row;
                        row.setBackgroundResource(R.drawable.settings_row_focused_bg);
                    } else {
                        row.post(() -> {
                            if (!row.hasFocus()) {
                                row.setBackground(null);
                                if (focusedSettingsRow == row) focusedSettingsRow = null;
                            }
                        });
                    }
                });
            } else if (child instanceof android.view.ViewGroup) {
                bindFocusInsideRow(row, (android.view.ViewGroup) child);
            }
        }
    }

    private void selectCategory(int index) {
        activeCategory = index;
        if (sectionTitle != null) sectionTitle.setText(categoryTitleRes[index]);
        for (int i = 0; i < navItems.length; i++) {
            navItems[i].setSelected(i == index);
            categoryPanels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (settingsScroll != null) settingsScroll.scrollTo(0, 0);
    }

    private void focusFirstInPanel(View panel) {
        if (!(panel instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) panel;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.isFocusable()) {
                child.requestFocus();
                return;
            }
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup inner = (android.view.ViewGroup) child;
                for (int j = 0; j < inner.getChildCount(); j++) {
                    View innerChild = inner.getChildAt(j);
                    if (innerChild.getVisibility() == View.VISIBLE && innerChild.isFocusable()) {
                        innerChild.requestFocus();
                        return;
                    }
                }
            }
        }
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
        new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    spotlightEnabled = false;
                    SharedPreferences.Editor ed = choices.edit().putBoolean(Config.SPOTLIGHT_ENABLED_KEY, false);
                    if (which == images.size()) {
                        ed.putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        ed.putString(Config.WALLPAPER_CHOICE_KEY, images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    spotlightEnabled = false;
                    choices.edit()
                            .putBoolean(Config.SPOTLIGHT_ENABLED_KEY, false)
                            .remove(Config.WALLPAPER_CHOICE_KEY)
                            .apply();
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
        new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog)
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog);
        builder.setTitle(R.string.weather_city_label);
        final EditText input = new EditText(this);
        input.setText(weatherCity);
        input.setHint(R.string.weather_city_auto);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        int pad = (int) (18 * density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);
        builder.setPositiveButton(R.string.weather_city_search, (d, which) -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) {
                clearWeatherCity();
                return;
            }
            Toast.makeText(this, R.string.weather_city_searching, Toast.LENGTH_SHORT).show();
            WeatherManager.searchCities(this, query, results -> {
                if (isFinishing() || isDestroyed()) return;
                if (results == null || results.isEmpty()) {
                    Toast.makeText(this, R.string.weather_city_not_found, Toast.LENGTH_LONG).show();
                    return;
                }
                showWeatherCityResultsDialog(results);
            });
        });
        builder.setNeutralButton(R.string.weather_city_auto, (d, which) -> clearWeatherCity());
        builder.setNegativeButton(android.R.string.cancel, null);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    private void showWeatherCityResultsDialog(List<WeatherManager.CityOption> results) {
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            labels[i] = results.get(i).pickerLabel();
        }
        final int[] selected = {0};
        new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog)
                .setTitle(R.string.weather_city_pick_title)
                .setSingleChoiceItems(labels, 0, (d, which) -> selected[0] = which)
                .setPositiveButton(R.string.save, (d, which) -> {
                    WeatherManager.CityOption chosen = results.get(selected[0]);
                    weatherCity = chosen.savedLabel();
                    choices.edit()
                            .putString(Config.WEATHER_CITY_KEY, weatherCity)
                            .putLong(Config.WEATHER_LAT_KEY, Double.doubleToRawLongBits(chosen.lat))
                            .putLong(Config.WEATHER_LON_KEY, Double.doubleToRawLongBits(chosen.lon))
                            .apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearWeatherCity() {
        choices.edit()
                .remove(Config.WEATHER_CITY_KEY)
                .remove(Config.WEATHER_LAT_KEY)
                .remove(Config.WEATHER_LON_KEY)
                .apply();
        weatherCity = Config.load(this).weatherCity;
        changed();
        refresh();
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
        applyDockPanelStyle();
        setTogglePill(animationsChoice, animations);
        setTogglePill(advancedChoice, advancedEffects);
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.45f);

        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(spotlightEnabled ? getString(R.string.wallpaper_spotlight_active)
                : "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                : wallpaperCurrent != null ? wallpaperCurrent
                : getString(R.string.wallpaper_theme_default));
        wallpaperChoice.setSelected(!spotlightEnabled && wallpaperCurrent != null && !"none".equals(wallpaperCurrent));

        setTogglePill(spotlightEnabledChoice, spotlightEnabled);
        setTogglePill(spotlightWatchNextChoice, spotlightWatchNext);
        spotlightWatchNextChoice.setAlpha(spotlightEnabled ? 1f : 0.45f);
        updateSpotlightServicesSummary();
        spotlightServicesChoice.setAlpha(spotlightEnabled ? 1f : 0.45f);
        tmdbApiKeyChoice.setText(formatApiKeyState(SpotlightManager.getTmdbApiKey(this)));
        fanartApiKeyChoice.setText(formatApiKeyState(SpotlightManager.getFanartApiKey(this)));
        tvdbApiKeyChoice.setText(formatApiKeyState(SpotlightManager.getTvdbApiKey(this)));
        float apiAlpha = spotlightEnabled ? 1f : 0.45f;
        tmdbApiKeyChoice.setAlpha(apiAlpha);
        fanartApiKeyChoice.setAlpha(apiAlpha);
        tvdbApiKeyChoice.setAlpha(apiAlpha);

        setTogglePill(performanceModeChoice, performanceMode);
        setTogglePill(dockBandChoice, dockBand);
        setTogglePill(dockGlassChoice, dockGlass);
        dockGlassChoice.setAlpha(dockBand ? 1f : 0.45f);
        setTogglePill(alwaysShowNamesChoice, alwaysShowNames);
        setTogglePill(showGreetingChoice, showGreeting);
        greetingNameChoice.setText(greetingName.isEmpty() ? getString(R.string.off) : greetingName);
        greetingNameChoice.setSelected(!greetingName.isEmpty());
        greetingNameChoice.setAlpha(showGreeting ? 1f : 0.45f);
        setTogglePill(showStatsChoice, showStats);
        setTogglePill(showWeatherChoice, showWeather);
        weatherCityChoice.setText(weatherCity.isEmpty() ? getString(R.string.weather_city_auto) : weatherCity);
        weatherCityChoice.setSelected(!weatherCity.isEmpty());
        weatherCityChoice.setAlpha(showWeather ? 1f : 0.45f);
        setTogglePill(ambientGlowChoice, ambientGlow);
        setTogglePill(appGlowChoice, appGlow);
        setTogglePill(libraryButtonChoice, libraryButton);
        setTogglePill(showLibraryPillChoice, showLibraryPill);
        openOnStartChoice.setText(openOnStartLabel());
        followThemeChoice.setSelected(false);
    }

    private void setTogglePill(TextView pill, boolean on) {
        pill.setText(getString(on ? R.string.on : R.string.off));
        pill.setSelected(on);
    }

    /** Styles the right-hand container identically to the Home screen's DockDecoration (glass & dock_opacity). */
    private void applyDockPanelStyle() {
        if (settingsDockPanel == null) return;
        float density = getResources().getDisplayMetrics().density;
        float scale = Math.max(0, Math.min(100, dockOpacity)) / 100f;
        android.graphics.drawable.GradientDrawable bg;
        if (dockGlass) {
            int topAlpha = Math.max(0x0A, Math.round(0x58 * scale));
            int bottomAlpha = Math.max(0x05, Math.round(0x28 * scale));
            bg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { (topAlpha << 24) | 0xFFFFFF, (bottomAlpha << 24) | 0xD8E0E8 });
        } else {
            int alpha = Math.round(dockOpacity * 2.55f);
            bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor((alpha << 24) | 0x1A1E24);
        }
        bg.setCornerRadius(26f * density);
        int baseEdgeAlpha = dockGlass ? 0x66 : 0x33;
        int edgeAlpha = Math.max(0x10, Math.round(baseEdgeAlpha * scale));
        bg.setStroke(Math.max(1, Math.round(density)), (edgeAlpha << 24) | 0xFFFFFF);
        settingsDockPanel.setBackground(bg);
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
                || choices.contains(Config.SPOTLIGHT_ENABLED_KEY)
                || choices.contains(Config.SPOTLIGHT_SHOW_WATCH_NEXT_KEY)
                || choices.contains(Config.SPOTLIGHT_DISABLED_SERVICES_KEY)
                || choices.contains(Config.AMBIENT_GLOW_KEY) || choices.contains(Config.APP_GLOW_KEY)
                || choices.contains(Config.LIBRARY_BUTTON_KEY) || choices.contains(Config.SHOW_LIBRARY_PILL_KEY);
    }

    private void updateSpotlightServicesSummary() {
        if (spotlightServicesChoice == null) return;
        int total = detectedVideoServices.size();
        if (total == 0) {
            spotlightServicesChoice.setText(R.string.spotlight_services_empty);
            spotlightServicesChoice.setSelected(false);
            return;
        }
        java.util.Set<String> disabled = SpotlightManager.getDisabledServices(this);
        int enabled = 0;
        for (SpotlightManager.ServiceOption opt : detectedVideoServices) {
            if (!disabled.contains(opt.key)) enabled++;
        }
        if (enabled == total) {
            spotlightServicesChoice.setText(getString(R.string.spotlight_services_all, total));
            spotlightServicesChoice.setSelected(true);
        } else if (enabled == 0) {
            spotlightServicesChoice.setText(R.string.spotlight_services_none);
            spotlightServicesChoice.setSelected(false);
        } else {
            spotlightServicesChoice.setText(getString(R.string.spotlight_services_count, enabled, total));
            spotlightServicesChoice.setSelected(true);
        }
    }

    private void showSpotlightServicesDialog() {
        detectedVideoServices = SpotlightManager.detectInstalledVideoServices(this);
        if (detectedVideoServices.isEmpty()) {
            Toast.makeText(this, R.string.spotlight_services_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.Set<String> disabled = SpotlightManager.getDisabledServices(this);
        final boolean[] checked = new boolean[detectedVideoServices.size()];
        for (int i = 0; i < detectedVideoServices.size(); i++) {
            checked[i] = !disabled.contains(detectedVideoServices.get(i).key);
        }

        final float d = getResources().getDisplayMetrics().density;
        final int padH = Math.round(18 * d);
        final int padV = Math.round(12 * d);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(Math.round(22 * d), Math.round(18 * d), Math.round(22 * d), Math.round(18 * d));

        TextView title = new TextView(this);
        title.setText(R.string.spotlight_services_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(19);
        title.setPadding(Math.round(6 * d), 0, Math.round(6 * d), Math.round(14 * d));
        root.addView(title);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        android.widget.LinearLayout listBox = new android.widget.LinearLayout(this);
        listBox.setOrientation(android.widget.LinearLayout.VERTICAL);

        final android.graphics.drawable.GradientDrawable[] rowBgs = new android.graphics.drawable.GradientDrawable[detectedVideoServices.size()];
        final TextView[] nameViews = new TextView[detectedVideoServices.size()];
        final TextView[] stateViews = new TextView[detectedVideoServices.size()];
        final android.graphics.drawable.GradientDrawable[] badgeBgs = new android.graphics.drawable.GradientDrawable[detectedVideoServices.size()];

        final Runnable[] styleRow = new Runnable[detectedVideoServices.size()];
        final android.widget.LinearLayout[] rows = new android.widget.LinearLayout[detectedVideoServices.size()];

        for (int i = 0; i < detectedVideoServices.size(); i++) {
            final int idx = i;
            SpotlightManager.ServiceOption opt = detectedVideoServices.get(i);

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setPadding(padH, padV, padH, padV);
            android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = Math.round(6 * d);
            row.setLayoutParams(rowLp);

            android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
            rowBg.setCornerRadius(12 * d);
            row.setBackground(rowBg);
            rowBgs[i] = rowBg;

            TextView nameTv = new TextView(this);
            nameTv.setText(opt.label);
            nameTv.setTextSize(15);
            nameTv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameViews[i] = nameTv;
            row.addView(nameTv);

            TextView stateTv = new TextView(this);
            stateTv.setTextSize(12.5f);
            stateTv.setPadding(Math.round(12 * d), Math.round(4 * d), Math.round(12 * d), Math.round(4 * d));
            android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
            badgeBg.setCornerRadius(100 * d);
            stateTv.setBackground(badgeBg);
            stateViews[i] = stateTv;
            badgeBgs[i] = badgeBg;
            row.addView(stateTv);

            styleRow[i] = () -> {
                boolean focused = row.hasFocus();
                boolean on = checked[idx];
                rowBg.setColor(focused ? 0xFFFFFFFF : 0x18FFFFFF);
                nameTv.setTextColor(focused ? 0xFF111111 : (on ? 0xFFFFFFFF : 0x80FFFFFF));
                stateTv.setText(on ? R.string.on : R.string.off);
                if (focused) {
                    badgeBg.setColor(on ? 0xFF111111 : 0x26000000);
                    stateTv.setTextColor(on ? 0xFFFFFFFF : 0xFF444444);
                } else {
                    badgeBg.setColor(on ? 0x334ADE80 : 0x1AFFFFFF);
                    stateTv.setTextColor(on ? 0xFF86EFAC : 0x80FFFFFF);
                }
            };
            styleRow[i].run();

            row.setOnFocusChangeListener((v, hasFocus) -> styleRow[idx].run());
            row.setOnClickListener(v -> {
                checked[idx] = !checked[idx];
                styleRow[idx].run();
            });
            rows[i] = row;
            listBox.addView(row);
        }

        scroll.addView(listBox);
        // Cap scroll height so the dialog stays compact on 1080p TV screens
        int maxListH = Math.round(Math.min(detectedVideoServices.size() * 54, 340) * d);
        scrollLp.height = maxListH;
        scrollLp.weight = 0f;
        root.addView(scroll, scrollLp);

        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.END);
        actions.setPadding(0, Math.round(14 * d), 0, 0);

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_PureBoxDialog)
                .setView(root)
                .create();

        TextView btnCancel = createDialogPillButton(getString(android.R.string.cancel), d);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        android.widget.LinearLayout.LayoutParams cancelLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelLp.setMarginEnd(Math.round(10 * d));
        actions.addView(btnCancel, cancelLp);

        TextView btnSave = createDialogPillButton(getString(R.string.save), d);
        btnSave.setOnClickListener(v -> {
            java.util.Set<String> newDisabled = new java.util.HashSet<>();
            for (int i = 0; i < detectedVideoServices.size(); i++) {
                if (!checked[i]) newDisabled.add(detectedVideoServices.get(i).key);
            }
            choices.edit().putStringSet(Config.SPOTLIGHT_DISABLED_SERVICES_KEY, newDisabled).apply();
            changed();
            refresh();
            dialog.dismiss();
        });
        actions.addView(btnSave);
        root.addView(actions);

        dialog.show();
        if (rows.length > 0) rows[0].requestFocus();
    }

    private TextView createDialogPillButton(String text, float d) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        btn.setPadding(Math.round(20 * d), Math.round(10 * d), Math.round(20 * d), Math.round(10 * d));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(100 * d);
        bg.setColor(0x22FFFFFF);
        btn.setBackground(bg);
        btn.setTextColor(0xFFFFFFFF);
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            bg.setColor(hasFocus ? 0xFFFFFFFF : 0x22FFFFFF);
            btn.setTextColor(hasFocus ? 0xFF111111 : 0xFFFFFFFF);
        });
        return btn;
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
