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

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String key(String relativeUri, @Nullable Map<String, String> params) {
        StringBuilder sb = new StringBuilder(relativeUri);
        if (params != null) {
            for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
                sb.append('|').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
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
        if (ctx == null || content == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("time", System.currentTimeMillis());
            o.put("data", content);
            writeFile(new File(dir(ctx), fileName(key)), o.toString());
            trim(ctx);
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
            Log.i(TAG, "serving offline copy for " + key);
            return o.optString("data", null);
        } catch (Exception e) {
            Log.w(TAG, "get failed: " + e);
            return null;
        }
    }

    private static void trim(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_ENTRIES; i++) files[i].delete();
    }

    /** Writes favourites, history and the cached station lists to a SAF document. */
    public static synchronized int exportBackup(Context ctx, Uri uri) throws Exception {
        RadioDroidApp app = (RadioDroidApp) ctx.getApplicationContext();
        JSONObject root = new JSONObject();
        root.put("format", "radio-arkiv-stations-backup");
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
                    cache.put(new JSONObject(readStream(new FileInputStream(f))));
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

    /** Merges a backup: adds missing favourites/history and restores cached lists. Returns favourites added. */
    public static synchronized int importBackup(Context ctx, Uri uri) throws Exception {
        RadioDroidApp app = (RadioDroidApp) ctx.getApplicationContext();
        String text;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            text = readStream(is);
        }
        JSONObject root = new JSONObject(text);
        int added = mergeInto(app.getFavouriteManager(), root.optJSONArray("favourites"));
        mergeInto(app.getHistoryManager(), root.optJSONArray("history"));
        JSONArray cache = root.optJSONArray("cache");
        if (cache != null) {
            for (int i = 0; i < cache.length(); i++) {
                JSONObject o = cache.optJSONObject(i);
                if (o == null || !o.has("key") || !o.has("data")) continue;
                put(ctx, o.getString("key"), o.getString("data"));
            }
        }
        return added;
    }

    private static int mergeInto(StationSaveManager mgr, @Nullable JSONArray arr) {
        if (arr == null) return 0;
        List<DataRadioStation> list = DataRadioStation.DecodeJson(arr.toString());
        java.util.ArrayList<DataRadioStation> toAdd = new java.util.ArrayList<>();
        for (DataRadioStation s : list) {
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
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readStream(InputStream is) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
