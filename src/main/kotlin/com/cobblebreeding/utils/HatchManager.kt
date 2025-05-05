package com.cobblebreeding.utils

import com.cobblebreeding.CobblemonBreeding
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.OriginalTrainerType
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.stat.StatHandler
import net.minecraft.stat.Stats as MCStats // Alias Minecraft Stats
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.*

object HatchManager {

    private val playerWalkStats = mutableMapOf<UUID, Long>()
    private val readyToHatchMap = mutableMapOf<UUID, Queue<Int>>()
    private const val HATCH_STEP_DIVISOR = BreedingManager.STEPS_PER_EGG_CYCLE

    internal fun tickHatchingSteps(server: MinecraftServer) {
        server.playerManager.playerList.forEach { player ->
            val currentTotalCm = getTotalDistanceCm(player.statHandler)
            val previousTotalCm = playerWalkStats.getOrDefault(player.uuid, currentTotalCm)
            val cmMoved = currentTotalCm - previousTotalCm

            if (cmMoved >= HATCH_STEP_DIVISOR) {
                val stepsToCredit = (cmMoved / HATCH_STEP_DIVISOR).toInt()
                if (stepsToCredit > 0) {
                    //println("${CobblemonBreeding.PREFIX} Player ${player.gameProfile.name} moved ${cmMoved}cm, crediting $stepsToCredit steps.") // Optional debug
                    val party = Cobblemon.storage.getParty(player) ?: return@forEach
                    for (slotIndex in 0 until party.size()) {
                        val pokemon = party[PartyPosition(slotIndex)]
                        if (pokemon != null && pokemon.persistentData.getBoolean(BreedingManager.EGG_NBT_KEY)) {
                            val totalSteps = pokemon.persistentData.getInt(BreedingManager.TOTAL_HATCH_STEPS_KEY)
                            if (totalSteps > 0) {
                                val currentSteps = pokemon.persistentData.getInt(BreedingManager.CURRENT_HATCH_STEPS_KEY)
                                if (currentSteps < totalSteps) {
                                    val newSteps = (currentSteps + stepsToCredit).coerceAtMost(totalSteps)
                                    pokemon.persistentData.putInt(BreedingManager.CURRENT_HATCH_STEPS_KEY, newSteps)

                                    //val stepsRemaining = totalSteps - newSteps
                                    //println("${CobblemonBreeding.PREFIX}   Egg slot $slotIndex: $newSteps / $totalSteps steps ($stepsRemaining remaining).") // Optional debug

                                    if (newSteps >= totalSteps) {
                                        val queue = readyToHatchMap.computeIfAbsent(player.uuid) { LinkedList() }
                                        if (!queue.contains(slotIndex)) {
                                            //println("${CobblemonBreeding.PREFIX}   Egg slot $slotIndex added to hatch queue for ${player.gameProfile.name}.") // Optional debug
                                            queue.offer(slotIndex)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            playerWalkStats[player.uuid] = currentTotalCm
        }
    }

    private fun getTotalDistanceCm(statHandler: StatHandler): Long {
        var total: Long = 0
        total += statHandler.getStat(MCStats.CUSTOM.getOrCreateStat(MCStats.WALK_ONE_CM))
        total += statHandler.getStat(MCStats.CUSTOM.getOrCreateStat(MCStats.SPRINT_ONE_CM))
        total += statHandler.getStat(MCStats.CUSTOM.getOrCreateStat(MCStats.CROUCH_ONE_CM))
        total += statHandler.getStat(MCStats.CUSTOM.getOrCreateStat(MCStats.SWIM_ONE_CM))
        total += statHandler.getStat(MCStats.CUSTOM.getOrCreateStat(MCStats.FLY_ONE_CM))
        total += statHandler.getStat(MCStats.CUSTOM.getOrCreateStat(MCStats.AVIATE_ONE_CM))
        return total
    }

    internal fun processHatchQueue(server: MinecraftServer) {
        val iterator = readyToHatchMap.entries.iterator()
        while(iterator.hasNext()){
            val entry = iterator.next()
            val playerUUID = entry.key
            val queue = entry.value

            if (queue.isEmpty()) {
                iterator.remove()
                continue
            }

            val player = server.playerManager.getPlayer(playerUUID)
            if(player != null && !player.isRemoved && BattleRegistry.getBattleByParticipatingPlayer(player) == null) {
                val slotIndex = queue.poll()
                val party = Cobblemon.storage.getParty(player) ?: continue
                val eggPokemon = party[PartyPosition(slotIndex)]

                if (eggPokemon != null &&
                    eggPokemon.persistentData.getBoolean(BreedingManager.EGG_NBT_KEY) &&
                    eggPokemon.persistentData.getInt(BreedingManager.CURRENT_HATCH_STEPS_KEY) >= eggPokemon.persistentData.getInt(BreedingManager.TOTAL_HATCH_STEPS_KEY)) {

                    //println("${CobblemonBreeding.PREFIX} Player ${player.gameProfile.name}: Processing hatch for egg in slot $slotIndex.") // Optional debug
                    hatchEgg(player, slotIndex, eggPokemon)
                }

                if (queue.isEmpty()) {
                    iterator.remove()
                }
            } else if (player == null || player.isRemoved) {
                iterator.remove() // Clean up queue if player logs out
            }
        }
    }

    fun hatchEgg(player: ServerPlayerEntity, slotIndex: Int, eggPokemon: Pokemon) {
        val targetSpeciesId = eggPokemon.persistentData.getString(BreedingManager.TARGET_SPECIES_NBT_KEY)
        if (targetSpeciesId.isBlank()) {
            Cobblemon.LOGGER.error("Egg ${eggPokemon.uuid} for player ${player.uuid} is missing target species NBT!")
            return
        }

        val props = PokemonProperties.parse("$targetSpeciesId level=1")
        val hatchedPokemon: Pokemon = try {
            props.create()
        } catch (e: Exception) {
            Cobblemon.LOGGER.error("Exception creating Pokemon from properties: $props for egg ${eggPokemon.uuid}", e)
            player.sendMessage(Text.literal("Error during hatching process. Please report this.").formatted(Formatting.RED))
            return
        }

        // Apply calculated IVs
        hatchedPokemon.ivs.set(Stats.HP, eggPokemon.persistentData.getInt(BreedingManager.CALCULATED_IV_HP_KEY))
        hatchedPokemon.ivs.set(Stats.ATTACK, eggPokemon.persistentData.getInt(BreedingManager.CALCULATED_IV_ATK_KEY))
        hatchedPokemon.ivs.set(Stats.DEFENCE, eggPokemon.persistentData.getInt(BreedingManager.CALCULATED_IV_DEF_KEY))
        hatchedPokemon.ivs.set(Stats.SPECIAL_ATTACK, eggPokemon.persistentData.getInt(BreedingManager.CALCULATED_IV_SPA_KEY))
        hatchedPokemon.ivs.set(Stats.SPECIAL_DEFENCE, eggPokemon.persistentData.getInt(BreedingManager.CALCULATED_IV_SPD_KEY))
        hatchedPokemon.ivs.set(Stats.SPEED, eggPokemon.persistentData.getInt(BreedingManager.CALCULATED_IV_SPE_KEY))


        when (eggPokemon.originalTrainerType) {
            OriginalTrainerType.PLAYER -> {
                val ownerName: String? = eggPokemon.originalTrainerName
                val ownerUuidString: String? = eggPokemon.originalTrainer

                if (ownerName != null && ownerName.isNotEmpty()) {
                    hatchedPokemon.setOriginalTrainer(ownerName)
                } else if (ownerUuidString != null && ownerUuidString.isNotEmpty()) {
                    try {
                        val ownerUuid: UUID = UUID.fromString(ownerUuidString)
                        hatchedPokemon.setOriginalTrainer(ownerUuid)
                        Cobblemon.LOGGER.warn("Egg ${eggPokemon.uuid} (Player OT) had missing name, set OT via UUID.")
                    } catch (e: IllegalArgumentException) {
                        Cobblemon.LOGGER.error("Failed to parse originalTrainer UUID string '$ownerUuidString' for egg ${eggPokemon.uuid} even as fallback", e)
                    }
                } else {
                    Cobblemon.LOGGER.warn("Egg ${eggPokemon.uuid} has PLAYER OT type but null/empty originalTrainer string and name.")
                }
            }
            OriginalTrainerType.NPC -> {
                val ownerName: String? = eggPokemon.originalTrainerName
                if (ownerName != null) {
                    hatchedPokemon.setOriginalTrainer(ownerName)
                } else {
                    Cobblemon.LOGGER.warn("Egg ${eggPokemon.uuid} has NPC OT type but null name.")
                }
            }
            OriginalTrainerType.NONE -> {
                // No OT to set
            }
            // Handle potential future OT types gracefully
            else -> {
                Cobblemon.LOGGER.warn("Encountered unexpected OriginalTrainerType '${eggPokemon.originalTrainerType}' for egg ${eggPokemon.uuid}. OT not set.")
            }
        }

        hatchedPokemon.nickname = null // Hatched Pokemon don't have nicknames by default

        // Clean up egg-specific NBT data from the hatched Pokemon
        hatchedPokemon.persistentData.remove(BreedingManager.EGG_NBT_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.TARGET_SPECIES_NBT_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.TOTAL_HATCH_STEPS_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.CURRENT_HATCH_STEPS_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.CALCULATED_IV_HP_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.CALCULATED_IV_ATK_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.CALCULATED_IV_DEF_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.CALCULATED_IV_SPA_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.CALCULATED_IV_SPD_KEY)
        hatchedPokemon.persistentData.remove(BreedingManager.CALCULATED_IV_SPE_KEY)


        val party = Cobblemon.storage.getParty(player) ?: return
        party[PartyPosition(slotIndex)] = hatchedPokemon

        val world = player.serverWorld
        val pos = player.pos

        player.sendMessage(Text.literal("Oh?").formatted(Formatting.YELLOW))

        world.playSound(null, player.blockPos, SoundEvents.ENTITY_CHICKEN_EGG, SoundCategory.PLAYERS, 1.0f, 1.0f)
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.x, pos.y + player.standingEyeHeight * 0.7, pos.z, 20, 0.5, 0.5, 0.5, 0.1)

        val message = Text.translatable("cobblemonbreeding.egg_hatched", hatchedPokemon.species.name).formatted(Formatting.GREEN)
        player.sendMessage(message)
    }
}