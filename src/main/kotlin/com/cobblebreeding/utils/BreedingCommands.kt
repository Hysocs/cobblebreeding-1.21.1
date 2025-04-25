package com.cobblebreeding.utils

import com.cobblebreeding.CobblemonBreeding
import com.cobblebreeding.CobblemonBreeding.MOD_ID
import com.everlastingutils.command.CommandManager
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.CustomModelDataComponent // If you want to add model data later
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.command.CommandManager as FabricCommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting

object BreedingCommands {

    fun registerCommands() {
        // Use EverlastingUtils CommandManager for simplified registration + permission handling
        CommandManager(MOD_ID).apply {
            command(MOD_ID) { // Base command e.g., /cobblemonbreeding
                // Subcommand: /cobblemonbreeding give
                subcommand("give", permission = "cobblemonbreeding.command.give", opLevel = 2) {
                    executes(::executeGiveNestItem)
                }
                // Add other subcommands here later if needed
            }
            register() // Finalize registration
        }
    }

    private fun executeGiveNestItem(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player ?: run {
            source.sendError(Text.literal("This command can only be run by a player."))
            return 0
        }

        // Create the Nest Placer Item (Hay Bale for now)
        val nestPlacerStack = ItemStack(Items.HAY_BLOCK, 1)
        // Optional: Add custom name or lore if desired later
        // nestPlacerStack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Breeding Nest Placer").formatted(Formatting.GOLD))

        // Give the item to the player
        if (player.inventory.insertStack(nestPlacerStack)) {
            source.sendFeedback({ Text.literal("Gave you a Nest Placer (Hay Bale).").formatted(Formatting.GREEN) }, false)
        } else {
            // Inventory full, try dropping it
            player.dropItem(nestPlacerStack, false)
            source.sendFeedback({ Text.literal("Gave you a Nest Placer (Hay Bale) (dropped on ground).").formatted(Formatting.YELLOW) }, false)
        }

        return 1 // Success
    }
}