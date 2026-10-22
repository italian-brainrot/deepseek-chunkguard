package com.deepseek.chunkguard;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

/**
 * Prevents mods from synchronously loading chunks on the server tick thread.
 *
 * <p>Several mods probe random positions with {@code Level.getHeight},
 * {@code Level.getFluidState} or {@code Level.getBlockState} from a level tick
 * handler. When the probe lands in an unloaded chunk, vanilla's
 * {@code ServerChunkCache} waits for that chunk to load. That is illegal on the
 * server thread and, with a busy chunk pipeline such as C2ME, can park the whole
 * server tick for minutes.</p>
 *
 * <p>ChunkGuard returns vanilla-safe empty values for those unloaded-chunk
 * reads. It deliberately leaves normal world generation, block writes and
 * already-loaded chunks alone.</p>
 */
@Mod(ChunkGuard.MODID)
public final class ChunkGuard {
    public static final String MODID = "chunkguard";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String LILIS_TARGET =
            "net.satisfy.lilis_lucky_lures.core.util.FloatingPoolsSpawner";
    private static final String EXTRA_BIOMES_TARGET =
            "net.winepicfin.extrabiomes.entity.custom.GlacierSnowGolemSpawner";
    private static final String COBBLEMON_TARGET =
            "com.cobblemon.mod.common.block.habitat.HabitatBlockEntity";

    public ChunkGuard(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);

        LOGGER.info("[ChunkGuard] Loaded. Guarding mod chunk probes made from the server tick thread.");
        LOGGER.info(
                "[ChunkGuard] Known blocking-probe classes present: Lilis FloatingPoolsSpawner={}, "
                        + "ExtraBiomes GlacierSnowGolemSpawner={}, Cobblemon HabitatBlockEntity={}",
                classPresent(LILIS_TARGET),
                classPresent(EXTRA_BIOMES_TARGET),
                classPresent(COBBLEMON_TARGET));
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(MODID)) {
            Config.apply();
            logConfig("loaded");
        }
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(MODID)) {
            Config.apply();
            logConfig("reloaded");
        }
    }

    private static void logConfig(String action) {
        LOGGER.info(
                "[ChunkGuard] Config {}: enabled={}, guardLevelReads={}, guardChunkAccess={}, "
                        + "logBlockedCalls={}, logFullStack={}, logIntervalSeconds={}",
                action,
                Config.enabled,
                Config.guardLevelReads,
                Config.guardChunkAccess,
                Config.logBlockedCalls,
                Config.logFullStack,
                Config.logIntervalSeconds);
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, ChunkGuard.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
