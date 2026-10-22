package com.deepseek.chunkguard.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Non-creating check for an existing (possibly still-generating) chunk holder.
 * Used to avoid intercepting normal chunk loads that are already in progress.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder chunkguard$getVisibleChunkIfPresent(long packedChunkPos);
}
