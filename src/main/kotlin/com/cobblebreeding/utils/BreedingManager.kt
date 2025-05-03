package com.cobblebreeding.utils

import com.cobblebreeding.BreedingGuiState
import com.cobblebreeding.CobblemonBreeding
import com.cobblebreeding.mixin.MobEntityAccessor
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.mob.MobEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.EnumSet

object BreedingManager {

    private const val STAND_DURATION_TICKS = 60L
    private const val JUMP_DELAY_TICKS = 15L
    private const val JUMP_COUNT_TARGET = 2
    const val TICK_THROTTLE = 5L
    // MEETING_THRESHOLD_SQ is no longer used for success check, but WalkToPositionGoal might still use it internally
    const val MEETING_THRESHOLD_SQ = 1.2 * 1.2
    private const val WALK_TIMEOUT_EXTRA_TICKS = 600L // Overall timeout if things go wrong
    private const val WALK_TO_MEET_DURATION_TICKS = 100L // How long they walk towards each other (e.g., 5 seconds)

    val EGG_SPECIES_ID = Identifier.of("cobblemon", "egg")
    const val TARGET_SPECIES_NBT_KEY = "target_species"
    const val EGG_NBT_KEY = "is_egg"

    fun startBreeding(
        pasturePos: BlockPos,
        state: BreedingGuiState,
        world: ServerWorld
    ) {
        println("DEBUG: BreedingManager.startBreeding called for pasture $pasturePos")
        if (state.malePokemonUUID == null || state.femalePokemonUUID == null) {
            println("DEBUG: BreedingManager.startBreeding cancelled: Missing parent UUIDs.")
            return
        }
        state.world = world
        state.breedingStartTick = world.time
        state.walkingStarted = false
        state.meetingPoint = null
        state.eggItemStack = null
        state.meetingEndTime = null
        state.jumpCount = 0
        state.lastJumpTick = 0L
        state.heartsPlayed = false
        println("DEBUG: BreedingManager - State reset and breeding initiated: startTick=${state.breedingStartTick}")
        CobblemonBreeding.logger.debug("Breeding process initiated at tick ${world.time} for pasture $pasturePos")
    }

