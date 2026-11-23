package net.programmierecke.radiodroid2.recording;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import net.programmierecke.radiodroid2.utils.AppLog;

import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Radio fork: timed recordings, persisted in SharedPreferences, fired by exact alarms. */
public class RecordSchedules {
    private static final String PREF = "radio_record_schedules";
    static final String ACTION_FIRE = "io.github.arkivx0.radio.REC_FIRE";
    public static final String EXTRA_ID = "rec_id";

    public static class Item {
        public int id;
        public String stationJson;
        public String stationName;
        public int hour, minute;
        public int days;          // bit0=Sunday..bit6=Saturday; 0 = one-off
        public int durationMin;
        public boolean enabled = true;
        public long nextFire;
        public boolean silent = true; // record without playing aloud (default for old schedules too)

        public DataRadioStation station() {
            return DataRadioStation.DecodeJsonSingle(stationJson);
        }
    }

    public static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getInt("id");
                it.stationJson = o.getString("st");
                it.stationName = o.optString("name");
                it.hour = o.getInt("h");
                it.minute = o.getInt("m");
                it.days = o.optInt("d");
                it.durationMin = o.optInt("dur", 60);
                it.enabled = o.optBoolean("en", true);
                it.nextFire = o.optLong("next");
                it.silent = o.optBoolean("silent", true);
                out.add(it);
            }
        } catch (Exception e) {
            AppLog.e("RecSched", "load", e);
        }
        return out;
    }

    public static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id).put("st", it.stationJson).put("name", it.stationName)
                        .put("h", it.hour).put("m", it.minute).put("d", it.days)
                        .put("dur", it.durationMin).put("en", it.enabled).put("next", it.nextFire)
                        .put("silent", it.silent);
                a.put(o);
            }
        } catch (Exception e) {
            AppLog.e("RecSched", "save", e);
        }
        prefs(c).edit().putString(PREF, a.toString()).apply();
    }

    public static Item get(Context c, int id) {
        for (Item it : load(c)) if (it.id == id) return it;
        return null;
    }

    public static Item add(Context c, DataRadioStation st, int h, int m, int days, int dur) {
        return add(c, st, h, m, days, dur, true);
    }

    public static void setSilent(Context c, int id, boolean silent) {
        List<Item> items = load(c);
        for (Item i : items) if (i.id == id) i.silent = silent;
        save(c, items);
    }

    public static Item add(Context c, DataRadioStation st, int h, int m, int days, int dur, boolean silent) {
        List<Item> items = load(c);
        Item it = new Item();
        int max = 0;
        for (Item i : items) max = Math.max(max, i.id);
        it.id = max + 1;
        it.stationJson = st.toJson().toString();
        it.stationName = st.Name;
        it.hour = h; it.minute = m; it.days = days; it.durationMin = Math.max(1, dur);
        it.silent = silent;
        items.add(it);
        save(c, items);
        rescheduleAll(c);
        return it;
    }

    public static void remove(Context c, int id) {
        List<Item> items = load(c);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id == id) {
                cancelAlarm(c, id);
                items.remove(i);
            }
        }
        save(c, items);
    }

    /** Called after fire (one-off gets disabled) and on boot. */
    public static void rescheduleAll(Context c) {
        List<Item> items = load(c);
        for (Item it : items) {
            cancelAlarm(c, it.id);
            if (!it.enabled) { it.nextFire = 0; continue; }
            it.nextFire = nextTime(it);
            setAlarm(c, it.id, it.nextFire);
        }
        save(c, items);
    }

    static long nextTime(Item it) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, it.hour);
        cal.set(Calendar.MINUTE, it.minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            int dow = cal.get(Calendar.DAY_OF_WEEK) - 1;
            if (cal.getTimeInMillis() > now + 1000 && (it.days == 0 || (it.days & (1 << dow)) != 0)) {
                return cal.getTimeInMillis();
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return cal.getTimeInMillis();
    }

    private static PendingIntent pi(Context c, int id) {
        Intent i = new Intent(c, RecordScheduleReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(c, 10000 + id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAlarm(Context c, int id, long when) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p = pi(c, id);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, when, p);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, when, p);
        }
    }

    private static void cancelAlarm(Context c, int id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c, id));
    }

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }
}
