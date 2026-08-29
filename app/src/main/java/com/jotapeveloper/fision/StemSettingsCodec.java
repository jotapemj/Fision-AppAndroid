package com.jotapeveloper.fision;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * JSON serializer for per-stem settings.
 * Format: { "bass": {"v": 75, "p": 0.0}, "drums": {...}, ... }
 */
public class StemSettingsCodec {

    public static Map<String, StemSetting> parse(String json) {
        Map<String, StemSetting> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;
        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String stem = it.next();
                JSONObject obj = root.optJSONObject(stem);
                if (obj == null) continue;
                StemSetting s = new StemSetting();
                s.volume = obj.optInt("v", 75);
                s.pan    = (float) obj.optDouble("p", 0.0);
                // Optional 3D position: when missing, values stay as NaN (default is used).
                if (obj.has("x")) s.pos3DX = (float) obj.optDouble("x", Double.NaN);
                if (obj.has("y")) s.pos3DY = (float) obj.optDouble("y", Double.NaN);
                if (obj.has("e")) s.elevationDeg = (float) obj.optDouble("e", 0.0);
                if (obj.has("d")) s.distance3D   = (float) obj.optDouble("d", Double.NaN);
                if (obj.has("o")) s.omnipresent  = obj.optBoolean("o", false);
                if (obj.has("m")) s.mute3D       = obj.optBoolean("m", false);
                map.put(stem, s);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return map;
    }

    public static String encode(Map<String, StemSetting> map) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, StemSetting> e : map.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("v", e.getValue().volume);
                obj.put("p", (double) e.getValue().pan);
                if (!Float.isNaN(e.getValue().pos3DX)) obj.put("x", (double) e.getValue().pos3DX);
                if (!Float.isNaN(e.getValue().pos3DY)) obj.put("y", (double) e.getValue().pos3DY);
                if (Math.abs(e.getValue().elevationDeg) > 0.001f) obj.put("e", (double) e.getValue().elevationDeg);
                if (!Float.isNaN(e.getValue().distance3D)) obj.put("d", (double) e.getValue().distance3D);
                if (e.getValue().omnipresent) obj.put("o", true);
                if (e.getValue().mute3D) obj.put("m", true);
                root.put(e.getKey(), obj);
            }
            return root.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return "{}";
        }
    }

    /** Returns the setting for a stem, creating a default one when missing. */
    public static StemSetting getOrDefault(Map<String, StemSetting> map, String stem) {
        StemSetting s = map.get(stem);
        if (s == null) { s = new StemSetting(); map.put(stem, s); }
        return s;
    }
}
