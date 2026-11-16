package com.zec.qmix;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 串串自己的日志：照常打 logcat（tag qmix），同时追加写到 外部私有目录/qmix.log。
 * 小米系统不保留第三方 App 的 logcat，出了问题要靠这份文件；adb 不用 root 就能 pull。
 * 超过 1MB 就改名成 qmix.log.1（覆盖旧的）再开新文件，最多两份。不要往这里写 cookie 等凭证。
 */
public class L {

    static final long MAX_BYTES = 1024 * 1024;

    static File file;
    // 写文件放到单独线程，不卡主线程；跟进程同生命周期
    static final ExecutorService ex = Executors.newSingleThreadExecutor();
    static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** MainActivity / MixService 的 onCreate 里调，先于任何日志。 */
    public static void init(Context c) {
        file = new File(c.getExternalFilesDir(null), "qmix.log");
    }

    public static void i(String msg) {
        Log.i("qmix", msg);
        long now = System.currentTimeMillis();
        ex.execute(() -> {
            try {
                if (file.length() > MAX_BYTES) file.renameTo(new File(file.getPath() + ".1"));
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write((FMT.format(new Date(now)) + " " + msg + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.i("qmix", "log write fail " + e);
            }
        });
    }
}
