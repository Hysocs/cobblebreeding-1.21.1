package com.cobblebreeding.utils

import com.cobblebreeding.BreedingState
import com.cobblebreeding.CobblemonBreeding
import com.cobblebreeding.mixin.MobEntityAccessor
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.pasture.PokemonPasturedPacket
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species

import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.mob.MobEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.*

object BreedingManager {

    private const val STAND_DURATION_TICKS = 60L
    private const val JUMP_DELAY_TICKS = 15L
    private const val JUMP_COUNT_TARGET = 2
    const val TICK_THROTTLE = 5L
    const val MEETING_THRESHOLD_SQ = 1.2 * 1.2
    private const val WALK_TIMEOUT_EXTRA_TICKS = 600L
    private const val WALK_TO_MEET_DURATION_TICKS = 100L

    val EGG_SPECIES_ID = Identifier.of("cobblemon", "egg")
    const val TARGET_SPECIES_NBT_KEY = "target_species"
    const val EGG_NBT_KEY = "is_egg"

    fun tickBreedingProcess(pasturePos: BlockPos, state: BreedingState) {
        val world = state.world ?: return
        if (world.time % TICK_THROTTLE != 0L) return

        val pastureBlockEntity = world.getBlockEntity(pasturePos) as? PokemonPastureBlockEntity ?: return

        if (state.breedingStartTick == null) {
            val tetheredPokemon = pastureBlockEntity.tetheredPokemon
            val validTethers = tetheredPokemon.mapNotNull { tether ->
                tether.getPokemon()?.let { pokemon -> Pair(tether, pokemon) }
            }
            val speciesMap = validTethers.groupBy { it.second.species }

            for ((species, tethersWithPokemon) in speciesMap) {
                if (species == null) continue
                val actualTethers = tethersWithPokemon.map { it.first }
                val males = actualTethers.filter { it.getPokemon()?.gender == Gender.MALE }
                val females = actualTethers.filter { it.getPokemon()?.gender == Gender.FEMALE }
                if (males.isNotEmpty() && females.isNotEmpty()) {
                    val maleTether = males.first()
                    val femaleTether = females.first()
                    state.malePokemonUUID = maleTether.pokemonId
                    state.femalePokemonUUID = femaleTether.pokemonId
                    state.breedingStartTick = world.time
                    state.walkingStarted = false
                    state.meetingPoint = null
                    state.meetingEndTime = null
                    state.jumpCount = 0
                    state.lastJumpTick = 0L
                    state.heartsPlayed = false
                    state.world = world
                    break
                }
            }
            return
        }

        val currentTick = world.time
        val elapsedTicks = currentTick - state.breedingStartTick!!

        val maleEntity = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, state.malePokemonUUID!!)
        val femaleEntity = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, state.femalePokemonUUID!!)
        if (maleEntity == null || femaleEntity == null) {
            cancelBreeding(state, maleEntity, femaleEntity)
            return
        }

        if (!state.walkingStarted && state.meetingEndTime == null && elapsedTicks < state.breedingDurationTicks) {
            if (currentTick % 40 == 0L) {
                world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, maleEntity.x, maleEntity.eyeY + 0.5, maleEntity.z, 1, 0.3, 0.3, 0.3, 0.0)
                world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, femaleEntity.x, femaleEntity.eyeY + 0.5, femaleEntity.z, 1, 0.3, 0.3, 0.3, 0.0)
            }
        }
        else if (!state.walkingStarted && state.meetingEndTime == null && elapsedTicks >= state.breedingDurationTicks) {
            state.meetingPoint = calculateMeetingPoint(maleEntity, femaleEntity)
            state.meetingPoint?.let { mp ->
                addWalkGoalIfNotPresent(maleEntity, mp)
                addWalkGoalIfNotPresent(femaleEntity, mp)
            }
            state.walkingStarted = true
        }
        else if (state.walkingStarted && state.meetingPoint != null && state.meetingEndTime == null) {
            if (elapsedTicks >= state.breedingDurationTicks + WALK_TO_MEET_DURATION_TICKS) {
                removeWalkGoal(maleEntity)
                removeWalkGoal(femaleEntity)
                state.meetingEndTime = currentTick
                state.jumpCount = 0
                state.lastJumpTick = currentTick
                state.heartsPlayed = false
            }
            else if (elapsedTicks > state.breedingDurationTicks + WALK_TIMEOUT_EXTRA_TICKS) {
                cancelBreeding(state, maleEntity, femaleEntity)
            }
        }
        else if (state.meetingEndTime != null) {
            val ticksSinceMeeting = currentTick - state.meetingEndTime!!
            if (ticksSinceMeeting < STAND_DURATION_TICKS) {}
            else if (state.jumpCount < JUMP_COUNT_TARGET) {
                if (currentTick >= state.lastJumpTick + JUMP_DELAY_TICKS) {
                    maleEntity.jump()
                    world.playSound(null, maleEntity.blockPos, SoundEvents.ENTITY_RABBIT_JUMP, SoundCategory.NEUTRAL, 0.5f, world.random.nextFloat() * 0.2f + 0.9f)
                    state.jumpCount++
                    state.lastJumpTick = currentTick
                }
            }
            else if (!state.heartsPlayed) {
                world.spawnParticles(ParticleTypes.HEART, maleEntity.x, maleEntity.eyeY, maleEntity.z, 7, 0.5, 0.5, 0.5, 0.02)
                world.spawnParticles(ParticleTypes.HEART, femaleEntity.x, femaleEntity.eyeY, femaleEntity.z, 7, 0.5, 0.5, 0.5, 0.02)
                state.heartsPlayed = true
            }
            else {
                val femaleTether = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.femalePokemonUUID }
                if (femaleTether != null) {
                    val ownerUUID = femaleTether.playerId
                    val ownerPlayer = world.server.getPlayerManager().getPlayer(ownerUUID)
                    if (ownerPlayer != null) {
                        val success = generateAndAddEggToPasture(pasturePos, state, ownerPlayer)
                        cancelBreeding(state, maleEntity, femaleEntity)
                    } else {
                        println("${CobblemonBreeding.MOD_ID} INFO: Owner of breeding Pokémon offline. Cancelling breeding at $pasturePos.")
                        cancelBreeding(state, maleEntity, femaleEntity)
                    }
                } else {
                    println("${CobblemonBreeding.MOD_ID} INFO: Female Pokémon missing during egg creation. Cancelling breeding at $pasturePos.")
                    cancelBreeding(state, maleEntity, femaleEntity)
                }
            }
        }
    }

    private fun getPokemonEntityByPokemonUUID(world: ServerWorld, pastureBlockEntity: PokemonPastureBlockEntity, pokemonUuid: UUID): PokemonEntity? {
        val searchBox = Box(Vec3d.of(pastureBlockEntity.minRoamPos), Vec3d.of(pastureBlockEntity.maxRoamPos.add(1, 1, 1)))
        val potentialEntities = world.getEntitiesByClass(PokemonEntity::class.java, searchBox) { entity ->
            entity.pokemon?.uuid == pokemonUuid
        }
        return potentialEntities.firstOrNull()
    }


    fun generateAndAddEggToPasture(pasturePos: BlockPos, state: BreedingState, player: ServerPlayerEntity): Boolean {
        val world = state.world ?: return false
        val pastureBlockEntity = world.getBlockEntity(pasturePos) as? PokemonPastureBlockEntity ?: return false

        val maxTotalPokemon = pastureBlockEntity.getMaxTethered()
        if (pastureBlockEntity.tetheredPokemon.size >= maxTotalPokemon) {
            player.sendMessage(Text.literal("The pasture is full! Could not add the Pokémon Egg.").formatted(Formatting.YELLOW), false)
            return false
        }

        val femaleTether = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.femalePokemonUUID }
        val maleTether = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.malePokemonUUID }

        if (femaleTether == null || maleTether == null) {
            println("${CobblemonBreeding.MOD_ID} ERROR: Parent Pokemon missing from pasture during egg creation.")
            return false
        }
        val femalePokemon = femaleTether.getPokemon() ?: run {
            println("${CobblemonBreeding.MOD_ID} ERROR: Female Pokemon data missing during egg creation.")
            return false
        }

        val customEggSpecies = PokemonSpecies.getByIdentifier(EGG_SPECIES_ID) ?: run {
            println("${CobblemonBreeding.MOD_ID} ERROR: Could not find cobblemon:egg species!")
            return false
        }

        val parentSpeciesHeight = femalePokemon.species.height.toFloat()
        val parentScaleModifier = femalePokemon.scaleModifier
        val effectiveParentHeight = parentSpeciesHeight * parentScaleModifier

        // Define a normalization divisor - adjust this based on average Pokemon height and desired average egg scale
        val normalizationDivisor = 15.0f
        val calculatedScale = effectiveParentHeight / normalizationDivisor

        // Clamp the final scale to prevent extreme sizes
        val desiredEggScale = calculatedScale.coerceIn(0.3f, 1.2f) // Adjust min/max as needed

        val targetSpecies: Species = femalePokemon.species
        val targetSpeciesIdString = targetSpecies.showdownId()

        val eggPokemon = Pokemon().apply {
            species = customEggSpecies
            level = 1
            persistentData.putBoolean(EGG_NBT_KEY, true)
            persistentData.putString(TARGET_SPECIES_NBT_KEY, targetSpeciesIdString)
            setOriginalTrainer(player.uuid)
            nickname = Text.literal("${targetSpecies.name} Egg")
            this.scaleModifier = desiredEggScale
        }

        val eggEntity = PokemonEntity(world, pokemon = eggPokemon)
        val spawnPosVec = pasturePos.toCenterPos()
        eggEntity.refreshPositionAndAngles(spawnPosVec.x, spawnPosVec.y, spawnPosVec.z, world.random.nextFloat() * 360.0f, 0.0f)

        val spawned = world.spawnEntity(eggEntity)

        val pcStore = Cobblemon.storage.getPC(player) ?: return false
        if (!pcStore.add(eggPokemon)) {
            if(spawned) eggEntity.discard()
            player.sendMessage(Text.literal("Your PC is full! Could not store the Pokémon Egg.").formatted(Formatting.YELLOW), false)
            return false
        }

        val newTetheringId = UUID.randomUUID()
        eggPokemon.tetheringId = newTetheringId

        val eggTethering = PokemonPastureBlockEntity.Tethering(
            minRoamPos = pastureBlockEntity.minRoamPos,
            maxRoamPos = pastureBlockEntity.maxRoamPos,
            playerId = player.uuid,
            playerName = player.gameProfile.name,
            tetheringId = newTetheringId,
            pokemonId = eggPokemon.uuid,
            pcId = pcStore.uuid,
            entityId = if (spawned) eggEntity.id else -1
        )

        pastureBlockEntity.tetheredPokemon.add(eggTethering)
        if (spawned) {
            eggEntity.tethering = eggTethering
        } else {
            println("${CobblemonBreeding.MOD_ID} WARN: Failed to spawn egg entity at $pasturePos, but data was added.")
        }

        pastureBlockEntity.markDirty()

        eggTethering.toDTO(player)?.let { dto ->
            com.cobblemon.mod.common.CobblemonNetwork.sendPacketToPlayer(player, PokemonPasturedPacket(dto))
        }

        player.sendMessage(Text.literal("A Pokémon Egg has been added to the pasture!").formatted(Formatting.GREEN), false)
        return true
    }
    private fun calculateMeetingPoint(entity1: PokemonEntity, entity2: PokemonEntity): Vec3d {
        val avgX = (entity1.x + entity2.x) / 2.0
        val avgZ = (entity1.z + entity2.z) / 2.0
        val avgY = (entity1.y + entity2.y) / 2.0
        var groundY = avgY
        val world = entity1.world
        val checkPos = BlockPos.Mutable(avgX.toInt(), avgY.toInt(), avgZ.toInt())
        for (yOff in 0..5) {
            checkPos.y = (avgY - yOff).toInt()
            if (!world.getBlockState(checkPos.down()).isAir && world.getBlockState(checkPos).isAir) {
                groundY = checkPos.y.toDouble()
                break
            }
            else if (!world.getBlockState(checkPos).isAir && world.getBlockState(checkPos.up()).isAir) {
                groundY = checkPos.y + 1.0
                break
            }
        }
        if (groundY == avgY) groundY = avgY + 0.1
        return Vec3d(avgX, groundY, avgZ)
    }

    fun checkForInitialBreeders(pastureBlockEntity: PokemonPastureBlockEntity, state: BreedingState) {
        if (state.breedingStartTick != null) {
            return
        }

        val world = state.world ?: return

        val tetheredPokemon = pastureBlockEntity.tetheredPokemon
        if (tetheredPokemon.size < 2) return

        val validTethers = tetheredPokemon.mapNotNull { tether ->
            tether.getPokemon()?.let { pokemon -> Pair(tether, pokemon) }
        }

        val speciesMap = validTethers.groupBy { it.second.species }

        for ((species, tethersWithPokemon) in speciesMap) {
            if (species == null) continue

            val actualTethers = tethersWithPokemon.map { it.first }

            val males = actualTethers.filter { it.getPokemon()?.gender == Gender.MALE }
            val females = actualTethers.filter { it.getPokemon()?.gender == Gender.FEMALE }

            if (males.isNotEmpty() && females.isNotEmpty()) {
                val maleTether = males.first()
                val femaleTether = females.first()

                state.malePokemonUUID = maleTether.pokemonId
                state.femalePokemonUUID = femaleTether.pokemonId
                state.breedingStartTick = world.time
                state.walkingStarted = false
                state.meetingPoint = null
                state.meetingEndTime = null
                state.jumpCount = 0
                state.lastJumpTick = 0L
                state.heartsPlayed = false

                println("${CobblemonBreeding.MOD_ID} INFO: Found initial breeding pair (${maleTether.getPokemon()?.species?.name}) at ${pastureBlockEntity.pos} on load. Starting process.")

                return
            }
        }
    }


    private fun addWalkGoalIfNotPresent(entity: PokemonEntity, targetPos: Vec3d) {
        val mobEntity = entity as? MobEntity ?: return
        val goalSelector = (mobEntity as MobEntityAccessor).goalSelector
        val existingGoal = goalSelector.goals.find { (it.goal as? WalkToPositionGoal)?.isTarget(targetPos) == true }
        if (existingGoal == null) {
            removeWalkGoal(entity)
            val newGoal = WalkToPositionGoal(mobEntity, targetPos, 1.0)
            goalSelector.add(1, newGoal)
        }
    }

    private fun removeWalkGoal(entity: PokemonEntity) {
        val mobEntity = entity as? MobEntity ?: return
        val goalSelector = (mobEntity as MobEntityAccessor).goalSelector
        val goalsToRemove = goalSelector.goals.filter { it.goal is WalkToPositionGoal }
        goalsToRemove.forEach { goalSelector.remove(it.goal) }
    }

    fun cancelBreeding(state: BreedingState, maleEntity: PokemonEntity?, femaleEntity: PokemonEntity?) {
        maleEntity?.let { removeWalkGoal(it) }
        femaleEntity?.let { removeWalkGoal(it) }
        state.breedingStartTick = null
        state.meetingPoint = null
        state.walkingStarted = false
        state.meetingEndTime = null
        state.jumpCount = 0
        state.lastJumpTick = 0L
        state.heartsPlayed = false
    }
}

