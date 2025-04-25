// src/main/java/com/cobblebreeding/mixin/PokemonPastureBlockEntityMixin.java
package com.cobblebreeding.mixin;

import com.cobblebreeding.CobblemonBreeding;
import com.cobblebreeding.utils.ReturnToPastureGoal;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity; // Import BlockEntity
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;


@Mixin(PokemonPastureBlockEntity.class)
// Extend BlockEntity, which provides the getPos() method we need
public abstract class PokemonPastureBlockEntityMixin extends BlockEntity {

    // Required constructor for extending BlockEntity
    public PokemonPastureBlockEntityMixin(BlockPos blockPos, BlockState blockState) {
        // Call super - replace 'null' if needed, but often okay for mixins
        super(null, blockPos, blockState);
    }

    // Shadow the fields we want to access/modify directly from the target class.
    @Shadow @Final @Mutable private BlockPos minRoamPos;
    @Shadow @Final @Mutable private BlockPos maxRoamPos;

    // NOTE: We DO NOT need to @Shadow getPos() because this mixin class
    //       inherits it directly by extending BlockEntity.

    @Unique private static final Logger CBMixinLogger = LoggerFactory.getLogger(CobblemonBreeding.MOD_ID + "-PastureEntityMixin");
    @Unique private static final int CUSTOM_PASTURE_RADIUS = 5; // Define your desired radius

    /**
     * Helper method to calculate and apply the custom roam boundaries.
     * It uses this.getPos() which is inherited from BlockEntity.
     */
    @Unique
    private void applyCustomRadius(String context) {
        // Get the position using the inherited getPos() method
        BlockPos centerPos = this.getPos();

        // Log current values before changing (optional but good for debugging)
        BlockPos currentMin = this.minRoamPos;
        BlockPos currentMax = this.maxRoamPos;
        CBMixinLogger.info("[{}] Center Pos: {}", context, centerPos);
        CBMixinLogger.info("[{}] BEFORE Apply Custom Radius: Min={}, Max={}", context,
                currentMin != null ? currentMin.toString() : "null",
                currentMax != null ? currentMax.toString() : "null");

        // Calculate and set the new boundaries based on the center and radius
        this.minRoamPos = centerPos.subtract(new Vec3i(CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS));
        this.maxRoamPos = centerPos.add(new Vec3i(CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS));

        // Log the new values (optional)
        CBMixinLogger.info("[{}] AFTER Apply Custom Radius ({}): Min={}, Max={}", context, CUSTOM_PASTURE_RADIUS, this.minRoamPos, this.maxRoamPos);
    }

    /**
     * Injects at the end of the target class's constructor to set the custom radius immediately.
     * Signature: PokemonPastureBlockEntity(BlockPos pos, BlockState state)
     * Descriptor: (Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V
     */
    @Inject(method = "<init>(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V", at = @At("TAIL"))
    private void cobblebreeding_onInitTail(BlockPos initialPos, BlockState state, CallbackInfo ci) {
        CBMixinLogger.info("PokemonPastureBlockEntity <init> TAIL reached for {}.", initialPos);
        // Apply the custom radius using the inherited getPos()
        applyCustomRadius("INIT");
        CBMixinLogger.info("[INIT] Final values after init injection: Min={}, Max={}", this.minRoamPos, this.maxRoamPos);
    }

    /**
     * Injects BEFORE a Pokemon is tethered to ensure the radius is correct at that moment.
     * Signature: tether(ServerPlayerEntity player, Pokemon pokemon, Direction directionToBehind)
     * Descriptor: (Lnet/minecraft/server/network/ServerPlayerEntity;Lcom/cobblemon/mod/common/pokemon/Pokemon;Lnet/minecraft/util/math/Direction;)Z
     */
    @Inject(
            method = "tether(Lnet/minecraft/server/network/ServerPlayerEntity;Lcom/cobblemon/mod/common/pokemon/Pokemon;Lnet/minecraft/util/math/Direction;)Z",
            at = @At(
                    value = "INVOKE",
                    // Target the line where the tethering object is assigned to the entity
                    target = "Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;setTethering(Lcom/cobblemon/mod/common/block/entity/PokemonPastureBlockEntity$Tethering;)V",
                    shift = At.Shift.AFTER // Inject *after* this line executes
            ),
            locals = LocalCapture.CAPTURE_FAILHARD // Capture local variables, fail if it doesn't work
    )
    private void cobblebreeding_addReturnGoalOnTether(ServerPlayerEntity player, Pokemon pokemon, Direction directionToBehind, CallbackInfoReturnable<Boolean> cir, World world, PokemonEntity entity, double width, BlockPos idealPlace, Box box, int i, BlockPos fixedPosition, PCStore pc, PokemonPastureBlockEntity.Tethering tethering) {
        if (entity != null) {
            try {
                // Use the Accessor to get the GoalSelector
                GoalSelector goalSelector = ((MobEntityAccessor) entity).getGoalSelector();

                // Add our custom goal with a suitable priority.
                // Lower numbers = higher priority.
                // Make it reasonably high priority so it overrides wandering when needed.
                // Let's try priority 2. Common goals like WanderAroundGoal are often ~5-8.
                goalSelector.add(2, new ReturnToPastureGoal(entity, 1.0D)); // 1.0D is the movement speed modifier

                CBMixinLogger.info("Added ReturnToPastureGoal to tethered Pokémon: {}", entity.getName().getString());

            } catch (Exception e) {
                CBMixinLogger.error("Failed to add ReturnToPastureGoal to entity {}", entity.getName().getString(), e);
            }
        } else {
            CBMixinLogger.warn("Tried to add ReturnToPastureGoal, but captured entity was null in tether method!");
        }
    }


    /*
    // Optional: Inject into the NBT loading method if needed (e.g., if radius isn't correct after world load).
    // IMPORTANT: The method name ('loadAdditional', 'readNbt', 'load') and signature can vary
    //            significantly between Minecraft versions and mappings. VERIFY THE CORRECT METHOD AND DESCRIPTOR.
    // Example for potential newer versions/mappings:
    // @Inject(method = "readNbt(Lnet/minecraft/nbt/NbtCompound;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)V", at = @At("TAIL"))
    // private void cobblebreeding_onLoadTail(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup, CallbackInfo ci) {
    //     // Log using the inherited getPos()
    //     CBMixinLogger.info("PokemonPastureBlockEntity NBT read TAIL reached for {}.", this.getPos());
    //     // Apply the custom radius after NBT data has been loaded
    //     applyCustomRadius("LOAD");
    //     CBMixinLogger.info("[LOAD] Final values after load injection: Min={}, Max={}", this.minRoamPos, this.maxRoamPos);
    // }
    */

}