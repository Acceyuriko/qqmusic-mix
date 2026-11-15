package com.zec.qmix.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.zec.qmix.R;
import com.zec.qmix.model.Track;
import com.zec.qmix.player.MixService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    ImageView cover;
    TextView name, singer, curT, durT, status;
    SeekBar seek;
    Button playBtn;
    LinearLayout upBox;

    final Handler h = new Handler(Looper.getMainLooper());
    boolean dragging;
    String shownMid = "";
    String shownUp = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        cover = findViewById(R.id.cover);
        name = findViewById(R.id.name);
        singer = findViewById(R.id.singer);
        curT = findViewById(R.id.cur);
        durT = findViewById(R.id.dur);
        status = findViewById(R.id.status);
        seek = findViewById(R.id.seek);
        playBtn = findViewById(R.id.play);
        upBox = findViewById(R.id.up);

        findViewById(R.id.play).setOnClickListener(v -> {
            if (MixService.inst != null) MixService.inst.toggle();
        });
        findViewById(R.id.next).setOnClickListener(v -> {
            if (MixService.inst != null) MixService.inst.next();
        });
        findViewById(R.id.prev).setOnClickListener(v -> {
            if (MixService.inst != null) MixService.inst.prev();
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar sb) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (MixService.inst != null) MixService.inst.seekTo(sb.getProgress());
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        startForegroundService(new Intent(this, MixService.class));
        h.post(tick);
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            h.postDelayed(this, 500);
        }
    };

    static String fmt(int ms) {
        int s = ms / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    void render() {
        MixService s = MixService.inst;
        if (s == null) {
            status.setText("启动中...");
            return;
        }
        status.setText(s.status);

        Track t = s.cur;
        if (t != null) {
            if (!t.mid.equals(shownMid)) {
                shownMid = t.mid;
                name.setText(t.name);
                singer.setText(t.singer);
                cover.setImageBitmap(null);
            }
            if (cover.getDrawable() == null && s.cover != null) cover.setImageBitmap(s.cover);
        }

        playBtn.setText(s.playing ? "暂停" : "播放");

        int d = s.duration();
        int p = s.position();
        if (d > 0) {
            seek.setMax(d);
            if (!dragging) seek.setProgress(p);
        }
        curT.setText(fmt(p));
        durT.setText(fmt(d));

        List<Track> up = s.up;
        StringBuilder key = new StringBuilder();
        for (Track x : up) key.append(x.mid).append(x.kind);
        String k = key.toString();
        if (!k.equals(shownUp)) {
            shownUp = k;
            renderUp(up);
        }
    }

    void renderUp(List<Track> list) {
        upBox.removeAllViews();
        for (Track t : list) {
            boolean fav = "fav".equals(t.kind);
            String tag = fav ? "藏" : "新";
            SpannableString ss = new SpannableString(tag + "  " + t.name + "  " + t.singer);
            ss.setSpan(new ForegroundColorSpan(fav ? 0xff1db954 : 0xff6aa9e9), 0, 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            TextView tv = new TextView(this);
            tv.setText(ss);
            tv.setTextSize(14);
            tv.setTextColor(0xffbbbbbb);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setPadding(0, 14, 0, 14);
            upBox.addView(tv);
        }
        if (list.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("加载中...");
            tv.setTextSize(14);
            tv.setTextColor(0xff666666);
            tv.setPadding(0, 14, 0, 14);
            upBox.addView(tv);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (MixService.inst != null) MixService.inst.save();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
