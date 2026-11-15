package com.zec.qmix.net;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class Qq {

    static String COOKIE, UIN, SKEY;
    static int GTK;
    static final String GUID = "10000";

    public static void init(Context c) throws Exception {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getAssets().open("cookie.txt"), StandardCharsets.UTF_8))) {
            COOKIE = r.readLine();
            UIN = r.readLine();
            SKEY = r.readLine();
        }
        GTK = hash33(SKEY);
    }

    static int hash33(String s) {
        int e = 0;
        for (int i = 0; i < s.length(); i++) e += (e << 5) + s.charAt(i);
        return 2147483647 & e;
    }

    public static JSONObject call(String module, String method, JSONObject param) throws Exception {
        JSONObject comm = new JSONObject();
        comm.put("ct", 24);
        comm.put("cv", 4747474);
        comm.put("platform", "yqq.json");
        comm.put("uin", UIN);
        comm.put("g_tk", GTK);
        comm.put("g_tk_new_20200303", GTK);
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("notice", 0);
        comm.put("need_new_code", 1);

        JSONObject req = new JSONObject();
        req.put("module", module);
        req.put("method", method);
        req.put("param", param);

        JSONObject body = new JSONObject();
        body.put("comm", comm);
        body.put("req_0", req);

        HttpURLConnection c = (HttpURLConnection) new URL("https://u.y.qq.com/cgi-bin/musicu.fcg").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Cookie", COOKIE);
        c.setRequestProperty("Referer", "https://y.qq.com/");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        String s = new String(bo.toByteArray(), StandardCharsets.UTF_8);

        JSONObject r = new JSONObject(s).optJSONObject("req_0");
        if (r == null || r.optInt("code", -1) != 0) {
            throw new Exception("QQ code " + (r == null ? "none" : r.optInt("code")) + " "
                    + s.substring(0, Math.min(200, s.length())));
        }
        return r.getJSONObject("data");
    }

    public static String url(String mid) throws Exception {
        String u = one(mid, mid, "M800", ".mp3");
        if (u != null) return u;

        // 有的歌 mid 和媒体文件名不一样，用 media_mid 再试一次。
        String mm = mediaMid(mid);
        if (!mm.isEmpty() && !mm.equals(mid)) {
            u = one(mid, mm, "M800", ".mp3");
            if (u != null) return u;
        }

        String f = mm.isEmpty() ? mid : mm;
        u = one(mid, f, "M500", ".mp3");
        if (u != null) return u;
        return one(mid, f, "C400", ".m4a");
    }

    static String mediaMid(String mid) {
        try {
            JSONObject p = new JSONObject();
            p.put("song_type", 0);
            p.put("song_mid", mid);
            JSONObject d = call("music.pf_song_detail_svr", "get_song_detail_yqq", p);
            return d.getJSONObject("track_info").getJSONObject("file").optString("media_mid");
        } catch (Exception e) {
            return "";
        }
    }

    /** 换一档 vkey，并实际探测这个地址能不能下。 */
    static String one(String mid, String fileMid, String prefix, String ext) {
        try {
            JSONObject p = new JSONObject();
            p.put("guid", GUID);
            p.put("filename", new JSONArray().put(prefix + fileMid + ext));
            p.put("songmid", new JSONArray().put(mid));
            p.put("songtype", new JSONArray().put(0));
            p.put("uin", UIN);
            p.put("loginflag", 1);
            p.put("platform", "20");
            JSONObject d = call("vkey.GetVkeyServer", "CgiGetVkey", p);

            JSONArray mi = d.optJSONArray("midurlinfo");
            if (mi == null || mi.length() == 0) return null;
            String purl = mi.getJSONObject(0).optString("purl");
            if (purl.isEmpty()) return null;
            String u = d.getJSONArray("sip").getString(0) + purl;
            if (!reachable(u)) return null;
            Log.i("qmix", "url " + mid + " " + fileMid + " " + prefix);
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    /** 只要前 1KB，确认 CDN 真的能下，不整首下。 */
    static boolean reachable(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("Range", "bytes=0-1023");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return false;
            try (InputStream in = c.getInputStream()) {
                return in.read(new byte[1024]) > 0;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
