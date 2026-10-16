package com.mediverify.app.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Med {
    public long id;
    public String name = "";
    public String dose = "";          // e.g. "500 mg"
    public String form = "Tablet";    // Tablet / Kapsul / Kaplet
    public int pillsPerDose = 1;
    public int color = -1;            // index in PillProfile.COLORS
    public int shape = -1;            // index in PillProfile.SHAPES
    public String instructions = "";  // e.g. "Sesudah makan"
    public String times = "08:00";    // comma separated HH:mm, sorted
    public long startDate;            // millis at local midnight
    public long endDate;              // 0 = no end
    public int stock = -1;            // -1 unknown
    public boolean active = true;
    public boolean requireWater = true;
    public String refProfile;         // serialised PillProfile (null = not enrolled)
    public String refPhoto;           // absolute path of reference photo
    public long createdAt;

    public List<int[]> timeList() {
        List<int[]> out = new ArrayList<>();
        if (times == null) return out;
        for (String t : times.split(",")) {
            t = t.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split(":");
            try {
                int h = Integer.parseInt(p[0].trim()), m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
                if (h >= 0 && h < 24 && m >= 0 && m < 60) out.add(new int[]{h, m});
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out, (a, b) -> a[0] * 60 + a[1] - (b[0] * 60 + b[1]));
        return out;
    }

    public static String formatTimes(List<int[]> l) {
        StringBuilder sb = new StringBuilder();
        for (int[] t : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "%02d:%02d", t[0], t[1]));
        }
        return sb.toString();
    }

    public String doseLabel() {
        String d = dose == null || dose.isEmpty() ? "" : dose + " • ";
        return d + pillsPerDose + " " + form.toLowerCase(java.util.Locale.ROOT);
    }

    public int dosesPerDay() {
        return Math.max(1, timeList().size());
    }
}
