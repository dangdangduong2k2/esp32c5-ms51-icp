package vn.duong.ms51control;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** MS51 eData.sig_count[30]: big-endian uint32, one tick = 100 ms. */
final class DeviceHistory {
    static JSONObject fromConfig(String hex) throws Exception {
        byte[] raw = RuntimeConfigCodec.decode(hex).raw;
        JSONArray ticks = new JSONArray();
        for (int i = 0; i < 30; i++) {
            long value = 0;
            for (int b = 0; b < 4; b++) value = (value << 8) | (raw[0x33 + i * 4 + b] & 0xff);
            ticks.put(value);
        }
        return new JSONObject().put("ok", true).put("capacity", 30).put("tick_ms", 100).put("ticks", ticks);
    }

    static long[] decode(JSONObject response) throws Exception {
        if (!response.getBoolean("ok") || response.getInt("capacity") != 30 || response.getInt("tick_ms") != 100)
            throw new IllegalArgumentException("Định dạng nhật ký chưa được hỗ trợ.");
        JSONArray values = response.getJSONArray("ticks");
        if (values.length() != 30) throw new IllegalArgumentException("Nhật ký chưa đủ dữ liệu.");
        long[] ticks = new long[30];
        for (int i = 0; i < ticks.length; i++) {
            Object value = values.get(i);
            if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Thời lượng không hợp lệ.");
            ticks[i] = ((Number)value).longValue();
            if (ticks[i] < 0 || ticks[i] > 0xffffffffL) throw new IllegalArgumentException("Thời lượng ngoài giới hạn.");
        }
        return ticks;
    }

    static String format(long ticks) {
        long minutes = ticks / 600, seconds = ticks / 10 % 60, tenth = ticks % 10;
        String remainder = seconds + (tenth == 0 ? "" : "," + tenth);
        return minutes + " phút" + (seconds == 0 && tenth == 0 ? "" : " " + remainder + " giây");
    }

    static String text(long[] ticks) {
        if (ticks == null) return "Đang đọc thời lượng đã lưu…";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < ticks.length; i++) {
            if (ticks[i] == 0) continue; // unused slots; keep original display index
            if (result.length() > 0) result.append('\n');
            result.append(String.format(Locale.US, "L%02d", i)).append("    ").append(format(ticks[i]));
        }
        return result.length() == 0 ? "Chưa có thời lượng nào được lưu." : result.toString();
    }
}
