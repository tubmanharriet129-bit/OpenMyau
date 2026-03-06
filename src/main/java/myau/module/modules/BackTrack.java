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
import net.minecraft.network.INetHandler;
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
 * BackTrack — inbound packet hold, tightly integrated with KillAura.
 *
 * KILLAURA INTEGRATION
 * ─────────────────────
 * Mid Trade + BackTrack form a two-layer system:
 *
 *   Layer 1 — BackTrack holds incoming position packets, freezing the
 *   entity at a hittable position on your screen while the server has
 *   already moved them.
 *
 *   Layer 2 — Mid Trade suppresses outgoing clicks while the target
 *   still has iFrames, so no wasted hits land during the invincibility
 *   window.
 *
 * The two layers must fire on the SAME tick to be useful together:
 *
 *   • BackTrack holds during the entire iFrame window (while Mid Trade
 *     is also suppressing). The entity stays frozen — no position snap
 *     mid-fight that could confuse KillAura's ray-trace.
 *
 *   • When hurtResistantTime hits 0, BOTH systems release simultaneously:
 *     Mid Trade lifts its suppression, BackTrack drains its queue, and
 *     KillAura fires its next hit against the freshly-updated position.
 *
 *   • On a rapid target switch (within midTradeResetWindow ms), BackTrack
 *     hard-releases instead of draining, matching KillAura's behaviour of
 *     immediately clearing midTradePauseUntil for the new target.
 *
 *   • preActivate is automatically derived from the delay setting —
 *     no separate slider needed. The queue activates exactly `delay` ms
 *     before iFrames expire so the entity is already frozen when
 *     KillAura's first hit of the next window lands.
 */