    fun tickBreedingProcess(pasturePos: BlockPos, state: BreedingGuiState) {
        if (state.world?.time?.rem(TICK_THROTTLE) != 0L) { return }
        if (state.breedingStartTick == null || state.world == null || state.malePokemonUUID == null || state.femalePokemonUUID == null) { return }
        if (state.eggItemStack != null) { return }

        val world = state.world!!
        val currentTick = world.time
        val elapsedTicks = currentTick - state.breedingStartTick!!

        val pastureBlockEntity = world.getBlockEntity(pasturePos) as? PokemonPastureBlockEntity
        if (pastureBlockEntity == null) { handleCancellation("Pasture Block Entity not found.", pasturePos, state, null, null); return }
        val maleTether = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.malePokemonUUID }
        val femaleTether = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.femalePokemonUUID }
        if (maleTether == null || femaleTether == null) { handleCancellation("One or both tethering objects missing.", pasturePos, state, null, null); return }
        val targetMaleEntityId: Int = maleTether.entityId
        val targetFemaleEntityId: Int = femaleTether.entityId
        var localMaleEntity: PokemonEntity? = null
        var localFemaleEntity: PokemonEntity? = null
        val searchBox = Box(Vec3d.of(pastureBlockEntity.minRoamPos), Vec3d.of(pastureBlockEntity.maxRoamPos.add(1, 1, 1)))
        val nearbyEntities = world.getEntitiesByClass(PokemonEntity::class.java, searchBox) { entity -> entity.id == targetMaleEntityId || entity.id == targetFemaleEntityId }
        nearbyEntities.forEach { if (it.id == targetMaleEntityId) localMaleEntity = it else if (it.id == targetFemaleEntityId) localFemaleEntity = it }
        val maleEntity = localMaleEntity
        val femaleEntity = localFemaleEntity
        if (maleEntity == null || femaleEntity == null) { handleCancellation("One or both entities not found in pasture area.", pasturePos, state, maleEntity, femaleEntity); return }

        // --- State Machine ---

        // 1. Pre-Walking Phase
        if (!state.walkingStarted && state.meetingEndTime == null && elapsedTicks < state.breedingDurationTicks) {
            if (currentTick % 40 == 0L) {
                world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, maleEntity.x, maleEntity.eyeY + 0.5, maleEntity.z, 1, 0.3, 0.3, 0.3, 0.0)
                world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, femaleEntity.x, femaleEntity.eyeY + 0.5, femaleEntity.z, 1, 0.3, 0.3, 0.3, 0.0)
            }
        }
        // 2. Start Walking Phase
        else if (!state.walkingStarted && state.meetingEndTime == null && elapsedTicks >= state.breedingDurationTicks) {
            CobblemonBreeding.logger.debug("Duration met. Initiating walk sequence for $pasturePos.")
            if (state.meetingPoint == null) { state.meetingPoint = calculateMeetingPoint(maleEntity, femaleEntity) }
            state.meetingPoint?.let { mp -> addWalkGoalIfNotPresent(maleEntity, mp); addWalkGoalIfNotPresent(femaleEntity, mp) }
            state.walkingStarted = true
            println("DEBUG: Walking started towards ${state.meetingPoint} at tick $currentTick")
        }
        // 3. Walking / "Meeting" Check Phase (Timer Based)
        else if (state.walkingStarted && state.meetingPoint != null && state.meetingEndTime == null) {
            // Check if enough time has passed since breeding check + walk start time
            if (elapsedTicks >= state.breedingDurationTicks + WALK_TO_MEET_DURATION_TICKS) {
                println("DEBUG: Walk-to-meet duration reached ($elapsedTicks >= ${state.breedingDurationTicks + WALK_TO_MEET_DURATION_TICKS}). Triggering 'met' state.")
                CobblemonBreeding.logger.debug("Walk-to-meet duration reached at $pasturePos. Starting animation.")
                removeWalkGoal(maleEntity)
                removeWalkGoal(femaleEntity)
                state.meetingEndTime = currentTick
                state.jumpCount = 0
                state.lastJumpTick = currentTick
                state.heartsPlayed = false
            }
            // Still check for overall timeout if the timer somehow fails or walk goal gets stuck
            else if (elapsedTicks > state.breedingDurationTicks + WALK_TIMEOUT_EXTRA_TICKS) {
                handleCancellation("Breeding timed out (Walk took too long).", pasturePos, state, maleEntity, femaleEntity)
            }
            // Else: Still walking towards target, wait for timer
        }
        // 4. Post-Meeting Animation Phase
        else if (state.meetingEndTime != null) {
            val meetingEndTime = state.meetingEndTime!!
            val ticksSinceMeeting = currentTick - meetingEndTime
            if (ticksSinceMeeting < STAND_DURATION_TICKS) {}
            else if (state.jumpCount < JUMP_COUNT_TARGET) {
                if (currentTick >= state.lastJumpTick + JUMP_DELAY_TICKS) {
                    maleEntity.jump()
                    world.playSound(null, maleEntity.blockPos, SoundEvents.ENTITY_RABBIT_JUMP, SoundCategory.NEUTRAL, 0.5f, world.random.nextFloat() * 0.2f + 0.9f)
                    state.jumpCount++; state.lastJumpTick = currentTick
                }
            }
            else if (!state.heartsPlayed) {
                world.spawnParticles(ParticleTypes.HEART, maleEntity.x, maleEntity.eyeY, maleEntity.z, 7, 0.5, 0.5, 0.5, 0.02)
                world.spawnParticles(ParticleTypes.HEART, femaleEntity.x, femaleEntity.eyeY, femaleEntity.z, 7, 0.5, 0.5, 0.5, 0.02)
                state.heartsPlayed = true
            }
            else {
                println("DEBUG: Breeding animation complete at $pasturePos. Creating visual egg item.")
                if (state.eggItemStack == null) {
                    state.eggItemStack = createVisualEggItemStack()
                    println("DEBUG: EggItemStack created and stored in state for $pasturePos.")
                }
                state.breedingStartTick = null
                state.meetingPoint = null
                state.walkingStarted = false
                CobblemonBreeding.logger.debug("Breeding process concluded successfully at $pasturePos. Visual Egg produced.")
            }
            // Keep animation timeout as well
            if (elapsedTicks > state.breedingDurationTicks + WALK_TIMEOUT_EXTRA_TICKS + STAND_DURATION_TICKS + (JUMP_COUNT_TARGET * JUMP_DELAY_TICKS) + 200L) { // Extended overall timeout slightly
                handleCancellation("Breeding animation timed out.", pasturePos, state, maleEntity, femaleEntity)
            }
        }
    }

    fun generateAndGiveEggToParty(pasturePos: BlockPos, state: BreedingGuiState, player: ServerPlayerEntity): Boolean {
        println("DEBUG: generateAndGiveEggToParty called for player ${player.name.string} at $pasturePos")
        if (state.malePokemonUUID == null || state.femalePokemonUUID == null || state.world == null) { return false }
        val world = state.world!!
        val pastureBlockEntity = world.getBlockEntity(pasturePos) as? PokemonPastureBlockEntity
        if (pastureBlockEntity == null) { return false }

        val femalePokemon = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.femalePokemonUUID }?.getPokemon()
        val malePokemon = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.malePokemonUUID }?.getPokemon()

        if (femalePokemon == null || malePokemon == null) {
            println("ERROR: Could not find one or both parent Pokémon for egg species determination.")
            return false
        }

        val targetSpecies: Species = femalePokemon.species // Simplified assumption, adjust if needed for Ditto etc.

        val customEggSpecies = PokemonSpecies.getByIdentifier(EGG_SPECIES_ID)
        if (customEggSpecies == null) {
            println("ERROR: Custom egg species '$EGG_SPECIES_ID' not found!")
            player.sendMessage(Text.literal("Error: Egg species definition missing!").formatted(Formatting.RED), false)
            return false
        }

        val targetSpeciesIdString = targetSpecies.showdownId()

        val eggPokemon = Pokemon()
        eggPokemon.species = customEggSpecies
        eggPokemon.level = 1
        eggPokemon.persistentData.putBoolean(EGG_NBT_KEY, true)
        eggPokemon.persistentData.putString(TARGET_SPECIES_NBT_KEY, targetSpeciesIdString)
        println("DEBUG: Stored target species '$targetSpeciesIdString' in NBT Key '$TARGET_SPECIES_NBT_KEY'")
        eggPokemon.setOriginalTrainer(player.uuid)

        val eggNicknameText = Text.literal("${targetSpecies.name} Egg")
        eggPokemon.nickname = eggNicknameText
        println("DEBUG: Set egg nickname to '${eggNicknameText.string}'")

        val playerParty = Cobblemon.storage.getParty(player)
        if (playerParty == null) { return false }

        val added = playerParty.add(eggPokemon)
        if (added) {
            println("DEBUG: Generated custom Egg Pokemon ('${eggNicknameText.string}') added to party for player ${player.name.string}")
            state.eggItemStack = null
            cancelBreeding(state, null, null)
            return true
        } else {
            println("WARN: partyStore.add() returned false. Party likely full for player ${player.name.string}")
            player.sendMessage(Text.literal("Your party is full! Make space to receive the Egg.").formatted(Formatting.YELLOW), false)
            return false
        }
    }

    private fun calculateMeetingPoint(entity1: PokemonEntity, entity2: PokemonEntity): Vec3d {
        val avgX = (entity1.x + entity2.x) / 2.0; val avgZ = (entity1.z + entity2.z) / 2.0
        val avgY = (entity1.y + entity2.y) / 2.0; var groundY = avgY
        val world = entity1.world; val checkPos = BlockPos.Mutable(avgX.toInt(), avgY.toInt(), avgZ.toInt())
        for (yOff in 0..5) {
            checkPos.y = (avgY - yOff).toInt()
            if (!world.getBlockState(checkPos.down()).isAir && world.getBlockState(checkPos).isAir) { groundY = checkPos.y.toDouble(); break }
            else if (!world.getBlockState(checkPos).isAir && world.getBlockState(checkPos.up()).isAir) { groundY = checkPos.y + 1.0; break }
        }
        if (groundY == avgY) groundY = avgY + 0.1
        return Vec3d(avgX, groundY, avgZ)
    }

    private fun handleCancellation(reason: String, pasturePos: BlockPos, state: BreedingGuiState, maleEntity: PokemonEntity?, femaleEntity: PokemonEntity?) {
        println("DEBUG: BreedingManager - Cancelling breeding at $pasturePos. Reason: $reason")
        CobblemonBreeding.logger.warn("Breeding cancelled at $pasturePos: $reason")
        cancelBreeding(state, maleEntity, femaleEntity)
    }

    private fun addWalkGoalIfNotPresent(entity: PokemonEntity, targetPos: Vec3d) {
        try {
            val mobEntity = entity as? MobEntity ?: return
            val goalSelector = (mobEntity as MobEntityAccessor).getGoalSelector()
            val existingGoal = goalSelector.goals.find { gw -> (gw.goal as? WalkToPositionGoal)?.isTarget(targetPos) == true }
            if (existingGoal == null) {
                removeWalkGoal(entity)
                val newGoal = WalkToPositionGoal(mobEntity, targetPos, 1.0)
                goalSelector.add(1, newGoal)
            }
        } catch (e: Exception) { CobblemonBreeding.logger.error("Failed to add/check walk goal for ${entity.uuid}", e) }
    }

    private fun removeWalkGoal(entity: PokemonEntity) {
        try {
            val mobEntity = entity as? MobEntity ?: return
            val goalSelector = (mobEntity as MobEntityAccessor).getGoalSelector()
            val goalsToRemove = goalSelector.goals.mapNotNull { it.goal as? WalkToPositionGoal }.toList()
            goalsToRemove.forEach { goalSelector.remove(it) }
        } catch (e: Exception) { CobblemonBreeding.logger.error("Error removing walk goal for entity ${entity.uuid}", e) }
    }

    fun cancelBreeding(state: BreedingGuiState, maleEntity: PokemonEntity?, femaleEntity: PokemonEntity?) {
        println("DEBUG: BreedingManager.cancelBreeding called.")
        maleEntity?.let { removeWalkGoal(it) }
        femaleEntity?.let { removeWalkGoal(it) }
        state.breedingStartTick = null
        state.meetingPoint = null
        state.walkingStarted = false
        state.eggItemStack = null
        state.meetingEndTime = null
        state.jumpCount = 0
        state.lastJumpTick = 0L
        state.heartsPlayed = false
        CobblemonBreeding.logger.debug("Breeding state fully reset.")
    }

    private fun createVisualEggItemStack(): ItemStack {
        val styleYellow = Style.EMPTY.withColor(Formatting.YELLOW).withItalic(false)
        val styleGray = Style.EMPTY.withColor(Formatting.GRAY).withItalic(false)
        return ItemStack(Items.TURTLE_EGG).apply {
            set(DataComponentTypes.CUSTOM_NAME, Text.literal("Pokémon Egg").setStyle(styleYellow))
            set(DataComponentTypes.LORE, LoreComponent(listOf(
                Text.literal("A Pokémon is developing inside!").setStyle(styleGray),
                Text.literal("Click to add to your party.").setStyle(styleGray)
            )))
        }
    }
}

