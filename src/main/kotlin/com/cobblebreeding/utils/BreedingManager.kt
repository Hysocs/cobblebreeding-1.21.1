package com.cobblebreeding.utils

import com.cobblebreeding.BreedingState
import com.cobblebreeding.CobblemonBreeding
import com.cobblebreeding.mixin.MobEntityAccessor
import com.cobblebreeding.utils.BreedingManager.LOGGER
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.Natures
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.pasture.PokemonPasturedPacket
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Nature
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.mob.MobEntity
import net.minecraft.item.ItemStack
import net.minecraft.particle.DustParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
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
import org.joml.Vector3f
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.EnumSet
import kotlin.math.roundToLong
import kotlin.random.Random

object BreedingManager {

    val LOGGER: Logger = LoggerFactory.getLogger(CobblemonBreeding.MOD_ID + "-BreedingManager")

    private const val STAND_DURATION_TICKS = 60L
    private const val JUMP_DELAY_TICKS = 15L
    private const val JUMP_COUNT_TARGET = 2
    const val TICK_THROTTLE = 5L
    const val MEETING_THRESHOLD_SQ = 1.2 * 1.2
    private const val WALK_TIMEOUT_EXTRA_TICKS = 600L
    private const val WALK_TO_MEET_DURATION_TICKS = 100L
    private const val MAX_HEART_DISTANCE_SQ = 4.0 * 4.0

    private const val BASE_BREEDING_DURATION_TICKS = 100L
    private const val RADIUS = 4
    private const val WALK_RADIUS = 5
    private const val TIER_3_REDUCTION = 0.50
    private const val TIER_2_REDUCTION = 0.25

