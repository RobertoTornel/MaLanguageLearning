package com.ma.languagelearning;

import com.mojang.logging.LogUtils;
import com.ma.languagelearning.client.ClientBootstrap;
import com.ma.languagelearning.config.MllConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * MaLanguageLearning — learn a language by speaking the names of the items you acquire.
 *
 * <p>Client-side mod. The speech engine lives outside the game as a local sidecar
 * process, which this mod provisions automatically on first run.</p>
 */
@Mod(MaLanguageLearning.MODID)
public final class MaLanguageLearning {
    public static final String MODID = "malanguagelearning";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static MaLanguageLearning instance;

    public MaLanguageLearning(IEventBus modEventBus) {
        instance = this;
        MllConfig.get().load();
        LOGGER.info("[{}] initialising (side={})", MODID, FMLEnvironment.getDist());

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientBootstrap.init(modEventBus);
        }
    }

    public static MaLanguageLearning get() {
        return instance;
    }
}
