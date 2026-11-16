package com.zec.qmix.model;

import org.json.JSONArray;
import org.json.JSONObject;

public class Track {
    public String mid = "";
    public String name = "";
    public String singer = "";
    public String cover = "";
    public String url = "";
    public String kind = "";
    public String id = "";       // 歌曲数字 ID，上报最近播放用
    public String albumId = "";  // 专辑数字 ID，上报最近播放用

    public static Track from(JSONObject t) {
        Track x = new Track();
        x.mid = t.optString("mid");
        x.id = t.optString("id");
        x.name = t.optString("name", t.optString("title"));

        StringBuilder sb = new StringBuilder();
        JSONArray sa = t.optJSONArray("singer");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                if (i > 0) sb.append('/');
                sb.append(sa.optJSONObject(i).optString("name"));
            }
        }
        x.singer = sb.toString();

        JSONObject al = t.optJSONObject("album");
        String amid = al == null ? "" : al.optString("mid");
        x.albumId = al == null ? "" : al.optString("id");
        if (!amid.isEmpty()) {
            x.cover = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + amid + ".jpg";
        }
        return x;
    }
}
