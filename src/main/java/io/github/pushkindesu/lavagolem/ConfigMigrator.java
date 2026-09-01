package io.github.pushkindesu.lavagolem;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds config keys that ship in the plugin's bundled default config.yml but are missing from a
 * server owner's on-disk copy -- exactly what {@code saveDefaultConfig()} deliberately does NOT do
 * once the file already exists, which otherwise means every option this plugin adds stays invisible
 * to an existing install until someone deletes config.yml by hand and loses their own tuning.
 *
 * This works by APPENDING raw text, never by re-serialising the file. config.yml here is not a bare
 * key list, it is heavily commented prose that teaches the mechanics it tunes and is one of this
 * plugin's actual features -- the usual {@code copyDefaults(true)} + {@code saveConfig()} trick
 * round-trips the whole file through YAML and risks reflowing that prose, reordering keys, and
 * silently dropping whatever comments the owner added themselves. The file that exists on disk is
 * therefore only ever read and appended to here, never rewritten in place, and a backup is taken
 * first regardless.
 *
 * Call this AFTER {@code saveDefaultConfig()} (so a brand-new install already has a complete file
 * and this is a same-instant no-op) and BEFORE {@code reloadConfig()} + constructing
 * {@link PluginConfig} (so the newly appended keys are actually visible by the time anything reads
 * them) -- see LavaGolemPlugin#onEnable for the wiring.
 */
public final class ConfigMigrator {
    private ConfigMigrator() {}

    /** A top-level key line: starts at column 0 (every nested/continuation line in this file is
     *  indented), an identifier, a colon, then either its value or end of line. Deliberately does not
     *  match list items ("  - ...", indented) or comments (start with '#'), so it only ever fires on
     *  real top-level keys. */
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z0-9_.\\-]+):(?:\\s|$)");

    /** One occurrence of a top-level key in the raw default text, and the line it starts on. */
    private record KeyOccurrence(String key, int line) { }

    public static void migrate(LavaGolemPlugin plugin) {
        try {
            run(plugin);
        } catch (Exception e) {
            // A migration bug must never block startup: every value this plugin reads already has a
            // code-side default (see PluginConfig), so it runs correctly regardless -- just without
            // the new keys visible on disk until the owner sorts out whatever this warning names.
            plugin.getLogger().warning("Config migration skipped (" + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "") + "); starting with config.yml as-is.");
        }
    }

    private static void run(LavaGolemPlugin plugin) throws IOException, InvalidConfigurationException {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.isFile()) return; // saveDefaultConfig() just wrote a fresh, fully up-to-date copy

        List<String> defaultLines;
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return; // no bundled default to migrate from -- shouldn't happen in a real build
            defaultLines = readLines(in);
        }

        // Read the default twice, as intended: once through YamlConfiguration for the authoritative
        // key SET (it understands quoting/typing correctly, which a regex over raw text can't be
        // trusted to), once as plain text so the comment blocks can be sliced out verbatim below.
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString(String.join("\n", defaultLines));
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.load(configFile);

        Set<String> defaultKeys = defaults.getKeys(false);
        Set<String> diskKeys = onDisk.getKeys(false);

        // Never delete or rewrite a key the owner has that this version doesn't ship -- it may be
        // genuinely obsolete, or it may be a key whose MEANING changed (nav-starved-retries did,
        // earlier on this branch) where silently keeping the old value under a new meaning would be
        // far worse than just telling the owner to look at it themselves.
        List<String> obsolete = new ArrayList<>();
        for (String key : diskKeys) if (!defaultKeys.contains(key)) obsolete.add(key);
        if (!obsolete.isEmpty()) {
            Collections.sort(obsolete);
            plugin.getLogger().info("config.yml has key(s) this version no longer uses (left exactly as "
                    + "they are -- tidy them up yourself if you like): " + String.join(", ", obsolete));
        }

        // Walk the raw default text once, in FILE order, recording where every top-level key starts.
        // This is what lets a missing key's own comment block be found (bounded by its neighbours on
        // either side) and lets the appended chunk read in the same order the shipped file uses.
        List<KeyOccurrence> occurrences = new ArrayList<>();
        for (int i = 0; i < defaultLines.size(); i++) {
            Matcher m = TOP_LEVEL_KEY.matcher(defaultLines.get(i));
            if (m.find()) occurrences.add(new KeyOccurrence(m.group(1), i));
        }

        List<String> added = new ArrayList<>();
        List<String> appendLines = new ArrayList<>();
        for (int idx = 0; idx < occurrences.size(); idx++) {
            KeyOccurrence occ = occurrences.get(idx);
            // The regex is a heuristic; only trust it for keys YamlConfiguration itself also thinks
            // are real top-level keys, and only act on ones actually missing on disk.
            if (!defaultKeys.contains(occ.key()) || diskKeys.contains(occ.key())) continue;

            int nextKeyLine = idx + 1 < occurrences.size() ? occurrences.get(idx + 1).line() : defaultLines.size();
            int blockStart = commentBlockStart(defaultLines, occ.line());
            int blockEnd = valueBlockEnd(defaultLines, occ.line(), nextKeyLine);
            appendLines.addAll(defaultLines.subList(blockStart, blockEnd));
            added.add(occ.key());
        }

        if (added.isEmpty()) return; // nothing missing -- no noise on a normal start

        // Cheap insurance against a bug in the slicing above eating something the owner actually
        // tuned: back up the file about to be appended to before touching it at all.
        Path original = configFile.toPath();
        Files.copy(original, original.resolveSibling("config.yml.bak"), StandardCopyOption.REPLACE_EXISTING);

        try (BufferedWriter w = Files.newBufferedWriter(original, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            w.write("");
            w.newLine();
            w.write("# --- Added by LavaGolem " + plugin.getDescription().getVersion() + " ---");
            w.newLine();
            for (String line : appendLines) {
                w.write(line);
                w.newLine();
            }
        }

        plugin.getLogger().info("config.yml was missing " + added.size()
                + " key(s); added them with their default values (existing file backed up first, as "
                + "config.yml.bak): " + String.join(", ", added));
    }

    /** Walks backward from a key's own line, collecting the contiguous run of non-blank comment lines
     *  immediately above it. Stops the moment it hits anything that isn't a comment -- a blank line, or
     *  the previous key's own value line -- so a key never drags along the previous key's explanation
     *  (adjacent keys with no blank line between them are common in this file, e.g. two one-line
     *  settings back to back, and each keeps only its own comment because the boundary is simply "the
     *  first non-comment line above", which is always either the blank separator or the prior key). */
    private static int commentBlockStart(List<String> lines, int keyLine) {
        int i = keyLine;
        while (i > 0 && isCommentLine(lines.get(i - 1))) i--;
        return i;
    }

    /** From just after a key's own line up to (but never past) the next top-level key, collects any
     *  indented continuation lines (block-style list items, nested values) that are still part of
     *  THIS key's value. A plain scalar key has none, so this is a no-op for every key in this config
     *  today apart from the one flat exception (fisher-custom-catches) called out in the class-level
     *  design note above. */
    private static int valueBlockEnd(List<String> lines, int keyLine, int hardLimit) {
        int i = keyLine + 1;
        while (i < hardLimit && isContinuation(lines.get(i))) i++;
        return i;
    }

    private static boolean isCommentLine(String line) {
        return line.stripLeading().startsWith("#");
    }

    private static boolean isContinuation(String line) {
        return !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }

    private static List<String> readLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return lines;
    }
}
