package com.cobblebreeding

import com.cobblebreeding.utils.BreedingManager
import com.cobblebreeding.utils.HatchManager // Import HatchManager
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.*
import kotlin.jvm.internal.Intrinsics


data class WorldBlockPos(val dimensionId: Identifier, val pos: BlockPos) {
    constructor(worldKey: RegistryKey<World>, pos: BlockPos) : this(worldKey.value, pos)
}

data class BreedingState(
    var malePokemonUUID: UUID? = null,
    var femalePokemonUUID: UUID? = null,
    var worldKey: RegistryKey<World>? = null,
    var breedingStartTick: Long? = null,
    var meetingPoint: Vec3d? = null,
    var walkingStarted: Boolean = false,
    var meetingEndTime: Long? = null,
    var jumpCount: Int = 0,
    var lastJumpTick: Long = 0L,
    var heartsPlayed: Boolean = false,
    var breedingDurationTicks: Long = 0L,
    var breedingTier: Int = 1,
    var needsDurationCalc: Boolean = false,
    var dittoUUID: UUID? = null,
    var isDittoPair: Boolean = false
)

object CobblemonBreeding : ModInitializer {
    const val MOD_ID = "cobblemonbreeding"
    internal const val PREFIX = "[$MOD_ID] "
    internal val pastureBreedingStates = mutableMapOf<WorldBlockPos, BreedingState>()

    override fun onInitialize() {
        println("$PREFIX Initializing Cobblemon Breeding Mod")

        ServerTickEvents.END_SERVER_TICK.register { server ->
            val iterator = pastureBreedingStates.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val worldPosKey = entry.key
                val state = entry.value
                val worldKey = state.worldKey

                val world = worldKey?.let { server.getWorld(it) }

                if (world == null) {
                    continue
                }

                val pos = worldPosKey.pos
                val blockEntity = world.getBlockEntity(pos)

                if (blockEntity !is PokemonPastureBlockEntity) {
                    println("$PREFIX INFO: Pasture block at ${worldPosKey.dimensionId}:${pos} no longer exists or is not a pasture. Removing state.")
                    iterator.remove()
                    continue
                }
                BreedingManager.tickBreedingProcess(world, pos, state)
            }

            if (server.ticks % 20 == 0) {
                HatchManager.tickHatchingSteps(server) // Call HatchManager function
                HatchManager.processHatchQueue(server) // Call HatchManager function
            }
        }

        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            chunk.blockEntities.forEach { (pos, blockEntity) ->
                if (blockEntity is PokemonPastureBlockEntity) {
                    registerAndCheckPastureOnLoad(blockEntity, world)
                }
            }
        }

        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            chunk.blockEntities.keys.forEach { pos ->
                val worldKey = world.registryKey
                val worldPos = WorldBlockPos(worldKey, pos)
                if (pastureBreedingStates.containsKey(worldPos)) {
                    unregisterPastureOnUnload(worldPos)
                }
            }
        }
    }

    fun registerAndCheckPastureOnLoad(pastureBlockEntity: PokemonPastureBlockEntity, world: ServerWorld) {
        val pos = pastureBlockEntity.pos
        val worldKey = world.registryKey
        val worldPos = WorldBlockPos(worldKey, pos)

        val state = pastureBreedingStates.computeIfAbsent(worldPos) {
            println("$PREFIX INFO: Registering loaded pasture at ${worldKey.value}:${pos} for breeding checks.")
            BreedingState()
        }
        state.worldKey = worldKey

        BreedingManager.checkForInitialBreeders(world, pastureBlockEntity, state)
    }

    fun unregisterPastureOnUnload(worldPos: WorldBlockPos) {
        val removedState = pastureBreedingStates.remove(worldPos)
        if (removedState != null) {
            println("$PREFIX INFO: Unregistering unloaded pasture at ${worldPos.dimensionId}:${worldPos.pos}.")
        }
    }

    fun registerNewPasture(world: ServerWorld, pos: BlockPos) {
        val worldKey = world.registryKey
        val worldPos = WorldBlockPos(worldKey, pos)

        val state = pastureBreedingStates.computeIfAbsent(worldPos) {
            println("$PREFIX INFO: Registering newly placed pasture at ${worldKey.value}:${pos}.")
            BreedingState()
        }
        state.worldKey = worldKey
    }
}