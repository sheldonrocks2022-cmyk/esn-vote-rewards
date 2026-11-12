package com.esnhosting.voterewards;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Small YAML store: votes waiting for offline players, and per-player daily vote counts. */
final class VoteStore {
    private final File file;
    private final Logger log;
    private final List<Pending> pending = new ArrayList<>();
    private final Map<UUID, Daily> daily = new HashMap<>();
    private boolean dirty;

    private record Pending(String user, String service, long at) {}
    private record Daily(String day, int count) {}

    VoteStore(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Lower case, without a Floodgate "." or "*" prefix. */
    static String normalize(String name) {
        return name.replaceFirst("^[.*]", "").toLowerCase(Locale.ROOT);
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("pending")) {
            Object user = m.get("user"), service = m.get("service"), at = m.get("at");
            if (user == null || at == null) continue;
            try {
                pending.add(new Pending(user.toString(), service == null ? "a vote site" : service.toString(), Long.parseLong(at.toString())));
            } catch (NumberFormatException ignored) { }
        }
        ConfigurationSection d = y.getConfigurationSection("daily");
        if (d != null) {
            for (String key : d.getKeys(false)) {
                try {
                    daily.put(UUID.fromString(key), new Daily(d.getString(key + ".day", ""), d.getInt(key + ".count", 0)));
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    void addPending(String user, String service, long at) {
        pending.add(new Pending(user, service, at));
        dirty = true;
    }

    /** Removes and returns the services of every queued vote for this player newer than minTime. */
    List<String> takePending(String playerName, long minTime) {
        String want = normalize(playerName);
        List<String> out = new ArrayList<>();
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.at() < minTime) { it.remove(); dirty = true; continue; }
            if (normalize(p.user()).equals(want)) {
                out.add(p.service());
                it.remove();
                dirty = true;
            }
        }
        return out;
    }

    int pendingCount() {
        return pending.size();
    }

    /** Adds one rewarded vote for today and returns today's total for this player. */
    int incrementToday(UUID id, String today) {
        Daily d = daily.get(id);
        int count = (d != null && d.day().equals(today)) ? d.count() + 1 : 1;
        daily.put(id, new Daily(today, count));
        dirty = true;
        return count;
    }

    void saveIfDirty() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Pending p : pending) {
            Map<String, Object> m = new HashMap<>();
            m.put("user", p.user());
            m.put("service", p.service());
            m.put("at", p.at());
            list.add(m);
        }
        y.set("pending", list);
        for (Map.Entry<UUID, Daily> e : daily.entrySet()) {
            y.set("daily." + e.getKey() + ".day", e.getValue().day());
            y.set("daily." + e.getKey() + ".count", e.getValue().count());
        }
        try {
            file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException ex) {
            log.warning("Could not save " + file.getName() + ": " + ex.getMessage());
        }
    }
}
