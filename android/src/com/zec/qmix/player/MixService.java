package com.zec.qmix.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.zec.qmix.model.Track;
import com.zec.qmix.ui.MainActivity;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MixService extends Service {

    public static MixService inst;

    static final String CH = "qmix";
    static final String ACT_TOGGLE = "com.zec.qmix.TOGGLE";
    static final String ACT_NEXT = "com.zec.qmix.NEXT";
    static final String ACT_PREV = "com.zec.qmix.PREV";

    final List<Track> queue = Collections.synchronizedList(new ArrayList<Track>());
    final List<Track> stack = Collections.synchronizedList(new ArrayList<Track>());
    public volatile Track cur;
    public volatile List<Track> up = new ArrayList<>();
    public volatile String status = "加载中...";
    public volatile boolean playing;
    volatile boolean ready;

    MediaPlayer player;
    MediaSession session;
    Mixer mixer;
    public volatile Bitmap cover;
    String coverKey = "";

    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final ExecutorService imgEx = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;

        NotificationChannel ch = new NotificationChannel(CH, "播放", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);

        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnPreparedListener(mp -> {
            ready = true;
            mp.start();
            playing = true;
            notifyUpdate();
        });
        player.setOnCompletionListener(mp -> next());
        player.setOnErrorListener((mp, what, extra) -> {
            next();
            return true;
        });

        session = new MediaSession(this, "qmix");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { main.post(() -> player.seekTo((int) pos)); }
        });
        session.setActive(true);

        startForeground(1, buildNotification());

        mixer = new Mixer(this);
        ex.execute(() -> {
            try {
                mixer.load();
            } catch (Exception e) {
                status = "加载收藏失败，请检查 cookie";
                return;
            }
            startPlay();
        });
    }

    // ---------- 歌单 ----------

    void refill() {
        List<Track> add = new ArrayList<>();
        while (queue.size() + add.size() < 7) {
            Track t;
            try {
                t = mixer.buildOne();
            } catch (Exception e) {
                break;
            }
            if (t == null) break;
            add.add(t);
        }
        synchronized (queue) {
            queue.addAll(add);
            if (queue.isEmpty()) {
                status = "没歌了";
                return;
            }
            cur = queue.get(0);
            up = new ArrayList<>(queue.subList(1, Math.min(6, queue.size())));
            status = "";
        }
    }

    void startPlay() {
        refill();
        playFirst();
    }

    void playFirst() {
        Track t;
        synchronized (queue) {
            t = queue.isEmpty() ? null : queue.get(0);
        }
        if (t == null) {
            status = "没歌了";
            return;
        }
        final Track ft = t;
        main.post(() -> {
            try {
                ready = false;
                player.reset();
                player.setDataSource(ft.url);
                player.prepareAsync();
            } catch (Exception e) {
                status = "播放失败";
                next();
            }
        });
    }

    public void next() {
        ex.execute(() -> {
            if (!queue.isEmpty()) stack.add(queue.remove(0));
            refill();
            playFirst();
        });
    }

    public void prev() {
        ex.execute(() -> {
            if (stack.isEmpty()) return;
            synchronized (queue) {
                if (queue.isEmpty()) return;
                Track old = queue.get(0);
                Track back = stack.remove(stack.size() - 1);
                queue.set(0, back);
                queue.add(1, old);
            }
            refill();
            playFirst();
        });
    }

    void playNow(Track t) {
        ex.execute(() -> {
            synchronized (queue) {
                if (!queue.isEmpty()) stack.add(queue.get(0));
                queue.remove(t);
                queue.add(0, t);
            }
            refill();
            playFirst();
        });
    }

    // ---------- 播放控制 ----------

    void resume() {
        main.post(() -> {
            if (ready) {
                player.start();
                playing = true;
            }
        });
    }

    void pause() {
        main.post(() -> {
            if (ready) {
                player.pause();
                playing = false;
            }
        });
    }

    public void toggle() {
        if (playing) pause();
        else resume();
    }

    public int position() {
        try {
            return ready ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int duration() {
        try {
            return ready ? player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void seekTo(int ms) {
        main.post(() -> {
            if (ready) player.seekTo(ms);
        });
    }

    // ---------- 通知 ----------

    PendingIntent act(String a) {
        return PendingIntent.getService(this, a.hashCode(),
                new Intent(this, MixService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
    }

    Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, "上一首", act(ACT_PREV)).build())
                .addAction(new Notification.Action.Builder(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", act(ACT_TOGGLE)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next, "下一首", act(ACT_NEXT)).build());

        Track t = cur;
        if (t != null) {
            b.setContentTitle(t.name);
            b.setContentText(t.singer);
            b.setLargeIcon(cover);
        } else {
            b.setContentTitle("穿插听");
            b.setContentText(status);
        }
        return b.build();
    }

    void notifyUpdate() {
        main.post(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, buildNotification());
            updateSession();
            loadCover();
        });
    }

    void updateSession() {
        Track t = cur;
        PlaybackState.Builder sb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO);
        sb.setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                ready ? player.getCurrentPosition() : 0, 1f);
        session.setPlaybackState(sb.build());

        if (t != null) {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.singer);
            if (cover != null) {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
            }
            session.setMetadata(mb.build());
        }
    }

    void loadCover() {
        Track t = cur;
        if (t == null) return;
        String key = t.mid;
        if (key.equals(coverKey)) return;
        coverKey = key;
        cover = null;
        if (t.cover.isEmpty()) return;
        final String u = t.cover;
        imgEx.execute(() -> {
            try (InputStream in = (InputStream) new URL(u).openStream()) {
                Bitmap bm = BitmapFactory.decodeStream(in);
                if (bm != null && key.equals(coverKey)) {
                    cover = bm;
                    main.post(() -> {
                        updateSession();
                        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                                .notify(1, buildNotification());
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }

    // ---------- 生命周期 ----------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_TOGGLE.equals(a)) toggle();
        else if (ACT_NEXT.equals(a)) next();
        else if (ACT_PREV.equals(a)) prev();
        notifyUpdate();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        inst = null;
        try { player.release(); } catch (Exception ignored) { }
        session.release();
        ex.shutdownNow();
        imgEx.shutdownNow();
        super.onDestroy();
    }
}
