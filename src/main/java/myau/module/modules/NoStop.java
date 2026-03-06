package myau.module.modules;

import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.ItemUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S19PacketEntityStatus;

public class NoStop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // -------------------------------------------------------
    // Properties
    // -------------------------------------------------------

    /**
     * When OFF — resets sprint when your hit actually damages an enemy.
     *            Confirmed by S19 opcode 2 on the target entity.
     * When ON  — resets sprint when YOU take damage instead.
     */
    public final BooleanProperty onDamage;

    /**
     * When enabled, only activates while holding a sword.
     */
    public final BooleanProperty weaponOnly;

    // -------------------------------------------------------
    // Constructor
    // -------------------------------------------------------

    public NoStop() {
        super("NoStop", false);
        this.onDamage   = new BooleanProperty("on-damage", false);
        this.weaponOnly = new BooleanProperty("weapon-only", true);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private boolean canActivate() {
        if (mc.thePlayer == null) return false;
        if (!mc.thePlayer.isSprinting()) return false;
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) return false;
        return true;
    }

    private void doReset() {
        mc.thePlayer.setSprinting(false);
        mc.thePlayer.setSprinting(true);
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || mc.thePlayer == null) return;

        // Default: fire when a hit actually damages an enemy.
        // S19 opcode 2 = entity hurt — only sent by the server when damage lands.
        // This fires on confirmed damage only, not on missed swings.
        if (!this.onDamage.getValue()
                && event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus s19 = (S19PacketEntityStatus) event.getPacket();
            if (s19.getOpCode() == 2 && mc.theWorld != null) {
                Entity hit = s19.getEntity(mc.theWorld);
                // Must be a living entity that is not ourselves
                if (hit instanceof EntityLivingBase
                        && hit.getEntityId() != mc.thePlayer.getEntityId()
                        && this.canActivate()) {
                    this.doReset();
                }
            }
            return;
        }

        // On-damage mode: fire every time WE take damage
        if (this.onDamage.getValue()
                && event.getPacket() instanceof S06PacketUpdateHealth
                && this.canActivate()) {
            float incoming = ((S06PacketUpdateHealth) event.getPacket()).getHealth();
            if (incoming < mc.thePlayer.getHealth()) {
                this.doReset();
            }
        }
    }
}
