package com.zec.qmix.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.zec.qmix.R;
import com.zec.qmix.net.Qq;
import com.zec.qmix.player.MixService;

/** 粘贴 cookie。验证通过才存进 SharedPreferences，然后重启 MixService 重新加载歌单。 */
public class CookieActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_cookie);

        EditText box = findViewById(R.id.cookie);
        Button save = findViewById(R.id.save);
        TextView msg = findViewById(R.id.msg);

        if (Qq.init(this)) box.setText(Qq.cookie());

        save.setOnClickListener(v -> {
            // 浏览器复制来的常带换行，换行放进 HTTP 头会出错，先去掉
            String s = box.getText().toString().replace("\r", "").replace("\n", "").trim();
            save.setEnabled(false);
            msg.setText("验证中...");
            new Thread(() -> {
                boolean ok = Qq.check(s);
                runOnUiThread(() -> {
                    save.setEnabled(true);
                    if (!ok) {
                        msg.setText("cookie 无效");
                        return;
                    }
                    getSharedPreferences("qmix", 0).edit().putString("cookie", s).apply();
                    Toast.makeText(this, "cookie 可用，已保存", Toast.LENGTH_LONG).show();
                    stopService(new Intent(this, MixService.class));
                    startForegroundService(new Intent(this, MixService.class));
                    finish();
                });
            }).start();
        });
    }
}
