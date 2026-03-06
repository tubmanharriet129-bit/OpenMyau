package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MSTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.*;

/**
 * OutboundBackTrack — delays your own outgoing packets (movement + attacks)
 * so the server processes them against the target's lagged position.
 *
 * How it works
 * ─────────────
 * Incoming packets (server → client) pass through untouched, so entities
 * move naturally on your screen. You see everything in real time.
 *
 * Outgoing packets (client → server) are queued for `delay` ms before being
 * sent. From the server's perspective you appear to be at a slightly older
 * position, identical to having high ping. Your attacks therefore register
 * on where the target *was*, which on Hypixel's prediction AC is processed
 * as a legitimate lagged hit rather than a reach violation.
 *
 * maxHurtTime gating
 * ──────────────────
 * Continuously delaying every tick looks mechanical. maxHurtTime lets you
 * gate the lag so it only runs while the target still has iFrames remaining
 * (hurtResistantTime > 0) — the moment they are hittable the queue flushes,
 * landing your delayed packets exactly when damage can register. Set it to
 * 500 (max) for constant lag, or lower for burst-on-cooldown behaviour.
 *
 * Range management
 * ─────────────────
 * When the target exits maxRange, or KillAura drops the target, the queue
 * is flushed immediately so your own position never desyncs server-side.
 *
 * Attack-only mode
 * ─────────────────
 * Setting delayMovement=false only delays C02PacketUseEntity (swing/attack)
 * packets — movement is sent instantly. This is safer on anti-cheats that
 * watch for movement inconsistencies while still giving the double-hit
 * window benefit on attacks.
 */
