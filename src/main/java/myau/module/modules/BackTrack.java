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
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * BackTrack — smooth interpolated XZ desync.
 *
 * No packets are ever cancelled or buffered.
 * All position packets process normally — Y is always live.
 *
 * Each tick:
 *   1. Restore real XZ from last tick's override.
 *   2. Record the entity's real XZ into a snapshot ring buffer.
 *   3. Find the snapshot from delayMs ago.
 *   4. Smoothly interpolate entity XZ toward that snapshot using lerpFactor.
 *   5. Update bounding box to match.
 *
 * The lerp eliminates the hard per-tick snap that caused jitter.
 * At lerpFactor=0.3 the entity glides smoothly to the delayed position
 * rather than teleporting there instantly each frame.
 *
 * Y is never touched — entities stay correctly grounded at all times.
 */
public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Settings ──────────────────────────────────────────────────────────

    /** How far back in ms to target for XZ desync. Max ~150ms for Hypixel. */
    public final IntProperty delayMs = new IntProperty("hold-time", 100, 20, 150);

    public final FloatProperty minDistance = new FloatProperty("min-dist", 1.0f, 0.5f, 4.0f);
    public final FloatProperty maxDistance = new FloatProperty("max-dist", 4.0f, 1.0f, 6.0f);

    public final IntProperty iFrameThreshold = new IntProperty("iframe-threshold", 2, 1, 10);
    public final BooleanProperty jumpPeak = new BooleanProperty("jump-peak", true);

    public final ModeProperty showPosition = new ModeProperty("show-position", 1,
            new String[]{"NONE", "DEFAULT", "HUD"});

    // ── Snapshot ──────────────────────────────────────────────────────────

    private static final class Snapshot {
        final double x, z;
        final long   timestamp;
        Snapshot(double x, double z, long ts) { this.x = x; this.z = z; this.timestamp = ts; }
    }

    // ── State ─────────────────────────────────────────────────────────────

    private final Deque<Snapshot> snapshots = new ArrayDeque<>();

    /** Lerp factor per tick — how quickly the override position tracks the snapshot.
     *  0.25 = smooth glide; 1.0 = instant snap (causes jitter). */
    private static final double LERP = 0.25;

    /** Current interpolated override XZ. Starts at real position. */
    private double overrideX, overrideZ;

    /** Real XZ saved before we applied the override this tick. */
    private double realX, realZ;
    private double realPrevX, realPrevZ;
    private double realLastX, realLastZ;

    /** Whether we currently have an override applied. */
    private boolean active = false;

    /** Whether the first attack has armed the module. */
    private boolean engaged = false;

    /**
     * Entity ID that KillAura is currently attacking but hasn't confirmed a
     * hit on yet. BackTrack only activates (engaged=true) once S19 opcode 2
     * arrives for this entity — confirming actual damage landed.
     */
    private int pendingTargetId = -1;

    /** Entity ID being tracked. */
    private int targetId = -1;

    /** Jump peak tracking. */
    private double prevTargetY  = 0.0;
    private double prevTargetDy = 0.0;
    private boolean prevYTracked = false;

    /** Whether double-hit snap is requested this tick. */
    private boolean snapRequested = false;

    public BackTrack() {
        super("BackTrack", false);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onEnabled()  { this.resetState(); }

    @Override
    public void onDisabled() { this.restorePosition(); this.resetState(); }

    // ── Main tick ─────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() != EventType.PRE) return;

        // Always restore real position first so this tick starts clean
        this.restorePosition();

        if (!this.engaged) return;

        EntityLivingBase target = this.getCurrentTarget();
        if (target == null) { this.resetState(); return; }

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) {
            this.resetState(); return;
        }

        long now = System.currentTimeMillis();

        // Record real XZ snapshot
        this.snapshots.addLast(new Snapshot(target.posX, target.posZ, now));
        while (this.snapshots.size() > 128) this.snapshots.pollFirst();

        // Double-hit: snap to real position and clear override
        boolean iframeSnap = target.hurtResistantTime > 0
                && target.hurtResistantTime <= this.iFrameThreshold.getValue();

        boolean peakSnap = false;
        if (this.jumpPeak.getValue() && this.prevYTracked) {
            double curDy = target.posY - this.prevTargetY;
            if (this.prevTargetDy > 0.01 && curDy <= 0.01) peakSnap = true;
        }
        this.updatePrevY(target);

        if (iframeSnap || peakSnap || this.snapRequested) {
            this.snapRequested = false;
            this.overrideX = target.posX;
            this.overrideZ = target.posZ;
            this.snapshots.clear(); // rearm
            this.active = false;
            return;
        }

        // Find the snapshot closest to delayMs ago
        Snapshot target_snapshot = null;
        for (Snapshot s : this.snapshots) {
            if (now - s.timestamp >= this.delayMs.getValue()) target_snapshot = s;
            else break;
        }

        if (target_snapshot == null) {
            // Not enough history yet
            this.overrideX = target.posX;
            this.overrideZ = target.posZ;
            this.active = false;
            return;
        }

        // Smoothly interpolate override XZ toward the delayed snapshot
        // Lerp: overrideX += (targetX - overrideX) * LERP
        this.overrideX += (target_snapshot.x - this.overrideX) * LERP;
        this.overrideZ += (target_snapshot.z - this.overrideZ) * LERP;

        // Save real position
        this.realX     = target.posX;     this.realZ     = target.posZ;
        this.realPrevX = target.prevPosX; this.realPrevZ = target.prevPosZ;
        this.realLastX = target.lastTickPosX; this.realLastZ = target.lastTickPosZ;

        // Apply smooth override — Y untouched
        double dx = this.overrideX - target.posX;
        double dz = this.overrideZ - target.posZ;

        target.posX          = this.overrideX;
        target.posZ          = this.overrideZ;
        // Interpolate prev/lastTick positions too so rendering is smooth
        target.prevPosX      = this.realPrevX + dx;
        target.prevPosZ      = this.realPrevZ + dz;
        target.lastTickPosX  = this.realLastX + dx;
        target.lastTickPosZ  = this.realLastZ + dz;

        // Shift bounding box to match
        target.setEntityBoundingBox(target.getEntityBoundingBox().offset(dx, 0.0, dz));

        this.active = true;
    }

    // ── Restore ───────────────────────────────────────────────────────────

    private void restorePosition() {
        if (!this.active) return;
        EntityLivingBase target = this.getCurrentTarget();
        if (target == null) { this.active = false; return; }

        double dx = this.realX - target.posX;
        double dz = this.realZ - target.posZ;

        target.posX         = this.realX;     target.posZ         = this.realZ;
        target.prevPosX     = this.realPrevX; target.prevPosZ     = this.realPrevZ;
        target.lastTickPosX = this.realLastX; target.lastTickPosZ = this.realLastZ;

        target.setEntityBoundingBox(target.getEntityBoundingBox().offset(dx, 0.0, dz));

        this.active = false;
    }

    // ── Packet handler ────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        // S08 teleport (us): hard reset
        if (event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.restorePosition();
            this.resetState();
            return;
        }

        // S19 opcode 2 on pending target: confirmed hit — now engage
        if (event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus s19 = (S19PacketEntityStatus) event.getPacket();
            if (s19.getOpCode() == 2 && mc.theWorld != null
                    && this.pendingTargetId != -1) {
                Entity hit = s19.getEntity(mc.theWorld);
                if (hit != null && hit.getEntityId() == this.pendingTargetId) {
                    // Hit confirmed — engage BackTrack on this target
                    if (this.targetId != -1 && this.targetId != this.pendingTargetId) {
                        this.restorePosition();
                        this.snapshots.clear();
                    }
                    this.targetId     = this.pendingTargetId;
                    this.engaged      = true;
                    this.overrideX    = hit.posX;
                    this.overrideZ    = hit.posZ;
                    this.prevYTracked = false;
                }
            }
            return;
        }

        // Outbound C02 attack: register pending target, do NOT engage yet
        // BackTrack stays inactive until S19 confirms the hit actually landed
        if (event.getType() == EventType.SEND
                && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;
            if (mc.theWorld == null) return;
            Entity entity = use.getEntityFromWorld(mc.theWorld);
            if (!(entity instanceof EntityLivingBase)) return;
            double dist = mc.thePlayer.getDistanceToEntity(entity);
            if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) return;

            // Track who we're attacking — engagement waits for S19 confirmation
            this.pendingTargetId = entity.getEntityId();
        }
    }

    // ── KillAura sync API ─────────────────────────────────────────────────

    public void onKillAuraBurstComplete() {
        if (!this.isEnabled()) return;
        this.snapRequested = true; // snap to real on next tick
    }

    public void onKillAuraResuming() {
        if (!this.isEnabled()) return;
        this.snapshots.clear(); // fresh snapshot window for next burst
    }

    public void syncTarget(int entityId) {
        if (!this.isEnabled()) return;
        if (this.targetId != entityId) {
            this.restorePosition();
            this.snapshots.clear();
            this.active = false;
        }
        this.pendingTargetId = entityId; // wait for S19 confirmation
        this.targetId        = entityId;
        this.engaged         = true;     // KillAura already confirmed a hit
        this.prevYTracked    = false;
        EntityLivingBase e = this.getCurrentTarget();
        if (e != null) { this.overrideX = e.posX; this.overrideZ = e.posZ; }
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
        this.snapshots.clear();
        this.active          = false;
        this.engaged         = false;
        this.targetId        = -1;
        this.pendingTargetId = -1;
        this.prevYTracked    = false;
        this.snapRequested   = false;
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
            default: return;
        }

        float size = target.getCollisionBorderSize();
        AxisAlignedBB aabb = new AxisAlignedBB(
                target.posX - target.width  / 2.0, target.posY,
                target.posZ - target.width  / 2.0,
                target.posX + target.width  / 2.0, target.posY + target.height,
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
