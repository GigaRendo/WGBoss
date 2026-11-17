package com.worstgen.wgbosses;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class Util {
    private Util() {}

    /** Template -> Component. Supports &-color codes and {placeholders} (String or Component values). */
    public static MutableComponent fmt(String tpl, Object... kv) {
        Map<String, Object> v = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) v.put(String.valueOf(kv[i]), kv[i + 1]);
        MutableComponent out = Component.empty();
        Style st = Style.EMPTY;
        StringBuilder sb = new StringBuilder();
        int i = 0, n = tpl.length();
        while (i < n) {
            char c = tpl.charAt(i);
            if (c == '&' && i + 1 < n) {
                ChatFormatting f = ChatFormatting.getByCode(tpl.charAt(i + 1));
                if (f != null) { flush(out, sb, st); st = applyFmt(st, f); i += 2; continue; }
            }
            if (c == '{') {
                int e = tpl.indexOf('}', i);
                if (e > i) {
                    String key = tpl.substring(i + 1, e);
                    if (v.containsKey(key)) {
                        Object o = v.get(key);
                        if (o instanceof Component comp) { flush(out, sb, st); out.append(comp); }
                        else sb.append(o);
                        i = e + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        flush(out, sb, st);
        return out;
    }

    private static void flush(MutableComponent out, StringBuilder sb, Style st) {
        if (sb.length() > 0) { out.append(Component.literal(sb.toString()).withStyle(st)); sb.setLength(0); }
    }

    private static Style applyFmt(Style st, ChatFormatting f) {
        if (f == ChatFormatting.RESET) return Style.EMPTY;
        if (f != null && f.isColor()) // If isColor exists in your version, or use:
// if (f != null && f.getColor() != null)
        if (f == ChatFormatting.BOLD) return st.withBold(true);
        if (f == ChatFormatting.ITALIC) return st.withItalic(true);
        if (f == ChatFormatting.UNDERLINE) return st.withUnderlined(true);
        if (f == ChatFormatting.STRIKETHROUGH) return st.withStrikethrough(true);
        if (f == ChatFormatting.OBFUSCATED) return st.withObfuscated(true);
        return st;
    }

    public static int rint(int a, int b) { return b <= a ? a : ThreadLocalRandom.current().nextInt(a, b + 1); }
    public static double rdbl(double a, double b) { return b <= a ? a : ThreadLocalRandom.current().nextDouble(a, b); }
    public static boolean chance(double p) { return ThreadLocalRandom.current().nextDouble() < p; }
    public static <T> T pick(List<T> l) { return l.get(ThreadLocalRandom.current().nextInt(l.size())); }

    public static void broadcast(MinecraftServer s, Component c) {
        s.getPlayerList().broadcastSystemMessage(c, false);
    }

    public static void title(ServerPlayer p, Component t, Component sub, int in, int stay, int out) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
        p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
        p.connection.send(new ClientboundSetTitleTextPacket(t));
    }

    public static void titleAll(MinecraftServer s, Component t, Component sub, int in, int stay, int out) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) title(p, t, sub, in, stay, out);
    }

    public static void soundAll(MinecraftServer s, SoundEvent e, float vol, float pitch) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), e, SoundSource.MASTER, vol, pitch);
    }

    public static void sound(ServerLevel l, double x, double y, double z, SoundEvent e, float vol, float pitch) {
        l.playSound(null, x, y, z, e, SoundSource.HOSTILE, vol, pitch);
    }

    public static boolean safe(ServerLevel l, BlockPos p) {
        var below = l.getBlockState(p.below());
        return l.getBlockState(p).isAir() && l.getBlockState(p.above()).isAir()
                && !below.getCollisionShape(l, p.below()).isEmpty() && below.getFluidState().isEmpty();
    }

    /** Find a safe standing spot between minD and maxD (horizontal) around a point. Returns null if none found. */
    public static Vec3 findSafe(ServerLevel l, double cx, double cy, double cz, double minD, double maxD) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int a = 0; a < 24; a++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double d = minD + r.nextDouble() * Math.max(0.01, maxD - minD);
            double x = cx + Math.cos(ang) * d;
            double z = cz + Math.sin(ang) * d;
            if (!l.hasChunkAt(BlockPos.containing(x, cy, z))) continue;
            for (int dy = 14; dy >= -14; dy--) {
                BlockPos p = BlockPos.containing(x, cy + dy, z);
                if (safe(l, p)) return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            }
        }
        return null;
    }

    /** Push a player away from a point. */
    public static void push(ServerPlayer p, double fromX, double fromZ, double h, double v) {
        double dx = p.getX() - fromX, dz = p.getZ() - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) { dx = 1; dz = 0; len = 1; }
        p.setDeltaMovement(dx / len * h, v, dz / len * h);
        sendMotion(p);
    }

    public static void sendMotion(ServerPlayer p) {
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
    }

    public static double flat(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
