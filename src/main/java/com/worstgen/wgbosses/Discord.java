package com.worstgen.wgbosses;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Posts spawn / kill messages to a Discord channel webhook (async, never blocks the server). */
public final class Discord {
    private Discord() {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void post(String text) {
        String url = WGBosses.config.discordWebhookUrl;
        if (url == null || url.isBlank()) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("content", text.length() > 1900 ? text.substring(0, 1900) : text);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.trim()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(o.toString()))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(ex -> {
                WGBosses.LOG.warn("Discord webhook failed: {}", ex.toString());
                return null;
            });
        } catch (Throwable t) {
            WGBosses.LOG.warn("Discord webhook error: {}", t.toString());
        }
    }

    public static void spawn(Boss b) {
        Config c = WGBosses.config;
        if (!c.discordOnSpawn || b.trueTier < c.discordMinTier) return;
        String dim = b.level.dimension().identifier().getPath();
        post("**BOSS SPAWNED** [" + b.tierName() + "] " + b.displayName().getString() + " in the " + dim
                + (b.arena != null ? " (arena " + b.arena.name + ")" : "") + (b.rainbow ? " - RAINBOW" : ""));
    }

    public static void kill(Boss b, List<String> topLines) {
        Config c = WGBosses.config;
        if (!c.discordOnKill || b.trueTier < c.discordMinTier) return;
        StringBuilder sb = new StringBuilder("**BOSS DEFEATED** [" + c.tiers.get(b.trueTier).name + (b.calamity ? " - CALAMITY" : "") + "] "
                + b.displayName().getString());
        for (String l : topLines) sb.append("\n").append(l);
        post(sb.toString());
    }
}
