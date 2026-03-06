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

    /** Ticks remaining in the stop phase (sprint is off). */
    private int stopTicksRemaining = 0;

    /** True when we've completed a stop and are waiting one tick to re-sprint. */
    private boolean resprintPending = false;

    // -------------------------------------------------------
    // Properties
    // -------------------------------------------------------

    /**
     * How many ticks to stop sprinting after a hit (1 tick = 50ms).
     * 1 tick is enough to register a sprint reset with minimal slowdown.
     * Higher values look more natural but slow you down more.
     */
    public final IntProperty stopTicks;

    /**
     * When enabled, the module only activates while holding a sword.
     */
    public final BooleanProperty weaponOnly;

    /**
     * When enabled, only triggers after you take damage yourself rather
     * than on every hit you land. Produces better knockback reduction
     * at the cost of less frequent resets.
     */
    public final BooleanProperty waitForDamage;

    // -------------------------------------------------------
    // Constructor
    // -------------------------------------------------------

    public NoStop() {
        super("NoStop", false);
        this.stopTicks    = new IntProperty("stop-ticks", 1, 1, 5);
        this.weaponOnly   = new BooleanProperty("weapon-only", true);
        this.waitForDamage = new BooleanProperty("wait-for-damage", false);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private boolean canActivate() {
        if (!mc.thePlayer.isSprinting()) return false;
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) return false;
        return true;
    }

    private void triggerReset() {
        this.stopTicksRemaining = this.stopTicks.getValue();
        this.resprintPending    = false;
    }

    private void resetState() {
        this.stopTicksRemaining = 0;
        this.resprintPending    = false;
        // Restore sprint in case we disabled mid-stop
        if (mc.thePlayer != null && !mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(true);
        }
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        if (this.stopTicksRemaining > 0) {
            // Stop phase: hold sprint off for the configured number of ticks
            mc.thePlayer.setSprinting(false);
            this.stopTicksRemaining--;

            if (this.stopTicksRemaining == 0) {
                // Stop phase done — queue re-sprint for next tick
                this.resprintPending = true;
            }
        } else if (this.resprintPending) {
            // Re-sprint phase: turn sprint back on immediately
            // setSprinting(true) goes through the normal game path — the
            // server receives START_SPRINTING as a natural consequence of
            // the sprint state change, not as an injected standalone packet
            mc.thePlayer.setSprinting(true);
            this.resprintPending = false;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || mc.thePlayer == null) return;

        // Standard mode: trigger on outgoing attack packet
        if (event.getType() == EventType.SEND
                && event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == Action.ATTACK
                && !this.waitForDamage.getValue()
                && this.stopTicksRemaining == 0
                && !this.resprintPending
                && this.canActivate()) {
            this.triggerReset();
        }

        // Wait-for-damage mode: trigger when we take a health hit
        if (event.getPacket() instanceof S06PacketUpdateHealth
                && this.waitForDamage.getValue()
                && this.stopTicksRemaining == 0
                && !this.resprintPending) {
            float incomingHealth = ((S06PacketUpdateHealth) event.getPacket()).getHealth();
            if (incomingHealth < mc.thePlayer.getHealth() && this.canActivate()) {
                this.triggerReset();
            }
        }
    }

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    @Override
    public void onEnabled() {
        this.stopTicksRemaining = 0;
        this.resprintPending    = false;
    }

    @Override
    public void onDisabled() {
        this.resetState();
    }
}
