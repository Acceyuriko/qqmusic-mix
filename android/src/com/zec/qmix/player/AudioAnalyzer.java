package com.zec.qmix.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分析本地缓存的歌：首静音结束点、尾静音开始点、整体响度。
 * 结果存 SharedPreferences "analyze"，key 是 mid，值 "start,end,loudness,文件大小"；解码失败存 "fail,文件大小"。
 * 一次只分析一首。
 */
public class AudioAnalyzer {

    static final double SILENCE_DB = -45;
    static final int WIN_MS = 20;
    static final int PRE_MS = 150;   // 起点往前收
    static final int POST_MS = 300;  // 终点往后放

    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final SharedPreferences sp;
    final File dir;

    AudioAnalyzer(Context c, File dir) {
        sp = c.getSharedPreferences("analyze", 0);
        this.dir = dir;
    }

    /** 本地有文件、还没分析过（或文件大小变了）就排队分析。 */
    void analyze(String mid) {
        ex.execute(() -> {
            File f = new File(dir, mid);
            if (!f.exists()) return;
            String old = sp.getString(mid, "");
            if (old.endsWith("," + f.length())) {
                Log.i("qmix", "analyze cached " + mid + " " + old);
                return;
            }
            long t0 = System.currentTimeMillis();
            try {
                String v = run(f);
                sp.edit().putString(mid, v).apply();
                Log.i("qmix", "analyze done " + mid + " " + v + " ms=" + (System.currentTimeMillis() - t0));
            } catch (Exception e) {
                // 解码失败也记下，同一个文件不再重试；被停掉的不算失败
                if (!Thread.currentThread().isInterrupted()) sp.edit().putString(mid, "fail," + f.length()).apply();
                Log.i("qmix", "analyze fail " + mid + " " + e);
            }
        });
    }

    void stop() {
        ex.shutdownNow();
    }

    /** 解码整首，每 20ms 算一次均方，再找首尾静音和响度。 */
    static String run(File f) throws Exception {
        List<Double> ms = new ArrayList<>();  // 每 20ms 的均方（满幅 = 1）
        MediaExtractor me = new MediaExtractor();
        MediaCodec codec = null;
        try {
            me.setDataSource(f.getPath());
            MediaFormat fmt = null;
            for (int i = 0; i < me.getTrackCount(); i++) {
                MediaFormat m = me.getTrackFormat(i);
                if (m.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    me.selectTrack(i);
                    fmt = m;
                    break;
                }
            }
            if (fmt == null) throw new Exception("no audio track");
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int win = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) * WIN_MS / 1000
                    * fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            double sum = 0;
            int cnt = 0;
            boolean inEnd = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new Exception("stop");
                if (!inEnd) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        int n = me.readSampleData(codec.getInputBuffer(i), 0);
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEnd = true;
                        } else {
                            codec.queueInputBuffer(i, 0, n, me.getSampleTime(), 0);
                            me.advance();
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    win = of.getInteger(MediaFormat.KEY_SAMPLE_RATE) * WIN_MS / 1000
                            * of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (o >= 0) {
                    ShortBuffer s = codec.getOutputBuffer(o).order(ByteOrder.nativeOrder()).asShortBuffer();
                    while (s.hasRemaining()) {
                        double x = s.get() / 32768.0;
                        sum += x * x;
                        if (++cnt == win) {
                            ms.add(sum / cnt);
                            sum = 0;
                            cnt = 0;
                        }
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            me.release();
        }

        double th = Math.pow(10, SILENCE_DB / 10);
        int first = -1, last = -1;
        double loudSum = 0;
        int loudCnt = 0;
        for (int i = 0; i < ms.size(); i++) {
            if (ms.get(i) <= th) continue;
            if (first < 0) first = i;
            last = i;
            loudSum += ms.get(i);
            loudCnt++;
        }
        if (first < 0) throw new Exception("all silent");

        int total = ms.size() * WIN_MS;
        int rawStart = first * WIN_MS;
        int rawEnd = (last + 1) * WIN_MS;
        int start = Math.max(0, rawStart - PRE_MS);
        int end = Math.min(total, rawEnd + POST_MS);
        double loud = 10 * Math.log10(loudSum / loudCnt);
        Log.i("qmix", "analyze raw " + f.getName() + " start=" + rawStart + " end=" + rawEnd + " total=" + total);
        return start + "," + end + "," + String.format(Locale.US, "%.1f", loud) + "," + f.length();
    }
}
