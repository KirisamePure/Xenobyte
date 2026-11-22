package forgefuck.team.xenobyte.coremod.asm;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Loading plugin: only registers {@link XRayTransformer}. Active when Xenobyte is installed in the mods folder
 * (jar manifest FMLCorePlugin), not when injected into a running game.
 */
@IFMLLoadingPlugin.Name("XenobyteCore")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1001) // after FML's deobfuscation: SRG / MCP names
@IFMLLoadingPlugin.TransformerExclusions("forgefuck.team.xenobyte.coremod.asm")
public class XenoCoreMod implements IFMLLoadingPlugin {

    @Override public String[] getASMTransformerClass() {
        return new String[] { XRayTransformer.class.getName() };
    }

    @Override public String getModContainerClass() {
        return null;
    }

    @Override public String getSetupClass() {
        return null;
    }

    @Override public void injectData(Map<String, Object> data) {}

    @Override public String getAccessTransformerClass() {
        return null;
    }

}