public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random RNG   = new Random();

    // ─────────────────────────────────────────────────────────────
    // Properties
    // ─────────────────────────────────────────────────────────────

    /**
     * Base ms to hold incoming position packets.
     * Also used as the automatic pre-activation window — the hold
     * starts `delay` ms before iFrames expire so the freeze is already
     * in effect when KillAura fires.
     */
    public final IntProperty delay =
            new IntProperty("delay", 100, 10, 400);

    /** ± ms of random variance per packet. Breaks timing fingerprints. */
    public final IntProperty jitter =
            new IntProperty("jitter", 10, 0, 40);

    /**
     * Maximum positional drift (blocks) allowed between the held server
     * position and the client-visible entity position before the queue
     * is force-released. Primary anti-teleport-back safeguard.
     */
    public final FloatProperty maxDivergence =
            new FloatProperty("max-divergence", 2.5f, 0.5f, 8.0f);

    /**
     * Maximum hurtResistantTime (ms) for the hold to activate when Mid
     * Trade is DISABLED. When Mid Trade IS enabled this is ignored —
     * the hold window is derived entirely from the iFrame state so that
     * the two systems stay in sync.
     */
    public final IntProperty maxHurtTime =
            new IntProperty("max-hurt-time", 500, 0, 500);

    public final FloatProperty maxRange =
            new FloatProperty("max-range", 4.0f, 2.0f, 10.0f);

    public final FloatProperty minRange =
            new FloatProperty("min-range", 2.0f, 0.0f, 6.0f);

    /** Maximum held packets before oldest is force-processed. */
    public final IntProperty maxQueueSize =
            new IntProperty("max-queue-size", 8, 2, 24);

    /**
     * Drain the queue gradually on deactivation rather than all at once.
     * Disabled automatically during rapid target switches so the new
     * target's position updates immediately.
     */
    public final BooleanProperty smoothRelease =
            new BooleanProperty("smooth-release", true);

    /** Hard-release if the player takes knockback. */
    public final BooleanProperty releaseOnHit =
            new BooleanProperty("release-on-hit", true);

    /** Absolute safety cap: hard-release after this many ms regardless. */
    public final IntProperty maxDelayCap =
            new IntProperty("max-delay-cap", 500, 50, 1200);

    public final BooleanProperty esp =
            new BooleanProperty("esp", true);

    public final ModeProperty espMode =
            new ModeProperty("esp-mode", 0, new String[]{"HITBOX", "NONE"});

    // ─────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────

    private final Queue<TimedPacket>   heldPackets     = new LinkedList<>();
    private final Map<Integer, Vec3>   serverPositions = new HashMap<>();

    private boolean releasing = false;
    private boolean draining  = false;
    private boolean holding   = false;
    private long    holdStart = -1L;

    private int  lastHurtResistantTime = 0;
    /** Last hurtResistantTime of the TARGET — used to detect iFrame expiry. */
    private int  lastTargetHurtTime    = 0;

    private EntityLivingBase target   = null;
    private KillAura          killAura = null;

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
        hardRelease();
        reset();
    }

    private void reset() {
        heldPackets.clear();
        serverPositions.clear();
        holding   = false;
        releasing = false;
        draining  = false;
        holdStart = -1L;
        lastHurtResistantTime = 0;
        lastTargetHurtTime    = 0;
        target    = null;
    }

    // ─────────────────────────────────────────────────────────────
    // processPacket generic helper
    // Packet<?> wildcard cannot be passed NetHandlerPlayClient
    // directly — the unchecked cast is safe because all S** packets
    // only ever accept NetHandlerPlayClient at runtime.
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T extends INetHandler> void process(Packet<T> packet) {
        if (mc.getNetHandler() == null) return;
        packet.processPacket((T) mc.getNetHandler());
    }

    // ─────────────────────────────────────────────────────────────
    // Packet interception — INBOUND ONLY
    // Outgoing packets are never touched — no teleport-backs possible.
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (killAura == null) return;
        if (event.getType() != EventType.RECEIVE) return;
        if (releasing) return;

        Packet<?> pkt = event.getPacket();

        // Always track server-side positions, even for held packets,
        // so divergence checks reflect the true server state.
        updateServerPosition(pkt);

        // Never hold velocity — desyncs client physics, causes snapping.
        if (pkt instanceof S12PacketEntityVelocity) return;

        if (target == null) return;
        if (!isTargetPositionPacket(pkt, target.getEntityId())) return;
        if (draining) return;
        if (!holding) return;

        // Divergence cap: if server position has already drifted too far,
        // release now rather than queuing a packet that would cause a
        // large visible snap on release.
        Vec3 serverPos = serverPositions.get(target.getEntityId());
        if (serverPos != null) {
            double div = target.getDistance(
                    serverPos.xCoord, serverPos.yCoord, serverPos.zCoord);
            if (div > maxDivergence.getValue()) {
                hardRelease();
                holding   = false;
                holdStart = -1L;
                return;
            }
        }

        // Queue size cap — force oldest out before adding new.
        if (heldPackets.size() >= maxQueueSize.getValue()) {
            TimedPacket oldest = heldPackets.poll();
            if (oldest != null) {
                releasing = true;
                try   { process(oldest.packet); }
                finally { releasing = false; }
            }
        }

        int j = jitter.getValue();
        int packetDelay = delay.getValue()
                + (j > 0 ? (RNG.nextInt(j * 2 + 1) - j) : 0);
        packetDelay = Math.max(10, packetDelay);

        heldPackets.add(new TimedPacket(pkt, System.currentTimeMillis(), packetDelay));
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

        // ── Target changed ──
        if (newTarget != target) {
            if (holding) {
                // Check if this is a rapid switch within KillAura's
                // midTradeResetWindow. If so, hard-release immediately
                // so the new target's position is current from tick 1,
                // matching KillAura's own rapid-switch bypass behaviour.
                long msSinceSwitch = System.currentTimeMillis() - killAura.getLastTargetSwitchTime();
                int resetWindow    = killAura.midTradeResetWindow.getValue();
                boolean rapidSwitch = resetWindow > 0 && msSinceSwitch <= resetWindow;

                if (rapidSwitch) {
                    hardRelease();
                    holding   = false;
                    holdStart = -1L;
                } else {
                    beginDrain();
                }
            }
            target             = newTarget;
            lastTargetHurtTime = 0;
        }

        // Smooth drain in progress.
        if (draining) {
            drainExpired();
            if (heldPackets.isEmpty()) {
                draining  = false;
                holding   = false;
                holdStart = -1L;
            }
            return;
        }

        if (target == null) {
            if (holding) beginDrain();
            return;
        }

        // Safety cap.
        if (holding && holdStart != -1L
                && (System.currentTimeMillis() - holdStart) > maxDelayCap.getValue()) {
            beginDrain();
            return;
        }

        // ── Player knockback detection ──
        int currentHurt = mc.thePlayer.hurtResistantTime;
        boolean tookHit = releaseOnHit.getValue()
                && currentHurt > lastHurtResistantTime
                && currentHurt == mc.thePlayer.maxHurtTime
                && mc.thePlayer.maxHurtTime > 0;
        lastHurtResistantTime = currentHurt;

        if (tookHit) {
            hardRelease();
            holding   = false;
            holdStart = -1L;
            return;
        }

        // ── Mid Trade synchronisation ──
        // When Mid Trade is active we track the TARGET's iFrame state
        // directly. The moment hurtResistantTime hits 0 both Mid Trade
        // and BackTrack release on the same tick:
        //   - Mid Trade: isMidTradePaused() returns false → KillAura fires
        //   - BackTrack: begins drain → entity position updates
        // This ensures KillAura's ray-trace fires against the real position
        // the instant it's allowed to attack.
        int targetHurtNow = target.hurtResistantTime;
        boolean midTradeActive = killAura.midTrade.getValue() > 0;

        if (midTradeActive && holding) {
            boolean iFramesJustExpired = lastTargetHurtTime > 0 && targetHurtNow == 0;
            if (iFramesJustExpired) {
                // iFrames hit 0 this tick — release in sync with Mid Trade.
                beginDrain();
                lastTargetHurtTime = targetHurtNow;
                return;
            }
        }
        lastTargetHurtTime = targetHurtNow;

        // Per-tick divergence check.
        if (holding && target != null) {
            Vec3 serverPos = serverPositions.get(target.getEntityId());
            if (serverPos != null) {
                double div = target.getDistance(
                        serverPos.xCoord, serverPos.yCoord, serverPos.zCoord);
                if (div > maxDivergence.getValue()) {
                    hardRelease();
                    holding   = false;
                    holdStart = -1L;
                    return;
                }
            }
        }

        if (shouldHold()) {
            if (!holding) {
                holding   = true;
                holdStart = System.currentTimeMillis();
            }
        } else {
            if (holding) beginDrain();
        }

        if (holding) drainExpired();
    }

    // ─────────────────────────────────────────────────────────────
    // Activation condition
    // ─────────────────────────────────────────────────────────────

    private boolean shouldHold() {
        if (target == null) return false;

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < minRange.getValue()) return false;
        if (dist > maxRange.getValue()) return false;

        int hurtTimeMs = target.hurtResistantTime * 50;

        if (killAura.midTrade.getValue() > 0) {
            // Mid Trade is active — hold whenever the target HAS iFrames.
            // This keeps the entity frozen exactly during the window when
            // KillAura is suppressing clicks anyway, so there's no visible
            // snap mid-suppression. We also pre-activate by `delay` ms so
            // the freeze is in place before the next attackable window.
            int preActivateMs = delay.getValue();
            int threshold     = killAura.midTrade.getValue() + preActivateMs;
            return hurtTimeMs <= threshold;
        } else {
            // Mid Trade off — use the standalone maxHurtTime slider.
            return hurtTimeMs <= maxHurtTime.getValue();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Release logic
    // ─────────────────────────────────────────────────────────────

    private void drainExpired() {
        long now = System.currentTimeMillis();

        ArrayList<TimedPacket> toRelease = new ArrayList<>();
        while (!heldPackets.isEmpty()) {
            TimedPacket head = heldPackets.peek();
            if (now - head.timestamp >= head.assignedDelay) {
                toRelease.add(heldPackets.poll());
            } else {
                break;
            }
        }

        if (toRelease.isEmpty()) return;

        releasing = true;
        try {
            for (TimedPacket tp : toRelease) process(tp.packet);
        } finally {
            releasing = false;
        }
    }

    private void beginDrain() {
        if (smoothRelease.getValue() && !heldPackets.isEmpty()) {
            draining = true;
        } else {
            hardRelease();
            holding   = false;
            holdStart = -1L;
        }
    }

    private void hardRelease() {
        ArrayList<TimedPacket> toRelease = new ArrayList<>(heldPackets);
        heldPackets.clear();
        draining = false;

        releasing = true;
        try {
            for (TimedPacket tp : toRelease) process(tp.packet);
        } finally {
            releasing = false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Server position tracking
    // ─────────────────────────────────────────────────────────────

    private void updateServerPosition(Packet<?> pkt) {
        if (pkt instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) pkt;
            Entity e = p.getEntity(mc.theWorld);
            if (e != null) {
                int  id  = e.getEntityId();
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

    private boolean isTargetPositionPacket(Packet<?> pkt, int entityId) {
        if (pkt instanceof S14PacketEntity) {
            Entity e = ((S14PacketEntity) pkt).getEntity(mc.theWorld);
            return e != null && e.getEntityId() == entityId;
        }
        if (pkt instanceof S18PacketEntityTeleport) {
            return ((S18PacketEntityTeleport) pkt).getEntityId() == entityId;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // ESP
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!esp.getValue() || espMode.getValue() != 0) return;
        if (target == null || !(holding || draining)) return;

        Vec3 real = serverPositions.get(target.getEntityId());
        if (real == null) return;

        double x = real.xCoord - mc.getRenderManager().viewerPosX;
        double y = real.yCoord - mc.getRenderManager().viewerPosY;
        double z = real.zCoord - mc.getRenderManager().viewerPosZ;

        double hw = target.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(
                x - hw, y,                z - hw,
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
    // TimedPacket
    // ─────────────────────────────────────────────────────────────

    private static final class TimedPacket {
        final Packet<?> packet;
        final long      timestamp;
        final int       assignedDelay;

        TimedPacket(Packet<?> packet, long timestamp, int assignedDelay) {
            this.packet        = packet;
            this.timestamp     = timestamp;
            this.assignedDelay = assignedDelay;
        }
    }
}
