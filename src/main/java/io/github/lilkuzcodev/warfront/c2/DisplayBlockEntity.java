package io.github.lilkuzcodev.warfront.c2;

import io.github.lilkuzcodev.warfront.block.WarfrontBlockEntities;
import io.github.lilkuzcodev.warfront.block.WarfrontBlocks;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Server-authoritative binding and snapshots shared by screens and projectors. */
public class DisplayBlockEntity extends BlockEntity {
	public static final int MAP_RADIUS = 128;
	private static final int REFRESH_TICKS = 20;

	private DisplayFeed feed = DisplayFeed.LIVE_MAP;
	private int centerX;
	private int centerZ;
	private UUID owner;
	private String satelliteId = "";
	private String satelliteName = "";
	private double satelliteX;
	private double satelliteZ;
	private double satelliteRadius;
	private boolean satelliteInPass;
	private boolean satelliteComms;
	private int satelliteArtificial;
	private int[] satelliteSignals = new int[0];
	private int[] satellitePixels = new int[0];
	private int[] tacticalMarkers = new int[0];
	private int dataRevision;
	private long lastRefresh = Long.MIN_VALUE;
	private long lastSatelliteRefresh = Long.MIN_VALUE;

	public DisplayBlockEntity(BlockPos pos, BlockState state) {
		super(WarfrontBlockEntities.DISPLAY, pos, state);
		centerX = pos.getX();
		centerZ = pos.getZ();
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, DisplayBlockEntity display) {
		if (!(level instanceof ServerLevel serverLevel)
				|| !RefreshSchedule.due(level.getGameTime(), display.lastRefresh, REFRESH_TICKS)) {
			return;
		}
		display.lastRefresh = level.getGameTime();
		DisplayWallLayout wall = display.wall();
		if (!wall.controller().equals(pos)) {
			return;
		}
		boolean changed = false;
		if (display.feed == DisplayFeed.TACTICAL && display.owner != null) {
			List<TacticalOverlayRegistry.Marker> markers = TacticalOverlayRegistry.snapshot(serverLevel, display.owner,
					new BlockPos(display.centerX, pos.getY(), display.centerZ), MAP_RADIUS * 2);
			int[] packed = packMarkers(markers);
			if (!Arrays.equals(packed, display.tacticalMarkers)) {
				display.tacticalMarkers = packed;
				changed = true;
			}
		}
		if (display.feed == DisplayFeed.SATELLITE && display.owner != null
				&& RefreshSchedule.due(level.getGameTime(), display.lastSatelliteRefresh, 100)) {
			display.lastSatelliteRefresh = level.getGameTime();
			var snapshot = CosmosReconBridge.image(serverLevel, display.owner, display.satelliteId,
					new BlockPos(display.centerX, pos.getY(), display.centerZ));
			if (snapshot.isPresent()) {
				changed |= display.applySatellite(snapshot.get());
			} else changed |= display.clearSatelliteSnapshot();
		}
		if (changed) {
			display.syncController();
		}
	}

	public void interact(Player player) {
		if (level == null || level.isClientSide()) {
			return;
		}
		DisplayWallLayout wall = wall();
		DisplayBlockEntity controller = controller(wall);
		if (controller == null) {
			return;
		}
		controller.owner = player.getUUID();
		controller.centerX = player.blockPosition().getX();
		controller.centerZ = player.blockPosition().getZ();
		boolean satelliteChanged = false;
		if (player.isShiftKeyDown() && controller.feed == DisplayFeed.SATELLITE && level instanceof ServerLevel serverLevel) {
			String previous = controller.satelliteId;
			controller.satelliteId = CosmosReconBridge.nextReconSatelliteId(serverLevel, controller.owner, previous)
					.orElse(previous);
			satelliteChanged = !controller.satelliteId.equals(previous);
		} else if (!player.isShiftKeyDown()) {
			controller.feed = controller.feed.next(CosmosReconBridge.available());
		}
		controller.clearVolatileSnapshots();
		controller.copyToWall(wall);
		controller.syncController();
		player.sendOverlayMessage(satelliteChanged
				? Component.translatable("message.warfront.display.satellite_selected", controller.satelliteId)
				: Component.translatable("message.warfront.display.bound",
						Component.translatable("feed.warfront." + controller.feed.id()), controller.centerX, controller.centerZ));
	}

