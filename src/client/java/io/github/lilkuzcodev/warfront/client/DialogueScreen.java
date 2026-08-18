package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.dialogue.WarfrontNet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * Wide, transcript-oriented conversation screen. The server owns dialogue state;
 * the client preserves the visible exchange history and renders branch navigation.
 */
public class DialogueScreen extends Screen {
	private static final int MAX_PANEL_WIDTH = 640;
	private static final int PANEL_TOP = 4;
	private static final int CHOICE_HEIGHT = 18;
	private static final int LINE_HEIGHT = 9;
	private static final int TRANSCRIPT_GAP = 6;
	private static final int CONTENT_PADDING = 6;
	private static final int HEADER_GAP = 3;
	private static final int SPEAKER_BODY_GAP = 2;

	private record Exchange(Component playerLine, Component soldierLine) {
	}

	private record HeaderLayout(Component name, Component rankFaction, Component topic,
			int nameY, int rankY, int topicY, int dividerY, int height) {
	}

	private WarfrontNet.DialogueS2C data;
	private final List<Exchange> history = new ArrayList<>();
	private Component leadSoldierLine;
	private Component pendingPlayerLine;
	private boolean awaitingReply;
	private int transcriptScroll;
	private int transcriptMaxScroll;
	private final List<WrappedButton> optionButtons = new ArrayList<>();

	public DialogueScreen(WarfrontNet.DialogueS2C data) {
		super(Component.literal(data.soldierName()));
		this.data = data;
		this.leadSoldierLine = Component.translatable(data.soldierLine());
	}

	public void refresh(WarfrontNet.DialogueS2C payload) {
		if (pendingPlayerLine != null && !"dialogue.warfront.resp.silent".equals(payload.soldierLine())) {
			history.add(new Exchange(pendingPlayerLine, Component.translatable(payload.soldierLine())));
			while (history.size() > 6) history.removeFirst();
			pendingPlayerLine = null;
		}
		this.data = payload;
		this.awaitingReply = false;
		// A new reply should open with its end visible; the player can scroll upward
		// when an unusually long exchange needs more than the available viewport.
		this.transcriptScroll = Integer.MAX_VALUE;
		rebuildWidgets();
	}

