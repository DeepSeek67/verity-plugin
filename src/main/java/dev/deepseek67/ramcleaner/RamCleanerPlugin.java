package dev.deepseek67.ramcleaner;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RamCleaner - Paper 1.21.11 memory-pressure optimizer.
 *
 * Important: Java does not provide a truthful API that can guarantee a specific
 * number of bytes will be returned to the operating system. This plugin never
 * invents a freed-RAM number. /ramclean performs real server-side cleanup first
 * (safe unused chunk unloading), then requests GC and measures the heap again.
 */
public final class RamCleanerPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final long AI_PERIOD_TICKS = 10L;
    private static final int CHUNKS_PER_TICK = 128;
    private static final int GC_PASSES = 4;
    private static final long MB = 1024L * 1024L;

    private final Map<Mob, Boolean> originalAware =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicBoolean cleaning = new AtomicBoolean(false);
    private BukkitTask aiTask;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ramclean")).setExecutor(this);
        Objects.requireNonNull(getCommand("ramstatus")).setExecutor(this);

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                rememberMob(entity);
            }
        }

        aiTask = Bukkit.getScheduler().runTaskTimer(
                this, this::updateMobAI, AI_PERIOD_TICKS, AI_PERIOD_TICKS);
        getLogger().info("RamCleaner enabled: safe chunk reclamation + adaptive mob AI + measured GC.");
    }

    @Override
    public void onDisable() {
        if (aiTask != null) {
            aiTask.cancel();
        }
        restoreMobAI();
        originalAware.clear();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        rememberMob(event.getEntity());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            rememberMob(entity);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        originalAware.remove(event.getEntity());
    }

    private void rememberMob(Entity entity) {
        if (entity instanceof Mob mob && mob.isValid()) {
            originalAware.putIfAbsent(mob, mob.isAware());
        }
    }

    /** Disables mob AI when no online player can currently see the mob. */
    private void updateMobAI() {
        synchronized (originalAware) {
            Iterator<Map.Entry<Mob, Boolean>> iterator = originalAware.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Mob, Boolean> entry = iterator.next();
                Mob mob = entry.getKey();

                if (mob == null || !mob.isValid() || mob.isDead()) {
                    iterator.remove();
                    continue;
                }

                boolean visibleToPlayer = false;
                for (Player player : mob.getTrackedBy()) {
                    if (player.isOnline() && !player.isDead() && player.hasLineOfSight(mob)) {
                        visibleToPlayer = true;
                        break;
                    }
                }

                if (!visibleToPlayer) {
                    if (mob.isAware()) {
                        mob.setAware(false);
                    }
                } else if (!mob.isAware()) {
                    mob.setAware(entry.getValue());
                }
            }
        }
    }

    private void restoreMobAI() {
        synchronized (originalAware) {
            for (Map.Entry<Mob, Boolean> entry : originalAware.entrySet()) {
                Mob mob = entry.getKey();
                if (mob != null && mob.isValid()) {
                    mob.setAware(entry.getValue());
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ramstatus")) {
            sendStatus(sender);
            return true;
        }
        if (command.getName().equalsIgnoreCase("ramclean")) {
            startCleanup(sender);
            return true;
        }
        return false;
    }

    private void startCleanup(CommandSender sender) {
        if (!cleaning.compareAndSet(false, true)) {
            sender.sendMessage(ChatColor.RED + "[RamCleaner] A cleanup is already running.");
            return;
        }

        final MemorySnapshot before = MemorySnapshot.capture();
        final long collectionsBefore = totalCollections();
        final long startNanos = System.nanoTime();
        final CleanupStats stats = new CleanupStats();

        sender.sendMessage(ChatColor.AQUA + "[RamCleaner] " + ChatColor.GRAY
                + "Aggressive cleanup started: unloading every safe unused loaded chunk, then forcing measured GC passes...");

        // Chunk unloading and Bukkit world access stay on the server thread.
        Bukkit.getScheduler().runTask(this, () -> processChunkCleanup(sender, before, collectionsBefore, startNanos, stats));
    }

    private void processChunkCleanup(CommandSender sender, MemorySnapshot before,
                                     long collectionsBefore, long startNanos, CleanupStats stats) {
        int processed = 0;

        for (World world : Bukkit.getWorlds()) {
            Chunk[] loaded = world.getLoadedChunks();
            for (Chunk chunk : loaded) {
                if (processed >= CHUNKS_PER_TICK) {
                    break;
                }
                processed++;
                stats.loadedSeen++;

                if (canUnload(world, chunk)) {
                    try {
                        if (world.unloadChunk(chunk)) {
                            stats.unloaded++;
                        } else {
                            stats.unloadFailed++;
                        }
                    } catch (Throwable throwable) {
                        stats.unloadFailed++;
                    }
                } else {
                    stats.protectedChunks++;
                }
            }
            if (processed >= CHUNKS_PER_TICK) {
                break;
            }
        }

        // More chunks may remain. Continue on the next tick without blocking the server.
        if (hasSafeUnloadCandidates()) {
            Bukkit.getScheduler().runTask(this,
                    () -> processChunkCleanup(sender, before, collectionsBefore, startNanos, stats));
            return;
        }

        // All safe candidates have been handled. GC requests are deliberately outside the tick thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> finishGarbageCollection(
                sender, before, collectionsBefore, startNanos, stats));
    }

    private boolean canUnload(World world, Chunk chunk) {
        // Never unload a chunk visible to a player.
        if (!world.getPlayersSeeingChunk(chunk).isEmpty()) {
            return false;
        }
        // Respect force-loaded chunks and plugin chunk tickets.
        if (chunk.isForceLoaded() || !chunk.getPluginChunkTickets().isEmpty()) {
            return false;
        }
        return world.isChunkLoaded(chunk);
    }

    private boolean hasSafeUnloadCandidates() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (canUnload(world, chunk)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void finishGarbageCollection(CommandSender sender, MemorySnapshot before,
                                         long collectionsBefore, long startNanos, CleanupStats stats) {
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            for (int i = 0; i < GC_PASSES; i++) {
                memoryBean.gc();
                System.gc();
                pause(350L);
            }

            MemorySnapshot after = MemorySnapshot.capture();
            long freed = Math.max(0L, before.usedHeap() - after.usedHeap());
            long committedDrop = Math.max(0L, before.committedHeap() - after.committedHeap());
            long collections = Math.max(0L, totalCollections() - collectionsBefore);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            Bukkit.getScheduler().runTask(this, () -> {
                sender.sendMessage(ChatColor.AQUA + "[RamCleaner] " + ChatColor.GREEN + "Cleanup complete.");
                sender.sendMessage(ChatColor.WHITE + "Actual measured heap freed: "
                        + ChatColor.GREEN + formatBytes(freed));
                sender.sendMessage(ChatColor.WHITE + "Committed heap reduced: "
                        + ChatColor.GREEN + formatBytes(committedDrop));
                sender.sendMessage(ChatColor.WHITE + "Loaded chunks unloaded: "
                        + ChatColor.GREEN + stats.unloaded);
                sender.sendMessage(ChatColor.WHITE + "Loaded chunks inspected: "
                        + ChatColor.AQUA + stats.loadedSeen);
                sender.sendMessage(ChatColor.WHITE + "Chunks protected from unloading: "
                        + ChatColor.YELLOW + stats.protectedChunks);
                sender.sendMessage(ChatColor.WHITE + "Unload failures: "
                        + ChatColor.YELLOW + stats.unloadFailed);
                sender.sendMessage(ChatColor.WHITE + "GC collections observed: "
                        + ChatColor.AQUA + collections);
                sender.sendMessage(ChatColor.WHITE + "Current used heap: "
                        + ChatColor.AQUA + formatBytes(after.usedHeap())
                        + ChatColor.GRAY + " / " + formatBytes(after.maxHeap()));
                sender.sendMessage(ChatColor.WHITE + "Cleanup duration: "
                        + ChatColor.AQUA + elapsedMs + " ms");
                if (freed == 0L) {
                    sender.sendMessage(ChatColor.YELLOW
                            + "[RamCleaner] 0 B was actually reclaimed from the measured heap. No fake RAM number was reported.");
                    sender.sendMessage(ChatColor.GRAY
                            + "Chunks were still unloaded where safe; the JVM may retain committed memory for future allocations.");
                }
                cleaning.set(false);
            });
        } catch (Throwable throwable) {
            getLogger().warning("Cleanup failed: " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
            Bukkit.getScheduler().runTask(this, () -> {
                sender.sendMessage(ChatColor.RED + "[RamCleaner] Cleanup failed safely; no fake value was reported.");
                cleaning.set(false);
            });
        }
    }

    private void sendStatus(CommandSender sender) {
        MemorySnapshot memory = MemorySnapshot.capture();
        long used = memory.usedHeap();
        long committed = memory.committedHeap();
        long max = memory.maxHeap();
        int tracked = 0;
        int throttled = 0;
        int loadedChunks = 0;

        synchronized (originalAware) {
            for (Map.Entry<Mob, Boolean> entry : originalAware.entrySet()) {
                Mob mob = entry.getKey();
                if (mob != null && mob.isValid() && !mob.isDead()) {
                    tracked++;
                    if (!mob.isAware()) {
                        throttled++;
                    }
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            loadedChunks += world.getLoadedChunks().length;
        }

        sender.sendMessage(ChatColor.AQUA + "━━━━━━━━ RamCleaner Status ━━━━━━━━");
        sender.sendMessage(ChatColor.WHITE + "Live used heap: " + ChatColor.AQUA + formatBytes(used));
        sender.sendMessage(ChatColor.WHITE + "Allocated/committed heap: " + ChatColor.AQUA + formatBytes(committed));
        sender.sendMessage(ChatColor.WHITE + "Free inside committed heap: " + ChatColor.AQUA
                + formatBytes(Math.max(0L, committed - used)));
        sender.sendMessage(ChatColor.WHITE + "Free until -Xmx: " + ChatColor.AQUA
                + formatBytes(Math.max(0L, max - used)));
        sender.sendMessage(ChatColor.WHITE + "Max heap (-Xmx): " + ChatColor.AQUA + formatBytes(max));
        sender.sendMessage(ChatColor.WHITE + "Loaded chunks: " + ChatColor.AQUA + loadedChunks);
        sender.sendMessage(ChatColor.WHITE + "Tracked mobs: " + ChatColor.AQUA + tracked);
        sender.sendMessage(ChatColor.WHITE + "AI currently throttled: " + ChatColor.AQUA + throttled);
        sender.sendMessage(ChatColor.WHITE + "Explicit GC disabled: " + ChatColor.AQUA + explicitGcDisabled());
        sender.sendMessage(ChatColor.AQUA + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private static long totalCollections() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    private static boolean explicitGcDisabled() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .anyMatch(arg -> arg.equalsIgnoreCase("-XX:+DisableExplicitGC"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < MB) {
            return bytes + " B";
        }
        double value = bytes / (double) MB;
        if (value < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MB", value);
        }
        return String.format(Locale.ROOT, "%.2f GB", value / 1024.0);
    }

    private static final class CleanupStats {
        private int loadedSeen;
        private int unloaded;
        private int protectedChunks;
        private int unloadFailed;
    }

    private record MemorySnapshot(long usedHeap, long committedHeap, long maxHeap) {
        private static MemorySnapshot capture() {
            Runtime runtime = Runtime.getRuntime();
            long committed = runtime.totalMemory();
            long used = Math.max(0L, committed - runtime.freeMemory());
            return new MemorySnapshot(used, committed, runtime.maxMemory());
        }
    }
}
