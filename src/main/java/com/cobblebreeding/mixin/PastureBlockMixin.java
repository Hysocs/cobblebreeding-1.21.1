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

        if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {

            PastureBlock self = (PastureBlock) (Object) this;
            BlockPos basePos = self.getBasePosition(state, pos);

            // --- Check if the clicked block IS the base/bottom block ---
            if (pos.equals(basePos)) {
                LOGGER.info("======================================================");
                LOGGER.info("Pasture Block BOTTOM half clicked at {}. Triggering Breeding Nest GUI.", pos);
                LOGGER.info("Player: {}", serverPlayer.getName().getString());

                // --- Breeding Nest Logic ---
                if (!serverPlayer.hasPermissionLevel(CobblemonBreeding.PERM_LEVEL_INTERACT)) {
                    serverPlayer.sendMessage(Text.literal("You do not have permission to interact with this Breeding Nest.").formatted(Formatting.RED), false);
                    cir.setReturnValue(ActionResult.FAIL); // Fail interaction
                    LOGGER.info("Interaction Cancelled: No Permission");
                    LOGGER.info("======================================================");
                    return;
                }

                // basePos is the correct position for the GUI context
                LOGGER.info("Opening GUI for basePos: {}", basePos);
                Objects.requireNonNull(serverPlayer.getServer()).execute(() -> {
                    // *** FIX: Provide the 3rd argument (requestedPage = 0) ***
                    CobblemonBreeding.INSTANCE.openNestGui(serverPlayer, basePos);
                });

                // Success: Cancel Cobblemon's default interaction for the bottom block
                cir.setReturnValue(ActionResult.SUCCESS);
                LOGGER.info("Interaction Handled by Breeding Mod. Returned SUCCESS.");
                LOGGER.info("======================================================");

            } else {
                // Top half was clicked
                LOGGER.debug("Top half of Pasture Block clicked at {}. Allowing default Cobblemon interaction.", pos);
                // Let the original onUse method continue
            }
        }
        // If on client or not ServerPlayer, do nothing.
    }
}