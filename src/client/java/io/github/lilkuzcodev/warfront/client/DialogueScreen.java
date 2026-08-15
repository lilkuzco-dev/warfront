package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.dialogue.WarfrontNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The compact dialogue screen (Stage 4B.4): soldier name/rank/faction header, standing
 * + disposition readout, the soldier's line, four option buttons, More..., Leave.
 * All state comes from the server payload; choices go back as ChooseC2S.
 */
public class DialogueScreen extends Screen {
	private static final int PANEL_WIDTH = 320;

	private WarfrontNet.DialogueS2C data;

	public DialogueScreen(WarfrontNet.DialogueS2C data) {
		super(Component.literal(data.soldierName()));
		this.data = data;
	}

	public void refresh(WarfrontNet.DialogueS2C payload) {
		this.data = payload;
		rebuildWidgets();
	}

	@Override
	protected void init() {
		int x = (width - PANEL_WIDTH) / 2;
		int y = height / 2 - 40;
		int index = 0;
		for (WarfrontNet.OptionEntry option : data.options()) {
			String id = option.id();
			addRenderableWidget(Button.builder(Component.translatable(option.textKey()),
					b -> ClientPlayNetworking.send(new WarfrontNet.ChooseC2S(id)))
					.bounds(x, y + index * 22, PANEL_WIDTH, 20).build());
			index++;
		}
		int bottomY = y + index * 22 + 6;
		if (data.canMore()) {
			addRenderableWidget(Button.builder(Component.translatable("dialogue.warfront.more"),
					b -> ClientPlayNetworking.send(new WarfrontNet.ChooseC2S("__more")))
					.bounds(x, bottomY, (PANEL_WIDTH - 4) / 2, 20).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("dialogue.warfront.leave"),
				b -> {
					ClientPlayNetworking.send(new WarfrontNet.ChooseC2S("__leave"));
					onClose();
				})
				.bounds(x + (data.canMore() ? (PANEL_WIDTH + 4) / 2 : 0), bottomY,
						data.canMore() ? (PANEL_WIDTH - 4) / 2 : PANEL_WIDTH, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int x = (width - PANEL_WIDTH) / 2;
		int headerY = height / 2 - 104;
		g.fill(x - 8, headerY - 8, x + PANEL_WIDTH + 8, height / 2 - 44, 0xB0101014);
		g.text(font, Component.literal(data.soldierName()).withStyle(s -> s.withBold(true)), x, headerY, 0xFFFFFFFF);
		g.text(font, Component.translatable("dialogue.warfront.header_rank_faction",
				Component.translatable("dialogue.warfront.rank." + data.rank()), data.factionName()),
				x, headerY + 12, 0xFFB0B0B8);
		g.text(font, Component.translatable("dialogue.warfront.header_standing",
				data.standing(), String.format("%.0f", data.standingValue()),
				Component.translatable("dialogue.warfront.band." + data.band())),
				x, headerY + 24, 0xFF8FA8C8);
		g.textWithWordWrap(font, Component.translatable(data.soldierLine()).withStyle(s -> s.withItalic(true)),
				x, headerY + 40, PANEL_WIDTH, 0xFFE8E4D0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
