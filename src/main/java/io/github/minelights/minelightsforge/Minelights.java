package io.github.minelights.minelightsforge;

import com.google.common.base.Preconditions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
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
    public static final String NAME = "$@MODNAME@";
    public static final String VERSION = "@VERSION@";

    private static final int REDUCTION_DIM = 16;
    private static final int REDUCTION_FIL = GL_NEAREST;
    private static final int BYTEWIDTH = 4; //RGBA8
    // private static final int OUTPUTSTA_DIM = 3;
    // private static final int OUTPUTSTA_FIL = GL_NEAREST;


    private final ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(BYTEWIDTH * REDUCTION_DIM * REDUCTION_DIM);

    private static Logger logger;

    private Framebuffer reductionBuffer;
    // private Framebuffer outputStageBuffer;
    private Framebuffer mcFb;
    private boolean isInitialized;


    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        logger = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        prechecks();
    }


    private void prechecks() {
        Preconditions.checkState(!isInitialized, "prechecks called twice.");

        if (FMLClientHandler.instance().hasOptifine()) {
            logger.warn("Optifine detected, ${modname} and Optifine might cause issues.");
        }

        if (!OpenGlHelper.isFramebufferEnabled()) {
            logger.info("Framebuffer not enabled, ${modname} will be unavailable");
            return;
        }

        // WARN: I'm not expecting this instance to change, but if it did we'd have trouble.
        mcFb = Minecraft.getMinecraft().getFramebuffer();
        FramebufferBlitter.setup(logger);
        MinecraftForge.EVENT_BUS.register(this);
        ConfigManager.sync(MODID, Config.Type.INSTANCE);
        isInitialized = true;
    }

    private void setup() {
        if (!isInitialized) return;
        if(MinelightsConfig.lightDimensions < 2) {
            MinelightsConfig.lightDimensions = 2;
            ConfigManager.sync(MODID, Config.Type.INSTANCE);
        }
        if (reductionBuffer == null) {
            reductionBuffer = new Framebuffer(MinelightsConfig.lightDimensions, MinelightsConfig.lightDimensions, false); // mcFb.framebufferWidth, mcFb.framebufferHeight, false);
            reductionBuffer.setFramebufferFilter(REDUCTION_FIL);
            Log.info("Framebuffer creation.");
        }/* else if (reductionBuffer.framebufferWidth != mcFb.framebufferWidth || reductionBuffer.framebufferHeight != mcFb.framebufferHeight) {
            reductionBuffer.createBindFramebuffer(mcFb.framebufferWidth, mcFb.framebufferHeight);
        }*/
    }

    private void teardown() {
        if (reductionBuffer != null) {
            reductionBuffer.deleteFramebuffer();
            reductionBuffer = null;
            isInitialized = false;
        }
    }

    private void computeAverageDisplayColor() {
        FramebufferBlitter.INSTANCE.blitFramebuffer(mcFb, reductionBuffer);
        mcFb.bindFramebuffer(false);
    }

    private void reloadConfig() {
        if (isInitialized && !MinelightsConfig.enable) {
            teardown();
        } else if(!isInitialized && MinelightsConfig.enable) {
            setup();
        }
        if (MinelightsConfig.enable && MinelightsConfig.lightDimensions != reductionBuffer.framebufferWidth) {
            reductionBuffer.createBindFramebuffer(MinelightsConfig.lightDimensions, MinelightsConfig.lightDimensions);
        }
    }

    @SubscribeEvent
    public void onConfigChangedEvent(final ConfigChangedEvent event){
        if (event.getModID().equals(MODID)) {
            ConfigManager.sync(MODID, Config.Type.INSTANCE);
            reloadConfig();
        }
    }

    @SubscribeEvent
    public void onWorldRender(final RenderWorldLastEvent event) {
        if (!isInitialized) return;
        setup();

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();

        computeAverageDisplayColor();
        reductionBuffer.framebufferRender(200, 200);
        glBindTexture(GL_TEXTURE_2D, reductionBuffer.framebufferTexture);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
        glBindTexture(GL_TEXTURE_2D, 0);
        int r = 0, g = 0, b = 0;
        final ByteBuffer snapshot = pixelBuffer.slice();
        final byte[] pixeldata = new byte[snapshot.remaining()];
        final int capacity = pixeldata.length;
        final int multiplicity = (capacity / BYTEWIDTH);
        snapshot.get(pixeldata);
        for (int i = 0; i < capacity; i += BYTEWIDTH) {
            r += pixeldata[i + 0] & 0xFF;
            g += pixeldata[i + 1] & 0xFF;
            b += pixeldata[i + 2] & 0xFF;
        }
        r /= multiplicity;
        g /= multiplicity;
        b /= multiplicity;
        System.out.format("RGB Screen %d,%d,%d\n", r, g, b);
        mcFb.bindFramebuffer(true);

        GlStateManager.popMatrix();
        GlStateManager.popAttrib();
    }

    @Config(modid = Minelights.MODID, name = Minelights.NAME)
    public static class MinelightsConfig {

        @Config.Comment("Enable ${modname}")
        public static boolean enable = true;

        @Config.Comment({"Render Mode", "Change the means of capturing the screen color."})
        public static RenderMode renderMode = RenderMode.Blit;
        public enum RenderMode { Blit/*, Shader, Mipmap*/ };

        @Config.Comment("How many pixels x*x should be exported from the screen.")
        @Config.RangeInt(min = 2, max = 64)
        public static int lightDimensions = REDUCTION_DIM;
    }
}
