package dev.deepseek67.ramcleaner;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RamCleaner is deliberately conservative about what it calls "freed" memory.
 * JVM GC is not guaranteed to reclaim a specific amount of memory, so the
 * command reports the measured reduction in used heap after the GC requests.
 */
public final class RamCleanerPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final long MB = 1024L * 1024L;

    private final Map<UUID, MobState> mobs = new HashMap<>();
    private final AtomicBoolean cleaning = new AtomicBoolean(false);
    private BukkitTask aiTask;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("ramclean").setExecutor(this);
        getCommand("ramstatus").setExecutor(this);

        // One initial scan; afterwards entities are maintained from events.
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                track(entity);
            }
        }

        // Every 10 ticks keeps the hot path small while reacting quickly.
        aiTask = Bukkit.getScheduler().runTaskTimer(this, this::updateMobAI, 10L, 10L);
        getLogger().info("RamCleaner enabled. Adaptive mob AI + measured JVM cleanup active.");
    }

    @Override
    public void onDisable() {
        if (aiTask != null) {
            aiTask.cancel();
        }
        restoreAllMobAI();
        mobs.clear();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        track(event.getEntity());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        mobs.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            track(entity);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            mobs.remove(entity.getUniqueId());
        }
    }

    private void track(Entity entity) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        if (!mob.isValid()) {
            return;
        }
        mobs.putIfAbsent(mob.getUniqueId(), new MobState(mob, mob.isAware()));
    }

    /**
     * Adaptive AI throttling:
     * - A mob with no tracked players has its AI disabled.
     * - If it is tracked, AI stays enabled only while at least one tracking
     *   player has line of sight to it.
     * - The check uses Paper's tracking state first, avoiding an O(mobs*players)
     *   distance scan for the common far-away case.
     * - State changes only happen when necessary.
     */
    private void updateMobAI() {
        Iterator<Map.Entry<UUID, MobState>> iterator = mobs.entrySet().iterator();
        while (iterator.hasNext()) {
            MobState state = iterator.next().getValue();
            Mob mob = state.mob;

            if (!mob.isValid() || mob.isDead()) {
                iterator.remove();
                continue;
            }

            boolean shouldRunAI = false;
            for (Player player : mob.getTrackedBy()) {
                if (!player.isOnline() || player.isDead()) {
                    continue;
                }
                if (mob.hasLineOfSight(player)) {
                    shouldRunAI = true;
                    break;
                }
            }

            // Keep the original state semantics: only mobs modified by this
            // plugin are restored to their original value later.
            if (!shouldRunAI) {
                if (mob.isAware()) {
                    mob.setAware(false);
                    state.modified = true;
                }
            } else if (state.modified && !mob.isAware()) {
                mob.setAware(state.originalAware);
                state.modified = false;
            }
        }
    }

    private void restoreAllMobAI() {
        for (MobState state : mobs.values()) {
            Mob mob = state.mob;
            if (state.modified && mob.isValid()) {
                mob.setAware(state.originalAware);
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
            if (!cleaning.compareAndSet(false, true)) {
                sender.sendMessage(ChatColor.RED + "[RamCleaner] A cleanup is already running.");
                return true;
            }

            // MemoryMXBean gives an additional before/after signal, while
            // Runtime gives the heap values normally useful to server admins.
            MemorySnapshot before = MemorySnapshot.capture();
            sender.sendMessage(ChatColor.AQUA + "[RamCleaner] " + ChatColor.GRAY + "Starting aggressive measured cleanup...");

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    // Two explicit requests separated by a short pause. This is
                    // intentionally aggressive, but JVM flags may ignore them.
                    System.gc();
                    sleep(150L);
                    System.gc();
                    sleep(250L);

                    MemorySnapshot after = MemorySnapshot.capture();
                    long freed = Math.max(0L, before.usedHeap - after.usedHeap);
                    long allocatedDrop = Math.max(0L, before.allocatedHeap - after.allocatedHeap);

                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.AQUA + "[RamCleaner] " + ChatColor.GREEN
                                + "Cleanup complete. Measured heap freed: " + formatBytes(freed)
                                + ChatColor.GRAY + " | Allocated heap released: " + formatBytes(allocatedDrop));
                        sender.sendMessage(ChatColor.GRAY + "[RamCleaner] Used: " + formatBytes(after.usedHeap)
                                + " / " + formatBytes(after.maxHeap) + " max"
                                + " | Free headroom: " + formatBytes(after.maxHeap - after.usedHeap));
                        if (before.usedHeap <= after.usedHeap) {
                            sender.sendMessage(ChatColor.YELLOW + "[RamCleaner] JVM reclaimed no measurable heap this run; no memory was faked.");
                        }
                        cleaning.set(false);
                    });
                } catch (Throwable throwable) {
                    getLogger().warning("Cleanup failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.RED + "[RamCleaner] Cleanup failed safely; no fake freed value was reported.");
                        cleaning.set(false);
                    });
                }
            });
            return true;
        }

        return false;
    }

    private void sendStatus(CommandSender sender) {
        MemorySnapshot memory = MemorySnapshot.capture();
        sender.sendMessage(ChatColor.AQUA + "━━━━━━━━ RamCleaner Status ━━━━━━━━");
        sender.sendMessage(ChatColor.WHITE + "Used RAM: " + ChatColor.AQUA + formatBytes(memory.usedHeap));
        sender.sendMessage(ChatColor.WHITE + "Allocated RAM: " + ChatColor.AQUA + formatBytes(memory.allocatedHeap));
        sender.sendMessage(ChatColor.WHITE + "Free RAM (allocated): " + ChatColor.AQUA + formatBytes(memory.allocatedHeap - memory.usedHeap));
        sender.sendMessage(ChatColor.WHITE + "Free RAM (max headroom): " + ChatColor.AQUA + formatBytes(memory.maxHeap - memory.usedHeap));
        sender.sendMessage(ChatColor.WHITE + "Max RAM (-Xmx): " + ChatColor.AQUA + formatBytes(memory.maxHeap));
        sender.sendMessage(ChatColor.WHITE + "Tracked mobs: " + ChatColor.AQUA + mobs.size());
        sender.sendMessage(ChatColor.WHITE + "AI throttled mobs: " + ChatColor.AQUA + countThrottledMobs());
        sender.sendMessage(ChatColor.WHITE + "Explicit GC disabled: " + ChatColor.AQUA + isExplicitGcDisabled());
        sender.sendMessage(ChatColor.AQUA + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private int countThrottledMobs() {
        int count = 0;
        for (MobState state : mobs.values()) {
            if (state.modified && state.mob.isValid() && !state.mob.isAware()) {
                count++;
            }
        }
        return count;
    }

    private boolean isExplicitGcDisabled() {
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            return args.stream().anyMatch(arg -> arg.equalsIgnoreCase("-XX:+DisableExplicitGC"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sleep(long millis) {
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
        if (value < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", value / 1024.0);
    }

    private static final class MobState {
        private final Mob mob;
        private final boolean originalAware;
        private boolean modified;

        private MobState(Mob mob, boolean originalAware) {
            this.mob = mob;
            this.originalAware = originalAware;
        }
    }

    private static final class MemorySnapshot {
        private final long usedHeap;
        private final long allocatedHeap;
        private final long maxHeap;

        private MemorySnapshot(long usedHeap, long allocatedHeap, long maxHeap) {
            this.usedHeap = usedHeap;
            this.allocatedHeap = allocatedHeap;
            this.maxHeap = maxHeap;
        }

        private static MemorySnapshot capture() {
            Runtime runtime = Runtime.getRuntime();
            long allocated = runtime.totalMemory();
            long free = runtime.freeMemory();
            long used = Math.max(0L, allocated - free);
            long max = runtime.maxMemory();
            return new MemorySnapshot(used, allocated, max);
        }
    }
}
