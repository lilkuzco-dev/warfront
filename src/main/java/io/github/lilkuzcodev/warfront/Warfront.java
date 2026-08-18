package io.github.lilkuzcodev.warfront;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Warfront implements ModInitializer {
	public static final String MOD_ID = "warfront";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		io.github.lilkuzcodev.warfront.data.WarfrontRegistry.init();
		io.github.lilkuzcodev.warfront.dialogue.DialogueRegistry.init();
		io.github.lilkuzcodev.warfront.dialogue.WarfrontNet.init();
		io.github.lilkuzcodev.warfront.dialogue.DialogueSessions.init();
		io.github.lilkuzcodev.warfront.block.WarfrontBlocks.init();
		io.github.lilkuzcodev.warfront.block.WarfrontBlockEntities.init();
		io.github.lilkuzcodev.warfront.block.WarfrontCreativeTab.init();
		io.github.lilkuzcodev.warfront.entity.WarfrontEntities.init();
		io.github.lilkuzcodev.warfront.civilization.CivilizationManager.init();
		io.github.lilkuzcodev.warfront.civilization.EconomyManager.init();
		io.github.lilkuzcodev.warfront.civilization.ExpeditionManager.init();
		io.github.lilkuzcodev.warfront.systems.TickScheduler.init();
		io.github.lilkuzcodev.warfront.systems.WarfrontSystems.init();
		io.github.lilkuzcodev.warfront.systems.BaseManager.init();
		io.github.lilkuzcodev.warfront.systems.WarfrontCommands.init();
		io.github.lilkuzcodev.warfront.order.General.registerExecutor("warfront:infantry_assault",
				new io.github.lilkuzcodev.warfront.order.InfantryAssaultExecution());
		LOGGER.info("Warfront initialized");
	}
}
