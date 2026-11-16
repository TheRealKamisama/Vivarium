package dev.vivarium.client;

import dev.vivarium.atlas.CompressedAtlas;
import dev.vivarium.config.Config;
import java.util.List;
import java.util.Locale;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/** Adds RAM/VRAM savings to the right column of the F3 screen. */
public final class DebugOverlay {
    private DebugOverlay() {}

    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!Config.get(Config.DEBUG_OVERLAY)) return;
        List<CompressedAtlas> live = CompressedAtlas.LIVE;
        if (live.isEmpty()) return;
        long ram0 = 0, ram1 = 0, vram0 = 0, vram1 = 0;
        for (CompressedAtlas a : live) {
            ram0 += a.vanillaRamBytes();
            ram1 += a.ramBytes();
            vram0 += a.vanillaVramBytes();
            vram1 += a.vramBytes();
        }
        List<String> right = event.getRight();
        right.add("");
        right.add(String.format(Locale.ROOT, "Vivarium: %d atlases", live.size()));
        right.add(String.format(Locale.ROOT, "Sprite RAM %.1f -> %.1f MiB", ram0 / 1048576.0, ram1 / 1048576.0));
        right.add(String.format(Locale.ROOT, "Atlas VRAM %.1f -> %.1f MiB", vram0 / 1048576.0, vram1 / 1048576.0));
    }
}
