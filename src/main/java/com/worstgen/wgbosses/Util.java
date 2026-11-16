package com.worstgen.wgbosses;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class Util {
    private Util() {}

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
