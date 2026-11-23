package net.programmierecke.radiodroid2.recording;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import net.programmierecke.radiodroid2.utils.AppLog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import androidx.recyclerview.widget.RecyclerView;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.R;

import java.io.File;
import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.RecordingItemViewHolder> {
    final static String TAG = "RecordingsAdapter";

    class RecordingItemViewHolder extends RecyclerView.ViewHolder {
        final ViewGroup viewRoot;
        final TextView textViewTitle;
        final TextView textViewTime;

        private RecordingItemViewHolder(View itemView) {
            super(itemView);

            viewRoot = (ViewGroup) itemView;
            textViewTitle = itemView.findViewById(R.id.textViewTitle);
            textViewTime = itemView.findViewById(R.id.textViewTime);
        }
    }

    private Context context;
    private List<DataRecording> recordings;

    public RecordingsAdapter(@NonNull Context context) {
        this.context = context;
    }

    @NonNull
    @Override
    public RecordingsAdapter.RecordingItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View itemView = inflater.inflate(R.layout.list_item_recording, parent, false);
        return new RecordingItemViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordingItemViewHolder holder, int position) {
        if (position == 0) {
            // Radio Arkiv: "Open audio file…" row at the top of the Recordings tab
            holder.textViewTitle.setText(R.string.action_open_audio_file);
            holder.textViewTitle.setContentDescription(context.getString(R.string.action_open_audio_file));
            android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_folder_open_24dp);
            if (icon != null) {
                icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate());
                androidx.core.graphics.drawable.DrawableCompat.setTint(icon, holder.textViewTitle.getCurrentTextColor());
            }
            holder.textViewTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
            holder.textViewTitle.setCompoundDrawablePadding(16);
            holder.textViewTime.setText(R.string.open_audio_file_hint);
            holder.viewRoot.setOnClickListener(v -> {
                if (context instanceof net.programmierecke.radiodroid2.ActivityMain) {
                    ((net.programmierecke.radiodroid2.ActivityMain) context).openAudioFilePicker();
                }
            });
            holder.viewRoot.setOnLongClickListener(null);
            return;
        }
        holder.textViewTitle.setCompoundDrawablesRelative(null, null, null, null);
        holder.textViewTitle.setContentDescription(null);
        holder.textViewTime.setText(null);
        final DataRecording recording = recordings.get(position - 1);

        holder.textViewTitle.setText(recording.Name);
        //holder.textViewTime.setText(recording.Time);

        holder.viewRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openRecording(recording);
            }
        });
        holder.viewRoot.setOnLongClickListener(view -> {
            openRecordingWith(recording);
            return true;
        });
    }

    public void setRecordings(List<DataRecording> recordings) {
        if (this.recordings != null && recordings.size() == this.recordings.size()) {
            boolean same = true;
            for (int i = 0; i < recordings.size(); i++) {
                if (!recordings.get(i).equals(this.recordings.get(i))) {
                    same = false;
                    break;
                }
            }

            if (same) {
                return;
            }
        }

        this.recordings = recordings;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return 1 + (recordings != null ? recordings.size() : 0);
    }

    // Radio fork: tap plays the recording in Radio's own player (seekable, lock-screen controls)
    void openRecording(DataRecording theData) {
        File file = new File(RecordingsManager.getRecordDir() + "/" + theData.Name);
        if (BuildConfig.DEBUG) {
            AppLog.d(TAG, "play: " + file);
        }
        net.programmierecke.radiodroid2.service.PlayerServiceUtil.play(
                DataRadioStation.fromLocalFile(Uri.fromFile(file).toString(), theData.Name));
    }

    // Long press: hand the recording to another app through our FileProvider
    void openRecordingWith(DataRecording theData) {
        File file = new File(RecordingsManager.getRecordDir() + "/" + theData.Name);
        Uri fileUri;
        try {
            fileUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            AppLog.e(TAG, "cannot share recording", e);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(fileUri, "audio/*");
        i.setClipData(ClipData.newUri(context.getContentResolver(), "Record", fileUri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(i, null);
        if (!(context instanceof android.app.Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooser);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(context, R.string.error_no_app_for_recording, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
