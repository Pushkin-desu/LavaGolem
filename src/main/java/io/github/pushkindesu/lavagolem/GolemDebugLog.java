package io.github.pushkindesu.lavagolem;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns {@code plugins/LavaGolem/golemdebug.log}, the file half of /golemdebug's output. This exists
 * because a courier under trace produces several lines per logic tick, and doing that write on the
 * main thread would reintroduce exactly the kind of tick-loop lag the navigation rewrite just fixed.
 *
 * The split is deliberate: {@link #enqueue} is the only thing GolemTicker (main thread) ever calls,
 * and it only ever pushes an already-formatted String onto a {@link ConcurrentLinkedQueue} — no file
 * handle, no world/entity lookup, nothing that isn't safe to do on a thread with no Bukkit context.
 * Every actual file operation (opening, writing, rotating) happens on the repeating async task
 * started by {@link #start}, which touches nothing but this queue, a File and a BufferedWriter.
 */
public final class GolemDebugLog {

    /** Above this size the file is rotated to golemdebug.log.old rather than left to grow forever —
     *  a courier under trace can produce megabytes per hour, and nobody wants that unbounded. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** Hard cap on lines waiting to be written. Past this, new lines are dropped (and counted) rather
     *  than letting the queue grow without bound if the disk ever falls behind the trace volume. */
    private static final int QUEUE_CAP = 10_000;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final LavaGolemPlugin plugin;
    private final File logFile;
    private final File oldFile;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    /** Mirrors the queue's length without paying ConcurrentLinkedQueue#size()'s O(n) cost on every
     *  single enqueue — this is checked on the main thread for every traced line. */
    private final AtomicInteger queueSize = new AtomicInteger();

    /** Lines dropped since the last flush, reported as one summary line per flush rather than one
     *  warning per drop (which would itself become part of the flood it's warning about). */
    private final AtomicInteger droppedSinceFlush = new AtomicInteger();

    private BufferedWriter writer;
    private BukkitTask task;

    /** Set as the very first step of {@link #shutdown}. Checked (unsynchronized, so it's only a fast
     *  pre-filter) by the periodic task before it bothers taking the lock at all — the real guarantee
     *  against writing after close is that shutdown's flush-then-close happens as ONE synchronized
     *  call (see {@link #drainAndClose}), so even a periodic run that slipped past this check and is
     *  already queued for the lock can only ever run entirely before or entirely after it, never in
     *  the gap between draining and closing. */
    private volatile boolean closed;

    public GolemDebugLog(LavaGolemPlugin plugin) {
        this.plugin = plugin;
        File dir = plugin.getDataFolder();
        this.logFile = new File(dir, "golemdebug.log");
        this.oldFile = new File(dir, "golemdebug.log.old");
    }

    /** Timestamp shared by every line this file ever writes — trace lines from GolemTicker and the
     *  on/off markers from the /golemdebug command alike — so the "HH:mm:ss" format is defined once. */
    public static String timestamp() {
        return TIME_FMT.format(LocalTime.now());
    }

    /** Starts the periodic drain. Cheap to run even in a session where file output is never actually
     *  used (chat-only): an empty queue flushes to nothing once a second. */
    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!closed) drain();
        }, 20L, 20L);
    }

    /** Enqueues one fully-formatted line. Called only from the main thread (GolemTicker's gdebug and
     *  LavaGolemPlugin's toggle markers); never touches the file itself, so it can never block the
     *  tick loop on disk I/O — that's the whole reason this class exists. */
    public void enqueue(String line) {
        if (queueSize.get() >= QUEUE_CAP) {
            droppedSinceFlush.incrementAndGet();
            return;
        }
        queue.add(line);
        queueSize.incrementAndGet();
    }

    /** Cancels the periodic task and does one last synchronous drain-then-close so the tail of a
     *  trace isn't lost just because the server stopped — a bad debugging experience the maintainer
     *  specifically asked not to have. Safe to call even if tracing was never switched on this
     *  session: an empty queue and a never-opened writer both no-op cleanly. */
    public void shutdown() {
        closed = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        drainAndClose();
    }

    /** Normal periodic path: drain whatever's queued, leaving the writer open for next time. Runs on
     *  the async task's own thread. Deliberately free of any Bukkit world/entity API — every String
     *  here was already fully resolved on the main thread before it was queued, which is what makes
     *  running this off the main thread safe at all. */
    private synchronized void drain() {
        // Re-checked here (not just by the caller in start()) because a run can pass that first check
        // and then queue up waiting for this method's lock while drainAndClose() is mid-flight on the
        // main thread; without this second check it would reopen the writer drainAndClose just closed.
        if (closed) return;
        try {
            ensureWriterOpen();
            writeQueued();
        } catch (IOException e) {
            plugin.getLogger().warning("[LG] golemdebug.log write failed: " + e);
        }
    }

    /** Shutdown path: drain and close the writer under the SAME lock acquisition, so a periodic
     *  {@link #drain} that already slipped past the {@code closed} check can never land in between —
     *  it either completes first (writer still gets closed right after) or waits for this whole
     *  method to finish (by which point the writer is already gone and it opens nothing new, since
     *  {@link #ensureWriterOpen} only runs from inside drain/drainAndClose, both gated the same way). */
    private synchronized void drainAndClose() {
        try {
            // Skip creating the file at all if it was never opened this session AND nothing is
            // queued -- a server that never once ran /golemdebug with file output shouldn't gain an
            // empty golemdebug.log on every restart just because shutdown always runs. A writer that
            // WAS opened still gets flushed and closed below regardless, same as the periodic path.
            if (writer != null || !queue.isEmpty()) {
                ensureWriterOpen();
                writeQueued();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[LG] golemdebug.log write failed: " + e);
        }
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[LG] golemdebug.log close failed: " + e);
        }
    }

    /** Drains the queue into the (already-open) writer and rotates if that pushed the file over the
     *  cap. Assumes the caller already holds the lock and has called {@link #ensureWriterOpen}. */
    private void writeQueued() throws IOException {
        String line;
        int written = 0;
        while ((line = queue.poll()) != null) {
            queueSize.decrementAndGet();
            writer.write(line);
            writer.newLine();
            written++;
        }
        int dropped = droppedSinceFlush.getAndSet(0);
        if (dropped > 0) {
            writer.write("[" + timestamp() + "] [queue full, dropped " + dropped + " trace line(s) since last flush]");
            writer.newLine();
            written++;
        }
        if (written > 0) {
            writer.flush();
            rotateIfOversize(); // re-check after writing, not just before opening
        }
    }

    private void ensureWriterOpen() throws IOException {
        if (writer != null) return;
        plugin.getDataFolder().mkdirs();
        rotateIfOversize(); // catches a file that grew past the cap while nothing was writing to it
        writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
    }

    /** Renames the current file to golemdebug.log.old (replacing any previous one) once it passes
     *  {@value MAX_BYTES} bytes, so the next write starts a fresh file. Closing the writer first is
     *  what makes the rename safe on Windows, which — unlike POSIX — refuses to rename a file that's
     *  still open for writing. */
    private void rotateIfOversize() {
        if (logFile.length() <= MAX_BYTES) return;
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
            Files.move(logFile.toPath(), oldFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("[LG] golemdebug.log rotation failed: " + e);
        }
    }
}
