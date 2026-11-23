package com.worstgen.wgbosses;

import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/** Optional PlaceholderAPI integration via reflection (no compile dependency). */
public final class PlaceholderCompat {
    private PlaceholderCompat() {}

    public static void tryRegister() {
        try {
            Class<?> ph = Class.forName("eu.pb4.placeholders.api.Placeholders");
            Class<?> handlerC = Class.forName("eu.pb4.placeholders.api.PlaceholderHandler");
            Class<?> resultC = Class.forName("eu.pb4.placeholders.api.PlaceholderResult");
            Method value = resultC.getMethod("value", String.class);
            Method reg = null;
            for (Method m : ph.getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 2 && m.getParameterTypes()[0] == Identifier.class) { reg = m; break; }
            }
            if (reg == null) throw new IllegalStateException("register() not found");
            for (String ns : new String[]{"top", "boss"}) {
                final String which = ns;
                Object handler = Proxy.newProxyInstance(handlerC.getClassLoader(), new Class<?>[]{handlerC}, (proxy, m, args) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        return switch (m.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "wgboss-" + which;
                        };
                    }
                    String arg = args.length > 1 && args[1] != null ? args[1].toString().trim() : "";
                    return value.invoke(null, resolve(which, arg));
                });
                reg.invoke(null, Identifier.fromNamespaceAndPath("wgboss", ns), handler);
            }
            WGBosses.LOG.info("PlaceholderAPI found: registered %wgboss:top% and %wgboss:boss%");
        } catch (Throwable t) {
            WGBosses.LOG.info("PlaceholderAPI integration skipped ({}).", t.getClass().getSimpleName());
        }
    }

    /** %wgboss:top kills 1 name%  |  %wgboss:boss name% / active / hp / tier */
    private static String resolve(String which, String arg) {
        String[] a = arg.split("\\s+");
        if (which.equals("top")) {
            String stat = a.length > 0 && !a[0].isEmpty() ? a[0].toLowerCase() : "kills";
            int rank = 1;
            try { if (a.length > 1) rank = Integer.parseInt(a[1]); } catch (Exception ignored) {}
            boolean wantValue = a.length > 2 && a[2].equalsIgnoreCase("value");
            List<Map.Entry<String, Double>> l = WGBosses.stats.top(stat, Math.max(1, rank));
            if (rank < 1 || rank > l.size()) return wantValue ? "0" : "-";
            Map.Entry<String, Double> e = l.get(rank - 1);
            return wantValue ? String.valueOf(Math.round(e.getValue())) : e.getKey();
        }
        Boss b = WGBosses.MANAGER.first();
        String k = a.length > 0 ? a[0].toLowerCase() : "name";
        return switch (k) {
            case "active" -> b != null ? "yes" : "no";
            case "hp" -> b != null ? String.valueOf(Math.round(100 * b.hp / b.maxHp)) : "0";
            case "tier" -> b != null ? b.tierName() : "-";
            case "next" -> {
                long t = WGBosses.MANAGER.ticksUntilNext();
                yield t < 0 ? "-" : (t / 1200 + 1) + "m";
            }
            case "arena" -> b != null && b.arena != null ? b.arena.name : WGBosses.MANAGER.nextArenaName();
            default -> b != null ? b.baseName : "-";
        };
    }
}
