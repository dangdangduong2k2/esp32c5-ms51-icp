package vn.duong.ms51control;

/** Observes the existing minute-only telemetry; does not infer phase from relays. */
final class CountdownTracker {
    private int green = -1, red = -1, active;
    private long changedAt;
    private String mode;
    void reset() { green = red = -1; active = 0; changedAt = 0; mode = null; }
    int update(String nextMode, String greenText, String redText, long now) {
        int g = number(greenText), r = number(redText);
        if (!("COOL".equals(nextMode) || "ICE_FLUSH".equals(nextMode)) || g < 0 || r < 0) {
            reset(); return 0;
        }
        if (!nextMode.equals(mode)) { reset(); mode = nextMode; }
        if (green >= 0 && red >= 0) {
            if (g < green && r == red) { active = 1; changedAt = now; }
            else if (r < red && g == green) { active = 2; changedAt = now; }
            else if (g != green || r != red) { active = 0; }
        }
        green = g; red = r;
        // A fresh sample is required separately; minute-only data expires after one step.
        if (now - changedAt > 70000 || (active == 1 && g == 0) || (active == 2 && r == 0)) active = 0;
        return active;
    }
    private static int number(String value) {
        if (value == null || !value.matches("[0-9]{1,3}")) return -1;
        return Integer.parseInt(value);
    }
}
