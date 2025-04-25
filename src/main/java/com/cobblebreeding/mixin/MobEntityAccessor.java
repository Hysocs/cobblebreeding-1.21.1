package com.cobblebreeding.mixin; // Adjust package if needed

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MobEntity.class)
public interface MobEntityAccessor {
    // Use the correct name based on mappings (usually "goalSelector" in Yarn/Mojmap)
    @Accessor("goalSelector")
    GoalSelector getGoalSelector();

    // Optional: Accessor for targetSelector if needed later
    // @Accessor("targetSelector")
    // GoalSelector getTargetSelector();
}