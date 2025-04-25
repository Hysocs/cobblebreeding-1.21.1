package com.cobblebreeding.utils

import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.entity.ai.goal.Goal
import net.minecraft.util.math.BlockPos
import java.util.EnumSet
import kotlin.math.max

class ReturnToPastureGoal(
    private val pokemonEntity: PokemonEntity,
    private val speed: Double
) : Goal() {

    // Use nullable types for fields that aren't always set or valid
    private var tethering: PokemonPastureBlockEntity.Tethering? = null
    private var centerPos: BlockPos? = null
    private var maxDistanceSq: Double = 0.0

    // Check frequency
    private var checkCooldown: Int = 0
    private val checkInterval: Int = 20 // Check roughly once per second

    init {
        // Use property access syntax for controls
        controls = EnumSet.of(Control.MOVE)
        // Initialize cooldown with a random offset using the entity's Java Random instance
        checkCooldown = pokemonEntity.random.nextInt(checkInterval / 2)
    }

    override fun canStart(): Boolean {
        // --- Cooldown Logic ---
        if (checkCooldown > 0) {
            checkCooldown--
            return false
        }
        // Reset cooldown with randomness
        checkCooldown = checkInterval + pokemonEntity.random.nextInt(checkInterval / 2)

        // --- Tethering Check ---
        // Use Kotlin's safe call ?.let which executes the block only if pokemonEntity.tethering is not null
        // If it's null, return false immediately. Assign to a local val for stability within the check.
        val currentTethering = pokemonEntity.tethering ?: return false
        this.tethering = currentTethering // Store the non-null value

        // --- Calculate Bounds (only if tethered) ---
        val min = currentTethering.minRoamPos
        val maxPos = currentTethering.maxRoamPos // Renamed from 'max' to avoid conflict with kotlin.math.max

        // Calculate center (use .x, .y, .z properties)
        val calculatedCenter = BlockPos(
            (min.x + maxPos.x) / 2,
            (min.y + maxPos.y) / 2, // Keep Y calculation for potential future use
            (min.z + maxPos.z) / 2
        )
        this.centerPos = calculatedCenter // Store the calculated center

        // Calculate approximate radius squared using kotlin.math.max
        val radiusX = (maxPos.x - calculatedCenter.x).toDouble()
        val radiusZ = (maxPos.z - calculatedCenter.z).toDouble()
        val radius = max(radiusX, radiusZ)
        this.maxDistanceSq = radius * radius

        // --- Distance Check ---
        // Use the non-null calculatedCenter directly. Use entity's current Y for horizontal check.
        // Note: BlockPos.getSquaredDistance(double x, double y, double z) is available
        val currentDistanceSq = pokemonEntity.blockPos.getSquaredDistance(
            calculatedCenter.x.toDouble(),
            pokemonEntity.y, // Use entity's current Y position
            calculatedCenter.z.toDouble()
        )

        // --- Conditions ---
        val isOutside = currentDistanceSq > this.maxDistanceSq
        val canNavigate = pokemonEntity.navigation.isIdle // Check if navigation is free

        // Optional Debug log (use Kotlin string template)
        // println("CanStart Check: ${pokemonEntity.name.string} Outside: $isOutside Idle: $canNavigate DistSq: $currentDistanceSq MaxDistSq: $maxDistanceSq")

        return isOutside && canNavigate
    }

    override fun shouldContinue(): Boolean {
        // Keep running as long as navigation is active and the entity is still tethered
        return !pokemonEntity.navigation.isIdle && pokemonEntity.tethering != null
    }

    override fun start() {
        // Use the stored centerPos, ensure it's not null before accessing using safe call ?.let
        centerPos?.let { targetPos ->
            // Optional Debug log
            // println("Starting ReturnToPastureGoal for: ${pokemonEntity.name.string} towards $targetPos")

            // Move towards the calculated center position (add 0.5 to target block center)
            pokemonEntity.navigation.startMovingTo(
                targetPos.x + 0.5,
                targetPos.y + 0.5, // Target center Y as well
                targetPos.z + 0.5,
                speed
            )
        } ?: run {
            // Log warning if centerPos was unexpectedly null
            // println("Warning: ReturnToPastureGoal.start() called but centerPos was null for ${pokemonEntity.name.string}")
        }
    }

    override fun stop() {
        // Optional Debug log
        // println("Stopping ReturnToPastureGoal for: ${pokemonEntity.name.string}")

        // No need to explicitly stop navigation usually, it stops on completion/interruption
        // Clear cached info
        tethering = null
        centerPos = null
    }
}