// src/main/java/com/cobblebreeding/mixin/BattleRegistryMixin.java
package com.cobblebreeding.mixin;


import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(value = BattleRegistry.class, remap = false)
public abstract class BattleRegistryMixin {

    /**
     * Redirects the call to packTeam within startShowdown.
     * Before the original packTeam is called, this method removes any Pokemon
     * identified as an Egg (using BreedingUtils.isEgg) from the team list.
     */
    @Redirect(
            method = "startShowdown", // The method where the battle setup happens
            at = @At(
                    value = "INVOKE",
                    // The specific method call we want to intercept
                    target = "Lcom/cobblemon/mod/common/battles/BattleRegistry;packTeam(Ljava/util/List;)Ljava/lang/String;"
            )
    )
    public String cobblebreeding_filterEggsFromTeam(BattleRegistry instance, List<? extends BattlePokemon> team) {
        // Remove any BattlePokemon whose underlying Pokemon is an Egg
        team.removeIf(battlePokemon -> isEgg(battlePokemon.getEffectedPokemon()));
        return BattleRegistry.INSTANCE.packTeam(team);
    }

    @Unique
    private static final String EGG_NBT_KEY = "is_egg"; // Use the same key as in PastureBlockMixin

    /**
     * Checks if the given Pokemon instance is marked as an Egg via NBT data.
     * @param pokemon The Pokemon instance to check.
     * @return true if the Pokemon is not null and has the "is_egg" NBT tag set to true, false otherwise.
     */
    @Unique
    private static boolean isEgg(Pokemon pokemon) {
        if (pokemon == null) {
            return false;
        }
        NbtCompound pData = pokemon.getPersistentData();
        return pData.getBoolean(EGG_NBT_KEY); // Returns false if the key doesn't exist or is false
    }
}