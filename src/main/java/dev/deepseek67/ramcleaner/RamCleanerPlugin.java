package dev.deepseek67.ramcleaner;

import org.bukkit.Bukkit;
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
import org.bukkit.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RamCleaner - lightweight Paper server memory/AI optimizer.
 *
 * Java cannot guarantee that a requested GC reclaims a particular number of
 * bytes. This plugin therefore reports only measured heap reduction and never
 * invents a freed-memory value.
 */
public final class RamCleanerPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final long AI_PERIOD_TICKS = 10L;
    private static final long MB = 1024L * 1024L;

    /* Weak keys ensure the plugin does not keep unloaded mobs alive. */
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
        getLogger().info("RamCleaner enabled: adaptive mob AI + measured heap cleanup.");
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

    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        originalAware.remove(event.getEntity());
    }

    private void rememberMob(Entity entity) {
        if (entity instanceof Mob mob && mob.isValid()) {
            originalAware.putIfAbsent(mob, mob.isAware());
        }
    }

    /**
     * Only lets a mob stay aware when a currently tracking player can actually
     * see it. Paper's tracking set avoids a full mobs x players distance scan.
     */
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

        MemorySnapshot before = MemorySnapshot.capture();
        long collectionsBefore = totalCollections();
        sender.sendMessage(ChatColor.AQUA + "[RamCleaner] " + ChatColor.GRAY
                + "Starting aggressive measured cleanup...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // Three explicit requests are intentionally aggressive, but the JVM
                // is still free to decide whether and when to collect.
                System.gc();
                pause(150L);
                System.gc();
                pause(250L);
                System.gc();
                pause(400L);

                MemorySnapshot after = MemorySnapshot.capture();
                long freed = Math.max(0L, before.usedHeap() - after.usedHeap());
                long committedDrop = Math.max(0L, before.committedHeap() - after.committedHeap());
                long collections = Math.max(0L, totalCollections() - collectionsBefore);

                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(ChatColor.AQUA + "[RamCleaner] " + ChatColor.GREEN
                            + "Cleanup complete.");
                    sender.sendMessage(ChatColor.WHITE + "Actual measured heap freed: "
                            + ChatColor.GREEN + formatBytes(freed));
                    sender.sendMessage(ChatColor.WHITE + "Committed heap reduced: "
                            + ChatColor.GREEN + formatBytes(committedDrop));
                    sender.sendMessage(ChatColor.WHITE + "GC collections observed: "
                            + ChatColor.AQUA + collections);
                    sender.sendMessage(ChatColor.WHITE + "Current used heap: "
                            + ChatColor.AQUA + formatBytes(after.usedHeap())
                            + ChatColor.GRAY + " / " + formatBytes(after.maxHeap()));
                    if (freed == 0L) {
                        sender.sendMessage(ChatColor.YELLOW
                                + "[RamCleaner] 0 B was reclaimed. No memory was faked.");
                    }
                    cleaning.set(false);
                });
            } catch (Throwable throwable) {
                getLogger().warning("Cleanup failed: " + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(ChatColor.RED
                            + "[RamCleaner] Cleanup failed safely; no fake value was reported.");
                    cleaning.set(false);
                });
            }
        });
    }

    private void sendStatus(CommandSender sender) {
        MemorySnapshot memory = MemorySnapshot.capture();
        long used = memory.usedHeap();
        long committed = memory.committedHeap();
        long max = memory.maxHeap();
        int tracked = 0;
        int throttled = 0;

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

        sender.sendMessage(ChatColor.AQUA + "━━━━━━━━ RamCleaner Status ━━━━━━━━");
        sender.sendMessage(ChatColor.WHITE + "Live used RAM: " + ChatColor.AQUA + formatBytes(used));
        sender.sendMessage(ChatColor.WHITE + "Allocated/committed RAM: " + ChatColor.AQUA
                + formatBytes(committed));
        sender.sendMessage(ChatColor.WHITE + "Free RAM in allocated heap: " + ChatColor.AQUA
                + formatBytes(Math.max(0L, committed - used)));
        sender.sendMessage(ChatColor.WHITE + "Free RAM until -Xmx: " + ChatColor.AQUA
                + formatBytes(Math.max(0L, max - used)));
        sender.sendMessage(ChatColor.WHITE + "Max RAM (-Xmx): " + ChatColor.AQUA + formatBytes(max));
        sender.sendMessage(ChatColor.WHITE + "Tracked mobs: " + ChatColor.AQUA + tracked);
        sender.sendMessage(ChatColor.WHITE + "AI currently throttled: " + ChatColor.AQUA + throttled);
        sender.sendMessage(ChatColor.WHITE + "Explicit GC disabled: " + ChatColor.AQUA
                + explicitGcDisabled());
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

    private record MemorySnapshot(long usedHeap, long committedHeap, long maxHeap) {
        private static MemorySnapshot capture() {
            Runtime runtime = Runtime.getRuntime();
            long committed = runtime.totalMemory();
            long used = Math.max(0L, committed - runtime.freeMemory());
            return new MemorySnapshot(used, committed, runtime.maxMemory());
        }
    }
}
