package io.github.lilkuzcodev.warfront.c2;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Finds the bounded rectangular wall containing a screen panel. The algorithm is
 * deliberately independent of block classes so it can be tested without bootstrapping
 * Minecraft registries.
 */
public record DisplayWallLayout(BlockPos controller, int column, int row, int width, int height,
		List<BlockPos> members) {
	public static final int MAX_WIDTH = 5;
	public static final int MAX_HEIGHT = 3;
	private static final int WIDTH_EXCEEDED = 1;
	private static final int HEIGHT_EXCEEDED = 2;

	public static DisplayWallLayout find(BlockPos panel, Direction facing, Predicate<BlockPos> matchingPanel) {
		int exceeded = exceededDimensions(panel, facing, matchingPanel);
		if (exceeded != 0) {
			return findInAbsoluteTile(panel, facing, matchingPanel, exceeded);
		}
		return findRectangle(panel, facing, matchingPanel, null);
	}

	/** Returns false when placing {@code panel} would join a wall wider than 5 or taller than 3. */
	public static boolean fitsWithinBounds(BlockPos panel, Direction facing, Predicate<BlockPos> matchingPanel) {
		return exceededDimensions(panel, facing,
				candidate -> candidate.equals(panel) || matchingPanel.test(candidate)) == 0;
	}

	private static int exceededDimensions(BlockPos panel, Direction facing, Predicate<BlockPos> matchingPanel) {
		Direction right = facing.getClockWise();
		ArrayDeque<BlockPos> pending = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		pending.add(panel);
		int minColumn = 0, maxColumn = 0, minRow = 0, maxRow = 0;
		int exceeded = 0;
		while (!pending.isEmpty() && visited.size() < 256) {
			BlockPos current = pending.removeFirst();
			if (!visited.add(current) || !matchingPanel.test(current)) continue;
			int column = coordinate(current.getX() - panel.getX(), current.getZ() - panel.getZ(), right);
			int row = current.getY() - panel.getY();
			minColumn = Math.min(minColumn, column);
			maxColumn = Math.max(maxColumn, column);
			minRow = Math.min(minRow, row);
			maxRow = Math.max(maxRow, row);
			if (maxColumn - minColumn + 1 > MAX_WIDTH) exceeded |= WIDTH_EXCEEDED;
			if (maxRow - minRow + 1 > MAX_HEIGHT) exceeded |= HEIGHT_EXCEEDED;
			if (exceeded == (WIDTH_EXCEEDED | HEIGHT_EXCEEDED)) return exceeded;
			pending.add(current.relative(right));
			pending.add(current.relative(right.getOpposite()));
			pending.add(current.above());
			pending.add(current.below());
		}
		return pending.isEmpty() ? exceeded : WIDTH_EXCEEDED | HEIGHT_EXCEEDED;
	}

	/* Malformed command-built clusters are partitioned on a world-stable grid, so no panel
	 * can discover an overlapping controller even when the placement guard was bypassed. */
	private static DisplayWallLayout findInAbsoluteTile(BlockPos panel, Direction facing,
			Predicate<BlockPos> matchingPanel, int exceeded) {
		Direction right = facing.getClockWise();
		BlockPos horizontalOrigin = panel;
		if ((exceeded & WIDTH_EXCEEDED) != 0) {
			int coordinate = panel.getX() * right.getStepX() + panel.getZ() * right.getStepZ();
			int tileCoordinate = Math.floorDiv(coordinate, MAX_WIDTH) * MAX_WIDTH;
			horizontalOrigin = panel.relative(right, tileCoordinate - coordinate);
		} else {
			for (int i = 1; i < MAX_WIDTH && matchingPanel.test(horizontalOrigin.relative(right.getOpposite())); i++)
				horizontalOrigin = horizontalOrigin.relative(right.getOpposite());
		}
		BlockPos tileOrigin = horizontalOrigin;
		if ((exceeded & HEIGHT_EXCEEDED) != 0) {
			tileOrigin = new BlockPos(horizontalOrigin.getX(), Math.floorDiv(panel.getY(), MAX_HEIGHT) * MAX_HEIGHT,
					horizontalOrigin.getZ());
		} else {
			for (int i = 1; i < MAX_HEIGHT && matchingPanel.test(tileOrigin.below()); i++) tileOrigin = tileOrigin.below();
		}
		return findRectangle(panel, facing, matchingPanel, tileOrigin);
	}

	private static DisplayWallLayout findRectangle(BlockPos panel, Direction facing,
			Predicate<BlockPos> matchingPanel, BlockPos fixedOrigin) {
		Direction right = facing.getClockWise();
		BlockPos origin = fixedOrigin == null ? panel : fixedOrigin;
		if (fixedOrigin == null) {
			for (int i = 1; i < MAX_WIDTH && matchingPanel.test(origin.relative(right.getOpposite())); i++) {
				origin = origin.relative(right.getOpposite());
			}
			for (int i = 1; i < MAX_HEIGHT && matchingPanel.test(origin.below()); i++) {
				origin = origin.below();
			}
		}

		int width = 0;
		while (width < MAX_WIDTH && matchingPanel.test(origin.relative(right, width))) {
			width++;
		}
		int height = 0;
		for (; height < MAX_HEIGHT; height++) {
			boolean complete = true;
			for (int x = 0; x < width; x++) {
				if (!matchingPanel.test(origin.relative(right, x).above(height))) {
					complete = false;
					break;
				}
			}
			if (!complete) {
				break;
			}
		}

		int column = coordinate(panel.getX() - origin.getX(), panel.getZ() - origin.getZ(), right);
		int row = panel.getY() - origin.getY();
		if (width == 0 || height == 0 || column < 0 || column >= width || row < 0 || row >= height) {
			return new DisplayWallLayout(panel, 0, 0, 1, 1, List.of(panel));
		}
		List<BlockPos> members = new ArrayList<>(width * height);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				members.add(origin.relative(right, x).above(y));
			}
		}
		return new DisplayWallLayout(origin, column, row, width, height, List.copyOf(members));
	}

	private static int coordinate(int dx, int dz, Direction right) {
		return dx * right.getStepX() + dz * right.getStepZ();
	}
}
