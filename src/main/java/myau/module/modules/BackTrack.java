package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BackTrack — Slinky-style inbound position buffer.
 *
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  INBOUND  — target entity position packets are buffered.               ║
 * ║  Their position on your client is frozen at a recent snapshot          ║
 * ║  while their real server-side position advances.                       ║
 * ║                                                                        ║
 * ║  OUTBOUND — YOUR packets are NEVER touched. Your movement, attacks,    ║
 * ║  and animations all go out immediately with zero delay.                ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * How the desync works:
 *   The server knows the enemy is at position X (present).
 *   Your client thinks the enemy is at position X - N ticks (buffered).
 *   KillAura attacks the frozen position.
 *   The server validates the attack against its own known positions and
 *   accepts it because it was in range at the time the packet was sent.
 *
 * This is fundamentally different from outbound lag:
 *   Outbound lag  → holds YOUR packets, delaying your own movement.
 *   Slinky        → holds THEIR packets, your movement is always live.
 *
 * Double-hit cycle:
 *   The buffer is flushed when the target's i-frames are about to expire
 *   (hurtResistantTime <= iFrameThreshold). The instant flush causes their
 *   client-side position to snap to the real position. KillAura's next hit
 *   then fires at the freshly updated (real) position — producing a double
 *   hit in rapid succession. The buffer immediately rearms for the next cycle.
 *
 * Buffer contents (inbound, target entity only):
 *   S14PacketEntity          — relative position update
 *   S18PacketEntityTeleport  — absolute position update
 *
 * Never buffered (all other inbound passes through instantly):
 *   S08 teleport (us)        — always processed immediately
 *   S19 entity status        — always processed (hurt events, death, etc)
 *   All other packets        — untouched
 *
 * Flush triggers:
 *   1. i-frame threshold reached → flushAndRearm() (double-hit cycle)
 *   2. Jump peak detected         → flushAndRearm()
 *   3. delayMs timer expired      → flush() + rearm
 *   4. Target out of range        → dropBuffer()
 *   5. S08 teleport (us)          → dropBuffer() (safety)
 *   6. Module disabled            → flush()
 */
