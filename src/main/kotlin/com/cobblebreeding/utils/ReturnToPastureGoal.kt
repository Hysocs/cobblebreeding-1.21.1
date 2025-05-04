package com.cobblebreeding.utils

import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.entity.ai.goal.Goal
import net.minecraft.util.math.BlockPos
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max

class ReturnToPastureGoal(
    private val pokemonEntity: PokemonEntity,
    private val speed: Double
) : Goal() {

    private var tethering: PokemonPastureBlockEntity.Tethering? = null
    private var centerPos: BlockPos? = null
    private var maxDistanceSq: Double = 0.0
    private var checkCooldown: Int = 0
    private val checkInterval: Int = 20
    private val boundaryBuffer: Double = 2.0 // Buffer distance from the actual edge

    init {
        controls = EnumSet.of(Control.MOVE)
        checkCooldown = pokemonEntity.random.nextInt(checkInterval / 2)
    }

    override fun canStart(): Boolean {
        if (checkCooldown > 0) {
            checkCooldown--
            return false
        }
        checkCooldown = checkInterval + pokemonEntity.random.nextInt(checkInterval / 2)

        val currentTethering = pokemonEntity.tethering ?: return false
        this.tethering = currentTethering

        val min = currentTethering.minRoamPos
        val maxPos = currentTethering.maxRoamPos

        val calculatedCenter = BlockPos(
            (min.x + maxPos.x) / 2,
            (min.y + maxPos.y) / 2,
            (min.z + maxPos.z) / 2
        )
        this.centerPos = calculatedCenter

        // Calculate radius based on the *actual* boundaries
        val radiusX = (maxPos.x - calculatedCenter.x).toDouble()
        val radiusZ = (maxPos.z - calculatedCenter.z).toDouble()
        // Use absolute value in case center isn't perfectly integer-aligned
        val rawRadius = max(abs(radiusX), abs(radiusZ))

        // Apply the buffer inwards, ensuring it doesn't go below zero
        val effectiveRadius = max(0.0, rawRadius - boundaryBuffer)
        this.maxDistanceSq = effectiveRadius * effectiveRadius // Use buffered radius for trigger check

        val currentDistanceSq = pokemonEntity.blockPos.getSquaredDistance(
            calculatedCenter.x.toDouble(),
            pokemonEntity.y,
            calculatedCenter.z.toDouble()
        )

        // Trigger if current distance exceeds the *buffered* squared distance
        val isOutsideBuffered = currentDistanceSq > this.maxDistanceSq
        val canNavigate = pokemonEntity.navigation.isIdle

        return isOutsideBuffered && canNavigate
    }

    override fun shouldContinue(): Boolean {
        return !pokemonEntity.navigation.isIdle && pokemonEntity.tethering != null
    }

    override fun start() {
        centerPos?.let { targetPos ->
            pokemonEntity.navigation.startMovingTo(
                targetPos.x + 0.5,
                targetPos.y + 0.5,
                targetPos.z + 0.5,
                speed
            )
        }
    }

    override fun stop() {
        tethering = null
        centerPos = null
    }
}