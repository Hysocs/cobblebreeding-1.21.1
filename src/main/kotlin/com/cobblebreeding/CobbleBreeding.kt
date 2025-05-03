package com.cobblebreeding

import com.cobblebreeding.utils.BreedingManager
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Pokemon
import com.everlastingutils.gui.CustomGui
import com.everlastingutils.gui.InteractionContext
import com.everlastingutils.utils.logDebug
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.ceil
import kotlin.math.min

fun String.capitalized(): String {
	if (this.isEmpty()) return this
	return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

data class BreedingGuiState(
	var malePokemonUUID: UUID? = null,
	var femalePokemonUUID: UUID? = null,
	var eggItemStack: ItemStack? = null,
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

data class SelectionGuiState(
	var currentPage: Int,
	var slotMap: Map<Int, UUID>,
	val targetGender: Gender,
	val pasturePos: BlockPos
)

object CobblemonBreeding : ModInitializer {

	internal val logger = LoggerFactory.getLogger("cobblebreeding")
	const val MOD_ID = "cobblemonbreeding"
	const val PERM_LEVEL_INTERACT = 0
	private const val BREEDING_GUI_ROWS = 3
	private const val BREEDING_GUI_SIZE = BREEDING_GUI_ROWS * 9
	private const val MALE_SELECTOR_SLOT = 10
	private const val FEMALE_SELECTOR_SLOT = 16
	private const val RESULT_EGG_SLOT = 13
	private val MALE_PANE_SLOTS = listOf(0, 1, 2, 9, 11, 18, 19, 20)
	private val FEMALE_PANE_SLOTS = listOf(6, 7, 8, 15, 17, 24, 25, 26)
	private val RESULT_PANE_SLOTS = listOf(3, 4, 5, 12, 14, 21, 22, 23)
	private const val SELECTION_GUI_ROWS = 6
	private const val SELECTION_GUI_SIZE = SELECTION_GUI_ROWS * 9
	private const val POKEMON_PER_PAGE = 28
	private val SELECTION_DISPLAY_SLOTS = (1..4).flatMap { r -> (1..7).map { c -> r * 9 + c } }
	private const val PREV_PAGE_SLOT = 45
	private const val NEXT_PAGE_SLOT = 53
	private const val BACK_BUTTON_SLOT = 49
	private const val NOT_TARGET_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODlhOTk1OTI4MDkwZDg0MmQ0YWZkYjIyOTZmZmUyNGYyZTk0NDI3MjIwNWNlYmE4NDhlZTQwNDZlMDFmMzE2OCJ9fX0="
	private val pastureBreedingGuiStates = mutableMapOf<BlockPos, BreedingGuiState>()
	private val playerSelectionGuiStates = mutableMapOf<UUID, SelectionGuiState>()
	private val styleItalicFalse = Style.EMPTY.withItalic(false)
	private val styleWhite = styleItalicFalse.withColor(Formatting.WHITE)
	private val styleGray = styleItalicFalse.withColor(Formatting.GRAY)
	private val styleDarkGray = styleItalicFalse.withColor(Formatting.DARK_GRAY)
	private val styleGreen = styleItalicFalse.withColor(Formatting.GREEN)
	private val styleYellow = styleItalicFalse.withColor(Formatting.YELLOW)
	private val styleGold = styleItalicFalse.withColor(Formatting.GOLD)
	private val styleAqua = styleItalicFalse.withColor(Formatting.AQUA)
	private val styleDarkAqua = styleItalicFalse.withColor(Formatting.DARK_AQUA)
	private val styleLightPurple = styleItalicFalse.withColor(Formatting.LIGHT_PURPLE)
	private val styleRed = styleItalicFalse.withColor(Formatting.RED)
	private val fillerPane: ItemStack = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply { remove(DataComponentTypes.CUSTOM_NAME) }
	private val malePane: ItemStack = ItemStack(Items.LIGHT_BLUE_STAINED_GLASS_PANE).apply { set(DataComponentTypes.CUSTOM_NAME, Text.literal(" ").setStyle(styleItalicFalse)) }
	private val femalePane: ItemStack = ItemStack(Items.PINK_STAINED_GLASS_PANE).apply { set(DataComponentTypes.CUSTOM_NAME, Text.literal(" ").setStyle(styleItalicFalse)) }
	private val resultPane: ItemStack = ItemStack(Items.YELLOW_STAINED_GLASS_PANE).apply { set(DataComponentTypes.CUSTOM_NAME, Text.literal(" ").setStyle(styleItalicFalse)) }

	override fun onInitialize() {
		logger.info("Initializing Cobblemon Breeding Mod")
		logDebug("Debug logging enabled.", MOD_ID)
		ServerTickEvents.END_SERVER_TICK.register { server ->
			pastureBreedingGuiStates.keys.toList().forEach { pos ->
				pastureBreedingGuiStates[pos]?.let { state ->
					BreedingManager.tickBreedingProcess(pos, state)
				}
			}
		}
	}

	private fun createPokemonDisplayItem(pokemon: Pokemon, ownerName: String? = null): ItemStack {
		val itemStack = PokemonItem.from(pokemon)
		val genderColor = when (pokemon.gender) { Gender.MALE -> styleAqua; Gender.FEMALE -> styleLightPurple; else -> styleGray }
		itemStack.set(DataComponentTypes.CUSTOM_NAME, pokemon.getDisplayName().copy().setStyle(genderColor))
		val loreLines = mutableListOf<Text>()
		if (ownerName != null) { loreLines.add(Text.literal("Owner: ").setStyle(styleGray).append(Text.literal(ownerName).setStyle(styleYellow))) }
		loreLines.add(Text.literal("Species: ${pokemon.species.name.capitalized()}").setStyle(styleWhite))
		loreLines.add(Text.literal("Lvl: ${pokemon.level} | Gender: ").append(Text.literal(pokemon.gender.name.capitalized()).setStyle(genderColor)).setStyle(styleWhite))
		loreLines.add(Text.literal("Nature: ${pokemon.nature.name.path.substringAfterLast('/').capitalized()}").setStyle(styleGreen))
		loreLines.add(Text.literal("Ability: ${pokemon.ability.name.capitalized()}").setStyle(styleYellow))
		if (pokemon.shiny) { loreLines.add(Text.literal("Shiny!").formatted(Formatting.GOLD, Formatting.BOLD).setStyle(styleItalicFalse)) }
		val aspects = pokemon.form.aspects.joinToString { it.toString() }
		if (aspects.isNotEmpty()) { loreLines.add(Text.literal("Aspects: $aspects").setStyle(styleLightPurple)) }
		if (pokemon.heldItem() != ItemStack.EMPTY) { loreLines.add(Text.literal("Held Item: ${pokemon.heldItem().name.string}").setStyle(styleGray)) }
		loreLines.add(Text.literal("Friendship: ${pokemon.friendship}").setStyle(styleLightPurple))
		loreLines.add(Text.literal(" ").setStyle(styleItalicFalse))
		val statsOrder = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
		loreLines.add(Text.literal("IVs:").setStyle(styleDarkAqua))
		val ivTexts = mutableListOf<Text>()
		statsOrder.forEach { stat -> val ivValue = pokemon.ivs[stat] ?: 0; ivTexts.add(Text.literal("${stat.displayName.string.take(3)}: $ivValue").setStyle(styleAqua)) }
		ivTexts.chunked(3).forEach { chunk -> val line = Text.literal("  ").setStyle(styleItalicFalse); chunk.forEachIndexed { index, text -> line.append(text); if (index < chunk.size - 1) { line.append(Text.literal(" | ").setStyle(styleDarkGray)) } }; loreLines.add(line) }
		loreLines.add(Text.literal("EVs:").setStyle(styleGold))
		val evTexts = mutableListOf<Text>()
		statsOrder.forEach { stat -> val evValue = pokemon.evs[stat] ?: 0; evTexts.add(Text.literal("${stat.displayName.string.take(3)}: $evValue").setStyle(styleYellow)) }
		evTexts.chunked(3).forEach { chunk -> val line = Text.literal("  ").setStyle(styleItalicFalse); chunk.forEachIndexed { index, text -> line.append(text); if (index < chunk.size - 1) { line.append(Text.literal(" | ").setStyle(styleDarkGray)) } }; loreLines.add(line) }
		loreLines.add(Text.literal(" ").setStyle(styleItalicFalse))
		loreLines.add(Text.literal("Click to Select (If Owner) / Right-Click to Remove (If Owner)").setStyle(styleGreen))
		itemStack.set(DataComponentTypes.LORE, LoreComponent(loreLines))
		return itemStack
	}
	private fun createMaleSelectorEgg(): ItemStack = ItemStack(Items.ALLAY_SPAWN_EGG).apply { set(DataComponentTypes.CUSTOM_NAME, Text.literal("Select Male Parent").setStyle(styleAqua)); set(DataComponentTypes.LORE, LoreComponent(listOf(Text.literal("Click to choose a Male Pokémon").setStyle(styleGray),Text.literal("from the Pasture.").setStyle(styleGray)))) }
	private fun createFemaleSelectorEgg(): ItemStack = ItemStack(Items.PIG_SPAWN_EGG).apply { set(DataComponentTypes.CUSTOM_NAME, Text.literal("Select Female Parent").setStyle(styleLightPurple)); set(DataComponentTypes.LORE, LoreComponent(listOf(Text.literal("Click to choose a Female Pokémon").setStyle(styleGray), Text.literal("from the Pasture.").setStyle(styleGray)))) }
	private fun createResultEggPlaceholder(): ItemStack = ItemStack(Items.EGG).apply { set(DataComponentTypes.CUSTOM_NAME, Text.literal("Breeding Result").setStyle(styleYellow)); set(DataComponentTypes.LORE, LoreComponent(listOf(Text.literal("Select compatible parents").setStyle(styleGray), Text.literal("to begin breeding.").setStyle(styleGray), Text.literal("An egg will appear here.").setStyle(styleDarkGray)))) }
	private fun createBreedingInProgressPlaceholder(): ItemStack = ItemStack(Items.EGG).apply { set(DataComponentTypes.CUSTOM_NAME, Text.literal("Breeding In Progress...").setStyle(styleYellow)); set(DataComponentTypes.LORE, LoreComponent(listOf(Text.literal("Parents are getting acquainted.").setStyle(styleGray), Text.literal("Wait for the result!").setStyle(styleGray)))) }
	private fun createBackButton(): ItemStack = CustomGui.createPlayerHeadButton(textureName = "BackArrow", title = Text.literal("Back").setStyle(styleYellow), lore = listOf<Text>(Text.literal("Return to previous screen").setStyle(styleGray)), textureValue = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0=")
	private fun createPageArrow(next: Boolean): ItemStack = ItemStack(Items.ARROW).apply { val action = if (next) "Next" else "Previous"; set(DataComponentTypes.CUSTOM_NAME, Text.literal("$action Page").setStyle(styleGreen)); set(DataComponentTypes.LORE, LoreComponent(listOf(Text.literal("Click to go to the $action page.").setStyle(styleGray)))) }
	private fun createIncompatibilityIndicatorItem(reason: String): ItemStack = CustomGui.createPlayerHeadButton(textureName = "IncompatiblePokemon", title = Text.literal("Incompatible!").setStyle(styleRed), lore = listOf<Text>(Text.literal(reason).setStyle(styleGray), Text.literal("Select different Pokémon.").setStyle(styleDarkGray)), textureValue = NOT_TARGET_TEXTURE)

	private fun getPokemonFromPasture(player: ServerPlayerEntity, pastureBasePos: BlockPos): Map<UUID, Pair<Pokemon, UUID>> {
		val world = player.serverWorld
		val blockEntity = world.getBlockEntity(pastureBasePos) as? PokemonPastureBlockEntity ?: return emptyMap()
		return blockEntity.tetheredPokemon.mapNotNull { tethering -> try { tethering.getPokemon()?.let { pokemon -> tethering.pokemonId to Pair(pokemon, tethering.playerId) } } catch (e: Exception) { logger.error("Error getting Pokemon (UUID: ${tethering.pokemonId}) from tethering object at $pastureBasePos", e); null } }.toMap()
	}

	private fun generateBreedingLayout(player: ServerPlayerEntity, pastureBasePos: BlockPos): List<ItemStack> {
		val layout = MutableList(BREEDING_GUI_SIZE) { fillerPane.copy() }
		val pastureState = pastureBreedingGuiStates.computeIfAbsent(pastureBasePos) { BreedingGuiState().apply { world = player.serverWorld } }
		if (pastureState.world == null) pastureState.world = player.serverWorld
		val pokemonOwnerMap = getPokemonFromPasture(player, pastureBasePos)
		MALE_PANE_SLOTS.forEach { layout[it] = malePane.copy() }; FEMALE_PANE_SLOTS.forEach { layout[it] = femalePane.copy() }; RESULT_PANE_SLOTS.forEach { layout[it] = resultPane.copy() }
		val maleInfo = pastureState.malePokemonUUID?.let { pokemonOwnerMap[it] }; val femaleInfo = pastureState.femalePokemonUUID?.let { pokemonOwnerMap[it] }
		val malePokemon = maleInfo?.first; val femalePokemon = femaleInfo?.first
		val getOwnerName: (UUID?) -> String? = { uuid -> uuid?.let { player.server.playerManager.getPlayer(it)?.gameProfile?.name ?: "Unknown" } }
		layout[MALE_SELECTOR_SLOT] = malePokemon?.let { createPokemonDisplayItem(it, getOwnerName(maleInfo?.second)) } ?: createMaleSelectorEgg()
		layout[FEMALE_SELECTOR_SLOT] = femalePokemon?.let { createPokemonDisplayItem(it, getOwnerName(femaleInfo?.second)) } ?: createFemaleSelectorEgg()

		if (pastureState.eggItemStack != null && pastureState.eggItemStack!!.isOf(Items.TURTLE_EGG)) {
			layout[RESULT_EGG_SLOT] = pastureState.eggItemStack!!.copy()
		} else if (malePokemon != null && femalePokemon != null) {
			val compatible = (malePokemon.species == femalePokemon.species) && ((malePokemon.gender == Gender.MALE && femalePokemon.gender == Gender.FEMALE) || (malePokemon.gender == Gender.FEMALE && femalePokemon.gender == Gender.MALE))
			if (compatible) {
				if (pastureState.breedingStartTick != null) {
					layout[RESULT_EGG_SLOT] = createBreedingInProgressPlaceholder()
				} else {
					layout[RESULT_EGG_SLOT] = createResultEggPlaceholder()
				}
			} else {
				layout[RESULT_EGG_SLOT] = createIncompatibilityIndicatorItem("Parents are incompatible.")
			}
		} else {
			layout[RESULT_EGG_SLOT] = createResultEggPlaceholder()
		}
		return layout
	}

	private fun generateSelectionLayout(player: ServerPlayerEntity, pastureBasePos: BlockPos, targetGender: Gender, page: Int): Pair<List<ItemStack>, SelectionGuiState> {
		val layout = MutableList(SELECTION_GUI_SIZE) { fillerPane.copy() }; val slotMap = mutableMapOf<Int, UUID>()
		val pokemonOwnerMap = getPokemonFromPasture(player, pastureBasePos)
		val filteredPokemonInfo = pokemonOwnerMap.entries.filter { (_, pair) -> pair.first.gender == targetGender }.map { (uuid, pair) -> uuid to pair }
		val totalPokemon = filteredPokemonInfo.size; val maxPages = if (totalPokemon == 0) 1 else ceil(totalPokemon.toDouble() / POKEMON_PER_PAGE).toInt()
		val currentPage = page.coerceIn(0, maxPages - 1); val startIndex = currentPage * POKEMON_PER_PAGE
		val endIndex = min(startIndex + POKEMON_PER_PAGE, totalPokemon)
		val pokemonInfoToShow = if (startIndex < totalPokemon) filteredPokemonInfo.subList(startIndex, endIndex) else emptyList()
		val getOwnerName: (UUID?) -> String? = { uuid -> uuid?.let { player.server.playerManager.getPlayer(it)?.gameProfile?.name ?: "Unknown" } }
		pokemonInfoToShow.forEachIndexed { index, (pokemonUUID, pokemonInfo) -> if (index < SELECTION_DISPLAY_SLOTS.size) { val guiSlot = SELECTION_DISPLAY_SLOTS[index]; layout[guiSlot] = createPokemonDisplayItem(pokemonInfo.first, getOwnerName(pokemonInfo.second)); slotMap[guiSlot] = pokemonUUID } }
		if (currentPage > 0) layout[PREV_PAGE_SLOT] = createPageArrow(false)
		if (currentPage < maxPages - 1) layout[NEXT_PAGE_SLOT] = createPageArrow(true)
		layout[BACK_BUTTON_SLOT] = createBackButton()
		val filledSlots = pokemonInfoToShow.size
		for (i in filledSlots until POKEMON_PER_PAGE) { if (i < SELECTION_DISPLAY_SLOTS.size) { val slotIndex = SELECTION_DISPLAY_SLOTS[i]; if (layout[slotIndex] == fillerPane || layout[slotIndex].isEmpty) { layout[slotIndex] = fillerPane.copy() } } }
		val currentGuiState = SelectionGuiState(currentPage, slotMap.toMap(), targetGender, pastureBasePos)
		return Pair(layout, currentGuiState)
	}

	fun openNestGui(player: ServerPlayerEntity, pastureBasePos: BlockPos) {
		val pastureState = pastureBreedingGuiStates.computeIfAbsent(pastureBasePos) { BreedingGuiState().apply { world = player.serverWorld } }
		if (pastureState.world == null) pastureState.world = player.serverWorld

		val initialLayout = generateBreedingLayout(player, pastureBasePos)
		val title = "Breeding Nest @ $pastureBasePos"
		logDebug("Opening Breeding Nest GUI for ${player.name.string} at $pastureBasePos", MOD_ID)

		try {
			CustomGui.openGui( player, title, initialLayout, BREEDING_GUI_ROWS,
				{ context: InteractionContext ->
					val currentPastureState = pastureBreedingGuiStates[pastureBasePos]
					if (currentPastureState == null) { println("ERROR: Breeding GUI state missing for $pastureBasePos"); logger.error("Breeding GUI state not found for pasture at $pastureBasePos"); context.player.closeHandledScreen(); return@openGui }

					val clickedSlotIndex = context.slotIndex
					val interactingPlayerUUID = context.player.uuid
					val pokemonOwnerMap = getPokemonFromPasture(context.player, pastureBasePos)

					val handleDeselect = { isMale: Boolean ->
						val pokemonUUID = if (isMale) currentPastureState.malePokemonUUID else currentPastureState.femalePokemonUUID
						if (pokemonUUID != null && context.clickType == ClickType.RIGHT) {
							val ownerUUID = pokemonOwnerMap[pokemonUUID]?.second
							if (ownerUUID == interactingPlayerUUID) {
								if (isMale) currentPastureState.malePokemonUUID = null else currentPastureState.femalePokemonUUID = null
								BreedingManager.cancelBreeding(currentPastureState, null, null)
								context.player.sendMessage(Text.literal("${if (isMale) "Male" else "Female"} parent deselected.").setStyle(if (isMale) styleAqua else styleLightPurple), true)
								CustomGui.refreshGui(context.player, generateBreedingLayout(context.player, pastureBasePos))
							} else {
								context.player.sendMessage(Text.literal("You are not the owner of this Pokémon.").setStyle(styleRed), true)
							}
							true
						} else { false }
					}

					when (clickedSlotIndex) {
						MALE_SELECTOR_SLOT -> { if (!handleDeselect(true)) openPokemonSelectionGui(context.player, pastureBasePos, Gender.MALE) }
						FEMALE_SELECTOR_SLOT -> { if (!handleDeselect(false)) openPokemonSelectionGui(context.player, pastureBasePos, Gender.FEMALE) }

						RESULT_EGG_SLOT -> {
							val currentItem = context.player.currentScreenHandler.getSlot(clickedSlotIndex).stack

							if (currentItem.isOf(Items.TURTLE_EGG) && currentPastureState.eggItemStack != null) {
								println("DEBUG: Player clicked completed egg slot for $pastureBasePos. Attempting generation and party add.")

								val givenSuccessfully = BreedingManager.generateAndGiveEggToParty(pastureBasePos, currentPastureState, context.player)

								if (givenSuccessfully) {
									context.player.sendMessage(Text.literal("You collected the Pokémon Egg!").setStyle(styleGreen), true)
									CustomGui.refreshGui(context.player, generateBreedingLayout(context.player, pastureBasePos))
								} else {
									context.player.sendMessage(Text.literal("Your party is full! Cannot collect the egg.").setStyle(styleRed), true)
								}
							}
							else if (currentItem.isOf(Items.PLAYER_HEAD)) {
								context.player.sendMessage(Text.literal("These Pokémon are incompatible.").setStyle(styleRed), true)
							} else if (currentItem.isOf(Items.EGG)) {
								if (currentPastureState.breedingStartTick != null && currentPastureState.eggItemStack == null) {
									context.player.sendMessage(Text.literal("Breeding is in progress...").setStyle(styleYellow), true)
								} else {
									context.player.sendMessage(Text.literal("Select compatible parents to begin.").setStyle(styleGray), true)
								}
							} else if (currentItem == ItemStack.EMPTY || currentItem.isOf(Items.GRAY_STAINED_GLASS_PANE)) {
								// Ignore clicks
							} else {
								logDebug("Clicked result slot with unexpected item: ${currentItem.item} for pasture $pastureBasePos", MOD_ID)
							}
						}

						in MALE_PANE_SLOTS, in FEMALE_PANE_SLOTS, in RESULT_PANE_SLOTS -> {}
						else -> {
							val stack = context.player.currentScreenHandler.getSlot(clickedSlotIndex)?.stack ?: ItemStack.EMPTY
							if (!stack.isOf(Items.GRAY_STAINED_GLASS_PANE) && clickedSlotIndex >= 0 && clickedSlotIndex < BREEDING_GUI_SIZE) {
								logDebug("Player $interactingPlayerUUID clicked unhandled slot $clickedSlotIndex in Breeding GUI.", MOD_ID)
							}
						}
					}
				},
				{ _: Inventory -> logDebug("Closed Breeding Nest GUI for ${player.name.string} at $pastureBasePos.", MOD_ID) }
			)
		} catch (e: Throwable) {
			println("ERROR: CRITICAL ERROR opening Breeding Nest GUI!"); e.printStackTrace()
			logger.error("!!! CRITICAL ERROR opening Breeding Nest GUI for player ${player.name.string} at $pastureBasePos !!!", e)
			player.sendMessage(Text.literal("Critical Error opening GUI. See server console!").setStyle(styleRed))
		}
	}

	fun openPokemonSelectionGui(player: ServerPlayerEntity, pastureBasePos: BlockPos, targetGender: Gender, requestedPage: Int = 0) {
		val currentPastureState = pastureBreedingGuiStates.computeIfAbsent(pastureBasePos) { BreedingGuiState().apply { world = player.serverWorld } }
		if (currentPastureState.world == null) currentPastureState.world = player.serverWorld
		val (initialLayout, initialSelectionState) = generateSelectionLayout(player, pastureBasePos, targetGender, requestedPage)
		playerSelectionGuiStates[player.uuid] = initialSelectionState
		val genderString = targetGender.name.capitalized(); val title = "Select $genderString Pokémon (Page ${initialSelectionState.currentPage + 1})"
		logDebug("Opening Pokemon Selection GUI ($genderString, Page ${initialSelectionState.currentPage + 1}) for ${player.name.string}", MOD_ID)
		try {
			CustomGui.openGui( player, title, initialLayout, SELECTION_GUI_ROWS,
				{ context: InteractionContext ->
					val selectionState = playerSelectionGuiStates[context.player.uuid]
					val interactionPastureState = pastureBreedingGuiStates[pastureBasePos]
					if (selectionState == null || interactionPastureState == null || selectionState.pasturePos != pastureBasePos || selectionState.targetGender != targetGender) { println("ERROR: GUI State mismatch/missing state"); logger.error("GUI State mismatch/missing state"); context.player.closeHandledScreen(); return@openGui }
					if (interactionPastureState.world == null) interactionPastureState.world = player.serverWorld

					val clickedSlotIndex = context.slotIndex; val interactingPlayerUUID = context.player.uuid
					val currentPokemonOwnerMap = getPokemonFromPasture(player, selectionState.pasturePos)
					val refreshSelectionPage = { newPage: Int -> val (nl, ns) = generateSelectionLayout(player, selectionState.pasturePos, selectionState.targetGender, newPage); playerSelectionGuiStates[player.uuid] = ns; val nt = "Select $genderString Pokémon (Page ${ns.currentPage + 1})"; CustomGui.refreshGui(player, nl) }

					when (clickedSlotIndex) {
						PREV_PAGE_SLOT -> { if (selectionState.currentPage > 0) refreshSelectionPage(selectionState.currentPage - 1) }
						NEXT_PAGE_SLOT -> { val filteredCount = currentPokemonOwnerMap.values.count { (p, _) -> p.gender == selectionState.targetGender }; val maxP = if (filteredCount == 0) 1 else ceil(filteredCount.toDouble() / POKEMON_PER_PAGE).toInt(); if (selectionState.currentPage < maxP - 1) refreshSelectionPage(selectionState.currentPage + 1) }
						BACK_BUTTON_SLOT -> { openNestGui(player, pastureBasePos) }
						in selectionState.slotMap.keys -> {
							val clickedPokemonUUID = selectionState.slotMap[clickedSlotIndex] ?: return@openGui
							val clickedPokemonInfo = currentPokemonOwnerMap[clickedPokemonUUID]
							val clickedPokemonOwnerUUID = clickedPokemonInfo?.second
							if (clickedPokemonOwnerUUID != interactingPlayerUUID) { player.sendMessage(Text.literal("You are not the owner of this Pokémon.").setStyle(styleRed), true); return@openGui }

							val isDeselecting = (targetGender == Gender.MALE && interactionPastureState.malePokemonUUID == clickedPokemonUUID) || (targetGender == Gender.FEMALE && interactionPastureState.femalePokemonUUID == clickedPokemonUUID)
							if (isDeselecting) {
								if (targetGender == Gender.MALE) interactionPastureState.malePokemonUUID = null else interactionPastureState.femalePokemonUUID = null
								BreedingManager.cancelBreeding(interactionPastureState, null, null)
								player.sendMessage(Text.literal("$genderString parent deselected.").setStyle(if (targetGender == Gender.MALE) styleAqua else styleLightPurple), true)
								openNestGui(player, pastureBasePos)
							} else {
								if ((targetGender == Gender.MALE && interactionPastureState.malePokemonUUID != null) || (targetGender == Gender.FEMALE && interactionPastureState.femalePokemonUUID != null)) { BreedingManager.cancelBreeding(interactionPastureState, null, null) }
								if (targetGender == Gender.MALE) interactionPastureState.malePokemonUUID = clickedPokemonUUID else interactionPastureState.femalePokemonUUID = clickedPokemonUUID
								val maleUUID = interactionPastureState.malePokemonUUID; val femaleUUID = interactionPastureState.femalePokemonUUID
								if (maleUUID != null && femaleUUID != null) {
									val maleInfo = currentPokemonOwnerMap[maleUUID]; val femaleInfo = currentPokemonOwnerMap[femaleUUID]
									if (maleInfo != null && femaleInfo != null) {
										val compatible = (maleInfo.first.species == femaleInfo.first.species) && ((maleInfo.first.gender == Gender.MALE && femaleInfo.first.gender == Gender.FEMALE) || (maleInfo.first.gender == Gender.FEMALE && femaleInfo.first.gender == Gender.MALE))
										if (compatible) {
											val pastureBE = player.serverWorld.getBlockEntity(pastureBasePos) as? PokemonPastureBlockEntity
											val tethersOk = (pastureBE?.tetheredPokemon?.any { it.pokemonId == maleUUID } == true) && (pastureBE?.tetheredPokemon?.any { it.pokemonId == femaleUUID } == true)
											if (tethersOk) { BreedingManager.startBreeding(pastureBasePos, interactionPastureState, player.serverWorld); player.sendMessage(Text.literal("Breeding started!").setStyle(styleGreen), true) }
											else { player.sendMessage(Text.literal("One or both Pokémon seem to have left the pasture.").setStyle(styleYellow), true); BreedingManager.cancelBreeding(interactionPastureState, null, null) }
										} else { BreedingManager.cancelBreeding(interactionPastureState, null, null) }
									} else { BreedingManager.cancelBreeding(interactionPastureState, null, null) }
								}
								openNestGui(player, pastureBasePos)
							}
						}
						else -> { logDebug("Player $interactingPlayerUUID clicked unhandled slot $clickedSlotIndex in Selection GUI.", MOD_ID) }
					}
				},
				{ _: Inventory -> playerSelectionGuiStates.remove(player.uuid) }
			)
		} catch (e: Throwable) {
			println("ERROR: CRITICAL ERROR opening Selection GUI!"); e.printStackTrace()
			logger.error("!!! CRITICAL ERROR opening Pokemon Selection GUI for player ${player.name.string} !!!", e)
			player.sendMessage(Text.literal("Critical Error opening selection GUI. See server console!").setStyle(styleRed)); playerSelectionGuiStates.remove(player.uuid)
		}
	}
}