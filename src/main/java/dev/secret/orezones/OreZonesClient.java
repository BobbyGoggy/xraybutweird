package dev.secret.orezones;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class OreZonesClient implements ClientModInitializer {
    // ---- config ----
    static final int kToggleKey = GLFW.GLFW_KEY_F6;
    static final boolean kShowStatus = true;   // actionbar line with scan counts while enabled
    static final int kStatusEveryTicks = 20;

    static volatile boolean enabled = false;
    private static boolean keyWasDown = false;
    private static int statusTicks = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(OreZonesClient::tick);
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(ZoneRenderer::render);
    }

    /** Polled every frame (from the render callback) so short taps are not missed. */
    static void pollKey(Minecraft mc) {
        long window = GLFW.glfwGetCurrentContext();
        boolean down = window != 0L && mc.screen == null
                && GLFW.glfwGetKey(window, kToggleKey) == GLFW.GLFW_PRESS;
        if (down && !keyWasDown) {
            enabled = !enabled;
            if (!enabled) {
                ZoneEstimator.clear();
            }
            statusTicks = kStatusEveryTicks;
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("Ore Zones: " + (enabled ? "ON" : "OFF")), true);
            }
        }
        keyWasDown = down;
    }

    private static void tick(Minecraft mc) {
        if (enabled && mc.level != null && mc.player != null) {
            ZoneEstimator.tick(mc);
            if (kShowStatus && ++statusTicks >= kStatusEveryTicks) {
                statusTicks = 0;
                mc.player.displayClientMessage(Component.literal(ZoneEstimator.summary()), true);
            }
        }
    }
}