    private val typeToBlocks: Map<String, List<Block>> = mapOf(
        "normal" to listOf(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.HAY_BLOCK, Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG),
        "fire" to listOf(Blocks.MAGMA_BLOCK, Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE, Blocks.GLOWSTONE, Blocks.COAL_BLOCK),
        "water" to listOf(Blocks.SAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.WATER, Blocks.PRISMARINE, Blocks.DARK_PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.WET_SPONGE, Blocks.TUBE_CORAL_BLOCK, Blocks.BRAIN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK),
        "grass" to listOf(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MOSS_BLOCK, Blocks.DIRT, Blocks.ROOTED_DIRT, Blocks.MUD, Blocks.OAK_LOG, Blocks.JUNGLE_LOG, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.MANGROVE_ROOTS, Blocks.HAY_BLOCK),
        "electric" to listOf(Blocks.COPPER_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.EXPOSED_COPPER, Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER, Blocks.WAXED_COPPER_BLOCK, Blocks.LIGHTNING_ROD, Blocks.IRON_BLOCK, Blocks.TARGET),
        "ice" to listOf(Blocks.ICE, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.POWDER_SNOW, Blocks.SNOW, Blocks.WHITE_WOOL, Blocks.CYAN_TERRACOTTA),
        "fighting" to listOf(Blocks.STONE_BRICKS, Blocks.TERRACOTTA, Blocks.BRICKS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_GRANITE, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.TARGET, Blocks.ANVIL),
        "poison" to listOf(Blocks.MYCELIUM, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SLIME_BLOCK, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.SOUL_SOIL, Blocks.MAGENTA_GLAZED_TERRACOTTA, Blocks.PURPLE_TERRACOTTA),
        "ground" to listOf(Blocks.SAND, Blocks.RED_SAND, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.ROOTED_DIRT, Blocks.MUD, Blocks.PACKED_MUD, Blocks.DRIPSTONE_BLOCK),
        "flying" to listOf(Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.AMETHYST_BLOCK, Blocks.SMOOTH_BASALT, Blocks.END_STONE, Blocks.WHITE_CONCRETE_POWDER),
        "psychic" to listOf(Blocks.AMETHYST_BLOCK, Blocks.END_STONE, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.END_STONE_BRICKS, Blocks.CRYING_OBSIDIAN, Blocks.LAPIS_BLOCK, Blocks.GLOWSTONE, Blocks.MAGENTA_GLAZED_TERRACOTTA),
        "bug" to listOf(Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.HONEYCOMB_BLOCK, Blocks.BEEHIVE, Blocks.BEE_NEST, Blocks.SLIME_BLOCK, Blocks.COBWEB, Blocks.MANGROVE_ROOTS),
        "rock" to listOf(Blocks.STONE, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.RAW_IRON_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.RAW_COPPER_BLOCK),
        "ghost" to listOf(Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.COBBLED_DEEPSLATE, Blocks.SCULK, Blocks.COBWEB, Blocks.BONE_BLOCK, Blocks.RESPAWN_ANCHOR),
        "dragon" to listOf(Blocks.OBSIDIAN, Blocks.END_STONE, Blocks.END_STONE_BRICKS, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.CRYING_OBSIDIAN, Blocks.AMETHYST_BLOCK, Blocks.DRAGON_EGG, Blocks.RESPAWN_ANCHOR, Blocks.BLACKSTONE),
        "dark" to listOf(Blocks.COAL_BLOCK, Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.SOUL_SOIL, Blocks.SCULK, Blocks.TINTED_GLASS),
        "steel" to listOf(Blocks.IRON_BLOCK, Blocks.RAW_IRON_BLOCK, Blocks.SMOOTH_BASALT, Blocks.POLISHED_BASALT, Blocks.CHAIN, Blocks.ANVIL, Blocks.SMITHING_TABLE, Blocks.GRINDSTONE, Blocks.IRON_BARS, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.CYAN_TERRACOTTA, Blocks.DEEPSLATE_TILES),
        "fairy" to listOf(Blocks.PINK_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.MOSS_BLOCK, Blocks.CLAY, Blocks.AMETHYST_BLOCK, Blocks.FLOWERING_AZALEA, Blocks.PINK_WOOL, Blocks.GLOWSTONE, Blocks.SEA_LANTERN)
    )
    private val typeToDecorations: Map<String, List<Block>> = mapOf(
        "normal" to listOf(Blocks.POPPY, Blocks.DANDELION, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY, Blocks.WHEAT),
        "fire" to listOf(Blocks.NETHER_WART, Blocks.FIRE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.LAVA_CAULDRON, Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS, Blocks.NETHER_SPROUTS, Blocks.WEEPING_VINES, Blocks.TWISTING_VINES, Blocks.SHROOMLIGHT, Blocks.TORCH, Blocks.LANTERN),
        "water" to listOf(Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP_PLANT, Blocks.KELP, Blocks.TUBE_CORAL, Blocks.BRAIN_CORAL, Blocks.BUBBLE_CORAL, Blocks.FIRE_CORAL, Blocks.HORN_CORAL, Blocks.TUBE_CORAL_FAN, Blocks.BRAIN_CORAL_FAN, Blocks.BUBBLE_CORAL_FAN, Blocks.FIRE_CORAL_FAN, Blocks.HORN_CORAL_FAN, Blocks.LILY_PAD, Blocks.SEA_PICKLE, Blocks.CONDUIT, Blocks.SCULK_VEIN),
        "grass" to listOf(Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.VINE, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY, Blocks.SUNFLOWER, Blocks.SPORE_BLOSSOM, Blocks.BIG_DRIPLEAF, Blocks.SMALL_DRIPLEAF, Blocks.HANGING_ROOTS, Blocks.PUMPKIN, Blocks.MELON),
        "electric" to listOf(Blocks.REDSTONE_WIRE, Blocks.LIGHTNING_ROD, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_LAMP, Blocks.LEVER, Blocks.TRIPWIRE_HOOK, Blocks.POWERED_RAIL),
        "ice" to listOf(Blocks.SNOW, Blocks.POWDER_SNOW_CAULDRON, Blocks.WHITE_CARPET, Blocks.LIGHT_BLUE_CARPET, Blocks.CYAN_CARPET, Blocks.FROSTED_ICE),
        "fighting" to listOf(Blocks.BRICK_WALL, Blocks.TARGET, Blocks.STONE_BUTTON, Blocks.STONE_PRESSURE_PLATE, Blocks.GRINDSTONE, Blocks.BELL, Blocks.LOOM, Blocks.RED_CARPET),
        "poison" to listOf(Blocks.SLIME_BLOCK, Blocks.COBWEB, Blocks.WARPED_FUNGUS, Blocks.NETHER_SPROUTS, Blocks.LILAC, Blocks.PURPLE_CANDLE, Blocks.MAGENTA_CANDLE, Blocks.SCULK_VEIN, Blocks.WITHER_ROSE),
        "ground" to listOf(Blocks.CACTUS, Blocks.DEAD_BUSH, Blocks.POINTED_DRIPSTONE, Blocks.BROWN_MUSHROOM, Blocks.DEAD_TUBE_CORAL, Blocks.DEAD_BRAIN_CORAL, Blocks.DEAD_BUBBLE_CORAL, Blocks.DEAD_FIRE_CORAL, Blocks.DEAD_HORN_CORAL, Blocks.BROWN_CARPET, Blocks.TERRACOTTA),
        "flying" to listOf(Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.LIGHT_BLUE_CARPET, Blocks.WHITE_CARPET, Blocks.END_ROD, Blocks.AMETHYST_CLUSTER, Blocks.SCAFFOLDING,),
        "psychic" to listOf(Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD, Blocks.END_ROD, Blocks.CRYING_OBSIDIAN, Blocks.PURPLE_CANDLE, Blocks.MAGENTA_CANDLE, Blocks.ENCHANTING_TABLE, Blocks.SCULK_SENSOR),
        "bug" to listOf(Blocks.COBWEB, Blocks.VINE, Blocks.SPORE_BLOSSOM, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.TWISTING_VINES, Blocks.WEEPING_VINES, Blocks.SHROOMLIGHT, Blocks.HONEY_BLOCK, Blocks.CANDLE),
        "rock" to listOf(Blocks.STONE_BUTTON, Blocks.POLISHED_BLACKSTONE_BUTTON, Blocks.STONE_PRESSURE_PLATE, Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE, Blocks.COBBLESTONE_WALL, Blocks.DEEPSLATE_TILE_WALL, Blocks.POINTED_DRIPSTONE, Blocks.LARGE_AMETHYST_BUD, Blocks.LANTERN, Blocks.CAMPFIRE),
        "ghost" to listOf(Blocks.SOUL_LANTERN, Blocks.SOUL_TORCH, Blocks.SOUL_CAMPFIRE, Blocks.WITHER_ROSE, Blocks.WITHER_SKELETON_SKULL, Blocks.SKELETON_SKULL, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST, Blocks.SCULK_VEIN, Blocks.BLACK_CANDLE, Blocks.PURPLE_CANDLE, Blocks.COBWEB),
        "dragon" to listOf(Blocks.DRAGON_HEAD, Blocks.DRAGON_WALL_HEAD, Blocks.END_ROD, Blocks.PURPLE_CANDLE, Blocks.BLACK_CANDLE, Blocks.AMETHYST_CLUSTER, Blocks.OBSIDIAN),
        "dark" to listOf(Blocks.BLACK_CANDLE, Blocks.GRAY_CANDLE, Blocks.BROWN_CANDLE, Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_ROSE, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.COBWEB, Blocks.SOUL_LANTERN, Blocks.LANTERN, Blocks.CHAIN),
        "steel" to listOf(Blocks.CHAIN, Blocks.IRON_BARS, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.BELL, Blocks.STONECUTTER, Blocks.HOPPER, Blocks.CAULDRON, Blocks.LIGHT_GRAY_CANDLE),
        "fairy" to listOf(Blocks.PINK_TULIP, Blocks.ALLIUM, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.PINK_PETALS, Blocks.SPORE_BLOSSOM, Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.PINK_CANDLE, Blocks.MAGENTA_CANDLE, Blocks.PURPLE_CANDLE, Blocks.WHITE_CANDLE, Blocks.END_ROD, Blocks.TWISTING_VINES)
    )

