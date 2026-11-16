package dev.vivarium.selftest;

import dev.vivarium.Vivarium;
import dev.vivarium.atlas.Bc7SpriteData;
import dev.vivarium.atlas.CompressedAtlas;
import dev.vivarium.atlas.GlBc7;
import dev.vivarium.codec.Bc7Decoder;
import dev.vivarium.codec.Metrics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * {@code -Dvivarium.selftest=true}: once loading finished and animations have ticked, reads every compressed
 * atlas back from the GPU and checks
 * <ol>
 *   <li>the driver's decode equals our Java decode of what we uploaded, bit for bit (static texels);</li>
 *   <li>quality against the source RGBA (PSNR, alpha rule) per atlas and level;</li>
 *   <li>every pre-encoded animated sprite currently shows one of its frames exactly.</li>
 * </ol>
 * Writes {@code vivarium-selftest.txt} to the game directory and closes the game.
 */
public final class SelfTest {
    private SelfTest() {}

    public static final boolean ENABLED = Boolean.getBoolean("vivarium.selftest");
    /** Also create a world, let it render, take a screenshot ({@code -Dvivarium.selftest.world=true}). */
    public static final boolean WORLD = Boolean.getBoolean("vivarium.selftest.world");
    private static final String WORLD_NAME = "vivarium-selftest";
    private static final int SETTLE_TICKS = 100;
    private static final int WORLD_TICKS = 400;

    private enum Phase { LOADING, TITLE, CREATING, IN_WORLD, DONE }

    private static Phase phase = Phase.LOADING;
    private static int ticks;

    /** Atlas level as the source would look (first frames painted into their slots). */
    public static int[] expectedLevel(CompressedAtlas ca, int level) {
        int lw = ca.levelWidth(level), lh = ca.levelHeight(level);
        int[] out = new int[lw * lh];
        for (Bc7SpriteData d : ca.sprites()) {
            int[] slot = d.selfTestSlot(level);
            if (slot == null) continue;
            int ox = d.x() >> level, oy = d.y() >> level, sw = d.slotWidth() >> level, sh = d.slotHeight() >> level;
            for (int y = 0; y < sh && oy + y < lh; y++) System.arraycopy(slot, y * sw, out, (oy + y) * lw + ox, Math.min(sw, lw - ox));
        }
        return out;
    }

