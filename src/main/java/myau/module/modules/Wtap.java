package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S06PacketUpdateHealth;

public class NoStop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // -------------------------------------------------------
    // Internal state
    // -------------------------------------------------------
    private long delayRemaining = 0L;  // ms until STOP_SPRINTING fires
    private long stopRemaining  = 0L;  // ms until START_SPRINTING fires
    private boolean armed    = false;  // waiting for delay to expire
    private boolean stopping = false;  // currently in stop phase

    // -------------------------------------------------------
    // Properties
    // -------------------------------------------------------

    /**
     * How long to wait after the trigger event before sending STOP_SPRINTING.
     * Lower values are more aggressive but may increase knockback taken.
     * 200-300ms is generally optimal.
     */
    public final IntProperty delayAfterAttack;

    /**
     * How long to hold STOP_SPRINTING before sending START_SPRINTING.
     * Higher values look more legit but slow you down more.
     */
    public final IntProperty stopDuration;

    /**
     * When enabled, the sprint reset only fires after you take damage,
     * rather than on every attack. Produces better timing and tends to
     * reduce your own knockback more consistently.
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
        this.delayAfterAttack = new IntProperty("delay", 250, 0, 500);
        this.stopDuration     = new IntProperty("stop-duration", 100, 50, 500);
        this.waitForDamage    = new BooleanProperty("wait-for-damage", false);
        this.weaponOnly       = new BooleanProperty("weapon-only", true);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private boolean canActivate() {
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) return false;
        return mc.thePlayer.isSprinting();
    }

    private void arm() {
        this.armed          = true;
        this.stopping       = false;
        this.delayRemaining = this.delayAfterAttack.getValue();
        this.stopRemaining  = 0L;
    }

    private void resetState() {
        this.armed          = false;
        this.stopping       = false;
        this.delayRemaining = 0L;
        this.stopRemaining  = 0L;
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;
        if (!this.armed) return;

        if (this.delayRemaining > 0L) {
            // Still waiting out the pre-fire delay
            this.delayRemaining -= 50L;
        } else if (!this.stopping) {
            // Delay expired — send STOP_SPRINTING and enter stop phase
            PacketUtil.sendPacket(new C0BPacketEntityAction(
                    mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING
            ));
            this.stopping      = true;
            this.stopRemaining = this.stopDuration.getValue();
        } else {
            // In stop phase — count down stop duration
            if (this.stopRemaining > 0L) {
                this.stopRemaining -= 50L;
            }
            if (this.stopRemaining <= 0L) {
                // Stop phase done — send START_SPRINTING and finish
                PacketUtil.sendPacket(new C0BPacketEntityAction(
                        mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING
                ));
                this.resetState();
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || mc.thePlayer == null) return;

        // Standard mode: arm on outgoing attack packet
        if (event.getType() == EventType.SEND
                && event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == Action.ATTACK
                && !this.waitForDamage.getValue()
                && !this.armed
                && this.canActivate()) {
            this.arm();
        }

        // Wait-for-damage mode: arm when we receive a health reduction packet
        if (event.getPacket() instanceof S06PacketUpdateHealth
                && this.waitForDamage.getValue()
                && !this.armed) {
            float incomingHealth = ((S06PacketUpdateHealth) event.getPacket()).getHealth();
            if (incomingHealth < mc.thePlayer.getHealth() && this.canActivate()) {
                this.arm();
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
    }
}