    val EGG_SPECIES_ID = Identifier.of("cobblemon", "egg")
    const val EGG_NBT_KEY = "is_egg"
    const val TARGET_SPECIES_NBT_KEY = "target_species"
    const val TOTAL_HATCH_STEPS_KEY = "totalHatchSteps"
    const val CURRENT_HATCH_STEPS_KEY = "currentHatchSteps"
    const val STEPS_PER_EGG_CYCLE = 25

    const val CALCULATED_IV_HP_KEY = "calculatedIVHP"
    const val CALCULATED_IV_ATK_KEY = "calculatedIVAtk"
    const val CALCULATED_IV_DEF_KEY = "calculatedIVDef"
    const val CALCULATED_IV_SPA_KEY = "calculatedIVSpA"
    const val CALCULATED_IV_SPD_KEY = "calculatedIVSpD"
    const val CALCULATED_IV_SPE_KEY = "calculatedIVSpe"
    const val CALCULATED_NATURE_KEY = "calculatedNature"

    private const val DESTINY_KNOT_ID = "cobblemon:destiny_knot"
    private const val EVERSTONE_ID = "cobblemon:everstone"
    private val powerItemStatMap: Map<String, Stat> = mapOf(
        "cobblemon:power_weight" to Stats.HP,
        "cobblemon:power_bracer" to Stats.ATTACK,
        "cobblemon:power_belt" to Stats.DEFENCE,
        "cobblemon:power_lens" to Stats.SPECIAL_ATTACK,
        "cobblemon:power_band" to Stats.SPECIAL_DEFENCE,
        "cobblemon:power_anklet" to Stats.SPEED
    )
    private val allStats = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)

    private val TIER_1_COLOR = Vector3f(1.0f, 1.0f, 1.0f)
    private val TIER_2_COLOR = Vector3f(1.0f, 1.0f, 0.0f)
    private val TIER_3_COLOR = Vector3f(0.0f, 1.0f, 0.0f)
    private const val PARTICLE_SCALE = 0.7f

    private fun calculateBreedingDuration(world: ServerWorld, pasturePos: BlockPos, referencePokemon: Pokemon): Pair<Long, Int> {
        val typeNames = listOfNotNull(
            referencePokemon.species.primaryType.name.lowercase(Locale.ROOT),
            referencePokemon.species.secondaryType?.name?.lowercase(Locale.ROOT)
        )
        LOGGER.info("Calculating duration based on ${referencePokemon.species.name} (Types: $typeNames) at $pasturePos")

        val favorableBaseBlocks = typeNames.flatMap { typeToBlocks[it] ?: emptyList() }.toSet()
        val favorableDecorBlocks = typeNames.flatMap { typeToDecorations[it] ?: emptyList() }.toSet()

        val minX = pasturePos.x - RADIUS
        val maxX = pasturePos.x + RADIUS
        val minZ = pasturePos.z - RADIUS
        val maxZ = pasturePos.z + RADIUS
        val baseY = pasturePos.y - 1
        val decorY = pasturePos.y

        var baseFavorableCount = 0
        var decorFavorableCount = 0

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val baseBlockPos = BlockPos(x, baseY, z)
                val baseBlock = world.getBlockState(baseBlockPos).block
                if (baseBlock in favorableBaseBlocks) {
                    baseFavorableCount++
                }

                val decorBlockPos = BlockPos(x, decorY, z)
                val decorBlock = world.getBlockState(decorBlockPos).block

                if (decorBlockPos == pasturePos) {
                    decorFavorableCount++
                } else {
                    if (decorBlock in favorableDecorBlocks) {
                        decorFavorableCount++
                    }
                }
            }
        }

        val totalFavorableCount = baseFavorableCount + decorFavorableCount
        val areaSize = (2 * RADIUS + 1) * (2 * RADIUS + 1)
        val totalBlocksChecked = areaSize * 2

        val tier3Threshold = (totalBlocksChecked * 0.75).toInt()
        val tier2Threshold = (totalBlocksChecked * 0.50).toInt()

        val tier = when {
            totalFavorableCount >= tier3Threshold -> 3
            totalFavorableCount >= tier2Threshold -> 2
            else -> 1
        }

        LOGGER.info("Counts: Base=$baseFavorableCount, Decor=$decorFavorableCount, Total=$totalFavorableCount / $totalBlocksChecked")
        LOGGER.info("Thresholds: Tier3=$tier3Threshold (75%), Tier2=$tier2Threshold (50%)")
        LOGGER.info("Calculated Tier: $tier")

        val multiplier = when (tier) {
            3 -> 1.0 - TIER_3_REDUCTION
            2 -> 1.0 - TIER_2_REDUCTION
            else -> 1.0
        }

        val duration = (BASE_BREEDING_DURATION_TICKS * multiplier).roundToLong().coerceAtLeast(1L)

        return Pair(duration, tier)
    }

    private fun isDitto(pokemon: Pokemon?): Boolean {
        return pokemon?.species?.showdownId() == "ditto"
    }

    private fun isGenderless(pokemon: Pokemon?): Boolean {
        return pokemon?.gender == Gender.GENDERLESS
    }

    fun tickBreedingProcess(world: ServerWorld, pasturePos: BlockPos, state: BreedingState) {
        if (world.time % TICK_THROTTLE != 0L) return

        val pastureBlockEntity = world.getBlockEntity(pasturePos) as? PokemonPastureBlockEntity ?: return

        if (state.breedingStartTick == null) {
            val tetheredPokemonPairs = pastureBlockEntity.tetheredPokemon.mapNotNull { tether ->
                tether.getPokemon()?.let { pokemon -> Pair(tether, pokemon) }
            }
            if (tetheredPokemonPairs.size < 2) return

            var foundPair = false
            var potentialParent1Tether: PokemonPastureBlockEntity.Tethering? = null
            var potentialParent2Tether: PokemonPastureBlockEntity.Tethering? = null
            var isDittoPairResult = false

            val dittos = tetheredPokemonPairs.filter { isDitto(it.second) }
            val genderlessNonDittos = tetheredPokemonPairs.filter { isGenderless(it.second) && !isDitto(it.second) }
            val genderedNonDittos = tetheredPokemonPairs.filterNot { isDitto(it.second) || isGenderless(it.second) }

            if (dittos.size == 1) {
                val dittoPair = dittos.first()
                if (genderlessNonDittos.isNotEmpty()) {
                    val partnerPair = genderlessNonDittos.first()
                    potentialParent1Tether = dittoPair.first
                    potentialParent2Tether = partnerPair.first
                    isDittoPairResult = true
                    foundPair = true
                } else if (genderedNonDittos.isNotEmpty()) {
                    val partnerPair = genderedNonDittos.first()
                    if (partnerPair.second.gender != Gender.GENDERLESS) {
                        potentialParent1Tether = dittoPair.first
                        potentialParent2Tether = partnerPair.first
                        isDittoPairResult = true
                        foundPair = true
                    }
                }
            }

            if (!foundPair) {
                val speciesMap = genderedNonDittos.groupBy { it.second.species }
                for ((species, tethersInSpecies) in speciesMap) {
                    if (species == null || tethersInSpecies.size < 2) continue

                    val males = tethersInSpecies.filter { it.second.gender == Gender.MALE }
                    val females = tethersInSpecies.filter { it.second.gender == Gender.FEMALE }

                    if (males.isNotEmpty() && females.isNotEmpty()) {
                        potentialParent1Tether = males.first().first
                        potentialParent2Tether = females.first().first
                        isDittoPairResult = false
                        foundPair = true
                        break
                    }
                }
            }

            if (foundPair && potentialParent1Tether != null && potentialParent2Tether != null) {
                val entity1Check = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, potentialParent1Tether.pokemonId)
                val entity2Check = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, potentialParent2Tether.pokemonId)

                if (entity1Check != null && entity2Check != null) {
                    state.malePokemonUUID = potentialParent1Tether.pokemonId
                    state.femalePokemonUUID = potentialParent2Tether.pokemonId
                    state.isDittoPair = isDittoPairResult
                    state.breedingStartTick = world.time
                    state.needsDurationCalc = true
                    state.walkingStarted = false
                    state.meetingPoint = null
                    state.meetingEndTime = null
                    state.jumpCount = 0
                    state.lastJumpTick = 0L
                    state.heartsPlayed = false
                    state.breedingDurationTicks = BASE_BREEDING_DURATION_TICKS
                    state.breedingTier = 1
                } else {
                    // Entities missing, do nothing this tick
                }
            }
            return
        }

        val maleEntity = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, state.malePokemonUUID!!)
        val femaleEntity = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, state.femalePokemonUUID!!)

        if (maleEntity == null || femaleEntity == null) {
            cancelBreeding(state, maleEntity, femaleEntity)
            return
        }

        if (state.needsDurationCalc) {
            val calculationParentPokemon = if (state.isDittoPair) {
                val maleIsDitto = isDitto(maleEntity.pokemon)
                if (maleIsDitto) femaleEntity.pokemon else maleEntity.pokemon
            } else {
                femaleEntity.pokemon
            }

            if (calculationParentPokemon != null) {
                val (duration, tier) = calculateBreedingDuration(world, pastureBlockEntity.pos, calculationParentPokemon)
                state.breedingDurationTicks = duration
                state.breedingTier = tier
                state.needsDurationCalc = false
            } else {
                cancelBreeding(state, maleEntity, femaleEntity)
                return
            }
        }

        val currentTick = world.time
        val elapsedTicks = currentTick - state.breedingStartTick!!

        if (!state.walkingStarted && state.meetingEndTime == null && elapsedTicks < state.breedingDurationTicks) {
            if (currentTick % 40 == 0L) {
                val particleColor = when (state.breedingTier) {
                    3 -> TIER_3_COLOR
                    2 -> TIER_2_COLOR
                    else -> TIER_1_COLOR
                }
                val dustOptions = DustParticleEffect(particleColor, PARTICLE_SCALE)
                val particleX = pastureBlockEntity.pos.x + 0.5
                val particleY = pastureBlockEntity.pos.y + 0.8
                val particleZ = pastureBlockEntity.pos.z + 0.5
                world.spawnParticles(dustOptions, particleX, particleY, particleZ, 12, 0.5, 0.5, 0.5, 0.03)
            }
        } else if (!state.walkingStarted && state.meetingEndTime == null && elapsedTicks >= state.breedingDurationTicks) {
            state.meetingPoint = calculateMeetingPoint(maleEntity, femaleEntity)
            state.meetingPoint?.let { mp ->
                addWalkGoalIfNotPresent(maleEntity, mp)
                addWalkGoalIfNotPresent(femaleEntity, mp)
            }
            state.walkingStarted = true
        } else if (state.walkingStarted && state.meetingPoint != null && state.meetingEndTime == null) {
            if (!areEntitiesWalking(maleEntity, femaleEntity) || elapsedTicks >= state.breedingDurationTicks + WALK_TO_MEET_DURATION_TICKS) {
                removeWalkGoal(maleEntity)
                removeWalkGoal(femaleEntity)
                state.meetingEndTime = currentTick
                state.jumpCount = 0
                state.lastJumpTick = currentTick
                state.heartsPlayed = false
            } else if (elapsedTicks > state.breedingDurationTicks + WALK_TIMEOUT_EXTRA_TICKS) {
                cancelBreeding(state, maleEntity, femaleEntity)
            }
        } else if (state.meetingEndTime != null) {
            val ticksSinceMeeting = currentTick - state.meetingEndTime!!
            if (ticksSinceMeeting < STAND_DURATION_TICKS) {
                // Standing still phase
            } else if (state.jumpCount < JUMP_COUNT_TARGET) {
                if (currentTick >= state.lastJumpTick + JUMP_DELAY_TICKS) {
                    maleEntity.jump()
                    world.playSound(null, maleEntity.blockPos, SoundEvents.ENTITY_RABBIT_JUMP, SoundCategory.NEUTRAL, 0.5f, world.random.nextFloat() * 0.2f + 0.9f)
                    state.jumpCount++
                    state.lastJumpTick = currentTick
                }
            } else if (!state.heartsPlayed) {
                if (maleEntity.squaredDistanceTo(femaleEntity) <= MAX_HEART_DISTANCE_SQ) {
                    world.spawnParticles(ParticleTypes.HEART, maleEntity.x, maleEntity.eyeY, maleEntity.z, 7, 0.5, 0.5, 0.5, 0.02)
                    world.spawnParticles(ParticleTypes.HEART, femaleEntity.x, femaleEntity.eyeY, femaleEntity.z, 7, 0.5, 0.5, 0.5, 0.02)
                    state.heartsPlayed = true
                } else {
                    cancelBreeding(state, maleEntity, femaleEntity)
                }
            } else {
                val parentTetherForOwner = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.femalePokemonUUID }
                    ?: pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.malePokemonUUID } // Fallback check

                if (parentTetherForOwner != null) {
                    val ownerUUID = parentTetherForOwner.playerId
                    val ownerPlayer = world.server?.playerManager?.getPlayer(ownerUUID)
                    if (ownerPlayer != null) {
                        generateAndAddEggToPasture(world, pastureBlockEntity.pos, state, ownerPlayer)
                    } else {
                        // Owner offline, do nothing this cycle
                    }
                } else {
                    // Parent tether missing, log implicitly handled in generateAndAddEggToPasture or parent check
                }
                cancelBreeding(state, maleEntity, femaleEntity)
            }
        }
    }
    private fun isEntityWalking(entity: PokemonEntity?): Boolean {
        val mobEntity = entity as? MobEntity ?: return false
        val goalSelector = (mobEntity as? MobEntityAccessor)?.goalSelector ?: return false
        return goalSelector.goals.any { prioritizedGoal ->
            prioritizedGoal.goal is WalkToPositionGoal && prioritizedGoal.isRunning
        }
    }

    private fun areEntitiesWalking(entity1: PokemonEntity?, entity2: PokemonEntity?): Boolean {
        return isEntityWalking(entity1) || isEntityWalking(entity2)
    }

    private fun getPokemonEntityByPokemonUUID(world: ServerWorld, pastureBlockEntity: PokemonPastureBlockEntity, pokemonUuid: UUID): PokemonEntity? {
        val minPos = pastureBlockEntity.minRoamPos ?: pastureBlockEntity.pos.add(-WALK_RADIUS, -WALK_RADIUS, -WALK_RADIUS)
        val maxPos = pastureBlockEntity.maxRoamPos ?: pastureBlockEntity.pos.add(WALK_RADIUS, WALK_RADIUS, WALK_RADIUS)
        val searchBox = Box(Vec3d.of(minPos).add(-1.0, -1.0, -1.0), Vec3d.of(maxPos.add(1, 1, 1)).add(1.0, 2.0, 1.0))
        val potentialEntities = world.getEntitiesByClass(PokemonEntity::class.java, searchBox) { entity ->
            entity.pokemon?.uuid == pokemonUuid && !entity.isRemoved
        }
        return potentialEntities.firstOrNull()
    }

    private fun getItemId(itemStack: ItemStack?): String? {
        return if (itemStack == null || itemStack.isEmpty) null else Registries.ITEM.getId(itemStack.item)?.toString()
    }

    private fun calculateOffspringIvs(malePokemon: Pokemon, femalePokemon: Pokemon): Map<Stat, Int> {
        val finalIvs = mutableMapOf<Stat, Int>()
        val availableStats = allStats.toMutableList()

        val maleHeldItemId = getItemId(malePokemon.heldItem())
        val femaleHeldItemId = getItemId(femalePokemon.heldItem())

        val hasDestinyKnot = maleHeldItemId == DESTINY_KNOT_ID || femaleHeldItemId == DESTINY_KNOT_ID
        val ivsToInherit = if (hasDestinyKnot) 5 else 3
        var inheritedCount = 0

        val powerItemParentMap = mutableMapOf<Stat, Pokemon>()
        maleHeldItemId?.let { id -> powerItemStatMap[id]?.let { powerItemParentMap[it] = malePokemon } }
        femaleHeldItemId?.let { id -> powerItemStatMap[id]?.let { powerItemParentMap[it] = femalePokemon } }

        powerItemParentMap.forEach { (stat, parent) ->
            if (availableStats.contains(stat) && inheritedCount < ivsToInherit) {
                finalIvs[stat] = parent.ivs[stat] ?: 0
                availableStats.remove(stat)
                inheritedCount++
            }
        }

        val ivsToInheritRandomly = ivsToInherit - inheritedCount
        if (ivsToInheritRandomly > 0 && availableStats.isNotEmpty()) {
            availableStats.shuffle()
            val statsToInheritRandomly = availableStats.take(ivsToInheritRandomly)
            statsToInheritRandomly.forEach { stat ->
                val parentToInheritFrom = if (Random.nextBoolean()) malePokemon else femalePokemon
                finalIvs[stat] = parentToInheritFrom.ivs[stat] ?: 0
            }
            availableStats.removeAll(statsToInheritRandomly.toSet())
        }

        availableStats.forEach { stat ->
            if (!finalIvs.containsKey(stat)) {
                finalIvs[stat] = Random.nextInt(32)
            }
        }

        allStats.forEach { stat ->
            finalIvs.putIfAbsent(stat, Random.nextInt(32))
        }

        return finalIvs
    }

    private fun calculateOffspringNature(malePokemon: Pokemon, femalePokemon: Pokemon): Nature {
        val maleHeldItemId = getItemId(malePokemon.heldItem())
        val femaleHeldItemId = getItemId(femalePokemon.heldItem())

        val maleHasEverstone = maleHeldItemId == EVERSTONE_ID
        val femaleHasEverstone = femaleHeldItemId == EVERSTONE_ID

        return when {
            maleHasEverstone && femaleHasEverstone -> if (Random.nextBoolean()) malePokemon.nature else femalePokemon.nature
            maleHasEverstone -> malePokemon.nature
            femaleHasEverstone -> femalePokemon.nature
            else -> Natures.getRandomNature()
        }
    }

    fun generateAndAddEggToPasture(world: ServerWorld, pasturePos: BlockPos, state: BreedingState, player: ServerPlayerEntity): Boolean {
        val pastureBlockEntity = world.getBlockEntity(pasturePos) as? PokemonPastureBlockEntity ?: return false

        val maxTotalPokemon = pastureBlockEntity.getMaxTethered()
        if (pastureBlockEntity.tetheredPokemon.size >= maxTotalPokemon) {
            player.sendMessage(Text.literal("The pasture is full! Could not add the Pokémon Egg.").formatted(Formatting.YELLOW), false)
            return false
        }

        val malePokemon = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.malePokemonUUID }?.getPokemon()
        val femalePokemon = pastureBlockEntity.tetheredPokemon.find { it.pokemonId == state.femalePokemonUUID }?.getPokemon()

        if (femalePokemon == null || malePokemon == null) {
            LOGGER.error("Parent Pokemon data missing during egg creation attempt at {}. State: M={}, F={}, DittoPair={}", pasturePos, state.malePokemonUUID, state.femalePokemonUUID, state.isDittoPair)
            return false
        }

        val targetSpecies: Species?
        val scaleReferencePokemon: Pokemon

        if (state.isDittoPair) {
            val maleIsDitto = isDitto(malePokemon)
            targetSpecies = if (maleIsDitto) femalePokemon.species else malePokemon.species
            scaleReferencePokemon = if (maleIsDitto) femalePokemon else malePokemon
            LOGGER.debug("Ditto pair egg generation: Non-Ditto parent is {}", targetSpecies?.name)
        } else {
            targetSpecies = femalePokemon.species
            scaleReferencePokemon = femalePokemon
            LOGGER.debug("Standard pair egg generation: Female parent is {}", targetSpecies?.name)
        }

        if (targetSpecies == null) {
            LOGGER.error("Could not determine target species for egg at {}.", pasturePos)
            return false
        }
        if (targetSpecies.showdownId() == "ditto") {
            LOGGER.error("Attempted to generate a Ditto egg at {}. This should not happen. Cancelling.", pasturePos)
            return false
        }

        val targetSpeciesIdString = targetSpecies.showdownId()

        val offspringIvs = calculateOffspringIvs(malePokemon, femalePokemon)
        val offspringNature = calculateOffspringNature(malePokemon, femalePokemon)

        val eggCycles = targetSpecies.eggCycles
        val totalStepsRequired = if (eggCycles > 1) eggCycles * STEPS_PER_EGG_CYCLE else 10 * STEPS_PER_EGG_CYCLE

        val primaryType = targetSpecies.primaryType
        val typeAspect = primaryType.name.lowercase(Locale.ROOT)

        val parentSpeciesHeight = scaleReferencePokemon.species.height.toFloat()
        val parentScaleModifier = scaleReferencePokemon.scaleModifier
        val effectiveParentHeight = parentSpeciesHeight * parentScaleModifier
        val normalizationDivisor = 15.0f
        val calculatedScale = effectiveParentHeight / normalizationDivisor
        val desiredEggScale = calculatedScale.coerceIn(0.3f, 1.2f)


        val eggSpeciesIdentifierPath = EGG_SPECIES_ID.path
        val propertiesString = "$eggSpeciesIdentifierPath level=1 aspect=$typeAspect"
        val properties = PokemonProperties.parse(propertiesString)
        val eggEntity = properties.createEntity(world)

        if (eggEntity == null) {
            LOGGER.error("Failed to create egg entity with properties: {}", propertiesString)
            return false
        }

        val eggPokemon = eggEntity.pokemon

        eggPokemon.persistentData.putBoolean(EGG_NBT_KEY, true)
        eggPokemon.persistentData.putString(TARGET_SPECIES_NBT_KEY, targetSpeciesIdString)
        eggPokemon.persistentData.putInt(TOTAL_HATCH_STEPS_KEY, totalStepsRequired)
        eggPokemon.persistentData.putInt(CURRENT_HATCH_STEPS_KEY, 0)

        eggPokemon.persistentData.putInt(CALCULATED_IV_HP_KEY, offspringIvs[Stats.HP] ?: 0)
        eggPokemon.persistentData.putInt(CALCULATED_IV_ATK_KEY, offspringIvs[Stats.ATTACK] ?: 0)
        eggPokemon.persistentData.putInt(CALCULATED_IV_DEF_KEY, offspringIvs[Stats.DEFENCE] ?: 0)
        eggPokemon.persistentData.putInt(CALCULATED_IV_SPA_KEY, offspringIvs[Stats.SPECIAL_ATTACK] ?: 0)
        eggPokemon.persistentData.putInt(CALCULATED_IV_SPD_KEY, offspringIvs[Stats.SPECIAL_DEFENCE] ?: 0)
        eggPokemon.persistentData.putInt(CALCULATED_IV_SPE_KEY, offspringIvs[Stats.SPEED] ?: 0)
        eggPokemon.persistentData.putString(CALCULATED_NATURE_KEY, offspringNature.name.path)

        eggPokemon.setOriginalTrainer(player.uuid)
        eggPokemon.nickname = Text.literal("${targetSpecies.name} Egg")
        eggPokemon.scaleModifier = desiredEggScale

        val spawnPosVec = pasturePos.toCenterPos()
        eggEntity.refreshPositionAndAngles(spawnPosVec.x, spawnPosVec.y, spawnPosVec.z, world.random.nextFloat() * 360.0f, 0.0f)
        val spawned = world.spawnEntity(eggEntity)

        val pcStore = Cobblemon.storage.getPC(player) ?: run {
            LOGGER.error("PC storage not found for player {} during egg creation.", player.uuid)
            if (spawned) eggEntity.discard()
            return false
        }
        if (!pcStore.add(eggPokemon)) {
            if (spawned) eggEntity.discard()
            player.sendMessage(Text.literal("Your PC is full! Could not store the Pokémon Egg.").formatted(Formatting.YELLOW), false)
            return false
        }

        val newTetheringId = UUID.randomUUID()
        eggPokemon.tetheringId = newTetheringId

        val eggMinRoam = pastureBlockEntity.minRoamPos ?: pastureBlockEntity.pos.add(-WALK_RADIUS, -WALK_RADIUS, -WALK_RADIUS)
        val eggMaxRoam = pastureBlockEntity.maxRoamPos ?: pastureBlockEntity.pos.add(WALK_RADIUS, WALK_RADIUS, WALK_RADIUS)

        val eggTethering = PokemonPastureBlockEntity.Tethering(
            minRoamPos = eggMinRoam,
            maxRoamPos = eggMaxRoam,
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
            LOGGER.warn("Failed to spawn egg entity at {}, but data added to PC and pasture list.", pasturePos)
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

        val world = entity1.world
        val checkPos = BlockPos.Mutable(avgX.toInt(), avgY.toInt(), avgZ.toInt())

        for (yOff in 0..5) {
            checkPos.y = (avgY - yOff).toInt()
            if (checkPos.y < world.bottomY) break
            val stateDown = world.getBlockState(checkPos.down())
            val stateAt = world.getBlockState(checkPos)
            if (stateDown.isSolid && !stateDown.isLiquid && (stateAt.isAir || stateAt.isReplaceable)) {
                return Vec3d(avgX, checkPos.y.toDouble(), avgZ)
            }
        }
        for (yOff in 1..5) {
            checkPos.y = (avgY + yOff).toInt()
            if (checkPos.y >= world.topY) break
            val stateDown = world.getBlockState(checkPos.down())
            val stateAt = world.getBlockState(checkPos)
            if (stateDown.isSolid && !stateDown.isLiquid && (stateAt.isAir || stateAt.isReplaceable)) {
                return Vec3d(avgX, checkPos.y.toDouble(), avgZ)
            }
        }

        checkPos.x = avgX.toInt()
        checkPos.z = avgZ.toInt()
        val topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, checkPos.x, checkPos.z)
        for (y in topY downTo world.bottomY) {
            checkPos.y = y
            val stateDown = world.getBlockState(checkPos.down())
            val stateAt = world.getBlockState(checkPos)
            if (stateDown.isSolid && !stateDown.isLiquid && (stateAt.isAir || stateAt.isReplaceable)) {
                return Vec3d(avgX, y.toDouble(), avgZ)
            }
        }

        LOGGER.error("Could not find suitable meeting point near ({}, {}, {}). Using raw average Y.", avgX, avgY, avgZ)
        return Vec3d(avgX, avgY + 0.1, avgZ)
    }

    fun checkForInitialBreeders(world: ServerWorld, pastureBlockEntity: PokemonPastureBlockEntity, state: BreedingState) {
        if (state.breedingStartTick != null || state.needsDurationCalc) return

        val tetheredPokemonPairs = pastureBlockEntity.tetheredPokemon.mapNotNull { tether ->
            tether.getPokemon()?.let { pokemon -> Pair(tether, pokemon) }
        }
        if (tetheredPokemonPairs.size < 2) return

        var foundPair = false
        var potentialMaleTether: PokemonPastureBlockEntity.Tethering? = null
        var potentialFemaleTether: PokemonPastureBlockEntity.Tethering? = null
        var isDittoPairResult = false

        val dittos = tetheredPokemonPairs.filter { isDitto(it.second) }
        val nonDittos = tetheredPokemonPairs.filterNot { isDitto(it.second) }

        if (dittos.size == 1 && nonDittos.isNotEmpty()) {
            val dittoPair = dittos.first()
            val partnerPair = nonDittos.first()
            potentialMaleTether = dittoPair.first
            potentialFemaleTether = partnerPair.first
            isDittoPairResult = true
            foundPair = true
            LOGGER.debug("Potential initial Ditto pair found at {}: Ditto + {}", pastureBlockEntity.pos, partnerPair.second.species.name)
        }

        if (!foundPair) {
            val speciesMap = nonDittos.groupBy { it.second.species }
            for ((species, tethersInSpecies) in speciesMap) {
                if (species == null || tethersInSpecies.size < 2) continue
                val males = tethersInSpecies.filter { it.second.gender == Gender.MALE }
                val females = tethersInSpecies.filter { it.second.gender == Gender.FEMALE }
                if (males.isNotEmpty() && females.isNotEmpty()) {
                    potentialMaleTether = males.first().first
                    potentialFemaleTether = females.first().first
                    isDittoPairResult = false
                    foundPair = true
                    LOGGER.debug("Potential initial standard pair found at {}: {}", pastureBlockEntity.pos, species.name)
                    break
                }
            }
        }

        if (foundPair && potentialMaleTether != null && potentialFemaleTether != null) {
            val maleEntityCheck = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, potentialMaleTether.pokemonId)
            val femaleEntityCheck = getPokemonEntityByPokemonUUID(world, pastureBlockEntity, potentialFemaleTether.pokemonId)

            if (maleEntityCheck != null && femaleEntityCheck != null) {
                state.malePokemonUUID = potentialMaleTether.pokemonId
                state.femalePokemonUUID = potentialFemaleTether.pokemonId
                state.isDittoPair = isDittoPairResult
                state.breedingStartTick = world.time
                state.needsDurationCalc = true
                state.walkingStarted = false
                state.meetingPoint = null
                state.meetingEndTime = null
                state.jumpCount = 0
                state.lastJumpTick = 0L
                state.heartsPlayed = false
                state.breedingDurationTicks = BASE_BREEDING_DURATION_TICKS
                state.breedingTier = 1
                LOGGER.info("Found initial breeding pair at {} on load. Is Ditto Pair: {}. Calculation deferred.", pastureBlockEntity.pos, state.isDittoPair)
            } else {
                LOGGER.debug("Found initial pair data ({}) at {}, but entities missing. Skipping.", if(isDittoPairResult) "Ditto Pair" else potentialMaleTether.getPokemon()?.species?.name, pastureBlockEntity.pos)
            }
        }
    }

    private fun addWalkGoalIfNotPresent(entity: PokemonEntity, targetPos: Vec3d) {
        val mobEntity = entity as? MobEntity ?: return
        val goalSelector = (mobEntity as? MobEntityAccessor)?.goalSelector ?: return

        val existingGoal = goalSelector.goals.find { wrappedGoal ->
            (wrappedGoal.goal is WalkToPositionGoal) &&
                    (wrappedGoal.goal as WalkToPositionGoal).isTarget(targetPos) &&
                    wrappedGoal.isRunning
        }

        if (existingGoal == null) {
            removeWalkGoal(entity)
            val newGoal = WalkToPositionGoal(mobEntity, targetPos, 1.0)
            goalSelector.add(1, newGoal)
            LOGGER.trace("Added WalkToPositionGoal for {} to {}", entity.displayName?.string ?: "entity", targetPos)
        } else {
            LOGGER.trace("WalkToPositionGoal already present and active for {} to {}", entity.displayName?.string ?: "entity", targetPos)
        }
    }

    private fun removeWalkGoal(entity: PokemonEntity) {
        val mobEntity = entity as? MobEntity ?: return
        val goalSelector = (mobEntity as? MobEntityAccessor)?.goalSelector ?: return

        val goalsToRemove = goalSelector.goals.filter { it.goal is WalkToPositionGoal }

        if (goalsToRemove.isNotEmpty()) {
            goalsToRemove.forEach { goalSelector.remove(it.goal) }
            LOGGER.trace("Removed WalkToPositionGoal(s) for {}", entity.displayName?.string ?: "entity")
        }
    }

    fun cancelBreeding(state: BreedingState, maleEntity: PokemonEntity?, femaleEntity: PokemonEntity?) {
        val wasBreeding = state.breedingStartTick != null
        maleEntity?.let { if (!it.isRemoved) removeWalkGoal(it) }
        femaleEntity?.let { if (!it.isRemoved) removeWalkGoal(it) }

        state.breedingStartTick = null
        state.malePokemonUUID = null
        state.femalePokemonUUID = null
        state.isDittoPair = false
        state.breedingDurationTicks = BASE_BREEDING_DURATION_TICKS
        state.breedingTier = 1
        state.needsDurationCalc = false
        state.walkingStarted = false
        state.meetingPoint = null
        state.meetingEndTime = null
        state.jumpCount = 0
        state.lastJumpTick = 0L
        state.heartsPlayed = false

        if (wasBreeding) {
            LOGGER.info("Breeding state fully reset.")
        }
    }
}

