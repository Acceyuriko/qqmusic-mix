package com.zec.qmix.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.zec.qmix.model.Track;
import com.zec.qmix.net.Qq;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Mixer {

    static final String[] PATTERN = {"fav", "radar", "fav", "fav", "radar"};

    final Context ctx;
    final SharedPreferences sp;
    final List<Track> favs = new ArrayList<>();
    final Set<String> favMids = new HashSet<>();
    final Set<String> favNames = new HashSet<>();
    final List<String> history = new ArrayList<>();
    int radarPage = 2;
    final List<Track> radarBuf = new ArrayList<>();
    int pos = 0;

    Mixer(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("qmix", 0);
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[（(【\\[].*?[）)】\\]]", "")
                .replaceAll("[\\s\\-_·,.!?，。！？、'\"“”‘’:：]", "")
                .toLowerCase();
    }

    void load() throws Exception {
        Qq.init(ctx);
        JSONArray h = new JSONArray(sp.getString("history", "[]"));
        history.clear();
        for (int i = 0; i < h.length(); i++) history.add(h.getString(i));

        int num = 500;
        int begin = 0;
        favs.clear();
        while (true) {
            JSONObject p = new JSONObject();
            p.put("disstid", 0);
            p.put("dirid", 201);
            p.put("tag", true);
            p.put("song_begin", begin);
            p.put("song_num", num);
            p.put("userinfo", true);
            p.put("orderlist", true);
            JSONObject d = Qq.call("music.srfDissInfo.DissInfo", "CgiGetDiss", p);
            JSONArray list = d.optJSONArray("songlist");
            int len = list == null ? 0 : list.length();
            for (int i = 0; i < len; i++) favs.add(Track.from(list.getJSONObject(i)));
            if (len < num || favs.size() >= d.optInt("total_song_num", 0)) break;
            begin += num;
        }

        favMids.clear();
        favNames.clear();
        for (Track f : favs) {
            favMids.add(f.mid);
            favNames.add(norm(f.name));
        }
    }

    Track pickFav() {
        int n = history.size();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(history.get(i), i);

        double total = 0;
        double[] w = new double[favs.size()];
        for (int i = 0; i < favs.size(); i++) {
            Integer k = idx.get(favs.get(i).mid);
            w[i] = k == null ? n + 1 : k + 1;
            total += w[i];
        }
        double r = Math.random() * total;
        for (int i = 0; i < favs.size(); i++) {
            r -= w[i];
            if (r <= 0) return favs.get(i);
        }
        return favs.get(favs.size() - 1);
    }

    void fillRadar() throws Exception {
        JSONObject p = new JSONObject();
        p.put("Page", radarPage);
        p.put("Num", 20);
        JSONObject d = Qq.call("music.recommend.TrackRelationServer", "GetRadarSong", p);

        JSONArray arr = d.optJSONArray("VecSongs");
        if (arr == null) arr = d.optJSONArray("vecSong");
        radarBuf.clear();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONObject t = o.optJSONObject("Track");
                radarBuf.add(Track.from(t == null ? o : t));
            }
        }
        radarPage++;
        if (d.optInt("HasMore", 1) == 0) radarPage = 2;
    }

    Track nextRadar() throws Exception {
        for (int i = 0; i < 30; i++) {
            if (radarBuf.isEmpty()) fillRadar();
            if (radarBuf.isEmpty()) return null;
            Track t = radarBuf.remove(0);
            if (favMids.contains(t.mid)) continue;
            if (favNames.contains(norm(t.name))) continue;
            return t;
        }
        return null;
    }

    Track buildOne() throws Exception {
        String kind = PATTERN[(pos++) % PATTERN.length];
        for (int i = 0; i < 6; i++) {
            Track t;
            try {
                t = "fav".equals(kind) ? pickFav() : nextRadar();
            } catch (Exception e) {
                continue;
            }
            if (t == null) continue;

            String u;
            try {
                u = Qq.url(t.mid);
            } catch (Exception e) {
                continue;
            }
            if (u == null) continue;

            if ("fav".equals(kind)) {
                history.remove(t.mid);
                history.add(0, t.mid);
                sp.edit().putString("history", new JSONArray(history).toString()).apply();
            }
            t.url = u;
            t.kind = kind;
            return t;
        }
        return null;
    }
}
