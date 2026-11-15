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

    public static Track from(JSONObject t) {
        Track x = new Track();
        x.mid = t.optString("mid");
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
        if (!amid.isEmpty()) {
            x.cover = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + amid + ".jpg";
        }
        return x;
    }
}