	private void copyToWall(DisplayWallLayout wall) {
		if (level == null) {
			return;
		}
		for (BlockPos member : wall.members()) {
			if (level.getBlockEntity(member) instanceof DisplayBlockEntity other && other != this) {
				other.copyBinding(this);
				BlockState state = level.getBlockState(member);
				level.sendBlockUpdated(member, state, state, Block.UPDATE_CLIENTS);
			}
		}
	}

	private void copyBinding(DisplayBlockEntity source) {
		feed = source.feed;
		centerX = source.centerX;
		centerZ = source.centerZ;
		owner = source.owner;
		satelliteId = source.satelliteId;
		clearVolatileSnapshots();
		setChanged();
	}

	/** Carries an existing binding into a newly enlarged wall, including a new controller. */
	public void reconcileWallBinding() {
		if (level == null || level.isClientSide()) return;
		DisplayWallLayout wall = wall();
		DisplayBlockEntity source = null;
		for (BlockPos member : wall.members()) {
			if (level.getBlockEntity(member) instanceof DisplayBlockEntity candidate && candidate.owner != null) {
				source = candidate;
				if (member.equals(wall.controller())) break;
			}
		}
		DisplayBlockEntity controller = controller(wall);
		if (source == null || controller == null) return;
		if (source != controller) controller.copyBinding(source);
		controller.copyToWall(wall);
		controller.syncController();
	}

	private void syncController() {
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		setChanged();
	}

	private boolean applySatellite(CosmosReconBridge.Snapshot recon) {
		double nextX = recon.inPass() ? recon.groundX() : centerX;
		double nextZ = recon.inPass() ? recon.groundZ() : centerZ;
		double nextRadius = recon.inPass() ? recon.footprintRadius() : 0.0;
		int nextArtificial = recon.inPass() && recon.commsLink() ? recon.artificialBlocks() : 0;
		int[] signals = recon.inPass() && recon.commsLink() ? packPositions(recon.strongestSignals()) : new int[0];
		int[] pixels = recon.inPass() && recon.commsLink() ? recon.terrainPixels() : new int[0];
		boolean changed = !satelliteId.equals(recon.satelliteId()) || !satelliteName.equals(recon.satelliteName())
				|| Double.compare(satelliteX, nextX) != 0 || Double.compare(satelliteZ, nextZ) != 0
				|| Double.compare(satelliteRadius, nextRadius) != 0 || satelliteInPass != recon.inPass()
				|| satelliteComms != recon.commsLink() || satelliteArtificial != nextArtificial
				|| !Arrays.equals(satelliteSignals, signals) || !Arrays.equals(satellitePixels, pixels);
		satelliteId = recon.satelliteId();
		satelliteName = recon.satelliteName();
		satelliteX = nextX;
		satelliteZ = nextZ;
		satelliteRadius = nextRadius;
		satelliteInPass = recon.inPass();
		satelliteComms = recon.commsLink();
		satelliteArtificial = nextArtificial;
		satelliteSignals = signals;
		satellitePixels = pixels.clone();
		return changed;
	}

	private boolean clearSatelliteSnapshot() {
		boolean changed = !satelliteName.isEmpty() || satelliteInPass || satelliteComms || satelliteArtificial != 0
				|| satelliteRadius != 0.0 || satelliteSignals.length != 0 || satellitePixels.length != 0;
		satelliteName = "";
		satelliteX = centerX;
		satelliteZ = centerZ;
		satelliteRadius = 0.0;
		satelliteInPass = false;
		satelliteComms = false;
		satelliteArtificial = 0;
		satelliteSignals = new int[0];
		satellitePixels = new int[0];
		return changed;
	}

	private void clearVolatileSnapshots() {
		clearSatelliteSnapshot();
		tacticalMarkers = new int[0];
		lastSatelliteRefresh = Long.MIN_VALUE;
	}

	private DisplayBlockEntity controller(DisplayWallLayout wall) {
		return level != null && level.getBlockEntity(wall.controller()) instanceof DisplayBlockEntity display
				? display : null;
	}

