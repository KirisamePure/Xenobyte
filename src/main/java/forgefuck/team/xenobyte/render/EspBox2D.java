package forgefuck.team.xenobyte.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.glu.GLU;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;

/**
 * LiquidBounce-style 2D ESP: each entity's bounding box is projected to the screen during world
 * rendering ({@link #project}), then drawn as a flat box in the HUD pass ({@link #draw}) with a
 * health bar, armor and held item around it.
 */
public class EspBox2D {

    public static class Box {
        public final Entity entity;
        public final int color;
        /** Screen coordinates in pixels, origin top-left. */
        public final double left, top, right, bottom;

        Box(Entity entity, int color, double left, double top, double right, double bottom) {
            this.entity = entity;
            this.color = color;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screen = BufferUtils.createFloatBuffer(3);
    private final RenderItem itemRender = new RenderItem();
    private List<Box> boxes = new ArrayList<Box>();

    public List<Box> boxes() {
        return boxes;
    }

    public void clear() {
        boxes = new ArrayList<Box>();
    }

    /** Must be called from RenderWorldLastEvent, while the camera matrices are active. */
    public void project(List<Entity> entities, List<Integer> colors, float partialTicks) {
        List<Box> out = new ArrayList<Box>();
        modelview.clear();
        projection.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int displayHeight = Minecraft.getMinecraft().displayHeight;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e.isDead) continue;
            double ix = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTicks - e.posX - RenderManager.renderPosX;
            double iy = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTicks - e.posY - RenderManager.renderPosY;
            double iz = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTicks - e.posZ - RenderManager.renderPosZ;
            AxisAlignedBB bb = e.boundingBox;
            double grow = 0.1;
            double[] xs = { bb.minX - grow + ix, bb.maxX + grow + ix };
            double[] ys = { bb.minY + iy, bb.maxY + grow + iy };
            double[] zs = { bb.minZ - grow + iz, bb.maxZ + grow + iz };
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            boolean visible = true;
            for (double x : xs) {
                for (double y : ys) {
                    for (double z : zs) {
                        screen.clear();
                        if (!GLU.gluProject((float) x, (float) y, (float) z, modelview, projection, viewport, screen)) {
                            visible = false;
                            break;
                        }
                        float sz = screen.get(2);
                        if (sz < 0 || sz >= 1) {
                            visible = false; // behind the camera
                            break;
                        }
                        double sx = screen.get(0), sy = displayHeight - screen.get(1);
                        minX = Math.min(minX, sx);
                        maxX = Math.max(maxX, sx);
                        minY = Math.min(minY, sy);
                        maxY = Math.max(maxY, sy);
                    }
                    if (!visible) break;
                }
                if (!visible) break;
            }
            if (visible) {
                out.add(new Box(e, colors.get(i), minX, minY, maxX, maxY));
            }
        }
        boxes = out;
    }

