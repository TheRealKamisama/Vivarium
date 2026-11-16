package dev.vivarium;

import com.mojang.logging.LogUtils;
import dev.vivarium.client.DebugOverlay;
import dev.vivarium.config.Config;
import dev.vivarium.selftest.SelfTest;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = Vivarium.MOD_ID, dist = Dist.CLIENT)
public final class Vivarium {
    public static final String MOD_ID = "vivarium";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Vivarium(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(DebugOverlay::onDebugText);
        if (SelfTest.ENABLED) {
            NeoForge.EVENT_BUS.addListener(SelfTest::onClientTick);
            NeoForge.EVENT_BUS.addListener(SelfTest::onClientTickPre);
        }
    }
}
