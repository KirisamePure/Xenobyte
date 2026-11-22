package forgefuck.team.xenobyte.modules;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import forgefuck.team.xenobyte.api.config.Cfg;
import forgefuck.team.xenobyte.api.gui.WidgetMode;
import forgefuck.team.xenobyte.api.module.Category;
import forgefuck.team.xenobyte.api.module.CheatModule;
import forgefuck.team.xenobyte.api.module.PerformMode;
import forgefuck.team.xenobyte.coremod.XRayHook;
import forgefuck.team.xenobyte.gui.click.elements.Button;
import forgefuck.team.xenobyte.gui.click.elements.Panel;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.oredict.OreDictionary;

/**
 * LiquidBounce-style XRay: every block not on the visible list (stone, dirt, gravel, modded stones, ...) is not
 * rendered, so ores, fluids and the like can be seen through the terrain. Needs the Xenobyte coremod (installed in
 * the mods folder); the previous XRay, which highlights selected blocks, is now {@link BlockESP}.
 */
public class XRay extends CheatModule implements XRayHook.Filter {

    /** Always visible, like LiquidBounce's default XRay list (1.7.10 registry names). */
    private static final String[] VISIBLE = {
        "coal_ore", "iron_ore", "gold_ore", "redstone_ore", "lit_redstone_ore", "lapis_ore", "diamond_ore", "emerald_ore", "quartz_ore",
        "coal_block", "iron_block", "gold_block", "diamond_block", "emerald_block", "redstone_block", "lapis_block",
        "clay", "glowstone", "crafting_table", "torch", "ladder", "tnt", "fire", "mossy_cobblestone", "mob_spawner",
        "end_portal_frame", "enchanting_table", "bookshelf", "command_block", "furnace", "lit_furnace", "obsidian",
        "chest", "trapped_chest", "ender_chest", "portal", "end_portal"
    };

    @Cfg("fluids") private boolean fluids;
    @Cfg("bright") private boolean bright;
    @Cfg("espBlocks") private boolean espBlocks;
    private final Set<Block> visibleBlocks = new HashSet<Block>();
    private final Map<Long, Boolean> cache = new ConcurrentHashMap<Long, Boolean>();
    private BlockESPSelect selector;
    private FullBright fullBright;
    /** Smooth-lighting setting saved while XRay forces flat lighting; -1 when not saved. */
    private int savedAmbientOcclusion = -1;

    public XRay() {
        super("XRay", Category.WORLD, PerformMode.TOGGLE);
        fluids = true;
        bright = true;
        espBlocks = true;
    }

    /** Own config entry: the old "XRay" entry (keybind, settings) belongs to BlockESP now. */
    @Override public String configId() {
        return "XRayTransparent";
    }

    @Override public void onPostInit() {
        for (String name : VISIBLE) {
            Object block = Block.blockRegistry.getObject("minecraft:" + name);
            if (block instanceof Block) {
                visibleBlocks.add((Block) block);
            }
        }
        selector = (BlockESPSelect) moduleHandler().getModuleByClass(BlockESPSelect.class);
        fullBright = (FullBright) moduleHandler().getModuleByClass(FullBright.class);
    }

    @Override public void onEnabled() {
        activate();
    }

    /** Also called from onTick: modules that were on when the game closed are restored without onEnabled. */
    private void activate() {
        if (!XRayHook.isInstalled()) {
            widgetMessage(lang.get("needs Xenobyte installed in the mods folder (coremod), not injected", "нужна установка Xenobyte в папку mods (coremod), а не инжект"), WidgetMode.FAIL);
            moduleHandler().disable(this);
            return;
        }
        cache.clear();
        XRayHook.filter = this;
        // Revealed ores are enclosed by (hidden) opaque blocks: smooth lighting would render them almost black.
        savedAmbientOcclusion = utils.mc().gameSettings.ambientOcclusion;
        utils.mc().gameSettings.ambientOcclusion = 0;
        reloadChunks();
    }

