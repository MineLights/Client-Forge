package io.github.minelights.minelightsforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.Color;
import org.lwjgl.util.ReadableColor;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.lwjgl.opengl.GL11.*;

public abstract class LightingProvider {
    public abstract void setup(int width, int height);
    public abstract void teardown();
    public abstract void update();
    public abstract int getWidth();
    public abstract int getHeight();
    public abstract void setDimensions(int width, int height);
    public abstract ReadableColor[][] getColorMap();
    public abstract ReadableColor getAverage();
    public abstract void debug(boolean shouldDebug);
    public abstract boolean isValid();

    private static LightingProvider INSTANCE = new LightingProvider() {
        @Override
        public void setup(int width, int height) { }
        @Override
        public void teardown() { }
        @Override
        public void update() { }
        @Override
        public int getWidth() { return 0; }
        @Override
        public int getHeight() { return 0; }
        @Override
        public void setDimensions(int width, int height) { }
        @Override
        public ReadableColor[][] getColorMap() { return null; }
        @Override
        public ReadableColor getAverage() { return null; }
        @Override
        public void debug(boolean shouldDebug) { }
        @Override
        public boolean isValid() { return false; }
    };

    public static LightingProvider instance() {
        return INSTANCE;
    }

    public static ArrayList<Provider> available() {
        final ArrayList<Provider> providers = new ArrayList<>();
        if (OpenGlHelper.framebufferSupported && OpenGlHelper.isFramebufferEnabled()) {
            final ContextCapabilities caps = GLContext.getCapabilities();
            if (caps.GL_EXT_framebuffer_blit || caps.OpenGL30) {
                providers.add(Provider.Blit);
            }
        }
        return providers;
    }

    public static void select(final Provider provider) throws IllegalArgumentException {
        if (!available().contains(provider)) {
            throw new IllegalArgumentException("Provider is not available.");
        }
        if (INSTANCE != null) {
            INSTANCE.teardown();
            INSTANCE = null;
        }
        switch (provider) {
            case Blit:
                INSTANCE = new BlitProviderImpl();
        }
    }

    private static class BlitProviderImpl extends LightingProvider {

        private static final int BYTEWIDTH = 4;

        private Framebuffer framebuffer;
        private Color[][] colorMap;
        private Color average;
        private ByteBuffer pixelBuffer;
        private Logger logger = LogManager.getLogger("minelights.blitprovider");
        private boolean debug = false;

        @Override
        public void setup(int width, int height) {
            framebuffer = new Framebuffer(width, height, false);
            if (!FramebufferBlitter.INSTANCE.isValid()) {
                FramebufferBlitter.setup(logger);
            }
            createColorMap();
        }

        @Override
        public void teardown() {
            if (framebuffer != null) {
                framebuffer.deleteFramebuffer();
                framebuffer = null;
            }
        }

        @Override
        public void update() {
            try {
                if (framebuffer == null) return;
                if (!FramebufferBlitter.INSTANCE.isValid()) return;

                final Framebuffer mcfb = Minecraft.getMinecraft().getFramebuffer();
                FramebufferBlitter.INSTANCE.blitFramebuffer(mcfb, framebuffer);
                mcfb.bindFramebuffer(false);
                if (debug) {
                    framebuffer.framebufferRender(200, 200);
                }
                framebuffer.bindFramebufferTexture();
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
                framebuffer.unbindFramebufferTexture();
                int a_red = 0, a_green = 0, a_blue = 0, r, g, b;
                final ByteBuffer snapshot = pixelBuffer.slice();
                final byte[] pixeldata = new byte[snapshot.remaining()];
                final int capacity = pixeldata.length;
                final int multiplicity = (capacity / BYTEWIDTH);
                snapshot.get(pixeldata);
                for (int i = 0; i < capacity; i += BYTEWIDTH) {
                    a_red += (r = pixeldata[i + 0] & 0xFF);
                    a_green += (g = pixeldata[i + 1] & 0xFF);
                    a_blue += (b = pixeldata[i + 2] & 0xFF);
                    colorMap[(i / BYTEWIDTH) % framebuffer.framebufferHeight][(i / (framebuffer.framebufferHeight * BYTEWIDTH))].set(r, g, b);
                }
                a_red /= multiplicity;
                a_green /= multiplicity;
                a_blue /= multiplicity;
                average.set(a_red, a_green, a_blue);
                if (debug) {
                    logger.debug("Average color (%d,%d,%d)", a_red, a_green, a_blue);
                }
                mcfb.bindFramebuffer(true);
            } catch (Exception ex) {
                logger.error("Failed to update lighting provider.", ex);
                ex.printStackTrace();
            }
        }

        @Override
        public int getWidth() {
            return framebuffer.framebufferWidth;
        }

        @Override
        public int getHeight() {
            return framebuffer.framebufferHeight;
        }

        @Override
        public void setDimensions(final int width, final int height) {
            if (framebuffer == null) return;
            if (width != framebuffer.framebufferWidth || height != framebuffer.framebufferHeight) {
                framebuffer.createBindFramebuffer(width, height);
                framebuffer.unbindFramebuffer();
            }
            createColorMap();
        }

        @Override
        public ReadableColor[][] getColorMap() {
            createColorMap();
            return colorMap;
        }

        @Override
        public ReadableColor getAverage() {
            return average;
        }

        @Override
        public void debug(boolean shouldDebug) {
            debug = shouldDebug;
        }

        @Override
        public boolean isValid() {
            return framebuffer != null;
        }

        private void createColorMap() {
            if (framebuffer == null) return;
            if (colorMap == null || framebuffer.framebufferWidth != colorMap.length || colorMap[0] == null || colorMap[0].length != framebuffer.framebufferHeight) {
                colorMap = new Color[framebuffer.framebufferWidth][];
                for (int x = 0; x < framebuffer.framebufferWidth; x++) {
                    colorMap[x] = new Color[framebuffer.framebufferHeight];
                    for (int y = 0; y < framebuffer.framebufferHeight; y++) {
                        colorMap[x][y] = new Color(Color.BLACK);
                    }
                }
                average = new Color(Color.BLACK);
                if (pixelBuffer == null || pixelBuffer.capacity() < framebuffer.framebufferWidth * framebuffer.framebufferHeight * BYTEWIDTH) {
                    pixelBuffer = ByteBuffer.allocateDirect(framebuffer.framebufferWidth * framebuffer.framebufferHeight * BYTEWIDTH);
                }
            }
        }
    }

    public enum Provider { Blit/*, Shader, Mipmap*/ };
}
