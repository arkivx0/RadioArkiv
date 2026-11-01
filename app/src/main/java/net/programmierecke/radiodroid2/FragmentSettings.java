package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceScreen;

import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial;
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial;
import com.bytehamster.lib.preferencesearch.SearchConfiguration;
import com.bytehamster.lib.preferencesearch.SearchPreference;

import net.programmierecke.radiodroid2.interfaces.IApplicationSelected;
import net.programmierecke.radiodroid2.proxy.ProxySettingsDialog;

import static net.programmierecke.radiodroid2.ActivityMain.FRAGMENT_FROM_BACKSTACK;

public class FragmentSettings extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, IApplicationSelected, PreferenceFragmentCompat.OnPreferenceStartScreenCallback  {

    private static final java.util.concurrent.ExecutorService backupExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void backupToast(final Context ctx, final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    private final androidx.activity.result.ActivityResultLauncher<String> backupExportLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.CreateDocument(), uri -> {
                if (uri == null) return;
                final Context app = requireContext().getApplicationContext();
                backupExecutor.execute(() -> {
                    try {
                        OfflineStationCache.exportBackup(app, uri);
                        backupToast(app, app.getString(R.string.backup_export_done));
                    } catch (Throwable e) {
                        Log.e("SETTINGS", "backup export failed", e);
                        backupToast(app, app.getString(R.string.backup_failed));
                    }
                });
            });

