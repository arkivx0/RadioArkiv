package net.programmierecke.radiodroid2;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent on-device copy of every successful radio-browser API response.
 * Unlike the 1 hour HTTP cache it never expires; it is only used when all
 * API servers are unreachable, so browsing/search keeps working offline.
 */
public class OfflineStationCache {
    private static final String TAG = "OfflineCache";
    private static final String DIR = "offline_stations";
    private static final int MAX_ENTRIES = 400;
    private static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 4L * 1024 * 1024;
    public static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;
    private static final String FORMAT = "radio-arkiv-stations-backup";
    private static int putsSinceTrim = 0;
    /** Time (ms) of the cached copy last served instead of a live response, 0 if none. */
    public static volatile long lastServedOfflineTime = 0;

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String key(String relativeUri, @Nullable Map<String, String> params) {
        StringBuilder sb = new StringBuilder(enc(relativeUri));
        if (params != null) {
            for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
                sb.append('|').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
        }
        return sb.toString();
    }

    private static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Search results contain what the user typed; they stay on the device and are never exported. */
    static boolean isSearchKey(String key) {
        return key.contains("%2Fbyname") || key.contains("%2Fsearch");
    }

    private static String fileName(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.append(".json").toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode()) + ".json";
        }
    }

    public static synchronized void put(Context ctx, String key, String content) {
        if (ctx == null || content == null || content.length() > MAX_ENTRY_BYTES) return;
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("time", System.currentTimeMillis());
            o.put("data", content);
            writeFile(new File(dir(ctx), fileName(key)), o.toString());
            if (++putsSinceTrim >= 20) {
                putsSinceTrim = 0;
                trim(ctx);
            }
        } catch (Exception e) {
            Log.w(TAG, "put failed: " + e);
        }
    }

    @Nullable
    public static synchronized String get(Context ctx, String key) {
        if (ctx == null) return null;
        try {
            File f = new File(dir(ctx), fileName(key));
            if (!f.exists()) return null;
            JSONObject o = new JSONObject(readStream(new FileInputStream(f)));
            String data = o.optString("data", null);
            if (data != null) lastServedOfflineTime = o.optLong("time", f.lastModified());
            return data;
        } catch (Exception e) {
            // corrupt entry: drop it
            new File(dir(ctx), fileName(key)).delete();
            return null;
        }
    }

    private static void trim(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (int i = 0; i < files.length; i++) {
            total += files[i].length();
            if (i >= MAX_ENTRIES || total > MAX_TOTAL_BYTES || files[i].getName().endsWith(".tmp")) files[i].delete();
        }
    }

    /** Writes favourites, history and the cached station lists to a SAF document. */
    public static synchronized int exportBackup(Context ctx, Uri uri) throws Exception {
        RadioDroidApp app = (RadioDroidApp) ctx.getApplicationContext();
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", 1);
        root.put("created", System.currentTimeMillis());
        root.put("favourites", toArray(app.getFavouriteManager().getList()));
        root.put("history", toArray(app.getHistoryManager().getList()));
        JSONArray cache = new JSONArray();
        File[] files = dir(ctx).listFiles();
        int n = 0;
        if (files != null) {
            for (File f : files) {
                try {
                    JSONObject o = new JSONObject(readStream(new FileInputStream(f)));
                    if (isSearchKey(o.optString("key"))) continue;
                    cache.put(o);
                    n++;
                } catch (Exception ignored) {
                }
            }
        }
        root.put("cache", cache);
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            os.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        return n;
    }

    public static class Backup {
        public JSONArray favourites, history, cache;
        public int favouriteCount, historyCount, cacheCount;
    }

    /** Reads and validates a backup file without changing anything. */
    public static Backup readBackup(Context ctx, Uri uri) throws Exception {
        String text;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            text = readStream(is, MAX_IMPORT_BYTES);
        }
        JSONObject root = new JSONObject(text);
        if (!FORMAT.equals(root.optString("format")) || root.optInt("version", 0) != 1) {
            throw new IllegalArgumentException("not a stations backup");
        }
        Backup b = new Backup();
        b.favourites = validStations(root.optJSONArray("favourites"));
        b.history = validStations(root.optJSONArray("history"));
        b.cache = new JSONArray();
        JSONArray cache = root.optJSONArray("cache");
        if (cache != null) {
            for (int i = 0; i < cache.length() && i < MAX_ENTRIES; i++) {
                JSONObject o = cache.optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("key", ""), d = o.optString("data", "");
                if (k.isEmpty() || d.isEmpty() || isSearchKey(k)) continue;
                // only accept entries that really are station/list JSON
                try {
                    Object v = new org.json.JSONTokener(d).nextValue();
                    if (!(v instanceof JSONArray) && !(v instanceof JSONObject)) continue;
                } catch (Exception e) {
                    continue;
                }
                b.cache.put(o);
            }
        }
        b.favouriteCount = b.favourites.length();
        b.historyCount = b.history.length();
        b.cacheCount = b.cache.length();
        return b;
    }

    private static JSONArray validStations(@Nullable JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && i < 5000; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String uuid = o.optString("stationuuid", "");
            String url = o.optString("url", "");
            if (uuid.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) continue;
            out.put(o);
        }
        return out;
    }

    /** Applies a validated backup; must be called on the main thread. Cached lists never replace newer local ones. */
    public static int applyBackup(Context ctx, Backup b) {
        RadioDroidApp app = (RadioDroidApp) ctx.getApplicationContext();
        int added = mergeInto(app.getFavouriteManager(), b.favourites);
        mergeInto(app.getHistoryManager(), b.history);
        final JSONArray cache = b.cache;
        new Thread(() -> {
            for (int i = 0; i < cache.length(); i++) {
                JSONObject o = cache.optJSONObject(i);
                String k = o.optString("key");
                synchronized (OfflineStationCache.class) {
                    File f = new File(dir(ctx), fileName(k));
                    if (f.exists() && f.lastModified() >= o.optLong("time", 0)) continue;
                }
                put(ctx, k, o.optString("data"));
            }
        }).start();
        return added;
    }

    private static int mergeInto(StationSaveManager mgr, @Nullable JSONArray arr) {
        if (arr == null) return 0;
        List<DataRadioStation> list = DataRadioStation.DecodeJson(arr.toString());
        java.util.ArrayList<DataRadioStation> toAdd = new java.util.ArrayList<>();
        for (DataRadioStation s : list) {
            s.queue = mgr;
            if (s.StationUuid != null && !s.StationUuid.isEmpty() && !mgr.has(s.StationUuid)) toAdd.add(s);
        }
        if (!toAdd.isEmpty()) mgr.addMultiple(toAdd);
        return toAdd.size();
    }

    private static JSONArray toArray(List<DataRadioStation> list) {
        JSONArray a = new JSONArray();
        for (DataRadioStation s : list) a.put(s.toJson());
        return a;
    }

    private static void writeFile(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new java.io.IOException("rename failed");
        }
    }

    private static String readStream(InputStream is) throws Exception {
        return readStream(is, Long.MAX_VALUE);
    }

    private static String readStream(InputStream is, long max) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > max) throw new java.io.IOException("file too large");
                bos.write(buf, 0, r);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
