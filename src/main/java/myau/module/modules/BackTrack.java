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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

/**
 * BackTrack — inbound packet delay.
 *
 * Holds incoming position/move packets for the KillAura target so the
 * entity appears frozen at a hittable position on your screen while the
 * server has already moved them. Your own outgoing packets are NEVER
 * touched, so the server always has your real position — no rubberbanding,
 * no teleport-back, no position correction from Hypixel.
 *
 * Outbound approach caused teleport-back because the server's position
 * authority overwrote the delayed movement data. Inbound is immune to
 * this: only the client-side render is affected, the server is the
 * ground truth for your own movement at all times.
 *
 * Stability safeguards
 * ─────────────────────
 * 1. Drift cap: if the real server-side position diverges more than
 *    driftCap blocks from the frozen client position, we begin a smooth
 *    release immediately — prevents a large sudden snap.
 * 2. Smooth release: on deactivation, held packets are released one per
 *    tick rather than all at once, so the entity glides to its real
 *    position instead of teleporting.
 * 3. Queue size cap: limits how many packets accumulate, bounding the
 *    maximum possible snap distance even if smooth release is bypassed.
 * 4. Velocity pass-through: S12PacketEntityVelocity for the target is
 *    never held — only position packets are intercepted.
 * 5. Pre-activation: the hold starts preActivate ms before iFrames
 *    expire so the frozen hitbox is in place exactly when the damage
 *    window opens.
 */