    @Override public void onDisabled() {
        if (XRayHook.filter == this) {
            XRayHook.filter = null;
            if (savedAmbientOcclusion >= 0) {
                utils.mc().gameSettings.ambientOcclusion = savedAmbientOcclusion;
                savedAmbientOcclusion = -1;
            }
            reloadChunks();
        }
        if (bright && utils.isInGame() && !moduleHandler().isEnabled(fullBright)) {
            utils.reloadWorld(); // restores the normal light table
        }
    }

    @Override public void onTick(boolean inGame) {
        if (inGame && XRayHook.filter != this) {
            activate();
        }
        if (inGame && bright && XRayHook.filter == this) {
            // Hidden blocks still block light: show the revealed ores at full brightness.
            float[] table = utils.world().provider.lightBrightnessTable;
            for (int i = 0; i < table.length; i++) {
                table[i] = 1.0F;
            }
        }
    }

    private void reloadChunks() {
        if (utils.isInGame()) {
            utils.mc().renderGlobal.loadRenderers();
        }
    }

    private void settingsChanged() {
        cache.clear();
        if (XRayHook.filter == this) {
            reloadChunks();
        }
    }

    @Override public boolean hide(Block block, int meta) {
        long key = ((long) Block.getIdFromBlock(block) << 32) | (meta & 0xFFFFFFFFL);
        Boolean hidden = cache.get(key);
        if (hidden == null) {
            hidden = !isVisible(block, meta);
            cache.put(key, hidden);
        }
        return hidden;
    }

    private boolean isVisible(Block block, int meta) {
        if (block.getMaterial() == net.minecraft.block.material.Material.air) {
            return true;
        }
        if (visibleBlocks.contains(block) || block instanceof BlockOre || block instanceof BlockRedstoneOre) {
            return true;
        }
        if (fluids && (block instanceof BlockLiquid || block instanceof IFluidBlock)) {
            return true;
        }
        if (espBlocks && selector != null && selector.getBlock(block, meta) != null) {
            return true;
        }
        return isOre(block, meta);
    }

    /** Modded ores: OreDictionary "ore*" entries, or ore-like registry names (e.g. GregTech gt.blockores). */
    private static boolean isOre(Block block, int meta) {
        Object nameObj = Block.blockRegistry.getNameForObject(block);
        String name = nameObj == null ? "" : String.valueOf(nameObj).toLowerCase(Locale.ROOT);
        String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        if (path.contains("blockores") || path.matches("(.*[^a-z])?ores?([^a-z].*)?") || path.endsWith("_ore") || path.startsWith("ore")) {
            return true;
        }
        try {
            Item item = Item.getItemFromBlock(block);
            if (item != null) {
                for (int id : OreDictionary.getOreIDs(new ItemStack(item, 1, meta))) {
                    String ore = OreDictionary.getOreName(id);
                    if (ore.startsWith("ore") || ore.startsWith("denseore")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override public String moduleDesc() {
        return lang.get("Hides stone, dirt and other terrain to see ores (like LiquidBounce). For selected-block highlighting use BlockESP",
            "Скрывает камень, землю и прочий ландшафт, чтобы видеть руды (как в LiquidBounce). Подсветка выбранных блоков — BlockESP");
    }

    @Override public Panel settingPanel() {
        return new Panel(
            new Button("Fluids", fluids) {
                @Override public void onLeftClick() {
                    buttonValue(fluids = !fluids);
                    settingsChanged();
                }
                @Override public String elementDesc() {
                    return lang.get("Show water and lava", "Показывать воду и лаву");
                }
            },
            new Button("BlockESPBlocks", espBlocks) {
                @Override public void onLeftClick() {
                    buttonValue(espBlocks = !espBlocks);
                    settingsChanged();
                }
                @Override public String elementDesc() {
                    return lang.get("Also show the blocks selected for BlockESP", "Также показывать блоки, выбранные для BlockESP");
                }
            },
            new Button("Bright", bright) {
                @Override public void onLeftClick() {
                    buttonValue(bright = !bright);
                    if (!bright && utils.isInGame() && !moduleHandler().isEnabled(fullBright)) {
                        utils.reloadWorld();
                    }
                }
                @Override public String elementDesc() {
                    return lang.get("Full brightness while XRay is on", "Полная яркость при включённом XRay");
                }
            }
        );
    }

}
