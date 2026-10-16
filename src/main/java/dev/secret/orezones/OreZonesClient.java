package dev.secret.orezones;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class OreZonesClient implements ClientModInitializer {
    // ---- config ----
    static final int kToggleKey = GLFW.GLFW_KEY_F6;   // on / off
    static final int kModeKey = GLFW.GLFW_KEY_F7;     // ALL -> RARE -> TOP
    static final boolean kShowStatus = true;          // actionbar line while enabled
    static final int kStatusEveryTicks = 20;

    static final String[] kModeNames = {"ALL", "RARE", "TOP"};

    static volatile boolean enabled = false;
    /** 0 = every ore, 1 = valuable (gold, redstone, lapis, diamond, emerald, debris), 2 = diamond, emerald, debris. */
    static volatile int mode = 0;

    private static boolean f6Was = false;
    private static boolean f7Was = false;
    private static int statusTicks = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(OreZonesClient::tick);
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(ZoneRenderer::render);
    }

    static boolean accepts(OreType t) {
        return t.tier >= mode;
    }

    /** Polled every frame (from the render callback) so short taps are not missed. */
    static void pollKeys(Minecraft mc) {
        long window = GLFW.glfwGetCurrentContext();
        boolean ok = window != 0L && mc.screen == null;
        boolean f6 = ok && GLFW.glfwGetKey(window, kToggleKey) == GLFW.GLFW_PRESS;
        boolean f7 = ok && GLFW.glfwGetKey(window, kModeKey) == GLFW.GLFW_PRESS;

        if (f6 && !f6Was) {
            enabled = !enabled;
            if (!enabled) {
                ZoneEstimator.clear();
            }
            statusTicks = kStatusEveryTicks;
            say(mc, "Ore Zones: " + (enabled ? "ON" : "OFF"));
        }
        if (f7 && !f7Was && enabled) {
            mode = (mode + 1) % kModeNames.length;
            ZoneEstimator.invalidateVisible();
            statusTicks = kStatusEveryTicks;
            say(mc, "Ore Zones mode: " + kModeNames[mode]);
        }
        f6Was = f6;
        f7Was = f7;
    }

    private static void say(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(text), true);
        }
    }

    private static void tick(Minecraft mc) {
        if (enabled && mc.level != null && mc.player != null) {
            ZoneEstimator.tick(mc);
            if (kShowStatus && ++statusTicks >= kStatusEveryTicks) {
                statusTicks = 0;
                say(mc, ZoneEstimator.summary());
            }
        }
    }
}