public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random RNG   = new Random();

    // ─────────────────────────────────────────────────────────────
    // Properties
    // ─────────────────────────────────────────────────────────────

    /**
     * How long (ms) to hold incoming position packets before releasing.
     * Effectively sets how "old" the frozen hitbox is.
     * Higher = entity stays frozen longer = more hit window, but a
     * larger snap on release. 50-100ms is the sweet spot for BedWars.
     */
    public final IntProperty delay =
            new IntProperty("delay", 60, 10, 300);

    /**
     * ±ms of random variance applied to the hold duration each cycle.
     * Breaks the mechanical fixed-interval pattern that Watchdog can
     * fingerprint. 8-12ms is enough without feeling uneven.
     */
    public final IntProperty jitter =
            new IntProperty("jitter", 10, 0, 30);

    /**
     * Maximum hurtResistantTime (ms) for the hold to apply.
     * 500 = always hold. 150-200 = only hold near the damage window.
     */
    public final IntProperty maxHurtTime =
            new IntProperty("max-hurt-time", 500, 0, 500);

    /**
     * Activate the hold this many ms BEFORE the iFrame window closes,
     * accounting for the delay value. Set equal to delay for the frozen
     * hitbox to be in place exactly when the target becomes hittable.
     */
    public final IntProperty preActivate =
            new IntProperty("pre-activate", 60, 0, 200);

    /** Maximum distance at which BackTrack may hold. */
    public final FloatProperty maxRange =
            new FloatProperty("max-range", 4.0f, 2.0f, 10.0f);

    /** Minimum distance — no point holding a target already in melee range. */
    public final FloatProperty minRange =
            new FloatProperty("min-range", 2.0f, 0.0f, 6.0f);

    /**
     * Maximum blocks the real server position may drift from the frozen
     * client position before a smooth release is forced.
     * Prevents large entity snaps that look suspicious and feel bad.
     * 2.5-3.0 is safe; lower = more frequent early releases.
     */
    public final FloatProperty driftCap =
            new FloatProperty("drift-cap", 2.5f, 0.5f, 6.0f);

    /**
     * Maximum number of position packets to hold in the queue.
     * When reached, the oldest packet is released before adding the new one.
     * Caps the maximum snap distance independently of drift-cap.
     */
    public final IntProperty maxQueueSize =
            new IntProperty("max-queue-size", 8, 2, 24);

    /**
     * Release held packets one-per-tick on deactivation instead of all
     * at once. Eliminates the sudden teleport snap on release.
     * Disable only if you need instant re-sync (e.g. target dies).
     */
    public final BooleanProperty smoothRelease =
            new BooleanProperty("smooth-release", true);

    /** Flush immediately if the player takes knockback. */
    public final BooleanProperty flushOnHit =
            new BooleanProperty("flush-on-hit", true);

    /** Hard flush everything after this many ms regardless of conditions. */
    public final IntProperty maxDelayCap =
            new IntProperty("max-delay-cap", 400, 50, 1000);

    public final BooleanProperty esp =
            new BooleanProperty("esp", true);

    public final ModeProperty espMode =
            new ModeProperty("esp-mode", 0, new String[]{"HITBOX", "NONE"});

    // ─────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────

    /** Intercepted inbound position packets for the current target. */
    private final Queue<Packet<?>> heldPackets = new LinkedList<>();

    /**
     * Interpolated real server-side position of every entity, built by
     * accumulating relative-move and teleport deltas as they arrive.
     * Updated BEFORE deciding whether to hold the packet, so the drift
     * check always uses the freshest known real position.
     */
    private final Map<Integer, Vec3> serverPositions = new HashMap<>();

    /**
     * Whether we are currently in smooth-release drain mode.
     * In this state we stop intercepting new packets for the target and
     * release one held packet per tick until the queue is empty.
     */
    private boolean draining = false;

    /** Jittered hold duration for the current active window (ms). */
    private int currentDelay = 60;

    private long holdStart  = -1L;
    private boolean holding = false;

    /**
     * Previous hurtResistantTime for the player, used to detect the
     * exact tick a hit registers one frame earlier than hurtTime checks.
     */
    private int lastPlayerHurtResistant = 0;

    private EntityLivingBase target = null;
    private KillAura killAura       = null;

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
        // Hard flush on manual disable so no packets linger.
        hardFlush();
        reset();
    }

    private void reset() {
        heldPackets.clear();
        serverPositions.clear();
        holding  = false;
        draining = false;
        holdStart = -1L;
        currentDelay = delay.getValue();
        lastPlayerHurtResistant = 0;
        target   = null;
    }

    // ─────────────────────────────────────────────────────────────
    // Packet interception — INBOUND ONLY
    // Outgoing packets are never touched. The server always has the
    // player's real position; rubberbanding is impossible.
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (killAura == null) return;
        if (event.getType() != EventType.RECEIVE) return;

        Packet<?> pkt = event.getPacket();

        // ── Step 1: always update our server-position tracker ──
        // We update BEFORE checking whether to hold the packet so
        // serverPositions always reflects the real world state and
        // drift-cap can fire on this very packet if needed.
        updateServerPosition(pkt);

        // ── Step 2: intercept position packets for the active target ──
        if (!holding && !draining) return;
        if (target == null) return;
        if (!isPositionPacketForTarget(pkt, target.getEntityId())) return;

        // Velocity packets are NEVER held — only positions.
        // (S12 is already excluded by isPositionPacketForTarget, but
        //  this comment is intentional documentation.)

        if (draining) {
            // During drain we let new incoming packets through unblocked
            // so the entity continues updating to its real position.
            return;
        }

        // ── Step 3: drift safeguard ──
        // If the real position has already moved more than driftCap blocks
        // from the frozen client position, holding further would cause a
        // large snap on release. Begin smooth drain instead.
        if (driftExceeded()) {
            beginDrain();
            return; // let this packet through as the drain starts
        }

        // ── Step 4: queue cap ──
        // Force-release the oldest packet before adding a new one if full.
        // This bounds the maximum snap distance independently of drift-cap.
        if (heldPackets.size() >= maxQueueSize.getValue()) {
            Packet<?> oldest = heldPackets.poll();
            if (oldest != null) oldest.processPacket(mc.getNetHandler());
        }

        heldPackets.add(pkt);
        event.setCancelled(true);
    }

    // ─────────────────────────────────────────────────────────────
    // Main tick logic
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled() || mc.thePlayer == null || killAura == null) return;
        if (event.getType() != EventType.PRE) return;

        EntityLivingBase newTarget = killAura.getTarget();

        // ── Early knockback detection ──
        // Detect the tick the player's hurtResistantTime jumps back to
        // maxHurtTime (= the tick we were hit). This fires one tick before
        // the hurtTime == maxHurtTime check, ensuring flush happens before
        // knockback trajectory is processed by the server.
        int curHurt = mc.thePlayer.hurtResistantTime;
        boolean tookHit = flushOnHit.getValue()
                && curHurt > lastPlayerHurtResistant
                && curHurt == mc.thePlayer.maxHurtTime
                && mc.thePlayer.maxHurtTime > 0;
        lastPlayerHurtResistant = curHurt;

        if (tookHit) {
            hardFlush();
            holding  = false;
            draining = false;
            holdStart = -1L;
            // Don't return — still need to update target below.
        }

        // Target changed — drain for old target, reset for new.
        if (newTarget != target) {
            if (holding || draining) beginDrain();
            target = newTarget;
        }

        // ── Smooth drain tick ──
        // If we're draining, release one packet per tick and wait for the
        // queue to empty. This makes the entity glide to its real position
        // rather than snap, and avoids a detectable burst of position updates.
        if (draining) {
            if (!heldPackets.isEmpty()) {
                if (smoothRelease.getValue()) {
                    // One packet per tick = smooth glide.
                    releaseOne();
                } else {
                    hardFlush();
                }
            }
            if (heldPackets.isEmpty()) {
                draining = false;
                holding  = false;
                holdStart = -1L;
            }
            return;
        }

        if (target == null) return;

        // Hard cap — been holding too long, force smooth drain.
        if (holding && holdStart != -1L
                && (System.currentTimeMillis() - holdStart) > maxDelayCap.getValue()) {
            beginDrain();
            return;
        }

        // ── Activation / deactivation ──
        if (shouldHold()) {
            if (!holding) {
                holding = true;
                holdStart = System.currentTimeMillis();
                // Assign a jittered delay for this hold window.
                int j = jitter.getValue();
                currentDelay = delay.getValue()
                        + (j > 0 ? (RNG.nextInt(j * 2 + 1) - j) : 0);
                currentDelay = Math.max(10, currentDelay);
            } else if (System.currentTimeMillis() - holdStart >= currentDelay) {
                // The hold window has elapsed — release a packet this tick
                // so the client position ticks forward gradually.
                if (!heldPackets.isEmpty()) releaseOne();
                // Reset the window with a fresh jittered delay.
                holdStart = System.currentTimeMillis();
                int j = jitter.getValue();
                currentDelay = delay.getValue()
                        + (j > 0 ? (RNG.nextInt(j * 2 + 1) - j) : 0);
                currentDelay = Math.max(10, currentDelay);
            }
        } else {
            if (holding) beginDrain();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Activation condition
    // ─────────────────────────────────────────────────────────────

    private boolean shouldHold() {
        if (target == null) return false;

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < minRange.getValue()) return false;
        if (dist > maxRange.getValue()) return false;

        // Activate preActivate ms before iFrames expire so the frozen
        // hitbox is ready when the damage window opens.
        int hurtTimeMs = target.hurtResistantTime * 50;
        int threshold  = maxHurtTime.getValue() + preActivate.getValue();
        if (hurtTimeMs > threshold) return false;

        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // Drift safeguard
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns true if the real server-side position of the target has
     * moved more than driftCap blocks away from the client-visible
     * (frozen) position. When true, continuing to hold would produce a
     * large snap on release, so we begin a smooth drain instead.
     */
    private boolean driftExceeded() {
        if (target == null) return false;
        Vec3 real = serverPositions.get(target.getEntityId());
        if (real == null) return false;

        // Client-visible position = target.posX/Y/Z (frozen because we held
        // the update packets — this is the last position the client applied).
        double dx = real.xCoord - target.posX;
        double dy = real.yCoord - target.posY;
        double dz = real.zCoord - target.posZ;
        double drift = Math.sqrt(dx * dx + dy * dy + dz * dz);

        return drift > driftCap.getValue();
    }

    // ─────────────────────────────────────────────────────────────
    // Release helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Begin smooth drain: stop intercepting new packets for the target
     * and release existing held packets one-per-tick.
     */
    private void beginDrain() {
        draining = true;
        // holding stays true until drain completes so ESP persists.
    }

    /** Release one held packet to the client (one tick of smooth glide). */
    private void releaseOne() {
        if (mc.getNetHandler() == null) {
            heldPackets.clear();
            return;
        }
        Packet<?> pkt = heldPackets.poll();
        if (pkt != null) pkt.processPacket(mc.getNetHandler());
    }

    /** Release all held packets immediately with no delay. */
    private void hardFlush() {
        if (mc.getNetHandler() == null) {
            heldPackets.clear();
            return;
        }
        while (!heldPackets.isEmpty()) {
            heldPackets.poll().processPacket(mc.getNetHandler());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Server position tracker
    // ─────────────────────────────────────────────────────────────

    /**
     * Accumulates real server-side positions from every incoming position
     * packet regardless of whether we hold it. This is what allows the
     * drift cap to know the true entity location even while packets are held.
     */
    private void updateServerPosition(Packet<?> pkt) {
        if (pkt instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) pkt;
            Entity e = p.getEntity(mc.theWorld);
            if (e != null) {
                int id  = e.getEntityId();
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
    }

    // ─────────────────────────────────────────────────────────────
    // Position packet filter
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns true for packets that carry position data for the given
     * entity ID. Velocity (S12), health (S06), and all other packet
     * types pass through unaffected.
     */
    private boolean isPositionPacketForTarget(Packet<?> pkt, int entityId) {
        if (pkt instanceof S14PacketEntity) {
            Entity e = ((S14PacketEntity) pkt).getEntity(mc.theWorld);
            return e != null && e.getEntityId() == entityId;
        }
        if (pkt instanceof S18PacketEntityTeleport) {
            return ((S18PacketEntityTeleport) pkt).getEntityId() == entityId;
        }
        if (pkt instanceof S19PacketEntityHeadLook) {
            Entity e = mc.theWorld.getEntityByID(
                    ((S19PacketEntityHeadLook) pkt).getEntityId());
            return e != null && e.getEntityId() == entityId;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // ESP — draws the real server-side hitbox while holding
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!esp.getValue() || espMode.getValue() != 0) return;
        if (target == null || (!holding && !draining)) return;

        Vec3 real = serverPositions.get(target.getEntityId());
        if (real == null) return;

        double x = real.xCoord - mc.getRenderManager().viewerPosX;
        double y = real.yCoord - mc.getRenderManager().viewerPosY;
        double z = real.zCoord - mc.getRenderManager().viewerPosZ;

        double hw = target.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(
                x - hw, y,                  z - hw,
                x + hw, y + target.height,  z + hw
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
}