	@Override
	protected void init() {
		int panelWidth = panelWidth();
		int x = (width - panelWidth) / 2;
		int y = choicesTop();
		optionButtons.clear();
		for (WarfrontNet.OptionEntry option : data.options()) {
			String id = option.id();
			Component text = optionLabel(option);
			int buttonHeight = wrappedButtonHeight(text, panelWidth);
			WrappedButton button = new WrappedButton(x, y, panelWidth, buttonHeight, text, font,
					b -> {
						pendingPlayerLine = text.copy();
						awaitingReply = true;
						transcriptScroll = Integer.MAX_VALUE;
						ClientPlayNetworking.send(new WarfrontNet.ChooseC2S(id));
					});
			optionButtons.add(addRenderableWidget(button));
			y += buttonHeight + 2;
		}
		int bottomY = y + 4;
		if (data.inBranch() || data.canMore()) {
			String special = data.inBranch() ? "__topics" : "__more";
			String label = data.inBranch() ? "dialogue.warfront.change_subject" : "dialogue.warfront.more";
			addRenderableWidget(Button.builder(Component.translatable(label),
					b -> {
						pendingPlayerLine = null;
						ClientPlayNetworking.send(new WarfrontNet.ChooseC2S(special));
					})
					.bounds(x, bottomY, (panelWidth - 4) / 2, CHOICE_HEIGHT).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("dialogue.warfront.leave"),
				b -> {
					ClientPlayNetworking.send(new WarfrontNet.ChooseC2S("__leave"));
					onClose();
				})
				.bounds(x + (data.inBranch() || data.canMore() ? (panelWidth + 4) / 2 : 0), bottomY,
						data.inBranch() || data.canMore() ? (panelWidth - 4) / 2 : panelWidth, CHOICE_HEIGHT).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int panelWidth = panelWidth();
		int x = (width - panelWidth) / 2;
		HeaderLayout header = headerLayout(panelWidth);
		int headerY = PANEL_TOP;
		int headerHeight = header.height();
		int choicesY = choicesTop();
		g.fill(x - 10, 0, x + panelWidth + 10, height - 4, 0xE8101014);
		g.fill(x - 10, 0, x - 7, height - 4, 0xFF8A744B);
		// Widget render state must be extracted after the panel so buttons remain on top.
		super.extractRenderState(g, mouseX, mouseY, delta);
		int contentX = x + CONTENT_PADDING;
		int contentWidth = panelWidth - CONTENT_PADDING * 2;
		g.textWithWordWrap(font, header.name(), contentX, header.nameY(), contentWidth, 0xFFFFFFFF);
		g.textWithWordWrap(font, header.rankFaction(), contentX, header.rankY(), contentWidth, 0xFFB0B0B8);
		g.textWithWordWrap(font, header.topic(), contentX, header.topicY(), contentWidth, 0xFFFFD37A);
		g.fill(x, header.dividerY(), x + panelWidth, header.dividerY() + 1, 0xFF6A604E);

		int transcriptTop = headerY + headerHeight + 2;
		int transcriptBottom = Math.max(transcriptTop + LINE_HEIGHT, choicesY - TRANSCRIPT_GAP);
		int viewportHeight = transcriptBottom - transcriptTop;
		int contentHeight = transcriptContentHeight(panelWidth);
		transcriptMaxScroll = Math.max(0, contentHeight - viewportHeight);
		transcriptScroll = Mth.clamp(transcriptScroll, 0, transcriptMaxScroll);

		g.enableScissor(x, transcriptTop, x + panelWidth, transcriptBottom);
		int lineY = transcriptTop - transcriptScroll;
		if (history.isEmpty() && pendingPlayerLine == null) {
			lineY = drawSoldierLine(g, leadSoldierLine, x, lineY, panelWidth);
		} else {
			int first = Math.max(0, history.size() - 1);
			for (int i = first; i < history.size(); i++) {
				Exchange exchange = history.get(i);
				lineY = drawPlayerLine(g, exchange.playerLine(), x, lineY, panelWidth);
				lineY = drawSoldierLine(g, exchange.soldierLine(), x, lineY, panelWidth) + 3;
			}
		}
		if (pendingPlayerLine != null) {
			lineY = drawPlayerLine(g, pendingPlayerLine, x, lineY, panelWidth);
			if (awaitingReply) drawSoldierLine(g, Component.literal("..."), x, lineY, panelWidth);
		}
		g.disableScissor();
		if (transcriptMaxScroll > 0) drawTranscriptScrollbar(g, x + panelWidth - 3,
				transcriptTop, viewportHeight, contentHeight);
	}

	private int panelWidth() {
		return Math.min(MAX_PANEL_WIDTH, width - 32);
	}

	private int choicesTop() {
		int optionsHeight = data.options().stream()
				.mapToInt(option -> wrappedButtonHeight(optionLabel(option), panelWidth()) + 2)
				.sum();
		return height - (optionsHeight + 26);
	}

	private HeaderLayout headerLayout(int panelWidth) {
		int contentWidth = Math.max(1, panelWidth - CONTENT_PADDING * 2);
		Component name = Component.literal(data.soldierName()).withStyle(s -> s.withBold(true));
		Component rankFaction = Component.translatable("dialogue.warfront.header_rank_faction",
				Component.translatable("dialogue.warfront.rank." + data.rank()), data.factionName());
		Component topic = data.inBranch() && !data.topicKey().isEmpty()
				? Component.translatable(data.topicKey())
				: Component.translatable("dialogue.warfront.choose_topic");
		topic = topic.copy().withStyle(s -> s.withBold(true));
		int cursor = PANEL_TOP;
		int nameY = cursor;
		cursor += wrappedHeight(name, contentWidth) + HEADER_GAP;
		int rankY = cursor;
		cursor += wrappedHeight(rankFaction, contentWidth) + HEADER_GAP + 2;
		int topicY = cursor;
		cursor += wrappedHeight(topic, contentWidth) + HEADER_GAP;
		int dividerY = cursor + 1;
		return new HeaderLayout(name, rankFaction, topic,
				nameY, rankY, topicY, dividerY,
				dividerY - PANEL_TOP + 8);
	}

	private int headerHeight() {
		return headerLayout(panelWidth()).height();
	}

	private int wrappedHeight(Component text, int width) {
		return Math.max(LINE_HEIGHT, font.split(text, Math.max(1, width)).size() * LINE_HEIGHT);
	}

	private int wrappedButtonHeight(Component text, int buttonWidth) {
		return Math.max(CHOICE_HEIGHT, font.split(text, Math.max(1, buttonWidth - 12)).size() * LINE_HEIGHT + 6);
	}

	private int transcriptContentHeight(int panelWidth) {
		int contentHeight = 0;
		if (history.isEmpty() && pendingPlayerLine == null) {
			contentHeight += soldierLineHeight(leadSoldierLine, panelWidth);
		} else if (!history.isEmpty()) {
			Exchange exchange = history.getLast();
			contentHeight += playerLineHeight(exchange.playerLine(), panelWidth);
			contentHeight += soldierLineHeight(exchange.soldierLine(), panelWidth) + 3;
		}
		if (pendingPlayerLine != null) {
			contentHeight += playerLineHeight(pendingPlayerLine, panelWidth);
			if (awaitingReply) contentHeight += soldierLineHeight(Component.literal("..."), panelWidth);
		}
		return contentHeight;
	}

	private int playerLineHeight(Component line, int panelWidth) {
		return Math.max(12, font.split(line, playerTextWidth(panelWidth)).size() * LINE_HEIGHT + 3);
	}

	private int soldierLineHeight(Component line, int panelWidth) {
		return LINE_HEIGHT + SPEAKER_BODY_GAP
				+ font.split(line, transcriptTextWidth(panelWidth)).size() * LINE_HEIGHT + 3;
	}

	private int transcriptTextWidth(int panelWidth) {
		return Math.max(1, panelWidth - CONTENT_PADDING * 2);
	}

	private int playerTextWidth(int panelWidth) {
		return Math.max(1, panelWidth - CONTENT_PADDING * 2 - 34);
	}

	private void drawTranscriptScrollbar(GuiGraphicsExtractor g, int x, int y, int viewportHeight, int contentHeight) {
		g.fill(x, y, x + 2, y + viewportHeight, 0xFF34343C);
		int thumbHeight = Math.max(8, viewportHeight * viewportHeight / contentHeight);
		int travel = viewportHeight - thumbHeight;
		int thumbY = y + (transcriptMaxScroll == 0 ? 0 : travel * transcriptScroll / transcriptMaxScroll);
		g.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFB59A63);
	}