    /** Before textures tick: look every animated sprite up, so renderers that only animate visible sprites animate all. */
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        if (phase == Phase.LOADING || phase == Phase.DONE) return;
        Minecraft mc = Minecraft.getInstance();
        for (CompressedAtlas ca : CompressedAtlas.LIVE) {
            if (!(mc.getTextureManager().getTexture(ca.location()) instanceof TextureAtlas atlas)) continue;
            for (Bc7SpriteData d : ca.sprites()) if (d.animated()) atlas.getSprite(d.name());
        }
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        switch (phase) {
            case LOADING -> {
                // Self-test runs are unattended: keep them silent.
                if (mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).get() > 0.0) {
                    mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
                }
                if (mc.getOverlay() == null && mc.screen != null) {
                    phase = Phase.TITLE;
                    ticks = 0;
                }
            }
            case TITLE -> {
                if (++ticks < SETTLE_TICKS) return;
                if (!WORLD) {
                    finish(mc, null);
                    return;
                }
                phase = Phase.CREATING;
                createWorld(mc);
            }
            case CREATING -> {
                if (mc.level != null && mc.player != null && mc.screen == null) {
                    phase = Phase.IN_WORLD;
                    ticks = 0;
                    mc.player.connection.sendCommand("time set 6000");
                    mc.player.connection.sendCommand("weather clear");
                    mc.player.connection.sendCommand("tp @s 0 110 0 135 30");
                    mc.options.hideGui = true;
                }
            }
            case IN_WORLD -> {
                if (mc.player != null) {
                    mc.player.getAbilities().flying = true;
                    mc.player.setDeltaMovement(0, 0, 0);
                    if (ticks > 20) {
                        mc.player.setYRot(135f);
                        mc.player.setXRot(30f);
                    }
                }
                if (++ticks < WORLD_TICKS) return;
                String shot = (CompressedAtlas.LIVE.isEmpty() ? "vanilla" : "bc7") + "-" + System.getProperty("vivarium.selftest.tag", "run") + ".png";
                Screenshot.grab(mc.gameDirectory, shot, mc.getMainRenderTarget(), c -> {});
                finish(mc, shot);
            }
            case DONE -> {}
        }
    }

    private static void createWorld(Minecraft mc) {
        try {
            Path saves = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD_NAME);
            if (Files.exists(saves)) {
                try (var walk = Files.walk(saves)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (IOException e) {
            Vivarium.LOGGER.warn("could not delete old self-test world", e);
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
            WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, new WorldOptions(20261114L, true, false),
            WorldPresets::createNormalWorldDimensions, mc.screen);
    }

    private static void finish(Minecraft mc, String screenshot) {
        phase = Phase.DONE;
        List<String> report = new ArrayList<>();
        boolean pass = true;
        try {
            for (CompressedAtlas ca : CompressedAtlas.LIVE) pass &= check(ca, report);
        } catch (Throwable t) {
            pass = false;
            report.add("EXCEPTION " + t);
            Vivarium.LOGGER.error("Vivarium self-test crashed", t);
        }
        if (CompressedAtlas.LIVE.isEmpty()) report.add("no compressed atlases (mod disabled or unsupported)");
        if (screenshot != null) report.add("screenshot: screenshots/" + screenshot);
        report.add(0, "Vivarium self-test: " + (pass ? "PASS" : "FAIL"));
        Path out = FMLPaths.GAMEDIR.get().resolve("vivarium-selftest.txt");
        try {
            Files.write(out, report);
        } catch (IOException e) {
            Vivarium.LOGGER.error("could not write {}", out, e);
        }
        report.forEach(Vivarium.LOGGER::info);
        mc.stop();
    }

    private static boolean check(CompressedAtlas ca, List<String> report) {
        boolean pass = true;
        Bc7Decoder dec = new Bc7Decoder();
        int id = ca.textureId();
        report.add(String.format(Locale.ROOT, "%s (%dx%d, mip %d, %d sprites)", ca.location(), ca.levelWidth(0), ca.levelHeight(0),
            ca.mipLevel(), ca.sprites().size()));
        for (int level = 0; level <= ca.mipLevel(); level++) {
            int lw = ca.levelWidth(level), lh = ca.levelHeight(level);
            int fmt = GlBc7.levelInternalFormat(id, level);
            if (fmt != GlBc7.GL_COMPRESSED_RGBA_BPTC_UNORM) {
                report.add(String.format(Locale.ROOT, "  level %d: internal format 0x%X, expected BPTC", level, fmt));
                pass = false;
                continue;
            }
            int[] gpu = GlBc7.readLevelRgba(id, level, lw, lh);
            // Texels touched by animations are excluded from the static comparisons (whole covering blocks).
            boolean[] animated = new boolean[lw * lh];
            for (Bc7SpriteData d : ca.sprites()) {
                if (!d.animated() && !d.keepsRgba()) continue;
                int ox = d.x() >> level, oy = d.y() >> level;
                int x0 = (ox >> 2) << 2, y0 = (oy >> 2) << 2;
                int x1 = Math.min(lw, ((ox + (d.slotWidth() >> level) + 3) >> 2) << 2);
                int y1 = Math.min(lh, ((oy + (d.slotHeight() >> level) + 3) >> 2) << 2);
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) animated[y * lw + x] = true;
            }
            int mismatches = 0;
            ByteBuffer blocks = ca.levelBlocks(level);
            if (blocks != null) {
                byte[] b = new byte[blocks.remaining()];
                blocks.get(0, b);
                int[] cpu = new int[lw * lh];
                dec.decodeImage(b, 0, lw, lh, cpu);
                for (int i = 0; i < cpu.length; i++) if (!animated[i] && cpu[i] != gpu[i]) mismatches++;
            }
            int[] expected = ca.expectedRgba(level);
            String quality = "";
            if (expected != null) {
                int n = 0;
                int[] a = new int[lw * lh], g = new int[lw * lh];
                for (Bc7SpriteData d : ca.sprites()) {
                    if (d.animated() || d.keepsRgba()) continue;
                    int ox = d.x() >> level, oy = d.y() >> level;
                    int sw = Math.max(1, d.width() >> level), sh = Math.max(1, d.height() >> level);
                    for (int y = oy; y < oy + sh && y < lh; y++) {
                        for (int x = ox; x < ox + sw && x < lw; x++) {
                            a[n] = expected[y * lw + x];
                            g[n] = gpu[y * lw + x];
                            n++;
                        }
                    }
                }
                Metrics.Result r = Metrics.compare(a, g, n);
                quality = String.format(Locale.ROOT, ", PSNR %.2f dB, alpha-rule violations %d", r.psnr(), r.alphaRuleViolations());
                if (level == 0 && r.psnr() < 30) pass = false;
                if (r.alphaRuleViolations() > 0 && level == 0) pass = false;
            }
            if (mismatches > 0) pass = false;
            report.add(String.format(Locale.ROOT, "  level %d %dx%d: GPU/Java decode mismatches %d%s", level, lw, lh, mismatches, quality));
        }
        // Animated sprites must show one of their frames exactly (level 0).
        int animatedChecked = 0, animatedBad = 0, animatedAdvanced = 0;
        int lw = ca.levelWidth(0);
        int[] gpu0 = GlBc7.readLevelRgba(id, 0, lw, ca.levelHeight(0));
        for (Bc7SpriteData d : ca.sprites()) {
            if (!d.animated() || d.keepsRgba()) continue;
            animatedChecked++;
            boolean match = false;
            int[] slot = new int[d.slotWidth() * d.slotHeight()];
            int[] frames = d.usedFrameIndices();
            for (int f : frames) {
                dec.decodeImage(d.frameBlocksLevel0(f), 0, d.slotWidth(), d.slotHeight(), slot);
                boolean same = true;
                for (int y = 0; y < d.slotHeight() && same; y++) {
                    for (int x = 0; x < d.slotWidth(); x++) {
                        if (slot[y * d.slotWidth() + x] != gpu0[(d.y() + y) * lw + d.x() + x]) {
                            same = false;
                            break;
                        }
                    }
                }
                if (same) {
                    match = true;
                    if (f != frames[0]) animatedAdvanced++;
                    break;
                }
            }
            if (!match) {
                animatedBad++;
                report.add("  animated sprite shows no known frame: " + d.name());
            }
        }
        if (animatedBad > 0) pass = false;
        report.add(String.format(Locale.ROOT, "  animated sprites checked %d, bad %d, showing a later frame %d",
            animatedChecked, animatedBad, animatedAdvanced));
        int glError = org.lwjgl.opengl.GL11.glGetError();
        if (glError != 0) {
            pass = false;
            report.add(String.format(Locale.ROOT, "  GL error 0x%X", glError));
        }
        return pass;
    }
}
