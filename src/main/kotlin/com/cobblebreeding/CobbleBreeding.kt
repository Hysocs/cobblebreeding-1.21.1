package com.cobblebreeding

import com.cobblebreeding.utils.BreedingManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.*
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import kotlin.jvm.internal.Intrinsics

data class BreedingState(
    var malePokemonUUID: UUID? = null,
    var femalePokemonUUID: UUID? = null,
    var world: ServerWorld? = null,
    var breedingStartTick: Long? = null,
    var meetingPoint: Vec3d? = null,
    var walkingStarted: Boolean = false,
    var meetingEndTime: Long? = null,
    var jumpCount: Int = 0,
    var lastJumpTick: Long = 0L,
    var heartsPlayed: Boolean = false,
    val breedingDurationTicks: Int = 200
)

object CobblemonBreeding : ModInitializer {
    const val MOD_ID = "cobblemonbreeding"
    internal const val PREFIX = "[$MOD_ID] "
    internal val pastureBreedingStates = mutableMapOf<BlockPos, BreedingState>()

    override fun onInitialize() {
        println("$PREFIX Initializing Cobblemon Breeding Mod")

        ServerTickEvents.END_SERVER_TICK.register { server ->
            val iterator = pastureBreedingStates.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val pos = entry.key
                val state = entry.value
                val world = state.world ?: server.getWorld(state.world?.registryKey)?.also { state.world = it }

                if (world == null) {
                    continue
                }

                val blockEntity = world.getBlockEntity(pos)
                if (blockEntity !is PokemonPastureBlockEntity) {
                    println("$PREFIX INFO: Pasture block at $pos no longer exists or is not a pasture. Removing from breeding check.")
                    iterator.remove()
                    continue
                }

                BreedingManager.tickBreedingProcess(pos, state)
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
                if (pastureBreedingStates.containsKey(pos)) {
                    unregisterPastureOnUnload(pos)
                }
            }
        }
    }

    fun registerAndCheckPastureOnLoad(pastureBlockEntity: PokemonPastureBlockEntity, world: ServerWorld) {
        val pos = pastureBlockEntity.pos
        val state = pastureBreedingStates.computeIfAbsent(pos) {
            println("$PREFIX INFO: Registering loaded pasture at $pos for breeding checks.")
            BreedingState()
        }
        state.world = world

        BreedingManager.checkForInitialBreeders(pastureBlockEntity, state)
    }

    fun unregisterPastureOnUnload(pos: BlockPos) {
        val removedState = pastureBreedingStates.remove(pos)
        if (removedState != null) {
            println("$PREFIX INFO: Unregistering unloaded pasture at $pos.")
        }
    }
}