package io.github.pushkindesu.lavagolem;

import org.bukkit.Material;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One courier route: move items tagged {@code source} to {@code dest}, honoring an item filter.
 * A courier owns a list of these; they are serialized into a single PDC string.
 *
 * Serialization: routes joined by ';', fields by '|'. The tag strings are Base64-encoded so any
 * player-typed tag text is safe against the delimiters; Material names ([A-Z0-9_]) are delimiter-safe.
 */
public final class CourierRoute {

    public String source = "";
    public String dest = "";
    /** false = whitelist (carry only filter items), true = blacklist (carry all except them).
     *  Defaults to blacklist so a fresh, empty filter carries everything. */
    public boolean blacklist = true;
    public final Set<Material> filter = new LinkedHashSet<>();

    public CourierRoute() {}

    public CourierRoute(String source, String dest) {
        this.source = source;
        this.dest = dest;
    }

    /** Whether this route may carry {@code m}. An empty filter carries everything. */
    public boolean carries(Material m) {
        if (filter.isEmpty()) return true;
        return blacklist != filter.contains(m);
    }

    public boolean isConfigured() {
        return source != null && !source.isEmpty() && dest != null && !dest.isEmpty();
    }

    private static String enc(String s) {
        return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String s) {
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public static String serialize(List<CourierRoute> routes) {
        StringBuilder sb = new StringBuilder();
        for (CourierRoute r : routes) {
            if (sb.length() > 0) sb.append(';');
            StringBuilder mats = new StringBuilder();
            for (Material m : r.filter) {
                if (mats.length() > 0) mats.append(',');
                mats.append(m.name());
            }
            sb.append(enc(r.source)).append('|')
              .append(enc(r.dest)).append('|')
              .append(r.blacklist ? '1' : '0').append('|')
              .append(mats);
        }
        return sb.toString();
    }

    public static List<CourierRoute> parse(String s) {
        List<CourierRoute> routes = new ArrayList<>();
        if (s == null || s.isEmpty()) return routes;
        for (String rec : s.split(";", -1)) {
            if (rec.isEmpty()) continue;
            String[] f = rec.split("\\|", -1);
            CourierRoute r = new CourierRoute();
            if (f.length > 0) r.source = dec(f[0]);
            if (f.length > 1) r.dest = dec(f[1]);
            if (f.length > 2) r.blacklist = "1".equals(f[2]);
            if (f.length > 3 && !f[3].isEmpty()) {
                for (String mn : f[3].split(",")) {
                    try { r.filter.add(Material.valueOf(mn)); }
                    catch (IllegalArgumentException ignored) { /* dropped renamed/removed material */ }
                }
            }
            routes.add(r);
        }
        return routes;
    }
}
