package com.deepseek.chunkguard.mixin;

import com.deepseek.chunkguard.Guard;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Last line of defence for mods that call the chunk source directly instead of
 * going through Level's read helpers.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void chunkguard$getChunk(
            int chunkX,
            int chunkZ,
            ChunkStatus status,
            boolean requireChunk,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (Guard.shouldGuardDirectChunk(this.level, chunkX, chunkZ, "ServerChunkCache.getChunk")) {
            cir.setReturnValue(Guard.emptyChunk(this.level, chunkX, chunkZ));
        }
    }
}
