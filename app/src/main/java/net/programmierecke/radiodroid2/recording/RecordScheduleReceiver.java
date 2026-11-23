package net.programmierecke.radiodroid2.recording;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import net.programmierecke.radiodroid2.utils.AppLog;

import androidx.core.content.ContextCompat;

import net.programmierecke.radiodroid2.service.PlayerService;

import java.util.List;

/** Fires a scheduled recording: hands off to PlayerService (foreground, mediaPlayback). */
public class RecordScheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            RecordSchedules.rescheduleAll(context);
            return;
        }
        int id = intent.getIntExtra(RecordSchedules.EXTRA_ID, -1);
        RecordSchedules.Item it = RecordSchedules.get(context, id);
        if (it == null) return;
        if (it.days == 0) {
            List<RecordSchedules.Item> items = RecordSchedules.load(context);
            for (RecordSchedules.Item i : items) if (i.id == id) i.enabled = false;
            RecordSchedules.save(context, items);
        }
        RecordSchedules.rescheduleAll(context);

        if (it.silent) {
            SilentRecordService.start(context, it.stationJson, it.durationMin);
            return;
        }
        Intent svc = new Intent(context, PlayerService.class);
        svc.setAction(PlayerService.ACTION_SCHEDULED_RECORD);
        svc.putExtra(PlayerService.EXTRA_REC_STATION, it.stationJson);
        svc.putExtra(PlayerService.EXTRA_REC_MINUTES, it.durationMin);
        try {
            // Exact alarms grant a temporary exemption to start a foreground service.
            ContextCompat.startForegroundService(context, svc);
        } catch (Exception e) {
            AppLog.e("RecSched", "could not start service", e);
        }
    }
}
