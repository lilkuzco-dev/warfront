package io.github.lilkuzcodev.warfront.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.lilkuzcodev.warfront.dialogue.WarfrontNet;
import io.github.lilkuzcodev.warfront.entity.WarfrontEntities;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class WarfrontClient implements ClientModInitializer {
	/** JSON client-config flag: {"dialogue_chat_mode": true} renders dialogue as clickable chat. */
	private static boolean chatMode;

	@Override
	public void onInitializeClient() {
		EntityRenderers.register(WarfrontEntities.SOLDIER, SoldierRenderer::new);
		EntityRenderers.register(WarfrontEntities.CITIZEN, CitizenRenderer::new);
		BlockEntityRenderers.register(
				io.github.lilkuzcodev.warfront.block.WarfrontBlockEntities.DISPLAY, DisplayRenderer::new);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(DisplayTextureCache::clear));
		ClientTickEvents.END_CLIENT_TICK.register(DisplayTextureCache::tick);
		loadClientConfig();

		ClientPlayNetworking.registerGlobalReceiver(WarfrontNet.DialogueS2C.TYPE, (payload, context) ->
				context.client().execute(() -> {
					Minecraft client = context.client();
					if (chatMode) {
						presentInChat(client, payload);
						return;
					}
					if (client.gui.screen() instanceof DialogueScreen screen) {
						screen.refresh(payload);
					} else {
						client.gui.setScreen(new DialogueScreen(payload));
					}
				}));
		ClientPlayNetworking.registerGlobalReceiver(WarfrontNet.DialogueCloseS2C.TYPE, (payload, context) ->
				context.client().execute(() -> {
					if (context.client().gui.screen() instanceof DialogueScreen) {
						context.client().gui.setScreen(null);
					}
				}));
	}

	private static void presentInChat(Minecraft client, WarfrontNet.DialogueS2C payload) {
		java.util.function.Consumer<Component> chat = c -> client.player.sendSystemMessage(c);
		chat.accept(Component.literal("— ").append(Component.literal(payload.soldierName())
				.withStyle(s -> s.withBold(true)))
				.append(Component.literal(" (" + payload.factionName() + ", " + payload.standing() + "/"
						+ payload.band() + ", " + payload.personality() + "/" + payload.mood() + ") —")));
		chat.accept(Component.literal(payload.soldierName() + ": ").withStyle(s -> s.withBold(true))
				.append(Component.translatable(payload.soldierLine()).withStyle(s -> s.withItalic(true))));
		if (payload.inBranch()) {
			chat.accept(Component.translatable("dialogue.warfront.chat_topic",
					Component.translatable(payload.topicKey()), payload.branchDepth(), payload.branchMaxDepth())
					.withStyle(ChatFormatting.GOLD));
		}
		int index = 1;
		for (WarfrontNet.OptionEntry option : payload.options()) {
			final int n = index++;
			ChatFormatting color = switch (option.tone()) {
				case "positive" -> ChatFormatting.GREEN;
				case "negative" -> ChatFormatting.RED;
				case "exit" -> ChatFormatting.GRAY;
				default -> ChatFormatting.WHITE;
			};
			chat.accept(Component.literal("  [" + n + "] ")
					.append(Component.translatable("dialogue.warfront.tone." + option.tone()))
					.append(Component.literal(" ")).append(Component.translatable(option.textKey()))
					.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("warfront talk " + option.id()))
							.withUnderlined(true).withColor(color)));
		}
		if (payload.inBranch()) {
			chat.accept(Component.literal("  [")
					.append(Component.translatable("dialogue.warfront.change_subject")).append(Component.literal("]"))
					.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("warfront talk __topics"))));
		} else if (payload.canMore()) {
			chat.accept(Component.literal("  [")
					.append(Component.translatable("dialogue.warfront.more")).append(Component.literal("]"))
					.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("warfront talk __more"))));
		}
		chat.accept(Component.literal("  [")
				.append(Component.translatable("dialogue.warfront.leave")).append(Component.literal("]"))
				.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("warfront talk __leave"))));
	}

	private static void loadClientConfig() {
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("warfront-client.json");
			if (Files.exists(file)) {
				JsonObject json = new Gson().fromJson(Files.readString(file), JsonObject.class);
				chatMode = json.has("dialogue_chat_mode") && json.get("dialogue_chat_mode").getAsBoolean();
			} else {
				Files.writeString(file, "{\n  \"dialogue_chat_mode\": false\n}\n");
			}
		} catch (Exception e) {
			io.github.lilkuzcodev.warfront.Warfront.LOGGER.warn("Could not read warfront-client.json", e);
		}
	}
}
