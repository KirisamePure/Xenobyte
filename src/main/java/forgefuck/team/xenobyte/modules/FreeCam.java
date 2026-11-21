package forgefuck.team.xenobyte.modules;

import forgefuck.team.xenobyte.api.config.Cfg;
import forgefuck.team.xenobyte.api.module.Category;
import forgefuck.team.xenobyte.api.module.CheatModule;
import forgefuck.team.xenobyte.api.module.PerformMode;
import forgefuck.team.xenobyte.gui.click.elements.Button;
import forgefuck.team.xenobyte.gui.click.elements.Panel;
import forgefuck.team.xenobyte.gui.click.elements.ScrollSlider;
import forgefuck.team.xenobyte.utils.Keys;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.MathHelper;
import net.minecraft.client.multiplayer.WorldClient;

/**
 * FreeCam (based on the LiquidBounce implementation):
 * a client-side copy of the player stays at the real position, movement packets
 * are not sent to the server, and the camera (player) flies freely. On disable
 * the player is returned to the saved position.
 */
public class FreeCam extends CheatModule {

    @Cfg("speed") private float speed;
    @Cfg("fly") private boolean fly;
    @Cfg("noclip") private boolean noclip;

    private EntityOtherPlayerMP fake;
    private WorldClient fakeWorld;
    private double oX, oY, oZ;
    private float oYaw, oPitch;
    private boolean oOnGround;
    private volatile double[] serverPos;

    public FreeCam() {
        super("FreeCam", Category.PLAYER, PerformMode.TOGGLE);
        speed = 0.8F;
        noclip = true;
        fly = true;
    }

    private void setup() {
        EntityClientPlayerMP pl = utils.player();
        oX = pl.posX;
        oY = pl.posY;
        oZ = pl.posZ;
        oYaw = pl.rotationYaw;
        oPitch = pl.rotationPitch;
        oOnGround = pl.onGround;
        fake = new EntityOtherPlayerMP(utils.world(), pl.getGameProfile());
        fake.setPositionAndRotation(pl.posX, pl.boundingBox.minY, pl.posZ, pl.rotationYaw, pl.rotationPitch);
        fake.rotationYawHead = pl.rotationYawHead;
        fake.inventory = pl.inventory;
        fake.setSneaking(pl.isSneaking());
        fakeWorld = utils.world();
        fakeWorld.addEntityToWorld(-100500, fake);
    }

    private void removeFake() {
        if (fake != null && fakeWorld != null) {
            fakeWorld.removeEntityFromWorld(-100500);
        }
        fake = null;
        fakeWorld = null;
        serverPos = null;
    }

    @Override public void onDisabled() {
        if (fake != null && utils.isInGame() && fakeWorld == utils.world()) {
            EntityClientPlayerMP pl = utils.player();
            pl.noClip = false;
            pl.setPositionAndRotation(oX, oY, oZ, oYaw, oPitch);
            pl.motionX = pl.motionY = pl.motionZ = 0;
            pl.fallDistance = 0;
            pl.onGround = oOnGround;
        } else if (utils.isInGame()) {
            utils.player().noClip = false;
        }
        removeFake();
    }

    @Override public void onTick(boolean inGame) {
        if (!inGame) {
            fake = null;
            fakeWorld = null;
            return;
        }
        if (fake == null || fakeWorld != utils.world()) {
            fake = null;
            setup();
        }
        double[] pos = serverPos;
        if (pos != null) {
            serverPos = null;
            oX = pos[0];
            oY = pos[1];
            oZ = pos[2];
            oYaw = (float) pos[3];
            oPitch = (float) pos[4];
            fake.setPositionAndRotation(oX, oY - utils.player().yOffset, oZ, oYaw, oPitch);
            fake.rotationYawHead = oYaw;
        }
        EntityClientPlayerMP pl = utils.player();
        pl.noClip = fly && noclip;
        pl.fallDistance = 0;
        if (fly) {
            GameSettings gs = utils.mc().gameSettings;
            float forward = 0, strafe = 0, vertical = 0;
            if (utils.isInGameGui()) {
                if (Keys.isPressed(gs.keyBindForward)) forward += 1;
                if (Keys.isPressed(gs.keyBindBack)) forward -= 1;
                if (Keys.isPressed(gs.keyBindLeft)) strafe += 1;
                if (Keys.isPressed(gs.keyBindRight)) strafe -= 1;
                if (Keys.isPressed(gs.keyBindJump)) vertical += 1;
                if (Keys.isPressed(gs.keyBindSneak)) vertical -= 1;
            }
            float sin = MathHelper.sin(pl.rotationYaw * (float) Math.PI / 180.0F);
            float cos = MathHelper.cos(pl.rotationYaw * (float) Math.PI / 180.0F);
            pl.motionX = (strafe * cos - forward * sin) * speed;
            pl.motionY = vertical * speed;
            pl.motionZ = (forward * cos + strafe * sin) * speed;
        }
    }

    @Override public boolean doSendPacket(Packet packet) {
        return !(packet instanceof C03PacketPlayer || packet instanceof C0BPacketEntityAction);
    }

    @Override public boolean doReceivePacket(Packet packet) {
        if (packet instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook p = (S08PacketPlayerPosLook) packet;
            serverPos = new double[] { p.func_148932_c(), p.func_148928_d(), p.func_148933_e(), p.func_148931_f(), p.func_148930_g() };
            return false;
        }
        return true;
    }

    @Override public String moduleDesc() {
        return lang.get("Detached camera: the server sees you standing still", "Свободная камера: сервер видит вас стоящим на месте");
    }

    @Override public Panel settingPanel() {
        return new Panel(
            new ScrollSlider("Speed", (int) (speed * 10), 1, 50) {
                @Override public void onScroll(int dir, boolean withShift) {
                    speed = (float) processSlider(dir, withShift) / 10;
                }
                @Override public String elementDesc() {
                    return lang.get("Camera flight speed", "Скорость полёта камеры");
                }
            },
            new Button("Fly", fly) {
                @Override public void onLeftClick() {
                    buttonValue(fly = !fly);
                }
                @Override public String elementDesc() {
                    return lang.get("Free flight of the camera", "Свободный полёт камеры");
                }
            },
            new Button("NoClip", noclip) {
                @Override public void onLeftClick() {
                    buttonValue(noclip = !noclip);
                }
                @Override public String elementDesc() {
                    return lang.get("Camera passes through walls (only with Fly)", "Камера проходит сквозь стены (только с Fly)");
                }
            }
        );
    }

}
