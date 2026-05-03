package io.github.pushkindesu.lavagolem;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Messages {

    private final Map<String, String> map = new HashMap<>();

    public Messages(LavaGolemPlugin plugin) {
        String locale = plugin.cfg.locale;
        loadResource(plugin, "messages_" + locale + ".yml");
        // Fallback to English for any missing keys
        if (!"en".equals(locale)) {
            loadResourceFallback(plugin, "messages_en.yml");
        }
    }

    private void loadResource(LavaGolemPlugin plugin, String resource) {
        try (var in = plugin.getResource(resource)) {
            if (in == null) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String k : y.getKeys(false)) {
                map.put(k, y.getString(k));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load " + resource + ": " + e.getMessage());
        }
    }

    private void loadResourceFallback(LavaGolemPlugin plugin, String resource) {
        try (var in = plugin.getResource(resource)) {
            if (in == null) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String k : y.getKeys(false)) {
                map.putIfAbsent(k, y.getString(k));
            }
        } catch (Exception ignored) {}
    }

    public String get(String key) {
        return map.getOrDefault(key, key);
    }

    public String get(String key, Map<String, String> placeholders) {
        String s = get(key);
        for (var e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }
}
