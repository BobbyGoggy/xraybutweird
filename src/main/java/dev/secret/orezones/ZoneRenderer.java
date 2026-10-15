package dev.secret.orezones;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws each zone as a translucent box, through walls.
 *
 * The render type is a clone of vanilla's debug_quads pipeline with the depth test switched off.
 * RenderType.create is opened up by orezones.accesswidener.
 */
final class ZoneRenderer {
    // ---- config ----
    static final float kInset = 0.5f;
    static final float kMinAlpha = 0.28f;
    static final float kMaxAlpha = 0.60f;

    private static RenderType zoneType;

    private static RenderType type() {
        if (zoneType == null) {
            RenderPipeline base = RenderPipelines.DEBUG_QUADS;
            RenderPipeline.Builder b = RenderPipeline.builder()
                    .withLocation("pipeline/orezones_zones")
                    .withVertexShader(base.getVertexShader())
                    .withFragmentShader(base.getFragmentShader())
                    .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                    .withCull(false)
                    .withColorWrite(base.isWriteColor(), base.isWriteAlpha())
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
            for (var u : base.getUniforms()) {
                if (u.textureFormat() == null) {
                    b.withUniform(u.name(), u.type());
                } else {
                    b.withUniform(u.name(), u.type(), u.textureFormat());
                }
            }
            for (String s : base.getSamplers()) {
                b.withSampler(s);
            }
            if (base.getBlendFunction().isPresent()) {
                b.withBlend(base.getBlendFunction().get());
            } else {
                b.withoutBlend();
            }
            zoneType = RenderType.create("orezones_zones", RenderSetup.builder(b.build()).createRenderSetup());
        }
        return zoneType;
    }

    static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        OreZonesClient.pollKey(mc);
        if (!OreZonesClient.enabled) {
            return;
        }
        List<ZoneEstimator.Zone> zones = ZoneEstimator.zones();
        if (zones.isEmpty()) {
            return;
        }

        Vec3 cam = ctx.worldState().cameraRenderState.pos;
        PoseStack.Pose pose = ctx.matrices().last();
        MultiBufferSource consumers = ctx.consumers();
        RenderType type = type();
        VertexConsumer vc = consumers.getBuffer(type);

        for (ZoneEstimator.Zone z : zones) {
            float a = Math.max(kMinAlpha, Math.min(kMaxAlpha, z.score() * kMaxAlpha * 2f));
            int r = z.ore().r, g = z.ore().g, b = z.ore().b;
            int ai = (int) (a * 255f);

            float x0 = (float) (z.x() + kInset - cam.x);
            float y0 = (float) (z.y() + kInset - cam.y);
            float z0 = (float) (z.z() + kInset - cam.z);
            float s = ZoneEstimator.kCell - 2f * kInset;
            box(vc, pose, x0, y0, z0, x0 + s, y0 + s, z0 + s, r, g, b, ai);
        }

        if (consumers instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch(type);
        }
    }

    private static void v(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, int r, int g, int b, int a) {
        vc.addVertex(p, x, y, z).setColor(r, g, b, a);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose p,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             int r, int g, int b, int a) {
        v(vc, p, ax, ay, az, r, g, b, a);
        v(vc, p, bx, by, bz, r, g, b, a);
        v(vc, p, cx, cy, cz, r, g, b, a);
        v(vc, p, dx, dy, dz, r, g, b, a);
    }

    private static void box(VertexConsumer vc, PoseStack.Pose p,
                            float x0, float y0, float z0, float x1, float y1, float z1,
                            int r, int g, int b, int a) {
        quad(vc, p, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a); // bottom
        quad(vc, p, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a); // top
        quad(vc, p, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a); // north
        quad(vc, p, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a); // south
        quad(vc, p, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a); // west
        quad(vc, p, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a); // east
    }
}
