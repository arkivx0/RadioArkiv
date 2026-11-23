package net.programmierecke.radiodroid2.recording;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import net.programmierecke.radiodroid2.utils.AppLog;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Radio Arkiv: records a stream to Music/Radio by downloading it, without playing it
 * aloud and without taking audio focus. Runs as a dataSync foreground service and stops
 * at the end time (or from the notification's Stop action).
 */
public class SilentRecordService extends Service {
    private static final String TAG = "SilentRec";
    private static final String CHANNEL_ID = "silent_recording";
    private static final int NOTIFICATION_ID = 4242;
    static final String ACTION_START = "io.github.arkivx0.radio.SILENT_REC_START";
    static final String ACTION_STOP = "io.github.arkivx0.radio.SILENT_REC_STOP";
    static final String EXTRA_STATION = "station";
    static final String EXTRA_MINUTES = "minutes";

    private volatile boolean stopRequested;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static void start(Context context, String stationJson, int minutes) {
        Intent i = new Intent(context, SilentRecordService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_STATION, stationJson);
        i.putExtra(EXTRA_MINUTES, minutes);
        try {
            ContextCompat.startForegroundService(context, i);
        } catch (Exception e) {
            AppLog.e(TAG, "could not start silent recording", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        final DataRadioStation station = intent != null
                ? DataRadioStation.DecodeJsonSingle(intent.getStringExtra(EXTRA_STATION)) : null;
        final int minutes = intent != null ? Math.max(1, intent.getIntExtra(EXTRA_MINUTES, 60)) : 60;
        String name = station != null ? station.Name : "";
        startInForeground(notification(getString(R.string.silent_recording_title, name),
                getString(R.string.silent_recording_text, minutes)));
        if (station == null || worker != null) {
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        final long endTime = System.currentTimeMillis() + minutes * 60_000L;
        acquireLocks(minutes);
        worker = new Thread(() -> {
            try {
                record(station, endTime);
            } catch (Exception e) {
                AppLog.e(TAG, "silent recording failed", e);
            } finally {
                main.post(this::finish);
            }
        }, "silent-recorder");
        worker.start();
        return START_NOT_STICKY;
    }

    private void record(DataRadioStation station, long endTime) throws Exception {
        RadioDroidApp app = (RadioDroidApp) getApplication();
        OkHttpClient client = app.newHttpClient()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        String url = null;
        if (station.hasValidUuid()) {
            url = Utils.getRealStationLink(client, getApplicationContext(), station.StationUuid);
        }
        if (url == null) url = station.playableUrl != null ? station.playableUrl : station.StreamUrl;
        if (url == null) return;

        File out = null;
        OutputStream os = null;
        byte[] buf = new byte[16 * 1024];
        try {
            while (!stopRequested && StreamRecordUtils.remainingMs(endTime, System.currentTimeMillis()) > 0) {
                Request req = new Request.Builder().url(url).header("Icy-MetaData", "0").build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new java.io.IOException("HTTP " + resp.code());
                    String ct = resp.header("Content-Type");
                    String finalUrl = resp.request().url().toString();
                    if (StreamRecordUtils.isHls(ct, finalUrl)) {
                        AppLog.e(TAG, "HLS streams cannot be recorded silently");
                        return;
                    }
                    if (StreamRecordUtils.isPlaylist(ct, finalUrl)) {
                        String next = StreamRecordUtils.firstUrlInPlaylist(resp.body().string());
                        if (next == null || next.equals(url)) return;
                        url = next;
                        continue;
                    }
                    if (os == null) {
                        out = new File(RecordingsManager.getRecordDir(), fileName(station, StreamRecordUtils.extensionFor(ct, finalUrl)));
                        os = new FileOutputStream(out);
                        main.post(this::refreshRecordings);
                    }
                    InputStream in = resp.body().byteStream();
                    int n;
                    while (!stopRequested && System.currentTimeMillis() < endTime && (n = in.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                } catch (java.io.IOException e) {
                    if (stopRequested || System.currentTimeMillis() >= endTime) break;
                    AppLog.w(TAG, "stream interrupted, retrying: " + e);
                    Thread.sleep(3000);
                }
            }
        } finally {
            if (os != null) os.close();
        }
    }

    private String fileName(DataRadioStation station, String ext) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String format = prefs.getString("record_name_formatting", getString(R.string.settings_record_name_formatting_default));
        Map<String, String> args = new HashMap<>();
        args.put("station", Utils.sanitizeName(station.Name == null ? "station" : station.Name));
        args.put("artist", "-");
        args.put("track", "-");
        Date now = new Date();
        args.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now));
        args.put("time", new SimpleDateFormat("HH-mm", Locale.US).format(now));
        int num = prefs.getInt("record_num", 1);
        args.put("index", Integer.toString(num));
        prefs.edit().putInt("record_num", num + 1).apply();
        return Utils.formatStringWithNamedArgs(format, args) + "." + ext;
    }

    private void refreshRecordings() {
        try {
            ((RadioDroidApp) getApplication()).getRecordingsManager().updateRecordingsList();
        } catch (Exception ignored) {
        }
    }

    private void finish() {
        worker = null;
        refreshRecordings();
        releaseLocks();
        stopForeground(true);
        stopSelf();
    }

    private void acquireLocks(int minutes) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioArkiv:silentRecord");
        wakeLock.acquire(minutes * 60_000L + 60_000L);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioArkiv:silentRecord");
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        releaseLocks();
        super.onDestroy();
    }

    private void startInForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification notification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.silent_recording_channel), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, SilentRecordService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_start_recording)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_stop_24dp, getString(R.string.silent_recording_stop), stopPi)
                .build();
    }
}
