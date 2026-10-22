package com.deepseek.chunkguard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.deepseek.chunkguard.mixin.ChunkMapAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

/**
 * Shared state and decision logic for the ChunkGuard mixins.
 *
 * <p>The basic rule is: a mod is allowed to read any chunk that is already fully
 * loaded. If the chunk is not loaded and we are inside the server tick, the read
 * is turned into the same empty result vanilla uses for an absent chunk, rather
 * than letting {@code ServerChunkCache} synchronously load (and potentially
 * deadlock on) the chunk.</p>
 */
public final class Guard {
    private static final Logger LOGGER = ChunkGuard.LOGGER;
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final ThreadLocal<TickState> TICK = ThreadLocal.withInitial(TickState::new);
    private static final Map<String, Long> LAST_LOGGED = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Holder<Biome>> EMPTY_CHUNK_BIOMES = new ConcurrentHashMap<>();
    private static final AtomicLong BLOCKED_COUNT = new AtomicLong();

    private Guard() {
    }

    public static void enterLevelTick() {
        TICK.get().levelTickDepth++;
    }

    public static void exitLevelTick() {
        TickState state = TICK.get();
        if (state.levelTickDepth > 0) {
            state.levelTickDepth--;
        }
    }

    public static void enterServerTick() {
        TICK.get().serverTickDepth++;
    }

    public static void exitServerTick() {
        TickState state = TICK.get();
        if (state.serverTickDepth > 0) {
            state.serverTickDepth--;
        }
    }

