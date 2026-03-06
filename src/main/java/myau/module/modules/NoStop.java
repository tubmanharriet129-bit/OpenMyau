package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
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
    // Internal state
    // -------------------------------------------------------

    /**
     * Set true when a confirmed damage event arrives.
     * Consumed in onUpdate PRE — only restores sprint, never cancels it.
     * Minecraft's attack handler already cancels sprint on hit, so calling
     * setSprinting(false) ourselves produces a duplicate STOP_SPRINTING
     * packet which is what causes rubber banding.
     */
    private volatile boolean hitLanded  = false;
    private volatile boolean tookDamage = false;
    private volatile long    armedAt    = -1L;

    // -------------------------------------------------------
    // Properties
    // -------------------------------------------------------

    /**
     * When OFF — restores sprint when your hit actually damages an enemy.
     * When ON  — restores sprint when YOU take damage instead.
     */
    public final BooleanProperty onDamage;

    /**
     * How long to wait after the trigger before restoring sprint.
     * 0 = restore immediately on the very next movement tick.
     * With on-damage ON: waits this long after taking damage.
     * With on-damage OFF: waits this long after dealing damage.
     */
    public final myau.property.properties.IntProperty delay;

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
        this.delay      = new myau.property.properties.IntProperty("delay", 0, 0, 500);
        this.weaponOnly = new BooleanProperty("weapon-only", true);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private boolean canActivate() {
        if (mc.thePlayer == null) return false;
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) return false;
        return true;
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    /**
     * Restore sprint at the top of the movement tick.
     * We only call setSprinting(true) — never setSprinting(false).
     * The game already sent STOP_SPRINTING when the attack landed.
     * All we do here is immediately send START_SPRINTING so the next
     * C03 position packet goes out with sprint active.
     */
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        boolean shouldRestore = this.onDamage.getValue() ? this.tookDamage : this.hitLanded;
        if (!shouldRestore || !this.canActivate()) {
            if (!shouldRestore) {
                this.hitLanded  = false;
                this.tookDamage = false;
                this.armedAt    = -1L;
            }
            return;
        }

        // Arm the timer on the first tick the condition is met
        if (this.armedAt < 0L) this.armedAt = System.currentTimeMillis();

        // Wait for delay to elapse before restoring sprint
        if (System.currentTimeMillis() - this.armedAt < this.delay.getValue()) return;

        // Only restore — never cancel. The game cancelled it already.
        mc.thePlayer.setSprinting(true);
        this.hitLanded  = false;
        this.tookDamage = false;
        this.armedAt    = -1L;
    }

    /**
     * Packet handler — sets flags only, never touches sprint state directly.
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || mc.thePlayer == null) return;

        // Confirmed damage on an enemy via S19 opcode 2
        if (!this.onDamage.getValue()
                && event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus s19 = (S19PacketEntityStatus) event.getPacket();
            if (s19.getOpCode() == 2 && mc.theWorld != null) {
                Entity hit = s19.getEntity(mc.theWorld);
                if (hit instanceof EntityLivingBase
                        && hit.getEntityId() != mc.thePlayer.getEntityId()) {
                    this.hitLanded = true;
                }
            }
            return;
        }

        // We took damage
        if (this.onDamage.getValue()
                && event.getPacket() instanceof S06PacketUpdateHealth) {
            float incoming = ((S06PacketUpdateHealth) event.getPacket()).getHealth();
            if (incoming < mc.thePlayer.getHealth()) {
                this.tookDamage = true;
            }
        }
    }

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    @Override
    public void onEnabled() {
        this.hitLanded  = false;
        this.tookDamage = false;
        this.armedAt    = -1L;
    }

    @Override
    public void onDisabled() {
        this.hitLanded  = false;
        this.tookDamage = false;
        this.armedAt    = -1L;
    }
}
