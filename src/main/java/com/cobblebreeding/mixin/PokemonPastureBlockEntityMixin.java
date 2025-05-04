package com.cobblebreeding.mixin;

import com.cobblebreeding.CobblemonBreeding;
import com.cobblebreeding.utils.ReturnToPastureGoal;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
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

import java.util.List;


@Mixin(PokemonPastureBlockEntity.class)
public abstract class PokemonPastureBlockEntityMixin extends BlockEntity {

    public PokemonPastureBlockEntityMixin(BlockPos blockPos, BlockState blockState) {
        super(null, blockPos, blockState);
    }

    @Shadow @Final @Mutable private BlockPos minRoamPos;
    @Shadow @Final @Mutable private BlockPos maxRoamPos;

    @Unique private static final Logger CBMixinLogger = LoggerFactory.getLogger(CobblemonBreeding.MOD_ID + "-PastureEntityMixin");
    @Unique private static final int CUSTOM_PASTURE_RADIUS = 5;

    @Unique
    private void applyCustomRadius(String context) {
        BlockPos centerPos = this.getPos();
        BlockPos currentMin = this.minRoamPos;
        BlockPos currentMax = this.maxRoamPos;
        CBMixinLogger.info("[{}] Center Pos: {}", context, centerPos);
        CBMixinLogger.info("[{}] BEFORE Apply Custom Radius: Min={}, Max={}", context,
                currentMin != null ? currentMin.toString() : "null",
                currentMax != null ? currentMax.toString() : "null");

        this.minRoamPos = centerPos.subtract(new Vec3i(CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS));
        this.maxRoamPos = centerPos.add(new Vec3i(CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS));

        CBMixinLogger.info("[{}] AFTER Apply Custom Radius ({}): Min={}, Max={}", context, CUSTOM_PASTURE_RADIUS, this.minRoamPos, this.maxRoamPos);
    }

    @Inject(method = "<init>(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V", at = @At("TAIL"))
    private void cobblebreeding_onInitTail(BlockPos initialPos, BlockState state, CallbackInfo ci) {
        CBMixinLogger.info("PokemonPastureBlockEntity <init> TAIL reached for {}.", initialPos);
        applyCustomRadius("INIT");
        CBMixinLogger.info("[INIT] Final values after init injection: Min={}, Max={}", this.minRoamPos, this.maxRoamPos);
    }

    @Inject(
            method = "tether(Lnet/minecraft/server/network/ServerPlayerEntity;Lcom/cobblemon/mod/common/pokemon/Pokemon;Lnet/minecraft/util/math/Direction;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;setTethering(Lcom/cobblemon/mod/common/block/entity/PokemonPastureBlockEntity$Tethering;)V",
                    shift = At.Shift.AFTER
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void cobblebreeding_addReturnGoalOnTether(ServerPlayerEntity player, Pokemon pokemon, Direction directionToBehind, CallbackInfoReturnable<Boolean> cir, World world, PokemonEntity entity, double width, BlockPos idealPlace, Box box, int i, BlockPos fixedPosition, PCStore pc, PokemonPastureBlockEntity.Tethering tethering) {
        if (entity != null) {
            try {
                GoalSelector goalSelector = ((MobEntityAccessor) entity).getGoalSelector();
                goalSelector.add(2, new ReturnToPastureGoal(entity, 1.0D));
                CBMixinLogger.info("Added ReturnToPastureGoal to tethered Pokémon: {}", entity.getName().getString());
            } catch (Exception e) {
                CBMixinLogger.error("Failed to add ReturnToPastureGoal to entity {}", entity.getName().getString(), e);
            }
        } else {
            CBMixinLogger.warn("Tried to add ReturnToPastureGoal, but captured entity was null in tether method!");
        }
    }
    //Tether again to catch race conditions so the custom radius is still applied
    @Inject(method = "tether", at = @At("HEAD"))
    private void cobblebreeding_onGetTetheredPokemon(CallbackInfoReturnable<List<PokemonPastureBlockEntity.Tethering>> cir) {
        applyCustomRadius("GET_TETHERED");
        CBMixinLogger.info("[GET_TETHERED] Check/Apply Radius: Min={}, Max={}", this.minRoamPos, this.maxRoamPos);
    }


    @Inject(
            method = "tether(Lnet/minecraft/server/network/ServerPlayerEntity;Lcom/cobblemon/mod/common/pokemon/Pokemon;Lnet/minecraft/util/math/Direction;)Z",
            at = @At("HEAD"),
            cancellable = true
    )

    private void cobblebreeding_checkSpeciesBeforeTether(ServerPlayerEntity player, Pokemon pokemon, Direction directionToBehind, CallbackInfoReturnable<Boolean> cir) {
        // --- Use the implicit Kotlin getter method ---
        // Cast 'this' to the target type PokemonPastureBlockEntity to call its methods
        PokemonPastureBlockEntity targetEntity = (PokemonPastureBlockEntity) (Object) this;
        // Call the getter method generated by Kotlin for 'val tetheredPokemon'
        List<PokemonPastureBlockEntity.Tethering> currentTetheredPokemon = targetEntity.getTetheredPokemon();
        // --- End Use the implicit Kotlin getter method ---

        // Use the list obtained from the getter method
        if (currentTetheredPokemon != null && !currentTetheredPokemon.isEmpty()) { // Add null check just in case getter returns null
            PokemonPastureBlockEntity.Tethering firstTether = currentTetheredPokemon.get(0);
            Pokemon firstPokemon = firstTether.getPokemon();
            if (firstPokemon != null) {
                String firstSpecies = firstPokemon.getSpecies().getName();
                String newSpecies = pokemon.getSpecies().getName();
                if (!firstSpecies.equals(newSpecies)) {
                    cir.setReturnValue(false);
                    player.sendMessage(Text.of("Only Pokémon of the same species can be in this pasture."), false);
                }
            }
        }
        // If the list is null or empty, or if species match, the injection completes without cancelling,
        // allowing the original tether method to continue.
    }
}