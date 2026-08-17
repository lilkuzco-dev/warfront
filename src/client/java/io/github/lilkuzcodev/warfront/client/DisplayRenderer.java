package io.github.lilkuzcodev.warfront.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.lilkuzcodev.warfront.block.WarfrontBlocks;
import io.github.lilkuzcodev.warfront.c2.DisplayBlockEntity;
import io.github.lilkuzcodev.warfront.c2.DisplayWallLayout;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

/** Submits a single UV slice per panel, or a translucent table hologram per projector. */
public final class DisplayRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayRenderer.State> {
	public DisplayRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static final class State extends BlockEntityRenderState {
		Identifier texture;
		Direction facing = Direction.NORTH;
		boolean projector;
		float u0, v0, u1 = 1, v1 = 1;
	}

	@Override
	public State createRenderState() { return new State(); }

	@Override
	public void extractRenderState(DisplayBlockEntity display, State state, float partialTick, Vec3 cameraPos,
			ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(display, state, crumbling);
		DisplayWallLayout wall = display.wall();
		state.texture = DisplayTextureCache.texture(display, wall);
		state.projector = display.getBlockState().getBlock() == WarfrontBlocks.PROJECTOR;
		if (!state.projector) state.facing = display.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		state.u0 = wall.column() / (float) wall.width();
		state.u1 = (wall.column() + 1) / (float) wall.width();
		state.v0 = (wall.height() - wall.row() - 1) / (float) wall.height();
		state.v1 = (wall.height() - wall.row()) / (float) wall.height();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.texture == null) return;
		if (state.projector) {
			submitProjector(state, poseStack, collector);
		} else {
			submitScreen(state, poseStack, collector);
		}
	}

	private static void submitScreen(State state, PoseStack poseStack, SubmitNodeCollector collector) {
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(state.texture),
				(pose, vertices) -> wallQuad(pose, vertices, state));
	}

	private static void submitProjector(State state, PoseStack poseStack, SubmitNodeCollector collector) {
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(state.texture), (pose, vertices) -> {
			horizontalQuad(pose, vertices, 0.72F, 0.08F, 0.92F, 180);
			horizontalQuad(pose, vertices, 0.34F, 0.28F, 0.72F, 70);
			beamCurtain(pose, vertices, 0.34F, 0.72F, 0.08F, 0.28F, 70);
			beamCurtain(pose, vertices, 0.34F, 0.72F, 0.72F, 0.92F, 70);
		});
	}

	private static void wallQuad(PoseStack.Pose pose, VertexConsumer out, State state) {
		float e = 0.002F;
		switch (state.facing) {
			case NORTH -> quad(out, pose, 1, 0, -e, 0, 0, -e, 0, 1, -e, 1, 1, -e,
					state.u0, state.v1, state.u1, state.v0, 0, 0, -1, 255);
			case SOUTH -> quad(out, pose, 0, 0, 1 + e, 1, 0, 1 + e, 1, 1, 1 + e, 0, 1, 1 + e,
					state.u0, state.v1, state.u1, state.v0, 0, 0, 1, 255);
			case WEST -> quad(out, pose, -e, 0, 0, -e, 0, 1, -e, 1, 1, -e, 1, 0,
					state.u0, state.v1, state.u1, state.v0, -1, 0, 0, 255);
			case EAST -> quad(out, pose, 1 + e, 0, 1, 1 + e, 0, 0, 1 + e, 1, 0, 1 + e, 1, 1,
					state.u0, state.v1, state.u1, state.v0, 1, 0, 0, 255);
			default -> { }
		}
	}

	private static void horizontalQuad(PoseStack.Pose pose, VertexConsumer out, float y, float low, float high, int alpha) {
		quad(out, pose, low, y, low, high, y, low, high, y, high, low, y, high,
				0, 0, 1, 1, 0, 1, 0, alpha);
	}

	private static void beamCurtain(PoseStack.Pose pose, VertexConsumer out, float lowY, float highY,
			float lowZ, float highZ, int alpha) {
		quad(out, pose, 0.28F, lowY, lowZ, 0.72F, lowY, lowZ, 0.92F, highY, highZ, 0.08F, highY, highZ,
				0, 1, 1, 0, 0, 0, 1, alpha);
	}

	private static void quad(VertexConsumer out, PoseStack.Pose pose,
			float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, float x3, float y3, float z3,
			float u0, float v1, float u1, float v0, float nx, float ny, float nz, int alpha) {
		vertex(out, pose, x0, y0, z0, u0, v1, nx, ny, nz, alpha);
		vertex(out, pose, x1, y1, z1, u1, v1, nx, ny, nz, alpha);
		vertex(out, pose, x2, y2, z2, u1, v0, nx, ny, nz, alpha);
		vertex(out, pose, x3, y3, z3, u0, v0, nx, ny, nz, alpha);
	}

	private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z,
			float u, float v, float nx, float ny, float nz, int alpha) {
		out.addVertex(pose, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, nx, ny, nz);
	}

	@Override
	public int getViewDistance() { return 128; }
}
