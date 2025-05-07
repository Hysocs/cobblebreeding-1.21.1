package com.cobblebreeding

import com.cobblebreeding.utils.BreedingManager
import com.cobblebreeding.utils.HatchManager
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.*
import java.util.concurrent.TimeUnit

data class WorldBlockPos(val dimensionId: Identifier, val pos: BlockPos) {
    constructor(worldKey: RegistryKey<World>, pos: BlockPos) : this(worldKey.value, pos.toImmutable())
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
    var breedingDurationTicks: Long = BreedingManager.BASE_BREEDING_DURATION_TICKS,
    var breedingTier: Int = 1,
    var needsDurationCalc: Boolean = false,
    var isCalculatingDuration: Boolean = false,
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
                    iterator.remove()
                    continue
                }
                BreedingManager.tickBreedingProcess(world, pos, state)
            }

            if (server.ticks % 20 == 0) {
                HatchManager.tickHatchingSteps(server)
                HatchManager.processHatchQueue(server)
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
                val worldKeyObject = world.registryKey
                val worldPos = WorldBlockPos(worldKeyObject, pos)
                if (pastureBreedingStates.containsKey(worldPos)) {
                    unregisterPastureOnUnload(worldPos)
                }
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            println("$PREFIX Shutting down breeding calculation executor.")
            BreedingManager.breedingCalculationExecutor.shutdown()
            try {
                if (!BreedingManager.breedingCalculationExecutor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                    BreedingManager.breedingCalculationExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                BreedingManager.breedingCalculationExecutor.shutdownNow()
            }
        })
    }

    fun getBreedingState(world: ServerWorld, pos: BlockPos): BreedingState? {
        return pastureBreedingStates[WorldBlockPos(world.registryKey, pos)]
    }

    fun getBreedingState(worldPosKey: WorldBlockPos): BreedingState? {
        return pastureBreedingStates[worldPosKey]
    }

    fun registerAndCheckPastureOnLoad(pastureBlockEntity: PokemonPastureBlockEntity, world: ServerWorld) {
        val pos = pastureBlockEntity.pos
        val worldKey = world.registryKey
        val worldPos = WorldBlockPos(worldKey, pos)

        val state = pastureBreedingStates.computeIfAbsent(worldPos) {
            BreedingState()
        }
        state.worldKey = worldKey
        BreedingManager.checkForInitialBreeders(world, pastureBlockEntity, state)
    }

    fun unregisterPastureOnUnload(worldPos: WorldBlockPos) {
        pastureBreedingStates.remove(worldPos)
    }

    fun registerNewPasture(world: ServerWorld, pos: BlockPos) {
        val worldKey = world.registryKey
        val worldPos = WorldBlockPos(worldKey, pos)
        val state = pastureBreedingStates.computeIfAbsent(worldPos) {
            BreedingState()
        }
        state.worldKey = worldKey
    }
}