    /** Draws the projected boxes. Must be called in the HUD pass with the default GUI scale active. */
    public void draw(int scaleFactor, boolean info) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        double s = scaleFactor;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        for (Box b : boxes) {
            // Convert pixels to GUI units
            double l = b.left / s, t = b.top / s, r = b.right / s, bt = b.bottom / s;
            if (r - l < 1 || bt - t < 1) continue;
            beginFlat();
            outline(l, t, r, bt, 1.5, 0xFF000000);
            outline(l + 0.25, t + 0.25, r - 0.25, bt - 0.25, 0.5, b.color | 0xFF000000);
            endFlat();
            if (!info) continue;
            if (b.entity instanceof EntityLivingBase) {
                EntityLivingBase living = (EntityLivingBase) b.entity;
                drawHealth(font, living, l, t, bt);
                drawArmor(mc, font, living, r, t, bt);
                drawHeld(mc, font, living, l, r, bt);
            }
            drawName(font, b.entity, l, r, t);
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private void drawHealth(FontRenderer font, EntityLivingBase e, double l, double t, double b) {
        float max = Math.max(1, e.getMaxHealth());
        float hp = Math.max(0, Math.min(e.getHealth(), max));
        float ratio = hp / max;
        double x2 = l - 2, x1 = x2 - 1.5, h = b - t;
        beginFlat();
        rect(x1 - 0.5, t - 0.5, x2 + 0.5, b + 0.5, 0xFF000000);
        rect(x1, b - h * ratio, x2, b, healthColor(ratio));
        endFlat();
        if (ratio < 1) {
            String text = String.format("%.1f", hp);
            text = text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
            smallText(font, text, x1 - 1 - font.getStringWidth(text) * 0.5, b - h * ratio - 2, healthColor(ratio));
        }
    }

    private void drawArmor(Minecraft mc, FontRenderer font, EntityLivingBase e, double r, double t, double b) {
        double y = t;
        double step = Math.max(6, Math.min(8.5, (b - t) / 4));
        for (int slot = 4; slot >= 1; slot--) { // helmet .. boots
            ItemStack stack = e.getEquipmentInSlot(slot);
            if (stack != null) {
                item(mc, font, stack, r + 2, y, step / 16);
            }
            y += step;
        }
    }

    private void drawHeld(Minecraft mc, FontRenderer font, EntityLivingBase e, double l, double r, double b) {
        ItemStack held = e.getHeldItem();
        if (held == null) return;
        double size = 8;
        double cx = (l + r) / 2;
        item(mc, font, held, cx - size / 2, b + 1.5, size / 16);
        String name = held.getDisplayName();
        smallText(font, name, cx - font.getStringWidth(name) * 0.25, b + 2 + size, 0xFFFFFFFF);
    }

    private void drawName(FontRenderer font, Entity e, double l, double r, double t) {
        Minecraft mc = Minecraft.getMinecraft();
        String text = e.getCommandSenderName() + " §7" + Math.round(mc.thePlayer.getDistanceToEntity(e)) + "m";
        smallText(font, text, (l + r) / 2 - font.getStringWidth(text) * 0.25, t - 5.5, 0xFFFFFFFF);
    }

    private static int healthColor(float ratio) {
        int rgb = java.awt.Color.HSBtoRGB(ratio / 3f, 1f, 1f); // red (0) .. green (1/3)
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    private static void smallText(FontRenderer font, String text, double x, double y, int color) {
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, 0);
        GL11.glScaled(0.5, 0.5, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        font.drawStringWithShadow(text, 0, 0, color);
        GL11.glPopMatrix();
    }

    private void item(Minecraft mc, FontRenderer font, ItemStack stack, double x, double y, double scale) {
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, 0);
        GL11.glScaled(scale, scale, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            itemRender.zLevel = -100;
            itemRender.renderItemAndEffectIntoGUI(font, mc.getTextureManager(), stack, 0, 0);
            itemRender.renderItemOverlayIntoGUI(font, mc.getTextureManager(), stack, 0, 0);
        } catch (Exception ignored) {
            // a broken item renderer of some mod must not break the whole ESP
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
    }

    private static void beginFlat() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void endFlat() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static void outline(double l, double t, double r, double b, double w, int color) {
        rect(l - w / 2, t - w / 2, r + w / 2, t + w / 2, color);
        rect(l - w / 2, b - w / 2, r + w / 2, b + w / 2, color);
        rect(l - w / 2, t + w / 2, l + w / 2, b - w / 2, color);
        rect(r - w / 2, t + w / 2, r + w / 2, b - w / 2, color);
    }

    private static void rect(double l, double t, double r, double b, int color) {
        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
        Tessellator tes = Tessellator.instance;
        tes.startDrawingQuads();
        tes.addVertex(l, b, 0);
        tes.addVertex(r, b, 0);
        tes.addVertex(r, t, 0);
        tes.addVertex(l, t, 0);
        tes.draw();
    }

}
