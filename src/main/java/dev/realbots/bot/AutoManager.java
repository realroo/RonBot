package dev.realbots.bot;

import dev.realbots.RealBotsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bots that join and leave by themselves.
 *
 * - auto-join.on-startup: names from the names/ folder join shortly after the server starts.
 * - activity: simulates real players - keeps between min-online and max-online bots,
 *   each one stays for a random time, then leaves while new ones join.
 */
public final class AutoManager {

    private final RealBotsPlugin plugin;
    private final BotManager bots;
    private BukkitTask task;
    private final AtomicInteger pending = new AtomicInteger();   // spawns that have not finished yet

    public AutoManager(RealBotsPlugin plugin, BotManager bots) {
        this.plugin = plugin;
        this.bots = bots;
    }

    // ------------------------------------------------------------------ lifecycle

    /** (Re)starts the activity simulator according to config.yml. */
    public void restart() {
        stop();
        if (!plugin.getConfig().getBoolean("activity.enabled", false)) return;
        long period = Math.max(1, plugin.getConfig().getLong("activity.check-interval-seconds", 15)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------------ join on startup

    public void startupJoin() {
        if (!plugin.getConfig().getBoolean("auto-join.on-startup", false)) return;

        String file = plugin.getConfig().getString("auto-join.file", "");
        List<String> names = (file == null || file.isBlank())
                ? plugin.names().all()
                : plugin.names().fromFile(file);
        if (names == null || names.isEmpty()) {
            plugin.getLogger().info("auto-join: no names found (put .txt files in plugins/RealBots/names/).");
            return;
        }
        List<String> list = new ArrayList<>(names);
        int count = plugin.getConfig().getInt("auto-join.count", 0);
        if (count > 0 && count < list.size()) {
            Collections.shuffle(list);
            list = new ArrayList<>(list.subList(0, count));
        }
        long gap = Math.max(1, plugin.getConfig().getLong("auto-join.stagger-ticks", 20));
        long stay = plugin.getConfig().getBoolean("auto-join.leave-after-stay", false) ? stayMillis() : 0L;

        int i = 0;
        for (String name : list) {
            if (bots.get(name) != null || Bukkit.getPlayerExact(name) != null) continue;
            spawnAuto(name, i * gap, stay);
            i++;
        }
        plugin.getLogger().info("auto-join: joining " + i + " bots.");
    }

    // ------------------------------------------------------------------ activity simulator

    private void check() {
        int min = Math.max(0, plugin.getConfig().getInt("activity.min-online", 2));
        int max = Math.max(min, plugin.getConfig().getInt("activity.max-online", 8));
        int chance = plugin.getConfig().getInt("activity.join-chance-percent", 25);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();

        List<Bot> autos = new ArrayList<>();
        for (Bot b : bots.all()) if (b.auto) autos.add(b);
        int online = autos.size() + pending.get();

        // leave
        for (Bot b : autos) {
            if (b.leaveAt > 0 && now >= b.leaveAt) {
                if (online > min) {
                    bots.remove(b.name);
                    online--;
                } else {
                    b.leaveAt = now + stayMillis();   // too few online, stay a bit longer
                }
            }
        }

        // join
        int toJoin = 0;
        if (online < min) toJoin = min - online;
        else if (online < max && rnd.nextInt(100) < chance) toJoin = 1;
        toJoin = Math.min(toJoin, max - online);
        if (toJoin <= 0) return;

        String prefix = plugin.getConfig().getString("names.random-prefix", "Bot_");
        List<String> names = plugin.names().pick(bots, toJoin, prefix);
        long gap = 20L;
        int i = 0;
        for (String name : names) {
            spawnAuto(name, i * gap, stayMillis());
            i++;
        }
    }

    private void spawnAuto(String name, long delayTicks, long stayMs) {
        pending.incrementAndGet();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location where = Bukkit.getWorlds().get(0).getSpawnLocation();
            bots.spawn(name, null, where, true, stayMs, err -> pending.decrementAndGet());
        }, delayTicks);
    }

    private long stayMillis() {
        long min = Math.max(1, plugin.getConfig().getLong("activity.stay-minutes-min", 5));
        long max = Math.max(min, plugin.getConfig().getLong("activity.stay-minutes-max", 30));
        long minutes = min + ThreadLocalRandom.current().nextLong(max - min + 1);
        return minutes * 60_000L;
    }
}
