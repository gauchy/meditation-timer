package com.resonance.meditation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;

/**
 * Foreground service that rings the meditation bells at exact times even when the
 * screen is off. A partial wake lock keeps the CPU alive so the scheduled Handler
 * callbacks fire on time (Doze can't suspend a foreground service holding a wake
 * lock), and each bell is played natively via MediaPlayer on the ALARM stream so
 * it is reliably audible. All bell timing is driven here, not from the WebView JS
 * (which the OS freezes when the screen turns off).
 */
public class BellService extends Service {

    public static final String ACTION_START = "com.resonance.meditation.START";
    public static final String ACTION_STOP  = "com.resonance.meditation.STOP";
    public static final String EXTRA_BELL     = "bell";
    public static final String EXTRA_INTERVAL = "intervalSec";
    public static final String EXTRA_TOTAL    = "totalSec";
    public static final String EXTRA_ELAPSED  = "elapsedSec";

    private static final String CHANNEL_ID = "resonance_sit";
    private static final int NOTIF_ID = 42;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<MediaPlayer> players = new ArrayList<>();
    private PowerManager.WakeLock wakeLock;
    private String bellKey = "bowl";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            return START_NOT_STICKY;
        }

        bellKey = intent.getStringExtra(EXTRA_BELL);
        if (bellKey == null) bellKey = "bowl";
        int intervalSec = intent.getIntExtra(EXTRA_INTERVAL, 0);
        int totalSec    = intent.getIntExtra(EXTRA_TOTAL, 0);
        int elapsedSec  = intent.getIntExtra(EXTRA_ELAPSED, 0);

        startForegroundNotice();
        acquireWakeLock();

        // Reset any prior schedule (e.g. resume after pause).
        handler.removeCallbacksAndMessages(null);
        scheduleSession(intervalSec, totalSec, elapsedSec);
        return START_STICKY;
    }

    /** Build the whole bell timeline and post each future bell relative to now. */
    private void scheduleSession(int intervalSec, int totalSec, int elapsedSec) {
        long elapsedMs = elapsedSec * 1000L;

        // Opening bell at t=0 (only fires when starting fresh; skipped on resume).
        scheduleBellAt(0L, elapsedMs);

        // Interval bells: interval, 2*interval, ... strictly before the end.
        if (intervalSec > 0) {
            for (int t = intervalSec; t < totalSec; t += intervalSec) {
                scheduleBellAt(t * 1000L, elapsedMs);
            }
        }

        // Closing sequence: three bells at the end, 5s apart.
        long endMs = totalSec * 1000L;
        scheduleBellAt(endMs, elapsedMs);
        scheduleBellAt(endMs + 5000L, elapsedMs);
        scheduleBellAt(endMs + 10000L, elapsedMs);

        // Stop the service after the last bell has had time to ring out.
        long stopDelay = (endMs + 10000L) - elapsedMs + 12000L;
        if (stopDelay < 0) stopDelay = 0;
        handler.postDelayed(this::stopEverything, stopDelay);
    }

    /** Schedule a single strike at session-time whenMs; skip if already in the past. */
    private void scheduleBellAt(long whenMs, long elapsedMs) {
        long delay = whenMs - elapsedMs;
        if (delay < -500) return;          // already passed (resume) — don't replay
        if (delay < 0) delay = 0;
        handler.postDelayed(this::strike, delay);
    }

    private void strike() {
        int resId = getResources().getIdentifier("bell_" + bellKey, "raw", getPackageName());
        if (resId == 0) resId = getResources().getIdentifier("bell_bowl", "raw", getPackageName());
        if (resId == 0) return;

        final MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        players.add(mp);
        mp.setOnCompletionListener(p -> { p.release(); players.remove(p); });
        mp.setOnErrorListener((p, what, extra) -> { p.release(); players.remove(p); return true; });
        try {
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + resId);
            mp.setDataSource(this, uri);
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            try { mp.release(); } catch (Exception ignored) {}
            players.remove(mp);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resonance:sit");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L); // safety cap: 4h
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void startForegroundNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Meditation sit", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps interval bells ringing while you sit");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Resonance")
                .setContentText("Sitting — bells will ring on time")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void stopEverything() {
        handler.removeCallbacksAndMessages(null);
        for (MediaPlayer mp : new ArrayList<>(players)) {
            try { mp.release(); } catch (Exception ignored) {}
        }
        players.clear();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }
}
