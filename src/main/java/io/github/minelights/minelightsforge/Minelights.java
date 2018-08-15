package io.github.minelights.minelightsforge;

import com.google.common.base.Preconditions;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.Logger;
import org.jline.utils.Log;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

@Mod(modid = Minelights.MODID, name = Minelights.NAME, version = Minelights.VERSION)
public class Minelights
{
    // Filled in by build.gradle
    public static final String MODID = "@MODID@";
    public static final String NAME = "@MODNAME@";
    public static final String VERSION = "@VERSION@";

    private static Logger logger;
    private boolean isInitialized;


    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        logger = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)    {
        MinecraftForge.EVENT_BUS.register(this);
        ConfigManager.sync(MODID, Config.Type.INSTANCE);
    }

    // Note: setup must be deferred until render time so that the FBOs are made in the right thread
    private void setup() {
        Preconditions.checkState(!isInitialized, "Already initialized.");
        if(MinelightsConfig.lightDimensions < 2) {
            MinelightsConfig.lightDimensions = 2;
            ConfigManager.sync(MODID, Config.Type.INSTANCE);
        }
        try {
            LightingProvider.select(LightingProvider.Provider.Blit);
            LightingProvider.instance().debug(MinelightsConfig.debug);
            LightingProvider.instance().setup(MinelightsConfig.lightDimensions, MinelightsConfig.lightDimensions);
        } catch (IllegalArgumentException iae) {
            logger.error("Choked on selecting blitter", iae);
        }
        isInitialized = true;
    }

    private void teardown() {
        isInitialized = false;
        LightingProvider.instance().teardown();
    }

    private void reloadConfig() {
        teardown();
    }

    private boolean validateConfig() {
        return true;
    }

    @SubscribeEvent
    public void onConfigChangedEvent(final ConfigChangedEvent event){
        if (event.getModID().equals(MODID)) {
            if(!validateConfig()) {
                event.setResult(Event.Result.DENY);
                return;
            }
            ConfigManager.sync(MODID, Config.Type.INSTANCE);
            reloadConfig();
        }
    }

    @SubscribeEvent
    public void onWorldRender(final RenderWorldLastEvent event) {
        if (MinelightsConfig.enable) {
            if (!isInitialized) {
                setup();
            }
            final LightingProvider lightingProvider = LightingProvider.instance();
            if (!lightingProvider.isValid()) {
                return;
            }
            lightingProvider.update();
        }
    }

    @Config(modid = Minelights.MODID, name = Minelights.NAME)
    public static class MinelightsConfig {

        @Config.Comment("Enable @MODNAME@")
        public static boolean enable = true;

        @Config.Comment({"Render Mode", "Change the means of capturing the screen color."})
        public static LightingProvider.Provider renderMode = LightingProvider.Provider.Blit;

        // https://github.com/MinecraftForge/MinecraftForge/pull/5026
        @Config.Comment("How many pixels x*x should be exported from the screen.")
        @Config.RangeInt(min = 2, max = 64)
        public static int lightDimensions = 4;

        @Config.Comment("Display Debug information")
        public static boolean debug = true;
    }
}