class WalkToPositionGoal( private val mob: MobEntity, private val targetPos: Vec3d, private val speed: Double ) : Goal() {
    private var stuckTicks = 0
    private val targetReachedThresholdSq = BreedingManager.MEETING_THRESHOLD_SQ
    init { controls = EnumSet.of(Control.MOVE) }
    fun isTarget(pos: Vec3d): Boolean { return this.targetPos.squaredDistanceTo(pos) < 0.01 }
    override fun canStart(): Boolean = true
    override fun start() { mob.navigation.startMovingTo(targetPos.x, targetPos.y, targetPos.z, speed); stuckTicks = 0 }
    override fun shouldContinue(): Boolean {
        if (mob.world.time % BreedingManager.TICK_THROTTLE != 0L) { return !mob.navigation.isIdle }
        val distSq = mob.pos.squaredDistanceTo(targetPos); val isNavigating = !mob.navigation.isIdle

        if (!isNavigating && distSq > targetReachedThresholdSq) {
            stuckTicks += BreedingManager.TICK_THROTTLE.toInt()
            if (stuckTicks > 60) { return false }
        } else { stuckTicks = 0 }

        return isNavigating
    }
    override fun stop() {
        if (!mob.navigation.isIdle) {
            val navTarget = mob.navigation.targetPos
            if (navTarget != null && navTarget.getSquaredDistance(targetPos) < 4.0) {
                mob.navigation.stop()
            }
        }
        stuckTicks = 0
    }
    override fun tick() {
        if (mob.world.time % BreedingManager.TICK_THROTTLE != 0L) { return }
        if (mob.navigation.isIdle && mob.pos.squaredDistanceTo(targetPos) > targetReachedThresholdSq) {
            if (stuckTicks > 20 && stuckTicks % 20 == 0 && stuckTicks <= 60) {
                mob.navigation.startMovingTo(targetPos.x, targetPos.y, targetPos.z, speed)
            }
        }
    }
}