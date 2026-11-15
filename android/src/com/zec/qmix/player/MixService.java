package com.zec.qmix.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.util.Log;

import com.zec.qmix.model.Track;
import com.zec.qmix.net.Qq;
import com.zec.qmix.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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
    volatile int pos0;   // 恢复出来的进度，没 prepare 时给界面用
    volatile int dur0;   // 恢复出来的时长，没 prepare 时给界面用
    int pendSeek;        // prepare 完成后先跳到这

    MediaPlayer player;
    MediaSession session;
    Mixer mixer;
    SharedPreferences sp;
    public volatile Bitmap cover;
    public volatile String coverKey = "";

    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final ExecutorService imgEx = Executors.newSingleThreadExecutor();
    final ExecutorService dlEx = Executors.newSingleThreadExecutor();  // 预下载，同一时间只下一首
    File audioDir;
    AudioAnalyzer analyzer;

    static final long KEEP_MS = 360L * 24 * 3600 * 1000;
    static final long MIN_FREE = 500L * 1024 * 1024;
    final Handler main = new Handler(Looper.getMainLooper());

    final Runnable saveTick = new Runnable() {
        @Override public void run() {
            if (playing) writeState();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("qmix", 0);
        inst = this;
        audioDir = new File(getFilesDir(), "audio");
        audioDir.mkdirs();
        dlEx.execute(this::dropExpired);
        analyzer = new AudioAnalyzer(this, audioDir);

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
            if (pendSeek > 0) {
                mp.seekTo(pendSeek);
                pendSeek = 0;
            } else {
                writeState();
            }
            mp.start();
            playing = true;
            notifyUpdate();
            prefetch();
        });
        player.setOnCompletionListener(mp -> next());
        player.setOnErrorListener((mp, what, extra) -> {
            // 坏的缓存文件删掉，下次重新下载。播的是流时这个文件不存在，delete 无副作用。
            Track t = cur;
            if (t != null) new File(audioDir, t.mid).delete();
            next();
            return true;
        });

        session = new MediaSession(this, "qmix");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        session.setActive(true);

        startForeground(1, buildNotification());

        mixer = new Mixer(this);
        ex.execute(() -> {
            boolean restored = restore();
            if (restored) notifyUpdate();
            try {
                mixer.load();
            } catch (Exception e) {
                status = "加载收藏失败，请检查 cookie";
                notifyUpdate();
                return;
            }
            if (restored) {
                // 等用户点播放
            } else {
                refill();
                save();
                notifyUpdate();
            }
        });
        main.postDelayed(saveTick, 2000);
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

    void playFirst() {
        playFirst(0);
    }

    void playFirst(int seek) {
        Track t;
        synchronized (queue) {
            t = queue.isEmpty() ? null : queue.get(0);
        }
        if (t == null) {
            status = "没歌了";
            return;
        }
        ready = false;
        pos0 = seek;
        if (seek == 0) dur0 = 0;
        final Track ft = t;
        // 本地有缓存就直接播文件，不用再换地址
        final File local = new File(audioDir, ft.mid);
        final boolean hit = local.exists();
        if (!hit && ft.url.isEmpty()) {
            String u = null;
            try {
                u = Qq.url(ft.mid);
            } catch (Exception ignored) {
            }
            if (u != null) ft.url = u;
        }
        if (!hit && ft.url.isEmpty()) {
            status = "播放失败";
            next();
            return;
        }
        final String src = hit ? local.getPath() : ft.url;
        Log.i("qmix", (hit ? "play local " : "play stream ") + ft.mid);
        if (hit) analyzer.analyze(ft.mid);
        final int at = seek;
        main.post(() -> {
            try {
                pendSeek = at;
                player.reset();
                player.setDataSource(src);
                player.prepareAsync();
            } catch (Exception e) {
                status = "播放失败";
                next();
            }
        });
    }

    // ---------- 本地缓存 ----------

    /** 下载完成满 360 天的文件和上次没下完的 .part 删掉。App 启动时跑一次。 */
    void dropExpired() {
        File[] fs = audioDir.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            if ((f.getName().endsWith(".part") || now - f.lastModified() >= KEEP_MS) && f.delete()) Log.i("qmix", "cache expired " + f.getName());
        }
    }

    /** 后台下载队列里的下一首。已有文件、空间不足、拿不到地址都直接跳过。 */
    void prefetch() {
        Track t;
        synchronized (queue) {
            t = queue.size() < 2 ? null : queue.get(1);
        }
        if (t == null) return;
        final String mid = t.mid;
        final String url0 = t.url;
        dlEx.execute(() -> {
            File f = new File(audioDir, mid);
            if (f.exists()) {
                analyzer.analyze(mid);  // 已缓存但可能还没分析，轮到前算好；分析过的会自己跳过
                return;
            }
            if (audioDir.getUsableSpace() < MIN_FREE) {
                Log.i("qmix", "cache skip low space " + mid);
                return;
            }
            File part = new File(audioDir, mid + ".part");
            HttpURLConnection c = null;
            try {
                String u = url0.isEmpty() ? Qq.url(mid) : url0;
                if (u == null) throw new Exception("no url");
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        if (Thread.currentThread().isInterrupted()) throw new Exception("stop");
                        out.write(b, 0, n);
                    }
                }
                if (!part.renameTo(f)) throw new Exception("rename");
                Log.i("qmix", "cache saved " + mid + " " + f.length());
                analyzer.analyze(mid);
            } catch (Exception e) {
                part.delete();
                Log.i("qmix", "cache fail " + mid + " " + e);
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    // ---------- 状态存取 ----------

    /** 读上次存的状态。数据坏了或拿不到当前歌的播放地址，就清掉，返回 false。 */
    boolean restore() {
        String raw = sp.getString("state", "");
        if (raw.isEmpty()) return false;

        List<Track> list = new ArrayList<>();
        int pos, dur, pat;
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray a = o.getJSONArray("q");
            for (int i = 0; i < a.length() && i < 7; i++) {
                JSONObject j = a.getJSONObject(i);
                Track t = new Track();
                t.mid = j.getString("mid");
                t.name = j.getString("name");
                t.singer = j.getString("singer");
                t.cover = j.optString("cover");
                t.kind = j.getString("kind");
                if (t.mid.isEmpty()) throw new Exception();
                if (!"fav".equals(t.kind) && !"radar".equals(t.kind)) throw new Exception();
                list.add(t);
            }
            if (list.isEmpty()) throw new Exception();
            pos = Math.max(0, o.optInt("pos"));
            dur = Math.max(0, o.optInt("dur"));
            pat = Math.max(0, o.optInt("pat"));
        } catch (Exception e) {
            dropSaved();
            return false;
        }

        String u = null;
        try {
            Qq.init(this);
            u = Qq.url(list.get(0).mid);
        } catch (Exception ignored) {
        }
        if (u == null) {
            dropSaved();
            return false;
        }

        list.get(0).url = u;
        synchronized (queue) {
            queue.addAll(list);
            cur = queue.get(0);
            up = new ArrayList<>(queue.subList(1, Math.min(6, queue.size())));
            status = "";
        }
        pos0 = pos;
        dur0 = dur;
        mixer.pos = pat;
        return true;
    }

    void dropSaved() {
        sp.edit().remove("state").apply();
    }

    void writeState() {
        if (queue.isEmpty()) return;
        try {
            JSONArray a = new JSONArray();
            synchronized (queue) {
                for (int i = 0; i < queue.size() && i < 7; i++) {
                    Track t = queue.get(i);
                    JSONObject j = new JSONObject();
                    j.put("mid", t.mid);
                    j.put("name", t.name);
                    j.put("singer", t.singer);
                    j.put("cover", t.cover);
                    j.put("kind", t.kind);
                    a.put(j);
                }
            }
            JSONObject o = new JSONObject();
            o.put("q", a);
            o.put("pos", position());
            o.put("dur", duration());
            o.put("pat", mixer.pos);
            sp.edit().putString("state", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void save() {
        main.post(this::writeState);
    }

    public void next() {
        ex.execute(() -> {
            ready = false;
            pos0 = 0;
            dur0 = 0;
            if (!queue.isEmpty()) stack.add(queue.remove(0));
            refill();
            playFirst();
            save();
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
            ready = false;
            pos0 = 0;
            dur0 = 0;
            refill();
            playFirst();
            save();
        });
    }

    // ---------- 播放控制 ----------

    void resume() {
        if (!ready) {
            if (cur == null) return;
            final int at = pos0;
            ex.execute(() -> playFirst(at));
            return;
        }
        main.post(() -> {
            player.start();
            playing = true;
            notifyUpdate();
        });
    }

    void pause() {
        main.post(() -> {
            if (ready) {
                player.pause();
                playing = false;
                writeState();
                notifyUpdate();
            }
        });
    }

    public void toggle() {
        if (playing) pause();
        else resume();
    }

    public int position() {
        try {
            return ready ? player.getCurrentPosition() : pos0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int duration() {
        try {
            return ready ? player.getDuration() : dur0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void seekTo(int ms) {
        pos0 = ms;
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
            b.setContentTitle("串串");
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
                position(), playing ? 1f : 0f);
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
                if (bm == null) return;
                // 检查 key 和写 cover 都在主线程做，中间切歌也不会写进旧图
                main.post(() -> {
                    if (!key.equals(coverKey)) return;
                    cover = bm;
                    updateSession();
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                            .notify(1, buildNotification());
                });
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
        writeState();
        main.removeCallbacksAndMessages(null);
        inst = null;
        try { player.release(); } catch (Exception ignored) { }
        session.release();
        ex.shutdownNow();
        imgEx.shutdownNow();
        dlEx.shutdownNow();
        analyzer.stop();
        super.onDestroy();
    }
}
