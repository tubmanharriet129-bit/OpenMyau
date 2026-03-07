package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C0BPacketEntityAction;

/**
 * NoStop — Slinky-style server-side sprint reset.
 *
 * Trigger: AttackEvent only. No hurtTime checks, no crosshair checks.
 *
 * Sequence on attack (player must be sprinting):
 *   t = attackTime
 *   t + delayBeforeStop  → send STOP_SPRINTING
 *   t + delayBeforeStop
 *     + stopDuration      → send START_SPRINTING, setSprinting(true)
 *
 * The player continues moving forward the entire time.
 * Only packets are sent — movement input is never interrupted.
 */
public class NoStop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Settings ──────────────────────────────────────────────────────────

    /**
     * How long after the attack to send STOP_SPRINTING.
     * 0 ms = send immediately on the tick the attack is detected.
     */
    public final IntProperty delayBeforeStop;

    /**
     * How long to hold the stopped state before sending START_SPRINTING.
     * Must always be > 0 so STOP and START are never in the same tick.
     */
    public final IntProperty stopDuration;

    // ── State ─────────────────────────────────────────────────────────────

    /** Wall-clock ms when the triggering attack was detected. -1 = inactive. */
    private long attackTime = -1L;

    /** True once STOP_SPRINTING has been sent in the current sequence. */
    private boolean sentStop  = false;

    /** True once START_SPRINTING has been sent in the current sequence. */
    private boolean sentStart = false;

    // ── Constructor ───────────────────────────────────────────────────────

    public NoStop() {
        super("NoStop", false);
        this.delayBeforeStop = new IntProperty("delay-before-stop",   0,  0, 200);
        this.stopDuration    = new IntProperty("stop-duration",       60, 50, 300);
    }

    // ── Attack trigger ────────────────────────────────────────────────────

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null) return;
        if (!mc.thePlayer.isSprinting()) return;

        // Begin a new reset sequence from this moment
        this.attackTime = System.currentTimeMillis();
        this.sentStop   = false;
        this.sentStart  = false;
    }

    // ── Tick handler ──────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) return;
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;

        // No active sequence
        if (this.attackTime < 0L) return;

        // Safety: if the player stopped sprinting manually, cancel the sequence
        if (!mc.thePlayer.isSprinting() && !this.sentStop) {
            this.reset();
            return;
        }

        long now     = System.currentTimeMillis();
        long elapsed = now - this.attackTime;
        int  delay   = this.delayBeforeStop.getValue();
        int  stop    = this.stopDuration.getValue();

        // Step 1: send STOP_SPRINTING after delayBeforeStop
        if (!this.sentStop && elapsed >= delay) {
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C0BPacketEntityAction(mc.thePlayer,
                            C0BPacketEntityAction.Action.STOP_SPRINTING));
            this.sentStop = true;
        }

        // Step 2: send START_SPRINTING after delayBeforeStop + stopDuration
        if (this.sentStop && !this.sentStart && elapsed >= delay + stop) {
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C0BPacketEntityAction(mc.thePlayer,
                            C0BPacketEntityAction.Action.START_SPRINTING));
            mc.thePlayer.setSprinting(true);
            this.sentStart = true;
            this.reset();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void reset() {
        this.attackTime = -1L;
        this.sentStop   = false;
        this.sentStart  = false;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override public void onEnabled()  { this.reset(); }
    @Override public void onDisabled() { this.reset(); }

    @Override
    public String[] getSuffix() {
        return new String[]{
            this.delayBeforeStop.getValue() + "ms+" + this.stopDuration.getValue() + "ms"
        };
    }
}