class WalkToPositionGoal(private val mob: MobEntity, private val targetPos: Vec3d, private val speed: Double) : Goal() {
    private var stuckTicks = 0
    private val targetReachedThresholdSq = BreedingManager.MEETING_THRESHOLD_SQ
    init { controls = EnumSet.of(Control.MOVE) }
    fun isTarget(pos: Vec3d): Boolean = this.targetPos.squaredDistanceTo(pos) < 0.01
    override fun canStart(): Boolean = true
    override fun start() {
        mob.navigation.startMovingTo(targetPos.x, targetPos.y, targetPos.z, speed)
        stuckTicks = 0
    }
    override fun shouldContinue(): Boolean {
        if (mob.world.time % BreedingManager.TICK_THROTTLE != 0L) return !mob.navigation.isIdle
        val distSq = mob.pos.squaredDistanceTo(targetPos)
        val isNavigating = !mob.navigation.isIdle
        if (!isNavigating && distSq > targetReachedThresholdSq) {
            stuckTicks += BreedingManager.TICK_THROTTLE.toInt()
            if (stuckTicks > 60) return false
        }
        else {
            stuckTicks = 0
        }
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
        if (mob.world.time % BreedingManager.TICK_THROTTLE != 0L) return
        if (mob.navigation.isIdle && mob.pos.squaredDistanceTo(targetPos) > targetReachedThresholdSq) {
            if (stuckTicks > 20 && stuckTicks % 20 == 0 && stuckTicks <= 60) {
                mob.navigation.startMovingTo(targetPos.x, targetPos.y, targetPos.z, speed)
            }
        }
    }
}