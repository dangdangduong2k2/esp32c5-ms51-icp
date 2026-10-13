package vn.duong.ms51control;

/** Uses the MS51 timer state, including the first sample before any digit changes. */
final class CountdownTracker {
    int update(String mode, String active, String green, String red, String paused) {
        if (!("COOL".equals(mode) || "ICE_FLUSH".equals(mode)) || "ON".equals(paused)) return 0;
        if ("GREEN".equals(active) && numeric(green)) return 1;
        if ("RED".equals(active) && numeric(red)) return 2;
        return 0;
    }
    private static boolean numeric(String value) {
        return value != null && value.matches("[0-9]{1,3}");
    }
}
