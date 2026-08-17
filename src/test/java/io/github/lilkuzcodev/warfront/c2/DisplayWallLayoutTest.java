package io.github.lilkuzcodev.warfront.c2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class DisplayWallLayoutTest {
	@Test
	void fiveByThreeWallHasOneControllerAndStableUvCoordinates() {
		BlockPos origin = new BlockPos(10, 64, 20);
		Set<BlockPos> panels = rectangle(origin, Direction.EAST, 5, 3);
		DisplayWallLayout wall = DisplayWallLayout.find(origin.relative(Direction.EAST, 3).above(2),
				Direction.NORTH, panels::contains);

		assertEquals(origin, wall.controller());
		assertEquals(3, wall.column());
		assertEquals(2, wall.row());
		assertEquals(5, wall.width());
		assertEquals(3, wall.height());
		assertEquals(15, wall.members().size());
	}

	@Test
	void orientationUsesViewerRightForEveryFacing() {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			Direction right = facing.getClockWise();
			BlockPos origin = new BlockPos(0, 70, 0);
			Set<BlockPos> panels = rectangle(origin, right, 3, 2);
			DisplayWallLayout wall = DisplayWallLayout.find(origin.relative(right, 2).above(), facing, panels::contains);
			assertEquals(origin, wall.controller());
			assertEquals(2, wall.column());
			assertEquals(1, wall.row());
		}
	}

	@Test
	void holesSplitAClusterIntoOnlyCompleteRectangles() {
		BlockPos origin = new BlockPos(0, 64, 0);
		Set<BlockPos> panels = rectangle(origin, Direction.EAST, 3, 2);
		BlockPos isolated = origin.relative(Direction.EAST, 2).above();
		panels.remove(origin.relative(Direction.EAST).above());
		DisplayWallLayout wall = DisplayWallLayout.find(isolated, Direction.NORTH, panels::contains);
		assertEquals(1, wall.width());
		assertEquals(2, wall.height());
		assertEquals(origin.relative(Direction.EAST, 2), wall.controller());
	}

	@Test
	void sixthPanelIsRejectedAndCommandBuiltOverflowNeverOverlapsControllers() {
		BlockPos origin = new BlockPos(10, 64, 20);
		Set<BlockPos> five = rectangle(origin, Direction.EAST, 5, 1);
		BlockPos sixth = origin.relative(Direction.EAST, 5);
		assertFalse(DisplayWallLayout.fitsWithinBounds(sixth, Direction.NORTH, five::contains));
		assertTrue(DisplayWallLayout.fitsWithinBounds(origin.above(), Direction.NORTH, five::contains));

		Set<BlockPos> malformed = rectangle(origin, Direction.EAST, 6, 1);
		DisplayWallLayout left = DisplayWallLayout.find(origin, Direction.NORTH, malformed::contains);
		DisplayWallLayout right = DisplayWallLayout.find(sixth, Direction.NORTH, malformed::contains);
		assertEquals(origin, left.controller());
		assertEquals(5, left.width());
		assertEquals(sixth, right.controller());
		assertEquals(1, right.width());
		assertTrue(java.util.Collections.disjoint(left.members(), right.members()));
	}

	@Test
	void commandBuiltFourthRowIsPartitionedWithoutOverlap() {
		BlockPos origin = new BlockPos(10, 63, 20);
		Set<BlockPos> malformed = rectangle(origin, Direction.EAST, 2, 4);
		DisplayWallLayout lower = DisplayWallLayout.find(origin.above(2), Direction.NORTH, malformed::contains);
		DisplayWallLayout upper = DisplayWallLayout.find(origin.above(3), Direction.NORTH, malformed::contains);
		assertEquals(origin, lower.controller());
		assertEquals(3, lower.height());
		assertEquals(origin.above(3), upper.controller());
		assertEquals(1, upper.height());
		assertTrue(java.util.Collections.disjoint(lower.members(), upper.members()));
	}

	private static Set<BlockPos> rectangle(BlockPos origin, Direction right, int width, int height) {
		Set<BlockPos> panels = new HashSet<>();
		for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
			panels.add(origin.relative(right, x).above(y));
		return panels;
	}
}
