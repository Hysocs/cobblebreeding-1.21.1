package com.cobblebreeding.mixin;

import com.cobblebreeding.CobblemonBreeding;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.block.PastureBlock;
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

@Mixin(PastureBlock.class)
public abstract class PastureBlockMixin {

    private static final String EGG_NBT_KEY = "is_egg";

    @Inject(method = "onUse", at = @At("HEAD"), cancellable = true)
    private void cobblebreeding_onUseBottomBlock(
            BlockState state,
            World world,
            BlockPos pos,
            PlayerEntity player,
            BlockHitResult hit,
            CallbackInfoReturnable<ActionResult> cir) {

        if (player instanceof ServerPlayerEntity serverPlayer && !world.isClient()) {

            PastureBlock self = (PastureBlock) (Object) this;
            BlockPos basePos = self.getBasePosition(state, pos);

            if (pos.equals(basePos)) {

                if (player.isSneaking()) {
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity instanceof PokemonPastureBlockEntity pastureEntity) {

                        PokemonPastureBlockEntity.Tethering targetTethering = null;
                        UUID targetPokemonUUID = null;
                        UUID ownerUUID = null;

                        List<PokemonPastureBlockEntity.Tethering> tetheredList = pastureEntity.getTetheredPokemon();

                        for (int i = tetheredList.size() - 1; i >= 0; i--) {
                            PokemonPastureBlockEntity.Tethering currentTethering = tetheredList.get(i);
                            Pokemon currentPokemon = currentTethering.getPokemon();
                            if (currentPokemon != null && currentPokemon.getPersistentData().getBoolean(EGG_NBT_KEY)) {
                                targetTethering = currentTethering;
                                targetPokemonUUID = currentTethering.getPokemonId();
                                ownerUUID = currentTethering.getPlayerId();
                                break;
                            }
                        }

                        if (targetTethering == null || targetPokemonUUID == null || ownerUUID == null) {
                            serverPlayer.sendMessage(Text.literal("No Eggs found in the pasture.").formatted(Formatting.YELLOW), false);
                            cir.setReturnValue(ActionResult.FAIL);
                            return;
                        }

                        // --- Ownership Check ---
                        if (!ownerUUID.equals(serverPlayer.getUuid())) {
                            serverPlayer.sendMessage(Text.literal("You do not own this Egg.").formatted(Formatting.RED), false);
                            cir.setReturnValue(ActionResult.FAIL);
                            return;
                        }
                        // --- End Ownership Check ---

                        DynamicRegistryManager registryManager = world.getRegistryManager();
                        PCStore pcStore = Cobblemon.INSTANCE.getStorage().getPC(ownerUUID, registryManager);
                        PartyStore partyStore = Cobblemon.INSTANCE.getStorage().getParty(serverPlayer);

                        if (pcStore == null) {
                            serverPlayer.sendMessage(Text.literal("Error: Could not access your PC.").formatted(Formatting.RED), false);
                            cir.setReturnValue(ActionResult.FAIL);
                            return;
                        }

                        Pokemon pokemonInPC = pcStore.get(targetPokemonUUID);

                        if (pokemonInPC == null) {
                            serverPlayer.sendMessage(Text.literal("Error: Egg data mismatch (not found in PC). Please contact an admin.").formatted(Formatting.RED), false);
                            Entity entityInWorld = world.getEntityById(targetTethering.getEntityId());
                            if (entityInWorld != null) entityInWorld.discard();
                            pastureEntity.releasePokemon(targetPokemonUUID);
                            pastureEntity.markDirty();
                            cir.setReturnValue(ActionResult.FAIL);
                            return;
                        }

                        if (!pokemonInPC.getPersistentData().getBoolean(EGG_NBT_KEY)) {
                            serverPlayer.sendMessage(Text.literal("Error: Data mismatch (PC Pokemon is not an Egg). Please contact an admin.").formatted(Formatting.RED), false);
                            cir.setReturnValue(ActionResult.FAIL);
                            return;
                        }

                        boolean partyHasSpace = partyStore.getFirstAvailablePosition() != null;

                        if (partyHasSpace) {
                            boolean removedFromPC = pcStore.remove(pokemonInPC);

                            if (removedFromPC) {
                                boolean addedToParty = partyStore.add(pokemonInPC);

                                if (addedToParty) {
                                    Entity entityInWorld = world.getEntityById(targetTethering.getEntityId());
                                    if (entityInWorld instanceof PokemonEntity pokemonEntity && pokemonEntity.getTethering() != null && pokemonEntity.getTethering().getTetheringId().equals(targetTethering.getTetheringId())) {
                                        pokemonEntity.discard();
                                    }
                                    pastureEntity.releasePokemon(targetPokemonUUID);
                                    pastureEntity.markDirty();

                                    serverPlayer.sendMessage(Text.literal("Moved an Egg to your party!").formatted(Formatting.GREEN), false);
                                    cir.setReturnValue(ActionResult.SUCCESS);
                                } else {
                                    Cobblemon.LOGGER.error("CRITICAL ERROR: Pokemon {} removed from PC {} but failed to add to party {} during pasture interaction!", targetPokemonUUID, pcStore.getUuid(), partyStore.getUuid());
                                    serverPlayer.sendMessage(Text.literal("Critical Error: Failed to add to party after removing from PC. Please check logs.").formatted(Formatting.RED), false);
                                    // pcStore.add(pokemonInPC); // Consider recovery logic
                                    cir.setReturnValue(ActionResult.FAIL);
                                }
                            } else {
                                serverPlayer.sendMessage(Text.literal("Error: Failed to remove Egg from PC storage. Data mismatch?").formatted(Formatting.RED), false);
                                Cobblemon.LOGGER.error("Failed to remove Pokemon {} from PC {} during pasture interaction, though it was retrieved moments before.", targetPokemonUUID, pcStore.getUuid());
                                cir.setReturnValue(ActionResult.FAIL);
                            }
                        } else {
                            serverPlayer.sendMessage(Text.literal("Your party is full!").formatted(Formatting.RED), false);
                            cir.setReturnValue(ActionResult.FAIL);
                        }
                    } else {
                        cir.setReturnValue(ActionResult.FAIL);
                    }
                }
            }
        }
    }

    @Inject(method = "onPlaced(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;)V",
            at = @At("TAIL"))
    private void cobblebreeding_onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack, CallbackInfo ci) {
        if (!world.isClient()) {
            PastureBlock self = (PastureBlock) (Object) this;
            BlockPos basePos = self.getBasePosition(state, pos);
            if (pos.equals(basePos)) {
                CobblemonBreeding.INSTANCE.registerNewPasture((ServerWorld) world, pos);
            }
        }
    }
}