package vn.duong.ms51control;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Byte-for-byte codec for the 217-byte MS51 runtime configuration block.
 * It intentionally uses the same offsets as the existing browser interface.
 */
final class RuntimeConfigCodec {
    static final int SIZE = 217;

    private RuntimeConfigCodec() {
    }

    static final class Config {
        final byte[] raw;
        final Map<String, Integer> values;

        Config(byte[] raw, Map<String, Integer> values) {
            this.raw = raw;
            this.values = values;
        }
    }

    static Config defaults() {
        byte[] raw = new byte[SIZE];
        Map<String, Integer> values = defaultValues();
        try {
            return new Config(encode(raw, values), values);
        } catch (IllegalArgumentException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Config decode(String hex) {
        if (hex == null || hex.length() != SIZE * 2 || !hex.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Dữ liệu cài đặt trả về không hợp lệ.");
        }
        byte[] raw = new byte[SIZE];
        for (int index = 0; index < SIZE; index++) {
            raw[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        if (u8(raw, 0x00) != 2) {
            throw new IllegalArgumentException("Phiên bản cài đặt của thiết bị không được hỗ trợ.");
        }

        Map<String, Integer> values = defaultValues();
        values.put("coolCl", u32(raw, 0x01));
        values.put("coolOp", u32(raw, 0x05));
        values.put("defrostCl", u32(raw, 0x09));
        values.put("defrostOp", u32(raw, 0x0D));
        values.put("delaySt", u8(raw, 0x11));
        values.put("stLed", u8(raw, 0x12));
        values.put("df", u8(raw, 0x13));
        values.put("end", u8(raw, 0x14));
        values.put("coolStart", u8(raw, 0x15));
        values.put("defrostStart", u8(raw, 0x16));
        values.put("displayMode", u8(raw, 0x17));
        values.put("lockTime", u16(raw, 0x18));
        values.put("greenBrightness", u8(raw, 0x1A));
        values.put("redBrightness", u8(raw, 0x1B));
        values.put("hcfMode", u8(raw, 0x1C));
        values.put("hdf", u8(raw, 0x1D));
        values.put("onTime", u8(raw, 0x1E));
        values.put("touch", u8(raw, 0x1F));
        values.put("tryTime", u16(raw, 0x20));
        values.put("hcfTime", u16(raw, 0x22));
        values.put("delayDf", u16(raw, 0x24));
        values.put("defrostOpSeconds", u8(raw, 0x26));
        values.put("showDf", u8(raw, 0x27));
        values.put("showEnd", u8(raw, 0x28));
        values.put("showSl", u8(raw, 0x29));
        values.put("showHcf", u8(raw, 0x2A));
        values.put("showHdf", u8(raw, 0x2B));
        values.put("tryUnit", u8(raw, 0x2C));

        String[] rangeNames = {"rangeCoolCl", "rangeCoolOp", "rangeDefrostCl", "rangeDefrostOp", "rangeStLcd", "rangeStLed"};
        for (int index = 0; index < rangeNames.length; index++) {
            values.put(rangeNames[index] + "Min", u16(raw, 0xAB + index * 2));
            values.put(rangeNames[index] + "Max", u16(raw, 0xB7 + index * 2));
        }
        values.put("showSt", u8(raw, 0xC3));
        values.put("stLcd", u8(raw, 0xC4));
        values.put("pinPassword", u16(raw, 0xC5));
        values.put("pinPeriod", u16(raw, 0xC7));
        values.put("pinBackupPassword", u16(raw, 0xCC));
        values.put("pinBackupPeriod", u16(raw, 0xD0));
        values.put("pinPeriodUnit", u8(raw, 0xD2));
        values.put("pinBackupPeriodUnit", u8(raw, 0xD3));
        values.put("pinEnable", u8(raw, 0xD4));
        values.put("pinPeriodShow", u8(raw, 0xD5));
        values.put("pinWrongLimit", u8(raw, 0xD6));
        return new Config(raw, values);
    }

    static byte[] encode(byte[] currentRaw, Map<String, Integer> values) {
        byte[] raw = currentRaw != null && currentRaw.length == SIZE
                ? Arrays.copyOf(currentRaw, SIZE)
                : new byte[SIZE];
        Map<String, Integer> merged = defaultValues();
        if (values != null) merged.putAll(values);

        int coolCl = number(merged, "coolCl", "Chạy lạnh CL", 1, 999);
        int coolOp = number(merged, "coolOp", "Chạy lạnh OP", 1, 999);
        int defrostCl = number(merged, "defrostCl", "Xả đá CL", 1, 999);
        int defrostOp = number(merged, "defrostOp", "Xả đá OP", 1, 999);
        int stLcd = number(merged, "stLcd", "ST LCD", 0, 90);
        int stLed = number(merged, "stLed", "ST LED", 0, 90);

        String[] rangeNames = {"rangeCoolCl", "rangeCoolOp", "rangeDefrostCl", "rangeDefrostOp", "rangeStLcd", "rangeStLed"};
        String[] rangeLabels = {"Chạy lạnh CL", "Chạy lạnh OP", "Xả đá CL", "Xả đá OP", "ST LCD", "ST LED"};
        int[] lowerBounds = {1, 1, 1, 1, 0, 0};
        int[] upperBounds = {999, 999, 999, 999, 90, 90};
        int[] timedValues = {coolCl, coolOp, defrostCl, defrostOp, stLcd, stLed};
        for (int index = 0; index < rangeNames.length; index++) {
            int min = number(merged, rangeNames[index] + "Min", rangeLabels[index] + " tối thiểu", lowerBounds[index], upperBounds[index]);
            int max = number(merged, rangeNames[index] + "Max", rangeLabels[index] + " tối đa", lowerBounds[index], upperBounds[index]);
            if (min > max) {
                throw new IllegalArgumentException(rangeLabels[index] + ": giá trị tối thiểu phải không lớn hơn tối đa.");
            }
            if (timedValues[index] < min || timedValues[index] > max) {
                throw new IllegalArgumentException(rangeLabels[index] + " phải nằm trong giới hạn Min / Max.");
            }
            putU16(raw, 0xAB + index * 2, min);
            putU16(raw, 0xB7 + index * 2, max);
        }

        raw[0x00] = 2;
        putU32(raw, 0x01, coolCl);
        putU32(raw, 0x05, coolOp);
        putU32(raw, 0x09, defrostCl);
        putU32(raw, 0x0D, defrostOp);
        raw[0x11] = (byte) number(merged, "delaySt", "Thời gian chờ ST", 1, 255);
        raw[0x12] = (byte) stLed;
        raw[0x13] = (byte) number(merged, "df", "Chế độ DF", 0, 1);
        raw[0x14] = (byte) number(merged, "end", "Chế độ END", 0, 1);
        raw[0x15] = (byte) number(merged, "coolStart", "Bắt đầu làm lạnh", 0, 1);
        raw[0x16] = (byte) number(merged, "defrostStart", "Bắt đầu xả đá", 0, 1);
        raw[0x17] = (byte) number(merged, "displayMode", "Kiểu màn hình", 0, 1);
        putU16(raw, 0x18, number(merged, "lockTime", "Tự khóa", 0, 999));
        raw[0x1A] = (byte) number(merged, "greenBrightness", "Độ sáng LED xanh", 0, 7);
        raw[0x1B] = (byte) number(merged, "redBrightness", "Độ sáng LED đỏ", 0, 7);
        raw[0x1C] = (byte) number(merged, "hcfMode", "Chế độ HCF", 0, 1);
        raw[0x1D] = (byte) number(merged, "hdf", "Chế độ HDF", 0, 1);
        raw[0x1E] = (byte) number(merged, "onTime", "Tỷ lệ thời gian R1/R2", 0, 3);
        raw[0x1F] = (byte) number(merged, "touch", "Số lần chạm", 0, 1);
        putU16(raw, 0x20, number(merged, "tryTime", "Thời gian thử lại", 0, 999));
        putU16(raw, 0x22, number(merged, "hcfTime", "Thời gian HCF OP", 1, 999));
        putU16(raw, 0x24, number(merged, "delayDf", "Trễ bật xả đá", 0, 90));
        raw[0x26] = (byte) number(merged, "defrostOpSeconds", "Xả đá OP tính giây", 0, 1);
        raw[0x27] = (byte) number(merged, "showDf", "Hiện DF", 0, 1);
        raw[0x28] = (byte) number(merged, "showEnd", "Hiện END", 0, 1);
        raw[0x29] = (byte) number(merged, "showSl", "Hiện SL", 0, 1);
        raw[0x2A] = (byte) number(merged, "showHcf", "Hiện HCF", 0, 1);
        raw[0x2B] = (byte) number(merged, "showHdf", "Hiện HDF", 0, 1);
        raw[0x2C] = (byte) number(merged, "tryUnit", "Đơn vị thời gian thử lại", 0, 1);
        raw[0xC3] = (byte) number(merged, "showSt", "Hiện ST LED", 0, 1);
        raw[0xC4] = (byte) stLcd;

        boolean pinEnabled = number(merged, "pinEnable", "Bật mật khẩu", 0, 1) == 1;
        Arrays.fill(raw, 0xC5, 0xD7, (byte) 0);
        if (pinEnabled) {
            putU16(raw, 0xC5, number(merged, "pinPassword", "Mật khẩu", 0, 999));
            putU16(raw, 0xC7, number(merged, "pinPeriod", "Chu kỳ mật khẩu", 0, 999));
            putU16(raw, 0xCC, number(merged, "pinBackupPassword", "Mật khẩu dự phòng", 0, 999));
            putU16(raw, 0xD0, number(merged, "pinBackupPeriod", "Thời gian dự phòng", 1, 999));
            raw[0xD2] = (byte) number(merged, "pinPeriodUnit", "Đơn vị chu kỳ", 0, 1);
            raw[0xD3] = (byte) number(merged, "pinBackupPeriodUnit", "Đơn vị dự phòng", 0, 1);
            raw[0xD4] = 1;
            raw[0xD5] = (byte) number(merged, "pinPeriodShow", "Hiện chỉnh chu kỳ", 0, 1);
            raw[0xD6] = (byte) number(merged, "pinWrongLimit", "Số lần nhập sai", 1, 99);
        }
        return raw;
    }

    static String toHex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.US, "%02X", value & 0xFF));
        return output.toString();
    }

    private static Map<String, Integer> defaultValues() {
        Map<String, Integer> values = new HashMap<>();
        values.put("coolCl", 5); values.put("coolOp", 5); values.put("defrostCl", 60); values.put("defrostOp", 6);
        values.put("delaySt", 70); values.put("stLed", 50); values.put("stLcd", 50); values.put("lockTime", 0);
        values.put("greenBrightness", 0); values.put("redBrightness", 0); values.put("tryTime", 0); values.put("tryUnit", 0);
        values.put("hcfTime", 10); values.put("delayDf", 0);
        values.put("df", 1); values.put("end", 1); values.put("coolStart", 1); values.put("defrostStart", 1);
        values.put("displayMode", 0); values.put("hcfMode", 1); values.put("hdf", 0); values.put("onTime", 0); values.put("touch", 0);
        values.put("defrostOpSeconds", 0); values.put("showDf", 0); values.put("showEnd", 0); values.put("showSl", 0);
        values.put("showHcf", 0); values.put("showHdf", 0); values.put("showSt", 0);
        String[] rangeNames = {"rangeCoolCl", "rangeCoolOp", "rangeDefrostCl", "rangeDefrostOp"};
        for (String name : rangeNames) { values.put(name + "Min", 1); values.put(name + "Max", 999); }
        values.put("rangeStLcdMin", 0); values.put("rangeStLcdMax", 90);
        values.put("rangeStLedMin", 0); values.put("rangeStLedMax", 90);
        values.put("pinEnable", 1); values.put("pinPassword", 123); values.put("pinWrongLimit", 3);
        values.put("pinPeriod", 0); values.put("pinPeriodUnit", 0); values.put("pinPeriodShow", 1);
        values.put("pinBackupPassword", 999); values.put("pinBackupPeriod", 1); values.put("pinBackupPeriodUnit", 0);
        return values;
    }

    private static int number(Map<String, Integer> values, String key, String label, int min, int max) {
        Integer boxed = values.get(key);
        int value = boxed == null ? 0 : boxed;
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + " phải từ " + min + " đến " + max + ".");
        }
        return value;
    }

    private static int u8(byte[] bytes, int offset) { return bytes[offset] & 0xFF; }
    private static int u16(byte[] bytes, int offset) { return (u8(bytes, offset) << 8) | u8(bytes, offset + 1); }
    private static int u32(byte[] bytes, int offset) {
        return (u8(bytes, offset) << 24) | (u8(bytes, offset + 1) << 16)
                | (u8(bytes, offset + 2) << 8) | u8(bytes, offset + 3);
    }
    private static void putU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }
    private static void putU32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
