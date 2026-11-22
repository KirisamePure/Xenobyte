package forgefuck.team.xenobyte.coremod.asm;

import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Hooks for the transparent XRay, kept minimal and without new branches (so no stack map frames change):
 * <ul>
 * <li>{@code RenderBlocks.renderBlockByRenderType(Block, x, y, z)}: at the head, the block argument is replaced by
 * {@code XRayHook.filterBlock(...)}, which returns air for hidden blocks; vanilla then skips them.</li>
 * <li>Every call of {@code Block.shouldSideBeRendered(IBlockAccess, x, y, z, side)} (vanilla RenderBlocks, mod block
 * renderers such as GregTech's) is redirected to {@code XRayHook.shouldSideBeRendered(block, ...)}, which calls the
 * real method and also shows faces next to hidden blocks. Patching call sites instead of the method keeps working
 * when a mod replaces the method itself (Angelica overwrites it with a mixin).</li>
 * </ul>
 */
public class XRayTransformer implements IClassTransformer, Opcodes {

    private static final Logger LOG = LogManager.getLogger("XenobyteCore");
    private static final String HOOK = "forgefuck/team/xenobyte/coremod/XRayHook";
    private static final String SIDE_DESC = "(Lnet/minecraft/world/IBlockAccess;IIII)Z";
    private static final byte[] SIDE_MCP = "shouldSideBeRendered".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIDE_SRG = "func_149646_a".getBytes(StandardCharsets.US_ASCII);
    private static int redirectedCalls;

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || transformedName == null || transformedName.startsWith("forgefuck.team.xenobyte.")) {
            return bytes;
        }
        boolean renderBlocks = "net.minecraft.client.renderer.RenderBlocks".equals(transformedName);
        if (!renderBlocks && !contains(bytes, SIDE_SRG) && !contains(bytes, SIDE_MCP)) {
            return bytes;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean changed = false;
            int calls = 0;
            for (MethodNode m : node.methods) {
                if (renderBlocks && (m.name.equals("renderBlockByRenderType") || m.name.equals("func_147805_b")) && m.desc.equals("(Lnet/minecraft/block/Block;III)Z")) {
                    InsnList head = new InsnList();
                    head.add(new VarInsnNode(ALOAD, 0));
                    head.add(new VarInsnNode(ALOAD, 1));
                    head.add(new VarInsnNode(ILOAD, 2));
                    head.add(new VarInsnNode(ILOAD, 3));
                    head.add(new VarInsnNode(ILOAD, 4));
                    head.add(new MethodInsnNode(INVOKESTATIC, HOOK, "filterBlock", "(Lnet/minecraft/client/renderer/RenderBlocks;Lnet/minecraft/block/Block;III)Lnet/minecraft/block/Block;", false));
                    head.add(new VarInsnNode(ASTORE, 1));
                    m.instructions.insert(head);
                    System.setProperty("xenobyte.xray.renderBlocks", "true");
                    LOG.info("XRay hook installed in {}.renderBlockByRenderType", transformedName);
                    changed = true;
                }
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn.getOpcode() != INVOKEVIRTUAL) continue;
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (!(call.name.equals("shouldSideBeRendered") || call.name.equals("func_149646_a")) || !call.desc.equals(SIDE_DESC) || !call.owner.startsWith("net/minecraft/block/")) continue;
                    // Same stack in and out: (block, access, x, y, z, side) -> boolean
                    m.instructions.set(call, new MethodInsnNode(INVOKESTATIC, HOOK, "shouldSideBeRendered", "(Lnet/minecraft/block/Block;" + SIDE_DESC.substring(1), false));
                    calls++;
                }
            }
            if (calls > 0) {
                redirectedCalls += calls;
                System.setProperty("xenobyte.xray.sides", String.valueOf(redirectedCalls));
                LOG.info("XRay hook: {} shouldSideBeRendered call(s) redirected in {}", calls, transformedName);
                changed = true;
            }
            if (!changed) {
                return bytes;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOG.error("XRay hook failed for " + transformedName, t);
            return bytes;
        }
    }

    private static boolean contains(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0, last = data.length - pattern.length; i <= last; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return true;
        }
        return false;
    }

}
