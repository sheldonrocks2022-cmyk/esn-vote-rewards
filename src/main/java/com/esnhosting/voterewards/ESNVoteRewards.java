package com.esnhosting.voterewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Listens for NuVotifier votes (hooked by reflection, so Votifier is not a compile or hard runtime dependency),
 * runs configurable reward commands, keeps votes for offline players until they join, and matches
 * Bedrock (Floodgate) names that carry a "." or "*" prefix.
 */
public final class ESNVoteRewards extends JavaPlugin implements Listener {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.*\\- ]{1,32}");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private VoteStore store;
    private boolean hooked;
    private long lastBroadcast;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new VoteStore(new File(getDataFolder(), "data.yml"), getLogger());
        store.load();
        getServer().getPluginManager().registerEvents(this, this);
        hookVotifier();
        if (!hooked) getLogger().info("Votifier is not enabled yet; will hook when it loads.");
        // Write changes to disk once a minute (and on shutdown).
        getServer().getScheduler().runTaskTimer(this, store::saveIfDirty, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        if (store != null) store.saveIfDirty();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (!hooked && e.getPlugin().getName().equals("Votifier")) hookVotifier();
    }

    private void hookVotifier() {
        if (hooked) return;
        Plugin votifier = getServer().getPluginManager().getPlugin("Votifier");
        if (votifier == null || !votifier.isEnabled()) return;
        try {
            Class<? extends Event> eventClass = Class
                    .forName("com.vexsoftware.votifier.model.VotifierEvent", true, votifier.getClass().getClassLoader())
                    .asSubclass(Event.class);
            EventExecutor executor = (listener, event) -> {
                if (eventClass.isInstance(event)) onVoteEvent(event);
            };
            getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.NORMAL, executor, this, false);
            hooked = true;
            getLogger().info("Hooked into Votifier " + votifier.getDescription().getVersion());
        } catch (Throwable t) {
            getLogger().warning("Votifier found but the vote hook failed: " + t);
        }
    }

    private void onVoteEvent(Event event) {
        try {
            Object vote = invoke(event, "getVote");
            String user = String.valueOf(invoke(vote, "getUsername")).trim();
            String service = String.valueOf(invoke(vote, "getServiceName")).trim();
            if (!VALID_NAME.matcher(user).matches()) {
                getLogger().warning("Ignored a vote with an invalid username from " + service);
                return;
            }
            String svc = service.isEmpty() ? "a vote site" : (service.length() > 64 ? service.substring(0, 64) : service);
            Runnable task = () -> handleVote(user, svc);
            if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(this, task);
        } catch (Throwable t) {
            getLogger().warning("Could not read a vote event: " + t);
        }
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    /** Main thread only. */
    void handleVote(String user, String service) {
        getLogger().info("Vote from " + service + " for " + user);
        Player p = findOnline(user);
        if (p == null) {
            store.addPending(user, service, System.currentTimeMillis());
            return;
        }
        reward(p, service);
    }

    /** Exact name first, then a case-insensitive match that ignores a Floodgate "." or "*" prefix. */
    static Player findOnline(String user) {
        Player exact = Bukkit.getPlayerExact(user);
        if (exact != null) return exact;
        String want = VoteStore.normalize(user);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (VoteStore.normalize(p.getName()).equals(want)) return p;
        }
        return null;
    }

    private String today() {
        ZoneId zone;
        try { zone = ZoneId.of(getConfig().getString("timezone", "UTC")); } catch (Exception e) { zone = ZoneId.of("UTC"); }
        return LocalDate.now(zone).toString();
    }

    private void reward(Player p, String service) {
        int count = store.incrementToday(p.getUniqueId(), today());
        int maxExtra = Math.max(0, getConfig().getInt("max-extra-votes-per-day", 5));
        List<String> commands;
        String messageKey;
        if (count == 1) {
            commands = getConfig().getStringList("first-vote-commands");
            messageKey = "messages.rewarded";
        } else if (count - 1 <= maxExtra) {
            commands = getConfig().getStringList("extra-vote-commands");
            messageKey = "messages.rewarded";
        } else {
            commands = List.of();
            messageKey = "messages.thanks-only";
        }
        for (String cmd : commands) {
            String line = fill(cmd, p.getName(), service, count);
            if (line.startsWith("/")) line = line.substring(1);
            if (!line.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        }
        send(p, fill(getConfig().getString(messageKey, ""), p.getName(), service, count));

        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, getConfig().getLong("broadcast-cooldown-seconds", 30)) * 1000L;
        String broadcast = getConfig().getString("messages.broadcast", "");
        if (!broadcast.isEmpty() && now - lastBroadcast >= cooldown) {
            lastBroadcast = now;
            Component c = LEGACY.deserialize(fill(broadcast, p.getName(), service, count));
            for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) other.sendMessage(c);
        }
    }

    private static String fill(String s, String player, String service, int count) {
        return s.replace("{player}", player).replace("{service}", service).replace("{count}", Integer.toString(count));
    }

    private static void send(CommandSender to, String legacyText) {
        if (legacyText != null && !legacyText.isEmpty()) to.sendMessage(LEGACY.deserialize(legacyText));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Wait a moment so the reward messages are not lost in the join spam.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            long maxAge = Math.max(1, getConfig().getInt("offline-queue-days", 7)) * 86_400_000L;
            for (String service : store.takePending(p.getName(), System.currentTimeMillis() - maxAge)) {
                reward(p, service);
            }
        }, 60L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("vote")) {
            send(sender, getConfig().getString("messages.vote-header", ""));
            String lineFormat = getConfig().getString("messages.vote-line", "&e- {name}");
            for (Map<?, ?> link : getConfig().getMapList("vote-links")) {
                Object name = link.get("name"), url = link.get("url");
                if (name == null || url == null) continue;
                Component line = LEGACY.deserialize(lineFormat.replace("{name}", name.toString()))
                        .clickEvent(ClickEvent.openUrl(url.toString()));
                sender.sendMessage(line);
            }
            return true;
        }
        // /votereward
        if (args.length == 0) {
            send(sender, "&e/votereward reload &7- reload config.yml");
            send(sender, "&e/votereward test <player> [site] &7- simulate a vote");
            send(sender, "&e/votereward stats &7- show counters");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                send(sender, "&aESNVoteRewards config reloaded.");
            }
            case "test" -> {
                if (args.length < 2 || !VALID_NAME.matcher(args[1]).matches()) {
                    send(sender, "&cUsage: /votereward test <player> [site]");
                    return true;
                }
                handleVote(args[1], args.length >= 3 ? args[2] : "test-site");
                send(sender, "&aTest vote sent for " + args[1] + ".");
            }
            case "stats" -> send(sender, "&7Votifier hooked: &e" + hooked + "&7, pending offline votes: &e" + store.pendingCount());
            default -> send(sender, "&cUnknown option. Use reload, test or stats.");
        }
        return true;
    }
}
