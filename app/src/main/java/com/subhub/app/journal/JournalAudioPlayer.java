package com.subhub.app.journal;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import java.util.Locale;

/** Foreground native playback releases its decoder whenever the owning screen leaves. */
final class JournalAudioPlayer extends LinearLayout implements AutoCloseable {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView play, time;
    private final SeekBar progress;
    private MediaPlayer player;
    private final Uri uri;
    private final Runnable unavailable;
    private boolean preparing;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player == null || preparing) return;
            try {
                progress.setMax(Math.max(1, player.getDuration())); progress.setProgress(player.getCurrentPosition());
                time.setText(clock(player.getCurrentPosition()) + " / " + clock(player.getDuration()));
                if (player.isPlaying()) handler.postDelayed(this, 200);
            } catch (IllegalStateException invalid) { close(); }
        }
    };
    JournalAudioPlayer(Context context, Uri uri, Runnable unavailable) {
        super(context); this.uri = uri; this.unavailable = unavailable; setOrientation(VERTICAL);
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        play = (TextView) android.view.LayoutInflater.from(context).inflate(R.layout.view_ux_action, row, false);
        play.setText(R.string.journal_play); play.setMinHeight(PersonUi.dp(context, 48)); play.setTag("journal_play");
        row.addView(play, new LayoutParams(-2, -2));
        time = new TextView(context); time.setTextColor(context.getColor(R.color.text_secondary)); time.setText("0:00");
        time.setTextSize(14); time.setGravity(Gravity.END); row.addView(time, new LayoutParams(0, -2, 1));
        addView(row, new LayoutParams(-1, -2));
        progress = new SeekBar(context); progress.setMinimumHeight(PersonUi.dp(context, 48));
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_text)));
        progress.setThumbTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_text)));
        progress.setContentDescription(context.getString(R.string.journal_playback_position));
        addView(progress, new LayoutParams(-1, PersonUi.dp(context, 48))); progress.setEnabled(false);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user && player != null && !preparing) player.seekTo(value); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { tick.run(); }
        });
        play.setOnClickListener(view -> toggle());
    }
    private void toggle() {
        if (preparing) return;
        try {
            if (player == null) {
                player = new MediaPlayer(); preparing = true; play.setEnabled(false);
                player.setDataSource(getContext(), uri);
                player.setOnPreparedListener(sound -> {
                    if (player != sound) return;
                    try { preparing = false; play.setEnabled(true); progress.setEnabled(true);
                        sound.start(); play.setText(R.string.journal_pause); tick.run(); }
                    catch (IllegalStateException invalid) { close(); unavailable.run(); }
                });
                player.setOnCompletionListener(sound -> { if (player == sound) { play.setText(R.string.journal_play); tick.run(); } });
                player.setOnErrorListener((sound, what, extra) -> { if (player == sound) { close(); unavailable.run(); } return true; });
                player.prepareAsync();
            } else if (player.isPlaying()) pause();
            else { player.start(); play.setText(R.string.journal_pause); tick.run(); }
        } catch (Exception failure) { close(); unavailable.run(); }
    }
    void pause() {
        handler.removeCallbacks(tick);
        if (player != null && !preparing) { try { player.pause(); } catch (IllegalStateException ignored) { } }
        play.setText(R.string.journal_play);
    }
    @Override public void close() {
        handler.removeCallbacks(tick); preparing = false;
        if (player != null) { player.release(); player = null; }
        play.setEnabled(true); play.setText(R.string.journal_play); progress.setEnabled(false);
    }
    static String clock(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
