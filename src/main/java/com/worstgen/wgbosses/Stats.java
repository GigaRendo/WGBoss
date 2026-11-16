package com.worstgen.wgbosses;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Leaderboards, saved to <world>/wgbosses_stats.json */
public final class Stats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class E {
        public String name = "?";
        public int kills, calamity, mvp;
        public int highest = -1;
        public double damage;
    }

    private Map<String, E> players = new HashMap<>();
    private Path file;

    public void load(MinecraftServer s) {
        try {
            file = s.getWorldPath(LevelResource.ROOT).resolve("wgbosses_stats.json");
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    Map<String, E> m = GSON.fromJson(r, new TypeToken<Map<String, E>>() {}.getType());
                    if (m != null) players = m;
                }
            }
        } catch (Exception e) {
            WGBosses.LOG.error("Could not load stats", e);
        }
    }

    public void save() {
        if (file == null) return;
        try (Writer w = Files.newBufferedWriter(file)) { GSON.toJson(players, w); }
        catch (Exception e) { WGBosses.LOG.error("Could not save stats", e); }
    }

    public E get(UUID id, String name) {
        E e = players.computeIfAbsent(id.toString(), k -> new E());
        if (name != null) e.name = name;
        return e;
    }

    public void seen(ServerPlayer p) { get(p.getUUID(), p.getName().getString()); }

    public Collection<String> knownNames() {
        List<String> l = new ArrayList<>();
        for (E e : players.values()) if (e.name != null && !e.name.equals("?")) l.add(e.name);
        return l;
    }

    /** Returns sorted (name, value) pairs, best first. */
    public List<Map.Entry<String, Double>> top(String stat, int n) {
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        for (E e : players.values()) {
            double v = switch (stat) {
                case "damage" -> e.damage;
                case "calamity" -> e.calamity;
                case "highest" -> e.highest + 1;
                case "mvp" -> e.mvp;
                default -> e.kills;
            };
            if (v > 0) out.add(Map.entry(e.name, v));
        }
        out.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return out.size() > n ? out.subList(0, n) : out;
    }
}
