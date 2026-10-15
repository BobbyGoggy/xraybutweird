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

    static volatile boolean enabled = false;
    private static boolean keyWasDown = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(OreZonesClient::tick);
        WorldRenderEvents.END_MAIN.register(ZoneRenderer::render);
    }

    private static void tick(Minecraft mc) {
        // F6 has no vanilla binding, so poll it directly. The client tick runs on the
        // render thread, where the current GLFW context is the game window.
        long window = GLFW.glfwGetCurrentContext();
        boolean down = window != 0L && mc.screen == null
                && GLFW.glfwGetKey(window, kToggleKey) == GLFW.GLFW_PRESS;
        if (down && !keyWasDown) {
            enabled = !enabled;
            if (!enabled) {
                ZoneEstimator.clear();
            }
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("Ore Zones: " + (enabled ? "ON" : "OFF")), true);
            }
        }
        keyWasDown = down;

        if (enabled && mc.level != null && mc.player != null) {
            ZoneEstimator.tick(mc);
        }
    }
}
