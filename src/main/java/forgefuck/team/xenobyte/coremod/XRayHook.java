package forgefuck.team.xenobyte.coremod;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.init.Blocks;
import net.minecraft.world.IBlockAccess;

/**
 * Called from Minecraft code patched by {@code XRayTransformer}. Must stay cheap and side-effect free: it runs for
 * every block and face while chunks are built, possibly on several threads (Angelica).
 */
public class XRayHook {

    /** Decides which blocks the transparent XRay hides. */
    public interface Filter {
        boolean hide(Block block, int meta);
    }

    public static volatile Filter filter;
    /** Statistics for tests and the module description; racy increments are fine. */
    public static long hiddenBlocks, revealedSides;

    /** True when the coremod patched the renderer (Xenobyte installed in the mods folder, not injected). */
    public static boolean isInstalled() {
        return Boolean.getBoolean("xenobyte.xray.renderBlocks") && Integer.getInteger("xenobyte.xray.sides", 0) > 0;
    }

    public static Block filterBlock(RenderBlocks renderer, Block block, int x, int y, int z) {
        Filter f = filter;
        if (f == null || block == null || renderer.blockAccess == null) {
            return block;
        }
        if (f.hide(block, renderer.blockAccess.getBlockMetadata(x, y, z))) {
            hiddenBlocks++;
            return Blocks.air;
        }
        return block;
    }

    /** Replaces calls of {@code block.shouldSideBeRendered(...)} in renderers. */
    public static boolean shouldSideBeRendered(Block block, IBlockAccess access, int x, int y, int z, int side) {
        return sideVisible(block.shouldSideBeRendered(access, x, y, z, side), access, x, y, z);
    }

    public static boolean sideVisible(boolean vanilla, IBlockAccess access, int x, int y, int z) {
        Filter f = filter;
        if (vanilla || f == null || access == null) {
            return vanilla;
        }
        Block neighbor = access.getBlock(x, y, z);
        if (neighbor != null && f.hide(neighbor, access.getBlockMetadata(x, y, z))) {
            revealedSides++;
            return true;
        }
        return false;
    }

}