    private final androidx.activity.result.ActivityResultLauncher<String[]> backupImportLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                final Context app = requireContext().getApplicationContext();
                backupExecutor.execute(() -> {
                    try {
                        final OfflineStationCache.Backup backup = OfflineStationCache.readBackup(app, uri);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> confirmImport(backup));
                    } catch (Throwable e) {
                        Log.e("SETTINGS", "backup import failed", e);
                        backupToast(app, app.getString(R.string.backup_invalid));
                    }
                });
            });

    private void confirmImport(final OfflineStationCache.Backup backup) {
        if (!isAdded()) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_backup_import)
                .setMessage(getString(R.string.backup_import_confirm, backup.favouriteCount, backup.historyCount, backup.cacheCount))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int added = OfflineStationCache.applyBackup(requireContext(), backup);
                    Toast.makeText(requireContext(), getString(R.string.backup_import_done, added), Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    public static FragmentSettings openNewSettingsSubFragment(ActivityMain activity, String key) {
        FragmentSettings f = new FragmentSettings();
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key);
        f.setArguments(args);
        FragmentTransaction fragmentTransaction = activity.getSupportFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.containerView, f).addToBackStack(String.valueOf(FRAGMENT_FROM_BACKSTACK)).commit();
        return f;
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat preferenceFragmentCompat,
                                           PreferenceScreen preferenceScreen) {
        openNewSettingsSubFragment((ActivityMain) getActivity(), preferenceScreen.getKey());
        return true;
    }

    private boolean isToplevel() {
        return getPreferenceScreen() == null || getPreferenceScreen().getKey().equals("pref_toplevel");
    }

    private void updateTimeShiftSummary(androidx.preference.ListPreference p, String value) {
        if (value == null) value = net.programmierecke.radiodroid2.players.exoplayer.TimeShiftSettings.DEFAULT;
        int idx = p.findIndexOfValue(value);
        CharSequence label = idx >= 0 ? p.getEntries()[idx] : value;
        int kbps = 320;
        int cap = net.programmierecke.radiodroid2.players.exoplayer.TimeShiftSettings.capacityFor(value, kbps);
        p.setSummary(getString(R.string.settings_timeshift_summary, label,
                net.programmierecke.radiodroid2.players.exoplayer.TimeShiftSettings.approxMb(value, kbps), kbps,
                getString(net.programmierecke.radiodroid2.players.exoplayer.TimeShiftSettings.useDisk(cap)
                        ? R.string.settings_timeshift_disk : R.string.settings_timeshift_ram)));
    }

    private void refreshToplevelIcons() {
        findPreference("shareapp_package").setSummary(getPreferenceManager().getSharedPreferences().getString("shareapp_package", ""));
        findPreference("pref_category_ui").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_monitor));
        findPreference("pref_category_startup").setIcon(Utils.IconicsIcon(getContext(), GoogleMaterial.Icon.gmd_flight_takeoff));
        findPreference("pref_category_interaction").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon.cmd_gesture_tap));
        findPreference("pref_category_player").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_play));
        findPreference("pref_category_alarm").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon.cmd_clock_outline));
        findPreference("pref_category_connectivity").setIcon(Utils.IconicsIcon(getContext(), GoogleMaterial.Icon.gmd_import_export));
        findPreference("pref_category_recordings").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_record_rec));
        findPreference("pref_category_mpd").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_speaker_wireless));
        findPreference("pref_category_other").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_information_outline));
    }

    private void refreshToolbar() {
        ActivityMain activity = (ActivityMain) getActivity();
        final Toolbar myToolbar = activity.getToolbar(); //findViewById(R.id.my_awesome_toolbar);

        if (myToolbar == null || getPreferenceScreen() == null)
            return;

        myToolbar.setTitle(getPreferenceScreen().getTitle());

        if (Utils.bottomNavigationEnabled(activity)) {
            if (isToplevel()) {
                activity.getSupportActionBar().setDisplayHomeAsUpEnabled(false);
                activity.getSupportActionBar().setDisplayShowHomeEnabled(false);
                myToolbar.setNavigationOnClickListener(v -> activity.onBackPressed());
            } else {
                activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
                myToolbar.setNavigationOnClickListener(v -> activity.onBackPressed());
            }
        }
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        setPreferencesFromResource(R.xml.preferences, s);
        refreshToolbar();
        decorateSkinPicker();
        if (s == null) {
            refreshToplevelIcons();
            SearchPreference searchPreference = (SearchPreference) findPreference("searchPreference");
            SearchConfiguration config = searchPreference.getSearchConfiguration();
            config.setActivity((AppCompatActivity) getActivity());
            config.index(R.xml.preferences);
        } else if (s.equals("pref_category_player")) {
            findPreference("equalizer").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);

                    if (getContext().getPackageManager().resolveActivity(intent, 0) == null) {
                        Toast.makeText(getContext(), R.string.error_no_equalizer_found, Toast.LENGTH_SHORT).show();
                    } else {
                        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
                        startActivityForResult(intent, ActivityMain.LAUNCH_EQUALIZER_REQUEST);
                    }

                    return false;
                }
            });
        } else if (s.equals("pref_category_connectivity")) {
            //final ListPreference servers = (ListPreference) findPreference("radiobrowser_server");
            //updateDnsList(servers);

            findPreference("settings_proxy").setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ProxySettingsDialog proxySettingsDialog = new ProxySettingsDialog();
                    proxySettingsDialog.setCancelable(true);
                    proxySettingsDialog.show(getFragmentManager(), "");
                    return false;
                }
            });

            findPreference("stations_backup_export").setOnPreferenceClickListener(preference -> {
                backupExportLauncher.launch("radio-arkiv-stations-backup.json");
                return true;
            });
            findPreference("stations_backup_import").setOnPreferenceClickListener(preference -> {
                backupImportLauncher.launch(new String[]{"application/json", "text/plain", "application/octet-stream"});
                return true;
            });

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                findPreference("settings_retry_timeout").setVisible(false);
                findPreference("settings_retry_delay").setVisible(false);
            }
        } else if (s.equals("pref_category_mpd")) {
            findPreference("mpd_servers_viewer").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    RadioDroidApp radioDroidApp = (RadioDroidApp) requireActivity().getApplication();
                    Utils.showMpdServersDialog(radioDroidApp, requireActivity().getSupportFragmentManager(), null);
                    return false;
                }
            });
        } else if (s.equals("pref_category_player")) {
            androidx.preference.ListPreference ts = findPreference("timeshift_minutes");
            if (ts != null) {
                updateTimeShiftSummary(ts, ts.getValue());
                ts.setOnPreferenceChangeListener((p, v) -> {
                    updateTimeShiftSummary((androidx.preference.ListPreference) p, (String) v);
                    return true;
                });
            }
        } else if (s.equals("pref_category_recordings")) {
            findPreference("rec_open_schedules").setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), net.programmierecke.radiodroid2.recording.ActivityRecordSchedules.class));
                return true;
            });
            findPreference("rec_record_now_silent").setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), net.programmierecke.radiodroid2.recording.ActivityRecordSchedules.class)
                        .putExtra(net.programmierecke.radiodroid2.recording.ActivityRecordSchedules.EXTRA_RECORD_NOW, true));
                return true;
            });
        } else if (s.equals("pref_category_other")) {
            findPreference("show_statistics").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ((ActivityMain) getActivity()).getToolbar().setTitle(R.string.settings_statistics);
                    FragmentServerInfo f = new FragmentServerInfo();
                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                    fragmentTransaction.replace(R.id.containerView, f).addToBackStack(String.valueOf(FRAGMENT_FROM_BACKSTACK)).commit();
                    return false;
                }
            });

            findPreference("show_about").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ((ActivityMain) getActivity()).getToolbar().setTitle(R.string.settings_about);
                    FragmentAbout f = new FragmentAbout();
                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                    fragmentTransaction.replace(R.id.containerView, f).addToBackStack(String.valueOf(FRAGMENT_FROM_BACKSTACK)).commit();
                    return false;
                }
            });
        }

        Preference batPref = getPreferenceScreen().findPreference(getString(R.string.key_ignore_battery_optimization));
        if (batPref != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                updateBatteryPrefDescription(batPref);
                batPref.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                    updateBatteryPrefDescription(batPref);
                    return true;
                });
            } else {
                batPref.getParent().removePreference(batPref);
            }
        }
    }

    /*
    private void setServersData(String[] list, ListPreference servers) {
        servers.setEntries(list);
        if (list.length > 0){
            servers.setDefaultValue(list[0]);
        }
        servers.setEntryValues(list);
    }

    void updateDnsList(final ListPreference lp){
        final AsyncTask<Void, Void, String[]> xxx = new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... params) {
                return RadioBrowserServerManager.getServerList(false);
            }

            @Override
            protected void onPostExecute(String[] result) {
                setServersData(result, lp);
                super.onPostExecute(result);
            }
        }.execute();
    }
    */

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

        refreshToolbar();

        if(isToplevel())
            refreshToplevelIcons();

        if(findPreference("shareapp_package") != null)
            findPreference("shareapp_package").setSummary(getPreferenceManager().getSharedPreferences().getString("shareapp_package", ""));

        Preference batPref = getPreferenceScreen().findPreference(getString(R.string.key_ignore_battery_optimization));
        if (batPref != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // the second condition should already follow from the first
            updateBatteryPrefDescription(batPref);
        }
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @RequiresApi(23)
    private void updateBatteryPrefDescription(Preference batPref) {
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
            batPref.setSummary(R.string.settings_ignore_battery_optimization_summary_on);
        } else {
            batPref.setSummary(R.string.settings_ignore_battery_optimization_summary_off);
        }
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        if (BuildConfig.DEBUG) {
            Log.d("AAA", "changed key:" + key);
        }
        if (key.equals("alarm_external")) {
            boolean active = sharedPreferences.getBoolean(key, false);
            if (active) {
                ApplicationSelectorDialog newFragment = new ApplicationSelectorDialog();
                newFragment.setCallback(this);
                newFragment.show(getActivity().getSupportFragmentManager(), "appPicker");
            }
        }
        if (key.equals("theme_name") || key.equals("circular_icons") || key.equals("bottom_navigation")) {
            if (key.equals("circular_icons"))
                ((RadioDroidApp) getActivity().getApplication()).getFavouriteManager().updateShortcuts();
            getActivity().recreate();
        }
    }

    // Radio fork: colour swatches (bg | surface | accent | text) in the skin picker
    private void decorateSkinPicker() {
        androidx.preference.ListPreference lp = findPreference("theme_name");
        if (lp == null || lp.getEntries() == null) return;
        CharSequence[] entries = lp.getEntries();
        CharSequence[] out = new CharSequence[entries.length];
        for (int i = 0; i < entries.length; i++) {
            int[] cols = null;
            for (int[] sk : Skins.ALL)
                if (getString(sk[0]).contentEquals(entries[i])) cols = new int[]{sk[4], sk[5], sk[3], sk[6]};
            if (cols == null) cols = entries[i].toString().equals(getString(R.string.theme_light))
                    ? new int[]{0xFFFFFFFF, 0xFFEEEEEE, 0xFF1D6293, 0xFF000000}
                    : new int[]{0xFF2C2C2E, 0xFF232325, 0xFF1D6293, 0xFFFFFFFF};
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            for (int c : cols) {
                int st = sb.length();
                sb.append("\u25A0");
                sb.setSpan(new android.text.style.ForegroundColorSpan(c), st, sb.length(), 0);
            }
            sb.append("  ").append(entries[i]);
            out[i] = sb;
        }
        lp.setEntries(out);
    }

    @Override
    public void onAppSelected(String packageName, String activityName) {
        if (BuildConfig.DEBUG) {
            Log.d("SEL", "selected:" + packageName + "/" + activityName);
        }
        SharedPreferences.Editor ed = getPreferenceManager().getSharedPreferences().edit();
        ed.putString("shareapp_package", packageName);
        ed.putString("shareapp_activity", activityName);
        ed.commit();

        findPreference("shareapp_package").setSummary(packageName);
    }
}