public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Settings ──────────────────────────────────────────────────────────

    /**
     * How many ms to buffer the target's position packets.
     * This is the desync window — higher = target is more frozen.
     * Capped at 150ms (Hypixel threshold).
     */
    public final IntProperty delayMs = new IntProperty("hold-time", 100, 20, 150);

    public final FloatProperty minDistance = new FloatProperty("min-dist", 1.0f, 0.5f, 4.0f);
    public final FloatProperty maxDistance = new FloatProperty("max-dist", 4.0f, 1.0f, 6.0f);

    /**
     * i-frame threshold for the double-hit trigger.
     * Flush and rearm when hurtResistantTime <= this value.
     * Lower = more aggressive (releases closer to i-frame expiry).
     * 2 ticks is optimal for most scenarios.
     */
    public final IntProperty iFrameThreshold = new IntProperty("iframe-threshold", 2, 1, 5);

    /**
     * Also flush and rearm at the peak of the target's jump.
     * The frozen position is below the apex — hit 1 lands at the lower
     * frozen pos, flush snaps them to peak, hit 2 lands at apex.
     */
    public final BooleanProperty jumpPeak = new BooleanProperty("jump-peak", true);

    /** Whether to drop the buffer when the server teleports us. */
    public final BooleanProperty flushOnTeleport = new BooleanProperty("flush-on-tp", true);

    public final ModeProperty showPosition = new ModeProperty("show-position", 1,
            new String[]{"NONE", "DEFAULT", "HUD"});

    // ── Constants ─────────────────────────────────────────────────────────

    private static final int MAX_BUFFER_DEPTH = 64;

    // ── State ─────────────────────────────────────────────────────────────

    /**
     * Per-entity inbound position packet buffer.
     * Key = entity ID. Only the current target is actively buffered.
     * Other entities are not touched.
     */
    private final Map<Integer, Deque<Packet<?>>> inboundBuffers = new HashMap<>();
    private final ReentrantLock bufferLock = new ReentrantLock();

    /** True while we're draining a buffer — prevents re-entrancy. */
    private volatile boolean draining = false;

    /** Current target entity ID being buffered. -1 = no target. */
    private int targetId = -1;

    /** Whether the first hit has landed (arming condition). */
    private boolean engaged = false;

    /** Wall-clock ms when buffering started for this cycle. */
    private long bufferStart = 0L;

    /** Y position tracking for jump peak detection. */
    private double prevTargetY  = 0.0;
    private double prevTargetDy = 0.0;
    private boolean prevYTracked = false;

    public BackTrack() {
        super("BackTrack", false);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.flushAll();
        this.resetState();
    }

    // ── Per-tick update ───────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || !this.engaged || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        EntityLivingBase target = this.getCurrentTarget();

        // Target gone or out of range — drop buffer
        if (target == null) {
            this.dropBuffer(this.targetId);
            this.resetState();
            return;
        }

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) {
            this.dropBuffer(this.targetId);
            this.resetState();
            return;
        }

        // Hard time cap
        if (System.currentTimeMillis() - this.bufferStart >= this.delayMs.getValue()) {
            this.flushAndRearm(this.targetId);
            return;
        }

        Deque<Packet<?>> buf = this.getBuffer(this.targetId);
        if (buf == null || buf.isEmpty()) return;

        // i-frame threshold trigger — flush and rearm for double hit
        if (target.hurtResistantTime > 0
                && target.hurtResistantTime <= this.iFrameThreshold.getValue()) {
            this.flushAndRearm(this.targetId);
            return;
        }

        // Jump peak trigger
        if (this.jumpPeak.getValue() && this.prevYTracked) {
            double curDy = target.posY - this.prevTargetY;
            if (this.prevTargetDy > 0.01 && curDy <= 0.01) {
                this.flushAndRearm(this.targetId);
                this.updatePrevY(target);
                return;
            }
        }

        this.updatePrevY(target);
    }

    // ── Packet handler ────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        // Only intercept inbound packets
        if (event.getType() != EventType.RECEIVE) return;
        // YOUR outbound packets are NEVER touched — no outbound handling at all

        Packet<?> packet = event.getPacket();

        // S08 — server teleporting us: drop buffer immediately for safety
        if (packet instanceof S08PacketPlayerPosLook) {
            if (this.flushOnTeleport.getValue()) {
                this.dropBuffer(this.targetId);
                this.resetState();
            }
            return;
        }

        // Not engaged yet — wait for first hit via onAttack
        if (!this.engaged) return;

        // Buffer S14 (relative move) for the target entity only
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            Entity e = p.getEntity(mc.theWorld);
            if (e != null && e.getEntityId() == this.targetId) {
                this.bufferPacket(this.targetId, packet);
                event.setCancelled(true);
            }
            return;
        }

        // Buffer S18 (teleport) for the target entity only
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            if (p.getEntityId() == this.targetId) {
                this.bufferPacket(this.targetId, packet);
                event.setCancelled(true);
            }
            return;
        }

        // S19 — entity status (hurt, death, etc): never buffered, always live
        // All other inbound: untouched
    }

    // ── Attack hook — arm on first hit ────────────────────────────────────

    @EventTarget
    public void onAttack(EventAttackEntity event) {
        if (!(event.getEntity() instanceof EntityLivingBase)) return;

        EntityLivingBase target = (EntityLivingBase) event.getEntity();
        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) return;

        int id = target.getEntityId();

        if (!this.engaged || this.targetId != id) {
            // New target or first engagement — flush old buffer if switching
            if (this.targetId != -1 && this.targetId != id) {
                this.flushAll();
            }
            this.targetId    = id;
            this.engaged     = true;
            this.bufferStart = System.currentTimeMillis();
            this.prevYTracked = false;
        } else if (this.engaged) {
            // Already engaged with this target — rearm if buffer drained
            Deque<Packet<?>> buf = this.getBuffer(id);
            if (buf == null || buf.isEmpty()) {
                this.bufferStart = System.currentTimeMillis();
            }
        }
    }

    // ── Buffer helpers ────────────────────────────────────────────────────

    private void bufferPacket(int entityId, Packet<?> packet) {
        this.bufferLock.lock();
        try {
            Deque<Packet<?>> buf = this.inboundBuffers.computeIfAbsent(
                    entityId, k -> new ArrayDeque<>());
            if (buf.size() >= MAX_BUFFER_DEPTH) {
                // Buffer full — flush oldest packet immediately to prevent stale buildup
                Packet<?> oldest = buf.pollFirst();
                if (oldest != null && mc.getNetHandler() != null) {
                    oldest.processPacket(mc.getNetHandler());
                }
            }
            buf.addLast(packet);
        } finally {
            this.bufferLock.unlock();
        }
    }

    /**
     * Flushes the buffer for an entity and immediately rearms.
     * This produces the double-hit: frozen position snap → KillAura
     * fires at the new real position within the same i-frame window.
     */
    private void flushAndRearm(int entityId) {
        this.drainAndProcess(entityId);
        // Rearm immediately for next cycle
        this.bufferStart = System.currentTimeMillis();
    }

    /** Flushes all buffers without rearming. Used on disable/target loss. */
    private void flushAll() {
        if (this.draining) return;
        this.draining = true;
        try {
            this.bufferLock.lock();
            try {
                for (Map.Entry<Integer, Deque<Packet<?>>> entry
                        : this.inboundBuffers.entrySet()) {
                    this.processDeque(entry.getValue());
                }
                this.inboundBuffers.clear();
            } finally {
                this.bufferLock.unlock();
            }
        } finally {
            this.draining = false;
        }
    }

    /** Drops (discards) the buffer for an entity without processing. */
    private void dropBuffer(int entityId) {
        this.bufferLock.lock();
        try {
            this.inboundBuffers.remove(entityId);
        } finally {
            this.bufferLock.unlock();
        }
    }

    /** Drains the buffer for an entity and processes all held packets. */
    private void drainAndProcess(int entityId) {
        if (this.draining) return;
        this.draining = true;
        try {
            Deque<Packet<?>> snapshot = null;
            this.bufferLock.lock();
            try {
                Deque<Packet<?>> buf = this.inboundBuffers.get(entityId);
                if (buf != null && !buf.isEmpty()) {
                    snapshot = new ArrayDeque<>(buf);
                    buf.clear();
                }
            } finally {
                this.bufferLock.unlock();
            }
            if (snapshot != null) this.processDeque(snapshot);
        } finally {
            this.draining = false;
        }
    }

    private void processDeque(Deque<Packet<?>> deque) {
        if (mc.getNetHandler() == null) return;
        for (Packet<?> p : deque) {
            try {
                p.processPacket(mc.getNetHandler());
            } catch (Exception ignored) {
                // Stale packet — skip
            }
        }
    }

    private Deque<Packet<?>> getBuffer(int entityId) {
        this.bufferLock.lock();
        try {
            return this.inboundBuffers.get(entityId);
        } finally {
            this.bufferLock.unlock();
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private EntityLivingBase getCurrentTarget() {
        if (this.targetId == -1 || mc.theWorld == null) return null;
        for (Object obj : mc.theWorld.loadedEntityList) {
            if (!(obj instanceof EntityLivingBase)) continue;
            EntityLivingBase e = (EntityLivingBase) obj;
            if (e.getEntityId() == this.targetId && e.getHealth() > 0f) return e;
        }
        return null;
    }

    private void updatePrevY(EntityLivingBase e) {
        if (this.prevYTracked) this.prevTargetDy = e.posY - this.prevTargetY;
        this.prevTargetY  = e.posY;
        this.prevYTracked = true;
    }

    private void resetState() {
        this.engaged      = false;
        this.targetId     = -1;
        this.bufferStart  = 0L;
        this.prevYTracked = false;
        this.draining     = false;
        this.bufferLock.lock();
        try { this.inboundBuffers.clear(); }
        finally { this.bufferLock.unlock(); }
    }

    // ── Render ────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || this.showPosition.getValue() == 0) return;

        EntityLivingBase target = this.getCurrentTarget();
        if (target == null) return;

        Color color;
        switch (this.showPosition.getValue()) {
            case 1:
                color = (target instanceof EntityPlayer)
                        ? TeamUtil.getTeamColor((EntityPlayer) target, 1.0f)
                        : new Color(255, 0, 0);
                break;
            case 2:
                color = ((HUD) Myau.moduleManager.modules.get(HUD.class))
                        .getColor(System.currentTimeMillis());
                break;
            default:
                return;
        }

        float size = target.getCollisionBorderSize();
        AxisAlignedBB aabb = new AxisAlignedBB(
                target.posX - target.width  / 2.0,
                target.posY,
                target.posZ - target.width  / 2.0,
                target.posX + target.width  / 2.0,
                target.posY + target.height,
                target.posZ + target.width  / 2.0
        ).expand(size, size, size).offset(
                -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX(),
                -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY(),
                -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ()
        );

        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(aabb, color.getRed(), color.getGreen(), color.getBlue());
        RenderUtil.disableRenderState();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%dms", this.delayMs.getValue())};
    }
}