public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ─────────────────────────────────────────────────────────────
    // Properties
    // ─────────────────────────────────────────────────────────────

    /** Artificial lag in ms to add to outgoing packets. */
    public final IntProperty delay =
            new IntProperty("delay", 50, 10, 300);

    /**
     * Maximum hurtResistantTime (ms) the target may have before the lag
     * is applied. When hurtTime > this value, packets are sent immediately.
     *
     * 500 = always lag (smooth constant delay).
     * 200 = only lag when the target has < 4 ticks of iFrames left
     *        — produces a short burst of lag right before they're hittable,
     *          maximising double-hit chance while looking natural otherwise.
     */
    public final IntProperty maxHurtTime =
            new IntProperty("max-hurt-time", 500, 0, 500);

    /** Distance below which BackTrack activates. */
    public final FloatProperty maxRange =
            new FloatProperty("max-range", 4.0f, 2.0f, 10.0f);

    /** Distance below which BackTrack deactivates (target already close). */
    public final FloatProperty minRange =
            new FloatProperty("min-range", 2.0f, 0.0f, 6.0f);

    /** Also delay C03PacketPlayer (movement). false = attacks only. */
    public final BooleanProperty delayMovement =
            new BooleanProperty("delay-movement", true);

    /** Immediately flush queue if the player receives knockback. */
    public final BooleanProperty flushOnHit =
            new BooleanProperty("flush-on-hit", true);

    /** Safety cap: flush everything after this many ms regardless. */
    public final IntProperty maxDelay =
            new IntProperty("max-delay-cap", 400, 50, 1000);

    /** Render server-side hitbox of target while lagging. */
    public final BooleanProperty esp =
            new BooleanProperty("esp", true);

    public final ModeProperty espMode =
            new ModeProperty("esp-mode", 0, new String[]{"HITBOX", "NONE"});

    // ─────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────

    /**
     * Queue of outgoing packets waiting to be released.
     * Each entry pairs the packet with the System.currentTimeMillis()
     * timestamp at which it was intercepted.
     */
    private final Queue<TimedPacket> outboundQueue = new LinkedList<>();

    /**
     * Tracks the interpolated server-side position of every entity by
     * accumulating incoming relative-move and teleport deltas.
     * Used purely for the ESP overlay and shouldActivate() check.
     */
    private final Map<Integer, Vec3> serverPositions = new HashMap<>();

    /** How long the current hold has been active (safety cap enforcement). */
    private final MSTimer holdTimer = new MSTimer();

    private boolean holding = false;
    private EntityLivingBase target = null;
    private KillAura killAura = null;

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    public BackTrack() {
        super("BackTrack", false);
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onEnabled() {
        killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        reset();
    }

    @Override
    public void onDisabled() {
        flush();
        reset();
    }

    private void reset() {
        outboundQueue.clear();
        serverPositions.clear();
        holding = false;
        target = null;
        holdTimer.reset();
    }

    // ─────────────────────────────────────────────────────────────
    // Packet interception
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (killAura == null) return;

        // ── Incoming packets → track server-side positions (never blocked) ──

        if (event.getType() == EventType.RECEIVE) {
            Packet<?> pkt = event.getPacket();

            if (pkt instanceof S14PacketEntity) {
                S14PacketEntity p = (S14PacketEntity) pkt;
                Entity e = p.getEntity(mc.theWorld);
                if (e != null) {
                    int id = e.getEntityId();
                    Vec3 cur = serverPositions.getOrDefault(id,
                            new Vec3(e.posX, e.posY, e.posZ));
                    serverPositions.put(id, cur.addVector(
                            p.func_149062_c() / 32.0,
                            p.func_149061_d() / 32.0,
                            p.func_149064_e() / 32.0));
                }
            } else if (pkt instanceof S18PacketEntityTeleport) {
                S18PacketEntityTeleport p = (S18PacketEntityTeleport) pkt;
                serverPositions.put(p.getEntityId(),
                        new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0));
            } else if (pkt instanceof S0FPacketSpawnMob) {
                S0FPacketSpawnMob p = (S0FPacketSpawnMob) pkt;
                serverPositions.put(p.getEntityID(),
                        new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0));
            }
            return; // never cancel incoming packets
        }

        // ── Outgoing packets → queue if holding ──

        if (event.getType() != EventType.SEND) return;
        if (!holding) return;

        Packet<?> pkt = event.getPacket();
        boolean isMovement = pkt instanceof C03PacketPlayer;
        boolean isAttack   = pkt instanceof C02PacketUseEntity;

        if (isAttack || (isMovement && delayMovement.getValue())) {
            outboundQueue.add(new TimedPacket(pkt, System.currentTimeMillis()));
            event.setCancelled(true);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Main tick logic
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled() || mc.thePlayer == null || killAura == null) return;
        if (event.getType() != EventType.PRE) return;

        EntityLivingBase newTarget = killAura.getTarget();

        // Target changed — flush everything for the old target.
        if (newTarget != target) {
            if (holding) stopHolding();
            target = newTarget;
        }

        if (target == null) {
            if (holding) stopHolding();
            return;
        }

        // Safety cap — never hold longer than maxDelayCap.
        if (holding && holdTimer.hasTimePassed(maxDelay.getValue())) {
            stopHolding();
            return;
        }

        // Flush on hit (player receives knockback).
        if (flushOnHit.getValue()
                && mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime
                && mc.thePlayer.maxHurtTime > 0) {
            if (holding) stopHolding();
            return;
        }

        // Decide whether to hold this tick.
        if (shouldHold()) {
            if (!holding) {
                holding = true;
                holdTimer.reset();
            }
        } else {
            if (holding) stopHolding();
        }

        // Release packets whose delay has expired.
        if (holding) drainExpired();
    }

    // ─────────────────────────────────────────────────────────────
    // Activation condition
    // ─────────────────────────────────────────────────────────────

    /**
     * Hold outbound packets when:
     *   1. Target is within [minRange, maxRange].
     *   2. The target's iFrames are within our maxHurtTime gate
     *      (so we only lag when a hit will count soon).
     */
    private boolean shouldHold() {
        if (target == null) return false;

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < minRange.getValue()) return false;
        if (dist > maxRange.getValue()) return false;

        // hurtResistantTime in ticks → ms (50 ms/tick).
        int hurtTimeMs = target.hurtResistantTime * 50;
        if (hurtTimeMs > maxHurtTime.getValue()) return false;

        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // Queue management
    // ─────────────────────────────────────────────────────────────

    /**
     * Drain any queued packets whose queued timestamp + delay has passed.
     * This creates the authentic "high ping" cadence — packets emerge
     * individually at the correct delayed time rather than all at once.
     */
    private void drainExpired() {
        long now = System.currentTimeMillis();
        int delayMs = delay.getValue();

        while (!outboundQueue.isEmpty()) {
            TimedPacket head = outboundQueue.peek();
            if (now - head.timestamp >= delayMs) {
                outboundQueue.poll();
                sendImmediate(head.packet);
            } else {
                break; // queue is FIFO; if head isn't ready, nothing else is
            }
        }
    }

    /** Flush all held packets immediately (no delay). */
    private void flush() {
        while (!outboundQueue.isEmpty()) {
            sendImmediate(outboundQueue.poll().packet);
        }
    }

    private void stopHolding() {
        flush();
        holding = false;
    }

    /**
     * Send a packet directly to the server, bypassing our packet event
     * so it isn't intercepted and re-queued.
     */
    private void sendImmediate(Packet<?> packet) {
        if (mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
            mc.getNetHandler().getNetworkManager().sendPacket(packet);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ESP — draws the server-side (real) hitbox while lagging
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!esp.getValue() || espMode.getValue() != 0) return;
        if (target == null || !holding) return;

        Vec3 real = serverPositions.get(target.getEntityId());
        if (real == null) return;

        double rx = mc.getRenderManager().viewerPosX;
        double ry = mc.getRenderManager().viewerPosY;
        double rz = mc.getRenderManager().viewerPosZ;

        double x = real.xCoord - rx;
        double y = real.yCoord - ry;
        double z = real.zCoord - rz;

        double hw = target.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(
                x - hw, y, z - hw,
                x + hw, y + target.height, z + hw
        );

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);

        RenderGlobal.drawOutlinedBoundingBox(box, 255, 80, 0, 180);

        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    // ─────────────────────────────────────────────────────────────
    // TimedPacket helper
    // ─────────────────────────────────────────────────────────────

    /** Pairs an outgoing packet with the wall-clock time it was captured. */
    private static final class TimedPacket {
        final Packet<?> packet;
        final long timestamp;

        TimedPacket(Packet<?> packet, long timestamp) {
            this.packet    = packet;
            this.timestamp = timestamp;
        }
    }
}
