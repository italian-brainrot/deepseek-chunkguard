package com.deepseek.chunkguard.mixin;

import com.deepseek.chunkguard.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards the read-only Level methods commonly used by spawner mods.
 *
 * <p>When the target chunk is not fully loaded and we are inside the server
 * tick, these return the same empty values vanilla uses outside a loaded chunk
 * instead of entering {@code ServerChunkCache.getChunkBlocking}.</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(
            method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void chunkguard$getHeight(
            Heightmap.Types type,
            int x,
            int z,
            CallbackInfoReturnable<Integer> cir) {
        Level level = (Level) (Object) this;
        if (Guard.shouldGuardHeight(level, x, z, "Level.getHeight")) {
            cir.setReturnValue(level.getMinBuildHeight());
        }
    }

    @Inject(
            method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void chunkguard$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        Level level = (Level) (Object) this;
        if (Guard.shouldGuardPos(level, pos.getX(), pos.getY(), pos.getZ(), "Level.getFluidState")) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }

    @Inject(
            method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void chunkguard$getBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        Level level = (Level) (Object) this;
        if (Guard.shouldGuardPos(level, pos.getX(), pos.getY(), pos.getZ(), "Level.getBlockEntity")) {
            cir.setReturnValue(null);
        }
    }

}