class WalkToPositionGoal(
    private val mob: MobEntity,
    private val targetPos: Vec3d,
    private val speed: Double
) : Goal() {
    private var stuckTicks = 0
    private val targetReachedThresholdSq = BreedingManager.MEETING_THRESHOLD_SQ

    init {
        controls = EnumSet.of(Control.MOVE)
    }

    fun isTarget(pos: Vec3d): Boolean = this.targetPos.squaredDistanceTo(pos) < 0.01

    override fun canStart(): Boolean = true

    override fun start() {
        LOGGER.trace("WalkToPositionGoal started for {} to {}", mob.displayName?.string ?: "mob", targetPos)
        mob.navigation.startMovingTo(targetPos.x, targetPos.y, targetPos.z, speed)
        stuckTicks = 0
    }

    override fun shouldContinue(): Boolean {
        if (mob.world.time % BreedingManager.TICK_THROTTLE != 0L && !mob.navigation.isIdle) {
            return true
        }

        val distSq = mob.pos.squaredDistanceTo(targetPos)
        if (distSq <= targetReachedThresholdSq) {
            LOGGER.trace("WalkToPositionGoal stopping for {}: Target reached.", mob.displayName?.string ?: "mob")
            return false
        }

        val isNavigating = !mob.navigation.isIdle
        if (!isNavigating) {
            stuckTicks += BreedingManager.TICK_THROTTLE.toInt()
            if (stuckTicks > 60) {
                LOGGER.debug("WalkToPositionGoal stopping for {} due to stuck timeout.", mob.displayName?.string ?: "mob")
                return false
            }
        } else {
            stuckTicks = 0
        }

        return true
    }

    override fun stop() {
        if (!mob.navigation.isIdle) {
            val currentNavTargetPos = mob.navigation.targetPos
            if (currentNavTargetPos != null && currentNavTargetPos.isWithinDistance(BlockPos.ofFloored(targetPos), 4.0)) {
                LOGGER.trace("WalkToPositionGoal stopping navigation for {}", mob.displayName?.string ?: "mob")
                mob.navigation.stop()
            } else {
                LOGGER.trace("WalkToPositionGoal stopping for {}, but navigation target ({}) is not close to goal ({}) or null, not stopping active nav.", mob.displayName?.string ?: "mob", currentNavTargetPos, targetPos)
            }
        } else {
            LOGGER.trace("WalkToPositionGoal stopping for {}, navigation already idle.", mob.displayName?.string ?: "mob")
        }
        stuckTicks = 0
    }

    override fun tick() {
        if (mob.world.time % BreedingManager.TICK_THROTTLE != 0L) return

        if (mob.navigation.isIdle && mob.pos.squaredDistanceTo(targetPos) > targetReachedThresholdSq) {
            if (stuckTicks > 20 && stuckTicks % 20 == 0 && stuckTicks <= 60) {
                LOGGER.trace("WalkToPositionGoal retrying navigation for stuck {}", mob.displayName?.string ?: "mob")
                mob.navigation.startMovingTo(targetPos.x, targetPos.y, targetPos.z, speed)
            }
        }
    }


}