// src/main/java/com/cobblebreeding/mixin/PastureBlockMixin.java
package com.cobblebreeding.mixin;

import com.cobblebreeding.CobblemonBreeding;
import com.cobblemon.mod.common.block.PastureBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

@Mixin(PastureBlock.class)
public abstract class PastureBlockMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(CobblemonBreeding.MOD_ID + "-Mixin");

    @Inject(method = "onUse", at = @At("HEAD"), cancellable = true)
    private void cobblebreeding_onUseBottomBlock(
            BlockState state,
            World world,
            BlockPos pos, // The specific block half clicked
            PlayerEntity player,
            BlockHitResult hit,
            CallbackInfoReturnable<ActionResult> cir) {


        // If on client or not ServerPlayer, do nothing.
    }
}