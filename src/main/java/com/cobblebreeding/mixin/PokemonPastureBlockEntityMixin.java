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
import net.minecraft.util.Formatting;
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
import java.util.UUID;


@Mixin(value = PokemonPastureBlockEntity.class, remap = false)
public abstract class PokemonPastureBlockEntityMixin extends BlockEntity {

    public PokemonPastureBlockEntityMixin(BlockPos blockPos, BlockState blockState) {
        super(null, blockPos, blockState);
    }

    @Shadow @Final @Mutable private BlockPos minRoamPos;
    @Shadow @Final @Mutable private BlockPos maxRoamPos;

    @Unique private static final Logger CBMixinLogger = LoggerFactory.getLogger(CobblemonBreeding.MOD_ID + "-PastureEntityMixin");
    @Unique private static final int CUSTOM_PASTURE_RADIUS = 5;
    @Unique private static final int CUSTOM_PASTURE_EXTRA_HEIGHT_ABOVE = 6;


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
        this.maxRoamPos = centerPos.add(new Vec3i(CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_RADIUS + CUSTOM_PASTURE_EXTRA_HEIGHT_ABOVE, CUSTOM_PASTURE_RADIUS));

        CBMixinLogger.info("[{}] AFTER Apply Custom Radius (H:{}, Y_UP_Extra:{}): Min={}, Max={}", context, CUSTOM_PASTURE_RADIUS, CUSTOM_PASTURE_EXTRA_HEIGHT_ABOVE, this.minRoamPos, this.maxRoamPos);
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
    private void cobblebreeding_checkCompatibilityBeforeTether(ServerPlayerEntity player, Pokemon newPokemon, Direction directionToBehind, CallbackInfoReturnable<Boolean> cir) {
        PokemonPastureBlockEntity targetEntity = (PokemonPastureBlockEntity) (Object) this;
        List<PokemonPastureBlockEntity.Tethering> currentTetheredPokemon = targetEntity.getTetheredPokemon();


        if (currentTetheredPokemon == null || currentTetheredPokemon.isEmpty()) {
            return;
        }

        PokemonPastureBlockEntity.Tethering firstTether = currentTetheredPokemon.get(0);
        Pokemon firstPokemon = firstTether.getPokemon();

        if (firstPokemon == null) {
            CBMixinLogger.warn("First tethered Pokemon was null when checking compatibility. Allowing tether, but this might indicate an issue.");
            return;
        }


        String newSpeciesName = newPokemon.getSpecies().getName();
        boolean isNewDitto = "Ditto".equalsIgnoreCase(newSpeciesName);

        String firstSpeciesName = firstPokemon.getSpecies().getName();
        boolean isFirstDitto = "Ditto".equalsIgnoreCase(firstSpeciesName);




        if (isFirstDitto && isNewDitto) {
            player.sendMessage(Text.literal("You cannot place two Dittos together for breeding.").formatted(Formatting.RED), false);
            CBMixinLogger.info("Tether cancelled: Attempted to add Ditto to existing Ditto.");
            cir.setReturnValue(false);
            return;
        }


        if (isFirstDitto || isNewDitto) {
            CBMixinLogger.info("Tether compatibility check passed: Ditto involved. First='{}'(Ditto={}), New='{}'(Ditto={})",
                    firstSpeciesName, isFirstDitto, newSpeciesName, isNewDitto);
            return;
        }




        boolean isNewGenderless = newPokemon.getGender() == com.cobblemon.mod.common.pokemon.Gender.GENDERLESS;
        boolean isFirstGenderless = firstPokemon.getGender() == com.cobblemon.mod.common.pokemon.Gender.GENDERLESS;


        if (isFirstGenderless && isNewGenderless) {
            player.sendMessage(Text.literal("You cannot place two Genderless Pokémon together for breeding.").formatted(Formatting.RED), false);
            CBMixinLogger.info("Tether cancelled: Attempted to add non-Ditto Genderless ({}) to existing non-Ditto Genderless ({}).", newSpeciesName, firstSpeciesName);
            cir.setReturnValue(false);
            return;
        }


        if (isFirstGenderless || isNewGenderless) {
            player.sendMessage(Text.literal("Genderless Pokémon can only breed with Ditto.").formatted(Formatting.RED), false);
            CBMixinLogger.info("Tether cancelled: Attempted to mix non-Ditto Genderless and non-Ditto Gendered. First='{}'(Genderless={}), New='{}'(Genderless={})",
                    firstSpeciesName, isFirstGenderless, newSpeciesName, isNewGenderless);
            cir.setReturnValue(false);
            return;
        }



        if (!firstSpeciesName.equalsIgnoreCase(newSpeciesName)) {
            player.sendMessage(Text.literal("This pasture only accepts Pokémon of the same species, or compatible pairs with Ditto.").formatted(Formatting.RED), false);
            CBMixinLogger.info("Tether cancelled: Mismatched species ({} vs {}) for non-Ditto, non-Genderless pair.", firstSpeciesName, newSpeciesName);
            cir.setReturnValue(false);
            return;
        }


        CBMixinLogger.info("Tether compatibility check passed: Same species M/F pair. First='{}', New='{}'", firstSpeciesName, newSpeciesName);
    }

}