	private int drawPlayerLine(GuiGraphicsExtractor g, Component line, int x, int y, int panelWidth) {
		g.text(font, Component.translatable("dialogue.warfront.you").withStyle(s -> s.withBold(true)),
				x + CONTENT_PADDING, y, 0xFFAAB6C8);
		g.textWithWordWrap(font, line, x + CONTENT_PADDING + 34, y,
				playerTextWidth(panelWidth), 0xFFD1D8E2);
		return y + playerLineHeight(line, panelWidth);
	}

	private int drawSoldierLine(GuiGraphicsExtractor g, Component line, int x, int y, int panelWidth) {
		Component label = Component.literal(data.soldierName() + ":").withStyle(s -> s.withBold(true));
		g.text(font, label, x + CONTENT_PADDING, y, 0xFFFFD37A);
		g.textWithWordWrap(font, line.copy().withStyle(s -> s.withItalic(true)),
				x + CONTENT_PADDING, y + LINE_HEIGHT + SPEAKER_BODY_GAP,
				transcriptTextWidth(panelWidth), 0xFFFFF0C8);
		return y + soldierLineHeight(line, panelWidth);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int transcriptTop = PANEL_TOP + headerHeight() + 2;
		int transcriptBottom = choicesTop() - TRANSCRIPT_GAP;
		if (transcriptMaxScroll > 0 && mouseY >= transcriptTop && mouseY < transcriptBottom) {
			transcriptScroll = Mth.clamp(transcriptScroll - (int) Math.round(scrollY * 18.0),
					0, transcriptMaxScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	boolean allOptionTextFitsForTest() {
		return optionButtons.size() == data.options().size() && optionButtons.stream()
				.allMatch(button -> button.getHeight() >= wrappedButtonHeight(button.getMessage(), button.getWidth()));
	}

	boolean headerRowsDoNotOverlapForTest() {
		HeaderLayout h = headerLayout(panelWidth());
		int width = transcriptTextWidth(panelWidth());
		return h.rankY() >= h.nameY() + wrappedHeight(h.name(), width)
				&& h.topicY() >= h.rankY() + wrappedHeight(h.rankFaction(), width)
				&& h.dividerY() > h.topicY() + wrappedHeight(h.topic(), width);
	}

	boolean replyUsesPaddedFullWidthForTest() {
		return transcriptTextWidth(panelWidth()) == panelWidth() - CONTENT_PADDING * 2;
	}

	boolean optionLabelsExposeNoToneMarkersForTest() {
		return data.options().stream().map(DialogueScreen::optionLabel)
				.map(Component::getString).noneMatch(label -> label.startsWith("["));
	}

	private static final class WrappedButton extends Button {
		private final Font font;

		private WrappedButton(int x, int y, int width, int height, Component message, Font font, OnPress onPress) {
			super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
			this.font = font;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
			extractDefaultSprite(g);
			ActiveTextCollector text = g.textRendererForWidget(this,
					GuiGraphicsExtractor.HoveredTextEffects.NONE);
			List<FormattedCharSequence> lines = font.split(getMessage(), Math.max(1, getWidth() - 12));
			int y = getY() + (getHeight() - lines.size() * LINE_HEIGHT) / 2;
			for (FormattedCharSequence line : lines) {
				text.accept(TextAlignment.CENTER, getX() + getWidth() / 2, y, line);
				y += LINE_HEIGHT;
			}
		}

	}

	String firstConversationalOptionLabelForTest() {
		return data.options().stream()
				.filter(option -> !option.id().startsWith("core_leave"))
				.findFirst()
				.map(option -> optionLabel(option).getString())
				.orElseThrow();
	}

	private static Component optionLabel(WarfrontNet.OptionEntry option) {
		return Component.translatable(option.textKey());
	}

	Set<String> optionIdsForTest() {
		return data.options().stream().map(WarfrontNet.OptionEntry::id)
				.collect(Collectors.toUnmodifiableSet());
	}

	boolean hasRequiredTonesForTest() {
		Set<String> tones = data.options().stream().map(WarfrontNet.OptionEntry::tone).collect(Collectors.toSet());
		return tones.containsAll(Set.of("positive", "neutral", "negative", "exit"));
	}

	boolean hasVisibleExchangeForTest() {
		return !history.isEmpty() && !awaitingReply;
	}

	String latestReplyForTest() {
		return history.isEmpty() ? "" : history.getLast().soldierLine().getString();
	}

	boolean isPrototypeBarrierTopicForTest() {
		return data.topicKey().endsWith("prototype_defensive_barrier");
	}

	float standingValueForTest() {
		return data.standingValue();
	}

	int screenWidthForTest() {
		return width;
	}

	boolean referenceExchangeFitsWithoutScrollForTest() {
		return transcriptMaxScroll == 0;
	}

	boolean inDeepBranchForTest() {
		return data.inBranch() && data.branchDepth() >= 2 && !data.topicKey().isEmpty();
	}

	String firstDeepOptionLabelForTest() {
		return data.options().stream().filter(option -> option.id().startsWith("deep_"))
				.findFirst().map(option -> optionLabel(option).getString()).orElseThrow();
	}

	String firstOptionLabelForToneForTest(String tone) {
		return data.options().stream().filter(option -> tone.equals(option.tone()))
				.findFirst().map(option -> optionLabel(option).getString()).orElseThrow();
	}

	int branchDepthForTest() {
		return data.branchDepth();
	}

	boolean atTopicRootForTest() {
		return !data.inBranch() && data.branchDepth() == 0;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
