package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ItemUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.server.S06PacketUpdateHealth;

public class NoStop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // -------------------------------------------------------
    // Internal state
    // -------------------------------------------------------

    /** System time when the reset was armed, -1 = not armed. */
    private long armedAt = -1L;

    /** Whether we are waiting for damage before allowing resets. */
    private boolean damageReceived = false;

    // -------------------------------------------------------
    // Properties
    // -------------------------------------------------------

    /**
     * How long after the trigger event to wait before executing the sprint reset.
     * 0 = fires instantly on every hit.
     * Higher values delay the reset, changing the timing relative to the hit.
     */
    public final IntProperty delay;

    /**
     * When enabled, the module will not reset your sprint until you take
     * damage yourself. This generally deals less knockback to the enemy
     * while chasing or combo'ing, but reduces your own knockback more
     * consistently since the reset timing aligns with the moment you get hit.
     */
    public final BooleanProperty waitForDamage;

    /**
     * When enabled, the module only activates while holding a sword.
     */
    public final BooleanProperty weaponOnly;

    // -------------------------------------------------------
    // Constructor
    // -------------------------------------------------------

    public NoStop() {
        super("NoStop", false);
        this.delay         = new IntProperty("delay", 0, 0, 500);
        this.waitForDamage = new BooleanProperty("wait-for-damage", false);
        this.weaponOnly    = new BooleanProperty("weapon-only", true);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private boolean canActivate() {
        if (!mc.thePlayer.isSprinting()) return false;
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) return false;
        return true;
    }

    private void doReset() {
        if (!this.canActivate()) return;
        mc.thePlayer.setSprinting(false);
        mc.thePlayer.setSprinting(true);
        this.armedAt = -1L;
    }

    private void resetState() {
        this.armedAt        = -1L;
        this.damageReceived = false;
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;
        if (this.armedAt < 0L) return;

        // Wait for damage mode: don't fire until we've taken a hit
        if (this.waitForDamage.getValue() && !this.damageReceived) return;

        // Delay gate: wait until configured ms have elapsed since arming
        if (System.currentTimeMillis() - this.armedAt < this.delay.getValue()) return;

        this.doReset();
        this.damageReceived = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || mc.thePlayer == null) return;

        // Arm on outgoing attack
        if (event.getType() == EventType.SEND
                && event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == Action.ATTACK
                && !this.waitForDamage.getValue()
                && this.canActivate()) {
            this.armedAt = System.currentTimeMillis();
        }

        // Wait-for-damage mode: arm when we take damage
        if (event.getPacket() instanceof S06PacketUpdateHealth
                && this.waitForDamage.getValue()) {
            float incomingHealth = ((S06PacketUpdateHealth) event.getPacket()).getHealth();
            if (incomingHealth < mc.thePlayer.getHealth() && this.canActivate()) {
                if (this.armedAt < 0L) this.armedAt = System.currentTimeMillis();
                this.damageReceived = true;
            }
        }
    }

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.resetState();
        // Restore sprint in case we disabled mid-reset
        if (mc.thePlayer != null && !mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(true);
        }
    }
}
