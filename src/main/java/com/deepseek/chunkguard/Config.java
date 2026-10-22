package com.deepseek.chunkguard;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for ChunkGuard.
 *
 * <p>The defaults are deliberately active before the config file is read, so the
 * guard is already in place during startup and early ticks.</p>
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment(
                    "Master switch for ChunkGuard.",
                    "When disabled, no chunk reads or chunk loads are intercepted.")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue GUARD_LEVEL_READS = BUILDER
            .comment(
                    "Guard read-only Level lookups made from the server thread during a server tick",
                    "when the target chunk is not fully loaded.",
                    "",
                    "This covers Level.getHeight(), Level.getFluidState() and Level.getBlockEntity()",
                    "and Level.getBlockEntity(). These are the methods used by most spawner mods",
                    "to probe a random position for a valid spawn. If the chunk is not loaded,",
                    "the guard returns the same kind of empty/min-height value vanilla would",
                    "return for an absent chunk, instead of synchronously loading the chunk.")
            .define("guardLevelReads", true);

    public static final ModConfigSpec.BooleanValue GUARD_CHUNK_ACCESS = BUILDER
            .comment(
                    "Also guard direct chunk access made by mods from the server thread during a tick.",
                    "",
                    "This covers Level.getChunkAt(), Level.getChunk(), and",
                    "ServerChunkCache.getChunk() when the caller is a mod, the chunk is not",
                    "loaded, no chunk holder exists yet, and the call is not part of worldgen",
                    "or a block/entity write. In that case an empty LevelChunk is returned.",
                    "",
                    "Disable this if a mod genuinely needs to force-load a chunk synchronously",
                    "during a tick. The Level read guard above is usually enough for spawners.")
            .define("guardChunkAccess", true);

    public static final ModConfigSpec.BooleanValue LOG_BLOCKED_CALLS = BUILDER
            .comment("Log each distinct prevented call (rate-limited by logIntervalSeconds).")
            .define("logBlockedCalls", true);

    public static final ModConfigSpec.BooleanValue LOG_FULL_STACK = BUILDER
            .comment(
                    "Log a full Java stack trace the first time a particular caller is blocked.",
                    "Useful for finding the next offending mod. This can be noisy, so it is off by default.")
            .define("logFullStack", false);

    public static final ModConfigSpec.IntValue LOG_INTERVAL_SECONDS = BUILDER
            .comment(
                    "Minimum number of seconds between log messages for the same caller and accessor.",
                    "Set to 0 to log every prevented call.")
            .defineInRange("logIntervalSeconds", 30, 0, 3600);

    public static final ModConfigSpec.ConfigValue<String> EXEMPT_PREFIXES = BUILDER
            .comment(
                    "Comma-separated class-name prefixes that ChunkGuard must never guard.",
                    "Example: com.example.mymod,net.example.othermod",
                    "Leave empty for the normal behaviour.")
            .define("exemptModPrefixes", "");

    static final ModConfigSpec SPEC = BUILDER.build();

    // Live values read by the mixins. The initial values are used until the config loads.
    public static volatile boolean enabled = true;
    public static volatile boolean guardLevelReads = true;
    public static volatile boolean guardChunkAccess = true;
    public static volatile boolean logBlockedCalls = true;
    public static volatile boolean logFullStack = false;
    public static volatile int logIntervalSeconds = 30;
    public static volatile List<String> exemptPrefixes = List.of();

    static void apply() {
        enabled = ENABLED.getAsBoolean();
        guardLevelReads = GUARD_LEVEL_READS.getAsBoolean();
        guardChunkAccess = GUARD_CHUNK_ACCESS.getAsBoolean();
        logBlockedCalls = LOG_BLOCKED_CALLS.getAsBoolean();
        logFullStack = LOG_FULL_STACK.getAsBoolean();
        logIntervalSeconds = LOG_INTERVAL_SECONDS.getAsInt();
        exemptPrefixes = parsePrefixes(EXEMPT_PREFIXES.get());
    }

    private static List<String> parsePrefixes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String prefix = part.trim();
            if (!prefix.isEmpty()) {
                result.add(prefix);
            }
        }
        return List.copyOf(result);
    }

    private Config() {
    }
}
