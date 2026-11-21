package forgefuck.team.xenobyte.modules;

import cpw.mods.fml.common.Loader;
import forgefuck.team.xenobyte.api.config.Cfg;
import forgefuck.team.xenobyte.api.gui.WidgetMode;
import forgefuck.team.xenobyte.api.integration.VeinScanner;
import forgefuck.team.xenobyte.api.module.Category;
import forgefuck.team.xenobyte.api.module.CheatModule;
import forgefuck.team.xenobyte.api.module.PerformMode;
import forgefuck.team.xenobyte.gui.click.elements.Button;
import forgefuck.team.xenobyte.gui.click.elements.Panel;
import forgefuck.team.xenobyte.gui.click.elements.ScrollSlider;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Identifies GregTech ore veins in all loaded chunks around the player, client-side, and adds
 * them to VisualProspecting, so they appear on JourneyMap without touching ores or using a
 * prospector. No cooldown, many veins at once.
 */
public class VeinScan extends CheatModule {

    @Cfg("radius") private int radius;
    @Cfg("chunksPerTick") private int chunksPerTick;
    @Cfg("chat") private boolean chat;

    private VeinScanner scanner;

    public VeinScan() {
        super("VeinScan", Category.WORLD, PerformMode.TOGGLE);
        radius = 16;
        chunksPerTick = 2;
        chat = true;
    }

    @Override public boolean isWorking() {
        return Loader.isModLoaded("visualprospecting") && Loader.isModLoaded("gregtech");
    }

    /** Exposed for tests and other modules. Null until the module ran once. */
    public VeinScanner scanner() {
        return scanner;
    }

    @Override public void onDisabled() {
        if (scanner != null) {
            scanner.reset();
        }
    }

    @Override public void onTick(boolean inGame) {
        if (!inGame) {
            return;
        }
        if (scanner == null) {
            scanner = new VeinScanner();
        }
        EntityPlayer pl = utils.player();
        int found = scanner.tick(utils.world(), pl.posX, pl.posZ, radius, chunksPerTick, !chat);
        if (found > 0) {
            widgetMessage("+" + found + lang.get(" ore veins", " рудных жил"), WidgetMode.SUCCESS);
        }
    }

    @Override public String moduleDesc() {
        return lang.get("Adds GregTech ore veins of all loaded chunks around you to VisualProspecting (JourneyMap)", "Добавляет рудные жилы GregTech из загруженных чанков в VisualProspecting (JourneyMap)");
    }

    @Override public Panel settingPanel() {
        return new Panel(
            new ScrollSlider("Radius", radius, 3, 32) {
                @Override public void onScroll(int dir, boolean withShift) {
                    radius = processSlider(dir, withShift);
                }
                @Override public String elementDesc() {
                    return lang.get("Scan radius in chunks (limited by the server view distance)", "Радиус сканирования в чанках (ограничен дальностью прорисовки сервера)");
                }
            },
            new ScrollSlider("Speed", chunksPerTick, 1, 16) {
                @Override public void onScroll(int dir, boolean withShift) {
                    chunksPerTick = processSlider(dir, withShift);
                }
                @Override public String elementDesc() {
                    return lang.get("Vein chunks scanned per tick", "Чанков жил за тик");
                }
            },
            new Button("Chat", chat) {
                @Override public void onLeftClick() {
                    buttonValue(chat = !chat);
                }
                @Override public String elementDesc() {
                    return lang.get("VisualProspecting chat message for new veins", "Сообщение VisualProspecting в чат о новых жилах");
                }
            }
        );
    }

}