    /**
     * Guard Level.getHeight(Heightmap.Types, int, int).
     */
    public static boolean shouldGuardHeight(Level level, int blockX, int blockZ, String accessor) {
        if (!Config.guardLevelReads) {
            return false;
        }
        // Level.getHeight has its own out-of-world handling; let vanilla do it.
        if (blockX < -30_000_000 || blockX >= 30_000_000
                || blockZ < -30_000_000 || blockZ >= 30_000_000) {
            return false;
        }
        return shouldGuard(
                level,
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockZ),
                blockX,
                blockZ,
                accessor,
                false);
    }

    /**
     * Guard a position-based Level read (getBlockState/getFluidState/getBlockEntity).
     */
    public static boolean shouldGuardPos(Level level, int blockX, int blockY, int blockZ, String accessor) {
        if (!Config.guardLevelReads) {
            return false;
        }
        if (level.isOutsideBuildHeight(blockY)) {
            // Vanilla already handles this without touching a chunk.
            return false;
        }
        return shouldGuard(
                level,
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockZ),
                blockX,
                blockZ,
                accessor,
                false);
    }

    /**
     * Guard ServerChunkCache.getChunk. The coordinates are chunk coordinates.
     */
    public static boolean shouldGuardDirectChunk(ServerLevel level, int chunkX, int chunkZ, String accessor) {
        if (!Config.guardChunkAccess) {
            return false;
        }
        return shouldGuard(
                level,
                chunkX,
                chunkZ,
                SectionPos.sectionToBlockCoord(chunkX),
                SectionPos.sectionToBlockCoord(chunkZ),
                accessor,
                true);
    }

    private static boolean shouldGuard(
            Level level,
            int chunkX,
            int chunkZ,
            int blockX,
            int blockZ,
            String accessor,
            boolean chunkAccess) {
        if (!Config.enabled || level.isClientSide()) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        TickState tick = TICK.get();
        if (tick.levelTickDepth <= 0 && tick.serverTickDepth <= 0) {
            return false;
        }

        MinecraftServer server = serverLevel.getServer();
        if (server == null || Thread.currentThread() != server.getRunningThread()) {
            return false;
        }

        // Cheap, non-loading check: only act when the chunk is not actually loaded.
        ChunkSource chunkSource = level.getChunkSource();
        if (chunkSource.getChunkNow(chunkX, chunkZ) != null) {
            return false;
        }

        StackInfo stack = inspectStack();
        if (!stack.modFrame()) {
            // Leave vanilla, NeoForge and C2ME internals alone.
            return false;
        }
        if (stack.worldgen()) {
            // Do not interfere with the chunk generation pipeline.
            return false;
        }

        if (chunkAccess) {
            // Direct chunk access is a much sharper tool. Never intercept writes,
            // and only act when no chunk holder exists yet.
            if (stack.writePath()) {
                return false;
            }
            // A chunk holder can exist while the chunk is still only partially
            // loaded. Reads from that chunk are exactly the calls that park the
            // server thread. Only skip holder-backed chunks for direct chunk
            // access that is not part of a normal read path, so mods that
            // deliberately request a chunk for a write keep working.
            if (!stack.readPath() && hasVisibleChunk(serverLevel, chunkX, chunkZ)) {
                return false;
            }
        }

        if (stack.exempt()) {
            return false;
        }

        logBlocked(blockX, blockZ, chunkX, chunkZ, accessor, stack);
        return true;
    }

    /**
     * Returns an empty, full-status chunk. It reports air, empty fluid, no block
     * entities and the minimum build height. Reads through it never cause a load.
     */
    public static LevelChunk emptyChunk(Level level, int chunkX, int chunkZ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            // The guard only ever calls this for ServerLevel. Returning null is
            // safer than throwing from inside a vanilla method.
            return null;
        }
        Holder<Biome> biome = EMPTY_CHUNK_BIOMES.computeIfAbsent(
                serverLevel.dimension(),
                key -> serverLevel.registryAccess()
                        .registryOrThrow(Registries.BIOME)
                        .getHolderOrThrow(Biomes.PLAINS));
        return new EmptyLevelChunk(level, new ChunkPos(chunkX, chunkZ), biome);
    }

    private static boolean hasVisibleChunk(ServerLevel level, int chunkX, int chunkZ) {
        try {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            if (chunkMap instanceof ChunkMapAccessor accessor) {
                return accessor.chunkguard$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ)) != null;
            }
        } catch (Throwable ignored) {
            // If the accessor is unavailable, be conservative and let the load proceed.
        }
        return true;
    }

    private static StackInfo inspectStack() {
        return WALKER.walk(frames -> {
            boolean modFrame = false;
            boolean writePath = false;
            boolean readPath = false;
            boolean worldgen = false;
            boolean exempt = false;
            StackTraceElement firstModFrame = null;

            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                String className = frame.getClassName();
                String methodName = frame.getMethodName();

                if (isWriteFrame(className, methodName)) {
                    writePath = true;
                }
                if (isReadFrame(className, methodName)) {
                    readPath = true;
                }
                if (isWorldgenFrame(className)) {
                    worldgen = true;
                }
                if (isModFrame(className)) {
                    modFrame = true;
                    if (firstModFrame == null) {
                        firstModFrame = frame.toStackTraceElement();
                    }
                    if (isExempt(className)) {
                        exempt = true;
                    }
                }
            }

            return new StackInfo(modFrame, firstModFrame, writePath, readPath, worldgen, exempt);
        });
    }

    private static boolean isModFrame(String className) {
        if (className.startsWith("com.deepseek.chunkguard.")) {
            return false;
        }
        return !isPlatformClass(className);
    }

    private static boolean isExempt(String className) {
        List<String> prefixes = Config.exemptPrefixes;
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classes that are part of the game, loader, Mixin, or common libraries. A
     * stack frame from anything else is treated as a mod frame.
     */
    private static boolean isPlatformClass(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("net.minecraft.")
                || className.startsWith("net.minecraftforge.")
                || className.startsWith("net.neoforged.")
                || className.startsWith("cpw.mods.")
                || className.startsWith("com.mojang.")
                || className.startsWith("com.llamalad7.")
                || className.startsWith("org.spongepowered.")
                || className.startsWith("com.ishland.")
                || className.startsWith("org.embeddedt.modernfix.")
                || className.startsWith("org.slf4j.")
                || className.startsWith("com.google.")
                || className.startsWith("org.apache.")
                || className.startsWith("it.unimi.")
                || className.startsWith("io.netty.")
                || className.startsWith("com.electronwill.")
                || className.startsWith("dev.architectury.")
                || className.startsWith("org.jetbrains.")
                || className.startsWith("kotlin.")
                || className.startsWith("kotlinx.")
                || className.startsWith("org.objectweb.asm.")
                || className.startsWith("org.ow2.asm.")
                || className.startsWith("net.fabricmc.")
                || className.startsWith("oshi.")
                || className.startsWith("com.github.");
    }

    private static boolean isWriteFrame(String className, String methodName) {
        if (className.equals("net.minecraft.world.level.Level")) {
            return methodName.equals("setBlock")
                    || methodName.equals("setBlockAndUpdate")
                    || methodName.equals("removeBlock")
                    || methodName.equals("destroyBlock")
                    || methodName.equals("setBlockEntity")
                    || methodName.equals("removeBlockEntity");
        }
        if (className.equals("net.minecraft.server.level.ServerLevel")) {
            return methodName.equals("addFreshEntity")
                    || methodName.equals("addWithUUID")
                    || methodName.equals("addDuringTeleport")
                    || methodName.equals("addNewPlayer")
                    || methodName.equals("addRespawnedPlayer");
        }
        return false;
    }

    /**
     * Vanilla read methods that may request a chunk. If one of these is on the
     * stack, a partially loaded chunk must not be allowed to block the tick.
     * This covers the common {@code BlockEntity.setChanged()} path, which ends
     * up in {@code Level.updateNeighbourForOutputSignal()} and then
     * {@code Level.getBlockState()} on a neighbouring, still-loading chunk.
     */
    private static boolean isReadFrame(String className, String methodName) {
        if (className.equals("net.minecraft.world.level.Level")) {
            return methodName.equals("getBlockState")
                    || methodName.equals("getFluidState")
                    || methodName.equals("getHeight")
                    || methodName.equals("getBlockEntity")
                    || methodName.equals("updateNeighbourForOutputSignal");
        }
        return false;
    }

    /**
     * Do not second-guess the chunk generator. These frames mean the current
     * lookup is part of normal world generation, not a tick-time probe.
     */
    private static boolean isWorldgenFrame(String className) {
        return className.startsWith("net.minecraft.world.level.chunk.status.")
                || className.startsWith("net.minecraft.world.level.levelgen.")
                || className.startsWith("net.minecraft.world.level.chunk.ChunkGenerator")
                || className.startsWith("net.minecraft.server.level.ChunkMap")
                || className.startsWith("net.minecraft.server.level.ChunkGenerationTask")
                || className.startsWith("net.minecraft.server.level.GenerationChunkHolder")
                || className.startsWith("com.ishland.c2me.rewrites.chunksystem.common.statuses.")
                || className.startsWith("com.ishland.c2me.rewrites.chunksystem.common.threadstate.")
                || className.startsWith("com.ishland.c2me.opts.worldgen.")
                || className.startsWith("com.ishland.c2me.fixes.worldgen.");
    }

    private static void logBlocked(
            int blockX,
            int blockZ,
            int chunkX,
            int chunkZ,
            String accessor,
            StackInfo stack) {
        long total = BLOCKED_COUNT.incrementAndGet();
        if (!Config.logBlockedCalls) {
            return;
        }

        StackTraceElement caller = stack.firstModFrame();
        String callerText = caller == null
                ? "unknown caller"
                : caller.getClassName() + "." + caller.getMethodName()
                        + "(" + caller.getFileName() + ":" + caller.getLineNumber() + ")";

        String key = accessor + "|" + callerText;
        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(0, Config.logIntervalSeconds) * 1000L;
        Long previous = LAST_LOGGED.get(key);
        if (previous != null && intervalMillis > 0 && now - previous < intervalMillis) {
            return;
        }
        LAST_LOGGED.put(key, now);

        LOGGER.info(
                "[ChunkGuard] Prevented blocking chunk access: {} at chunk [{}, {}] (block {}, {}) from {}; "
                        + "chunk was not loaded during the server tick. Prevented so far: {}",
                accessor,
                chunkX,
                chunkZ,
                blockX,
                blockZ,
                callerText,
                total);

        if (Config.logFullStack) {
            LOGGER.info("[ChunkGuard] Full stack for the blocked access above:", new Throwable("ChunkGuard blocked access"));
        }
    }

    private static final class TickState {
        private int levelTickDepth;
        private int serverTickDepth;
    }

    private record StackInfo(
            boolean modFrame,
            StackTraceElement firstModFrame,
            boolean writePath,
            boolean readPath,
            boolean worldgen,
            boolean exempt) {
    }
}
