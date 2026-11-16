package com.zec.qmix.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.icu.text.BreakIterator;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.zec.qmix.L;
import com.zec.qmix.model.Track;
import com.zec.qmix.net.Qq;
import com.zec.qmix.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    volatile boolean loading;  // 正在换地址 / prepare，还没好
    volatile boolean loaded;   // 收藏歌单加载成功过
    volatile String loadErr = "";
    volatile String why = "恢复";  // 下一次开播的原因，写进播放记录
    volatile int pos0;   // 恢复出来的进度，没 prepare 时给界面用
    volatile int dur0;   // 恢复出来的时长，没 prepare 时给界面用
    int pendSeek;        // prepare 完成后先跳到这
    double[] pendLevel;  // 这首歌的分析结果 {start, end, loudness}，没有就是 null
    int endMs;           // 播到这里就切下一首，0 = 不管
    LoudnessEnhancer le;
    long[] lrcT;         // 当前歌每行歌词的时间（毫秒），没歌词是 null
    String[][] lrcS;     // 每行切好的段（车机标题行只放得下 10 个字）
    int lrcIdx = -1;     // 当前显示的是第几行，-1 = 还没唱到 / 没歌词
    int lrcSeg = -1;     // 当前显示的是这一行的第几段

    static final double TARGET_DB = -16;

    MediaPlayer player;
    MediaSession session;
    Mixer mixer;
    SharedPreferences sp;
    public volatile Bitmap cover;
    public volatile String coverKey = "";

    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final ExecutorService imgEx = Executors.newSingleThreadExecutor();
    final ExecutorService dlEx = Executors.newSingleThreadExecutor();  // 预下载，同一时间只下一首
    final ExecutorService lrcEx = Executors.newSingleThreadExecutor();  // 拿歌词
    File audioDir;
    AudioAnalyzer analyzer;

    static final long KEEP_MS = 360L * 24 * 3600 * 1000;
    static final long MIN_FREE = 500L * 1024 * 1024;
    final Handler main = new Handler(Looper.getMainLooper());

    // 每 200ms 看一次是否到了尾静音
    final Runnable endTick = new Runnable() {
        @Override public void run() {
            if (ready && playing && endMs > 0) {
                int p = player.getCurrentPosition();
                if (p >= endMs) {
                    L.i("trim end " + (cur == null ? "" : cur.mid) + " pos=" + p + " end=" + endMs);
                    endMs = 0;
                    player.pause();  // 先停，不然放到文件真结尾时 onCompletion 会再切一次
                    why = "结尾裁剪";
                    next(false);
                }
            }
            main.postDelayed(this, 200);
        }
    };

    // 车机歌词提前这么多毫秒显示：每秒才查一次，车机刷新也慢，不提前的话总是唱完了才看到
    static final int LYRIC_AHEAD_MS = 1000;
    // 车机标题行最多显示 10 个字，长歌词行切成几段
    static final int SEG_MAX = 10;

    // 每秒对一次歌词行，行变了才更新 metadata（车机靠它显示歌词）
    final Runnable lyricTick = new Runnable() {
        @Override public void run() {
            if (ready && lrcT != null) {
                int p = player.getCurrentPosition();
                long q = p + LYRIC_AHEAD_MS;
                int i = lrcT.length - 1;
                while (i >= 0 && lrcT[i] > q) i--;
                long[] sg = i < 0 ? new long[]{-1, 0} : segAt(i, q);
                if (i != lrcIdx || sg[0] != lrcSeg) {
                    lrcIdx = i;
                    lrcSeg = (int) sg[0];
                    if (i >= 0) L.i("lyric " + cur.mid + " line=" + i + " seg=" + (lrcSeg + 1) + "/" + lrcS[i].length
                            + " t=" + sg[1] + " pos=" + p + " " + lrcS[i][lrcSeg]);
                    updateSession();
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    // 蓝牙耳机 / 车机断开（声音要从手机外放出来）时暂停
    final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            L.i("audio becoming noisy -> pause");
            pause();
        }
    };

    final Runnable saveTick = new Runnable() {
        @Override public void run() {
            if (playing) writeState();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        L.init(this);
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
            boolean resumed = pendSeek > 0;
            if (pendSeek > 0) {
                mp.seekTo(pendSeek);
                pendSeek = 0;
            } else {
                writeState();
            }
            applyLevel(mp, resumed);
            loading = false;
            status = "";  // 「加载中...」等提示到真正能播时才清
            if (playing) mp.start();  // 加载中点了暂停就不开播
            notifyUpdate();
            prefetch();
        });
        player.setOnCompletionListener(mp -> {
            endMs = 0;  // 先清掉，免得 endTick 在 next() 生效前再切一次
            why = "播完";
            next(false);
        });
        player.setOnErrorListener((mp, what, extra) -> {
            // 坏的缓存文件删掉，下次重新下载。播的是流时这个文件不存在，delete 无副作用。
            Track t = cur;
            if (t != null) new File(audioDir, t.mid).delete();
            why = "失败跳过";
            next(true);
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
            if (!loadFavs()) return;
            if (restored) {
                status = "";  // 等用户点播放；清掉 loadFavs 的「加载中...」
                notifyUpdate();
            } else {
                refill();
                save();
                notifyUpdate();
            }
        });
        main.postDelayed(saveTick, 2000);
        main.post(endTick);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        main.post(lyricTick);
    }

    // ---------- 歌单 ----------

    /** 加载收藏歌单（ex 线程）。失败时分清是没网还是 cookie 不行，提示不一样。 */
    boolean loadFavs() {
        status = "加载中...";  // 重试时先盖掉上次的失败提示
        notifyUpdate();
        try {
            mixer.load();
            loaded = true;
            return true;
        } catch (Exception e) {
            // 没网是 IOException（解析不了域名、连不上、超时）；别的（接口报错）当 cookie / 登录问题
            loadErr = e instanceof IOException ? "网络不通，联网后按播放重试" : "加载收藏失败，请重新登录（长按歌名）";
            L.i("load favs fail " + e.getClass().getSimpleName());
        }
        status = loadErr;
        notifyUpdate();
        return false;
    }

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
                status = loaded ? "没歌了" : loadErr;
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
            status = loaded ? "没歌了" : loadErr;
            loading = false;  // 上一首失败跳过来时 loading 还是 true，不清掉的话点播放会卡在「在播」
            playing = false;  // 没歌可放，显示「播放」、状态 PAUSED
            main.post(() -> player.reset());  // 停掉上一首，不然它会接着放完
            notifyUpdate();
            return;
        }
        ready = false;
        loading = true;
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
            L.i("play fail " + ft.mid);
            status = "播放失败";
            why = "失败跳过";
            next(true);
            return;
        }
        final String src = hit ? local.getPath() : ft.url;
        L.i((hit ? "play local " : "play stream ") + ft.mid);
        L.i("play why=" + why + " " + ft.mid + " " + ft.name + " - " + ft.singer);  // 播放记录
        if (hit) analyzer.analyze(ft.mid);
        final double[] lv = hit ? levelOf(ft.mid, local) : null;
        final int at = seek;
        main.post(() -> {
            try {
                pendSeek = at;
                pendLevel = lv;
                loadLyric(ft.mid);
                endMs = 0;
                if (le != null) {
                    le.release();
                    le = null;
                }
                player.reset();
                player.setDataSource(src);
                player.prepareAsync();
            } catch (Exception e) {
                status = "播放失败";
                why = "失败跳过";
                next(true);
            }
        });
    }

    // ---------- 歌词 ----------

    static final Pattern LRC_TAG = Pattern.compile("^\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\]");

    /** 主线程调：先清掉上一首的歌词，再到后台拿这首的。拿不到就当没歌词。 */
    void loadLyric(String mid) {
        lrcT = null;
        lrcS = null;
        lrcIdx = -1;
        lrcSeg = -1;
        lrcEx.execute(() -> {
            List<long[]> ts = new ArrayList<>();   // {时间, 行号}
            List<String> ls = new ArrayList<>();
            try {
                String raw = Qq.lyric(mid);
                if (raw != null) {
                    for (String line : raw.split("\n")) {
                        String text = line;
                        List<Long> times = new ArrayList<>();
                        Matcher m;
                        while ((m = LRC_TAG.matcher(text)).find()) {
                            String frac = m.group(3) == null ? "0" : (m.group(3) + "00").substring(0, 3);
                            times.add(Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000 + Long.parseLong(frac));
                            text = text.substring(m.end());
                        }
                        text = text.trim();
                        if (times.isEmpty() || text.isEmpty()) continue;
                        for (long tm : times) ts.add(new long[]{tm, ls.size()});
                        ls.add(text);
                    }
                }
            } catch (Exception e) {
                L.i("lyric fail " + mid + " " + e);
            }
            Collections.sort(ts, (a, b) -> Long.compare(a[0], b[0]));
            long[] t = new long[ts.size()];
            String[] l = new String[ts.size()];
            for (int i = 0; i < t.length; i++) {
                t[i] = ts.get(i)[0];
                l[i] = ls.get((int) ts.get(i)[1]);
            }
            String[][] segs = new String[l.length][];
            for (int i = 0; i < l.length; i++) {
                segs[i] = split(l[i]).toArray(new String[0]);
                if (segs[i].length > 1) L.i("lyric split line=" + i + " " + String.join(" | ", segs[i]));
            }
            L.i("lyric " + mid + " lines=" + t.length);
            main.post(() -> {
                if (cur == null || !cur.mid.equals(mid) || t.length == 0) return;
                lrcT = t;
                lrcS = segs;
                lrcIdx = -1;
                lrcSeg = -1;
            });
        });
    }

    /** 把一行歌词切成每段不超过 SEG_MAX 个字；按词切，不切开词（单个词太长才硬切）。 */
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s.length() <= SEG_MAX) {
            out.add(s);
            return out;
        }
        BreakIterator bi = BreakIterator.getWordInstance(Locale.CHINESE);
        bi.setText(s);
        StringBuilder cur = new StringBuilder();
        for (int a = bi.first(), b = bi.next(); b != BreakIterator.DONE; a = b, b = bi.next()) {
            String w = s.substring(a, b);
            if (cur.length() + w.length() > SEG_MAX) {
                addSeg(out, cur);
                while (w.trim().length() > SEG_MAX) {
                    out.add(w.trim().substring(0, SEG_MAX));
                    w = w.trim().substring(SEG_MAX);
                }
            }
            cur.append(w);
        }
        addSeg(out, cur);
        // 整行都是标点（如 "------------"）时去完就空了；保底留一段，不然段号是 -1 会越界
        if (out.isEmpty()) out.add(s.substring(0, Math.min(s.length(), SEG_MAX)));
        return out;
    }

    static void addSeg(List<String> out, StringBuilder cur) {
        // 去掉开头的空格和标点（中英文标点、撇号），段开头是「，」「'」不好看
        String seg = cur.toString().trim().replaceFirst("^[\\p{P}\\s]+", "");
        if (!seg.isEmpty()) out.add(seg);
        cur.setLength(0);
    }

    /** 第 i 行里位置 q 落在哪一段：每段时长 = 行时长 × 段字数 / 行总字数。返回 {段号, 段开始时间}。 */
    long[] segAt(int i, long q) {
        String[] segs = lrcS[i];
        long end = i + 1 < lrcT.length ? lrcT[i + 1] : duration();
        long lineMs = Math.max(0, end - lrcT[i]);
        int total = 0;
        for (String g : segs) total += g.length();
        long start = lrcT[i];
        for (int k = 0; k < segs.length - 1; k++) {
            long d = lineMs * segs[k].length() / total;
            if (q < start + d) return new long[]{k, start};
            start += d;
        }
        return new long[]{segs.length - 1, start};
    }

    // ---------- 掐头去尾、音量均衡 ----------

    /** 读分析结果。文件大小对不上、fail、start >= end、响度不是有限数，都当没有。 */
    double[] levelOf(String mid, File f) {
        String[] v = getSharedPreferences("analyze", 0).getString(mid, "").split(",");
        if (v.length != 4 || !v[3].equals(String.valueOf(f.length()))) return null;
        try {
            double start = Double.parseDouble(v[0]);
            double end = Double.parseDouble(v[1]);
            double loud = Double.parseDouble(v[2]);
            if (start >= end || Double.isNaN(loud) || Double.isInfinite(loud)) return null;
            return new double[]{start, end, loud};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** prepare 完成后调：从头播时跳过开头静音，设好结束点和音量。 */
    void applyLevel(MediaPlayer mp, boolean resumed) {
        String mid = cur == null ? "" : cur.mid;
        double[] lv = pendLevel;
        if (lv == null) {
            mp.setVolume(1f, 1f);
            L.i("level " + mid + " none");
            return;
        }
        if (!resumed && lv[0] > 0) mp.seekTo((int) lv[0]);
        endMs = (int) lv[1];
        double gainDb = Math.max(-12, Math.min(12, TARGET_DB - lv[2]));
        String how;
        if (gainDb > 0) {
            mp.setVolume(1f, 1f);
            try {
                le = new LoudnessEnhancer(mp.getAudioSessionId());
                le.setTargetGain((int) (gainDb * 100));
                le.setEnabled(true);
                how = "LoudnessEnhancer targetGain=" + (int) (gainDb * 100) + "mB";
            } catch (RuntimeException e) {
                // 设备不支持或资源不够时构造会抛，不加增益照常播
                L.i("level " + mid + " enhancer fail " + e);
                how = "no gain";
            }
        } else {
            float vol = (float) Math.pow(10, gainDb / 20);
            mp.setVolume(vol, vol);
            how = "setVolume " + String.format(java.util.Locale.US, "%.3f", vol);
        }
        L.i("level " + mid + " start=" + (resumed ? "skip(resume)" : String.valueOf((int) lv[0]))
                + " end=" + endMs + " loud=" + lv[2] + " gainDb=" + String.format(java.util.Locale.US, "%.1f", gainDb) + " " + how);
    }

    // ---------- 本地缓存 ----------

    /** 下载完成满 360 天的文件和上次没下完的 .part 删掉。App 启动时跑一次。 */
    void dropExpired() {
        File[] fs = audioDir.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            if ((f.getName().endsWith(".part") || now - f.lastModified() >= KEEP_MS) && f.delete()) L.i("cache expired " + f.getName());
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
                L.i("cache skip low space " + mid);
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
                L.i("cache saved " + mid + " " + f.length());
                analyzer.analyze(mid);
            } catch (Exception e) {
                part.delete();
                L.i("cache fail " + mid + " " + e);
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

        // 当前歌有本地缓存就不用换地址（断网也能恢复）；没缓存才要拿到地址
        if (!new File(audioDir, list.get(0).mid).exists()) {
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
        }
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

    /** 用户按的下一首（界面、通知栏、车机/蓝牙按键）。 */
    public void next() {
        why = "用户下一首";
        next(false);
    }

    /** failed = 这首放不了：不进历史栈（不然按上一首又落到它），也不改用户的播放/暂停选择。 */
    void next(boolean failed) {
        if (!failed) playing = true;
        // 马上标成「换歌中」：不然切歌后台还在补歌时来一个「播放」（车机常发），
        // resume() 会对播完的旧歌 start()（旧歌从头再放）或再排一次 playFirst（新歌从头再放）
        ready = false;
        loading = true;
        ex.execute(() -> {
            if (!loaded) loadFavs();  // 开机没网时歌单没加载，按下一首再试
            ready = false;
            pos0 = 0;
            dur0 = 0;
            if (!queue.isEmpty()) {
                Track t = queue.remove(0);
                if (!failed) stack.add(t);
            }
            refill();
            playFirst();
            save();
        });
    }

    public void prev() {
        ex.execute(() -> {
            if (stack.isEmpty()) return;
            playing = true;
            synchronized (queue) {
                if (queue.isEmpty()) return;
                Track old = queue.get(0);
                Track back = stack.remove(stack.size() - 1);
                queue.set(0, back);
                queue.add(1, old);
            }
            ready = false;
            loading = true;
            why = "上一首";
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
            if (cur == null && loaded) return;
            playing = true;
            notifyUpdate();
            // 正在加载就等它加载完自己开播；没在加载（刚恢复出来）才开始加载
            if (!loading) {
                final int at = pos0;
                why = "恢复";
                ex.execute(() -> {
                    // 开机没网时歌单没加载，按播放再试；队列里还有歌（恢复出来的）就先放，不等补满
                    if (!loaded && loadFavs() && queue.isEmpty()) refill();
                    playFirst(at);
                });
            }
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
            playing = false;  // 加载中也记下，加载完不开播
            if (ready) {
                player.pause();
                writeState();
            } else if (player.isPlaying()) {
                player.pause();  // 换歌中（补歌、准备新歌）旧歌还在响，也停掉
            }
            notifyUpdate();
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
            // 车机标题行字大：有歌词时标题放当前歌词段，艺术家放「歌名 - 歌手」；
            // 没歌词（或还没唱到）时标题放「歌名 - 歌手」，艺术家放歌手
            boolean ly = lrcS != null && lrcIdx >= 0;
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, ly ? lrcS[lrcIdx][lrcSeg] : t.name + " - " + t.singer)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, ly ? t.name + " - " + t.singer : t.singer);
            int d = duration();
            if (d > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, d);  // 车机进度条要用
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
        lrcEx.shutdownNow();
        dlEx.shutdownNow();
        analyzer.stop();
        if (le != null) le.release();
        unregisterReceiver(noisy);
        super.onDestroy();
    }
}
