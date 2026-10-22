package com.deepseek.chunkguard.mixin;

import com.deepseek.chunkguard.Guard;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the duration of a level tick. This is the phase in which mods commonly
 * run their spawner logic.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), require = 1)
    private void chunkguard$enterLevelTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        Guard.enterLevelTick();
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"), require = 1)
    private void chunkguard$exitLevelTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        Guard.exitLevelTick();
    }
}