	public DisplayWallLayout wall() {
		if (level == null || getBlockState().getBlock() != WarfrontBlocks.SCREEN) {
			return new DisplayWallLayout(worldPosition, 0, 0, 1, 1, List.of(worldPosition));
		}
		Direction facing = getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		return DisplayWallLayout.find(worldPosition, facing, candidate -> {
			BlockState candidateState = level.getBlockState(candidate);
			return candidateState.getBlock() == WarfrontBlocks.SCREEN
					&& candidateState.getValue(HorizontalDirectionalBlock.FACING) == facing;
		});
	}

	private static int[] packMarkers(List<TacticalOverlayRegistry.Marker> markers) {
		int[] packed = new int[markers.size() * 4];
		for (int i = 0; i < markers.size(); i++) {
			TacticalOverlayRegistry.Marker marker = markers.get(i);
			packed[i * 4] = marker.kind().ordinal();
			packed[i * 4 + 1] = marker.pos().getX();
			packed[i * 4 + 2] = marker.pos().getZ();
			packed[i * 4 + 3] = marker.rgb();
		}
		return packed;
	}

	private static int[] packPositions(List<BlockPos> positions) {
		int[] packed = new int[positions.size() * 2];
		for (int i = 0; i < positions.size(); i++) {
			packed[i * 2] = positions.get(i).getX();
			packed[i * 2 + 1] = positions.get(i).getZ();
		}
		return packed;
	}

	public DisplayFeed feed() { return feed; }
	public int centerX() { return centerX; }
	public int centerZ() { return centerZ; }
	public String satelliteName() { return satelliteName; }
	public double satelliteX() { return satelliteX; }
	public double satelliteZ() { return satelliteZ; }
	public double satelliteRadius() { return satelliteRadius; }
	public boolean satelliteInPass() { return satelliteInPass; }
	public boolean satelliteComms() { return satelliteComms; }
	public int satelliteArtificial() { return satelliteArtificial; }
	public int[] satelliteSignals() { return satelliteSignals; }
	public int[] satellitePixels() { return satellitePixels; }
	public int[] tacticalMarkers() { return tacticalMarkers; }
	public int dataRevision() { return dataRevision; }

	@Override
	public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("feed", feed.id());
		output.putInt("center_x", centerX);
		output.putInt("center_z", centerZ);
		if (owner != null) output.putString("owner", owner.toString());
		output.putString("satellite_id", satelliteId);
		output.putString("satellite_name", satelliteName);
		output.putDouble("satellite_x", satelliteX);
		output.putDouble("satellite_z", satelliteZ);
		output.putDouble("satellite_radius", satelliteRadius);
		output.putBoolean("satellite_in_pass", satelliteInPass);
		output.putBoolean("satellite_comms", satelliteComms);
		output.putInt("satellite_artificial", satelliteArtificial);
		output.putIntArray("satellite_signals", satelliteSignals);
		output.putIntArray("satellite_pixels", satellitePixels);
		output.putIntArray("tactical_markers", tacticalMarkers);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		feed = DisplayFeed.byId(input.getStringOr("feed", "live_map"));
		centerX = input.getIntOr("center_x", worldPosition.getX());
		centerZ = input.getIntOr("center_z", worldPosition.getZ());
		String ownerString = input.getStringOr("owner", "");
		try {
			owner = ownerString.isEmpty() ? null : UUID.fromString(ownerString);
		} catch (IllegalArgumentException ignored) {
			owner = null;
		}
		satelliteId = input.getStringOr("satellite_id", "");
		satelliteName = input.getStringOr("satellite_name", "");
		satelliteX = input.getDoubleOr("satellite_x", centerX);
		satelliteZ = input.getDoubleOr("satellite_z", centerZ);
		satelliteRadius = input.getDoubleOr("satellite_radius", 0.0);
		satelliteInPass = input.getBooleanOr("satellite_in_pass", false);
		satelliteComms = input.getBooleanOr("satellite_comms", false);
		satelliteArtificial = input.getIntOr("satellite_artificial", 0);
		satelliteSignals = input.getIntArray("satellite_signals").orElseGet(() -> new int[0]);
		satellitePixels = input.getIntArray("satellite_pixels").orElseGet(() -> new int[0]);
		tacticalMarkers = input.getIntArray("tactical_markers").orElseGet(() -> new int[0]);
		dataRevision++;
	}
}
