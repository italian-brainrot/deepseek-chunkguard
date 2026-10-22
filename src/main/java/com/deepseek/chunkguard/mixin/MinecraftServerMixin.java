package com.deepseek.chunkguard.mixin;

import com.deepseek.chunkguard.Guard;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Also covers mod handlers invoked from the wider server tick outside
 * {@code ServerLevel.tick}, such as server tick events.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), require = 0)
    private void chunkguard$enterServerTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        Guard.enterServerTick();
    }

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"), require = 0)
    private void chunkguard$exitServerTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        Guard.exitServerTick();
    }
}
