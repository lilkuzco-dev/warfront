package io.github.lilkuzcodev.warfront.dialogue;

import io.github.lilkuzcodev.warfront.Warfront;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Dialogue wire protocol (Stage 4B.4). The server owns all state and text selection;
 * the client renders lang keys and sends back choices. One compact screen, no inventory.
 */
public final class WarfrontNet {
	public record OptionEntry(String id, String textKey) {
	}

	/** S2C: open or refresh the dialogue screen. */
	public record DialogueS2C(int soldierId, String soldierName, String rank, String faction, String factionName,
			String standing, float standingValue, String band, String soldierLine, List<OptionEntry> options,
			boolean canMore, boolean openScreen) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<DialogueS2C> TYPE =
				new CustomPacketPayload.Type<>(Warfront.id("dialogue"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DialogueS2C> CODEC = StreamCodec.of(
				(buf, payload) -> {
					buf.writeVarInt(payload.soldierId());
					buf.writeUtf(payload.soldierName());
					buf.writeUtf(payload.rank());
					buf.writeUtf(payload.faction());
					buf.writeUtf(payload.factionName());
					buf.writeUtf(payload.standing());
					buf.writeFloat(payload.standingValue());
					buf.writeUtf(payload.band());
					buf.writeUtf(payload.soldierLine());
					buf.writeVarInt(payload.options().size());
					for (OptionEntry entry : payload.options()) {
						buf.writeUtf(entry.id());
						buf.writeUtf(entry.textKey());
					}
					buf.writeBoolean(payload.canMore());
					buf.writeBoolean(payload.openScreen());
				},
				buf -> {
					int soldierId = buf.readVarInt();
					String name = buf.readUtf();
					String rank = buf.readUtf();
					String faction = buf.readUtf();
					String factionName = buf.readUtf();
					String standing = buf.readUtf();
					float standingValue = buf.readFloat();
					String band = buf.readUtf();
					String line = buf.readUtf();
					int count = buf.readVarInt();
					List<OptionEntry> options = new ArrayList<>();
					for (int i = 0; i < count; i++) {
						options.add(new OptionEntry(buf.readUtf(), buf.readUtf()));
					}
					return new DialogueS2C(soldierId, name, rank, faction, factionName, standing, standingValue,
							band, line, options, buf.readBoolean(), buf.readBoolean());
				});

		@Override
		public CustomPacketPayload.Type<DialogueS2C> type() {
			return TYPE;
		}
	}

	/** S2C: close the dialogue screen (combat, distance, dismissal). */
	public record DialogueCloseS2C(int reason) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<DialogueCloseS2C> TYPE =
				new CustomPacketPayload.Type<>(Warfront.id("dialogue_close"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DialogueCloseS2C> CODEC = StreamCodec.of(
				(buf, payload) -> buf.writeVarInt(payload.reason()),
				buf -> new DialogueCloseS2C(buf.readVarInt()));

		@Override
		public CustomPacketPayload.Type<DialogueCloseS2C> type() {
			return TYPE;
		}
	}

	/** C2S: the player picked an option ("__more" and "__leave" are engine specials). */
	public record ChooseC2S(String optionId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ChooseC2S> TYPE =
				new CustomPacketPayload.Type<>(Warfront.id("dialogue_choose"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ChooseC2S> CODEC = StreamCodec.of(
				(buf, payload) -> buf.writeUtf(payload.optionId()),
				buf -> new ChooseC2S(buf.readUtf()));

		@Override
		public CustomPacketPayload.Type<ChooseC2S> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(DialogueS2C.TYPE, DialogueS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DialogueCloseS2C.TYPE, DialogueCloseS2C.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ChooseC2S.TYPE, ChooseC2S.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ChooseC2S.TYPE,
				(payload, context) -> DialogueSessions.choose(context.player(), payload.optionId()));
	}

	private WarfrontNet() {
	}
}
