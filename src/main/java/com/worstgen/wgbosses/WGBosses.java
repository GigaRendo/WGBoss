package com.worstgen.wgbosses;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WGBosses implements ModInitializer {
    public static final String ID = "wgbosses";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public static Config config;
    public static MessageBank bank;
    public static final Stats stats = new Stats();
    public static final BossManager MANAGER = new BossManager();

    public static void reloadAll() {
        config = Config.load();
        bank = MessageBank.load();
        MANAGER.resetPool();
    }

    @Override
    public void onInitialize() {
        reloadAll();
        MANAGER.loadPersisted();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { stats.load(s); MANAGER.init(s); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { MANAGER.shutdown(); stats.save(); });
        ServerTickEvents.END_SERVER_TICK.register(MANAGER::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> Cmd.register(dispatcher, ctx));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(MANAGER::allowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(MANAGER::afterDeath);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> MANAGER.entityLoaded(entity));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> stats.seen(handler.getPlayer()));
        PlaceholderCompat.tryRegister();
        LOG.info("WGBosses loaded.");
    }
}
