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
 * BackTrack — adaptive smooth XZ desync, no restore cycle.
 *
 * Core design:
 *   Real XZ is tracked separately from the override. The entity's posX/Z
 *   is permanently held at the smooth lerped override value. No restore
 *   cycle means no flicker — the entity visually glides to the delayed
 *   position and stays there.
 *
 * Improvements over previous version:
 *   1. Best-snapshot selection — picks the oldest snapshot still within
 *      attack range rather than blindly using delayMs. This maximises
 *      desync while guaranteeing every attack registers.
 *   2. Adaptive lerp — speed scales with the distance between override
 *      and target snapshot. Large gaps converge faster, small gaps stay
 *      smooth. Prevents rubber-band on fast-moving targets.
 *   3. Real position isolation — we record realX/Z BEFORE applyOverride
 *      so the snapshot buffer always contains server-authoritative values,
 *      not our overridden ones.
 *   4. Smooth release — when snapping to real (burst end / i-frame expiry),
 *      we lerp toward real rather than teleporting instantly.
 *   5. Dead-zone threshold — overrides below 0.001 blocks are skipped
 *      to avoid micro-jitter from floating point noise.
 */
public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Settings ──────────────────────────────────────────────────────────

    /**
     * How far back in ms to look for the best snapshot.
     * Higher = more desync but more visual lag. Max ~150ms for Hypixel.
     */
    public final IntProperty delay = new IntProperty("delay", 100, 20, 150);

    public final FloatProperty minDistance = new FloatProperty("min-dist", 1.0f, 0.5f, 4.0f);
    public final FloatProperty maxDistance = new FloatProperty("max-dist", 4.0f, 1.0f, 6.0f);

    /**
     * Release timing — when hurtResistantTime drops to this tick count,
     * the override snaps to real so KillAura can fire at the true position
     * as i-frames expire. Lower = more aggressive double-hit timing.
     * 1 = release at last possible moment. 10 = release immediately on damage.
     */
    public final IntProperty releaseTiming = new IntProperty("release-timing", 2, 1, 10);

    public final BooleanProperty jumpPeak = new BooleanProperty("jump-peak", true);

    public final ModeProperty showPosition = new ModeProperty("show-position", 1,
            new String[]{"NONE", "DEFAULT", "HUD"});

    // ── Snapshot ──────────────────────────────────────────────────────────

    private static final class Snapshot {
        final double x, z;
        final long   ts;
        Snapshot(double x, double z, long ts) { this.x = x; this.z = z; this.ts = ts; }
    }

    // ── Constants ─────────────────────────────────────────────────────────

    /** Base lerp factor for smooth movement. */
    private static final double LERP_BASE = 0.18;

    /** Dead-zone — ignore overrides smaller than this to prevent micro-jitter. */
    private static final double DEAD_ZONE = 0.001;

    /** How fast to lerp back to real during a smooth release. */
    private static final double RELEASE_LERP = 0.35;

    // ── State ─────────────────────────────────────────────────────────────

    private final Deque<Snapshot> snapshots = new ArrayDeque<>();

    /** Current smooth override XZ — never restored, permanently applied. */
    private double overrideX, overrideZ;

    /** Server-authoritative real XZ read this tick before any override. */
    private double realX, realZ;

    /** True while override is applied. */
    private boolean active = false;

    /** True once S19 confirms first hit. */
    private boolean engaged = false;

    /** Entity ID awaiting S19 hit confirmation. */
    private int pendingTargetId = -1;

    /** Entity ID currently being overridden. */
    private int targetId = -1;

    /** True when burst ends — triggers smooth release to real position. */
    private boolean releasing = false;

    /** Jump peak tracking. */
    private double  prevTargetY  = 0.0;
    private double  prevTargetDy = 0.0;
    private boolean prevYTracked = false;

    public BackTrack() { super("BackTrack", false); }

    @Override public void onEnabled()  { this.resetState(); }
    @Override public void onDisabled() { this.resetState(); }

    // ── Main tick ─────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() != EventType.PRE) return;
        if (!this.engaged) return;

        EntityLivingBase target = this.getCurrentTarget();
        if (target == null) { this.resetState(); return; }

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) {
            this.resetState(); return;
        }

        long now = System.currentTimeMillis();

        // Read real XZ from the entity — this is the server-authoritative value
        // because we accumulate our override on top of it, making posX drift.
        // We isolate the real value by un-applying our current override delta.
        if (this.active) {
            this.realX = target.posX + (this.realX - this.overrideX);
            this.realZ = target.posZ + (this.realZ - this.overrideZ);
        } else {
            this.realX = target.posX;
            this.realZ = target.posZ;
        }

        // Record server-authoritative snapshot
        this.snapshots.addLast(new Snapshot(this.realX, this.realZ, now));
        while (this.snapshots.size() > 128) this.snapshots.pollFirst();

        // Jump peak detection
        boolean peakSnap = false;
        if (this.jumpPeak.getValue() && this.prevYTracked) {
            double curDy = target.posY - this.prevTargetY;
            if (this.prevTargetDy > 0.01 && curDy <= 0.01) peakSnap = true;
        }
        this.updatePrevY(target);

        // i-frame release timing
        boolean iframeSnap = target.hurtResistantTime > 0
                && target.hurtResistantTime <= this.releaseTiming.getValue();

        // Any release trigger: smoothly return to real position
        if (iframeSnap || peakSnap || this.releasing) {
            this.releasing = false;

            // Smooth release — lerp override toward real
            this.overrideX += (this.realX - this.overrideX) * RELEASE_LERP;
            this.overrideZ += (this.realZ - this.overrideZ) * RELEASE_LERP;

            // Once close enough, snap exactly to real and clear buffer
            double releaseGap = Math.sqrt(
                    Math.pow(this.overrideX - this.realX, 2)
                  + Math.pow(this.overrideZ - this.realZ, 2));
            if (releaseGap < 0.05) {
                this.overrideX = this.realX;
                this.overrideZ = this.realZ;
                this.snapshots.clear();
            }

            this.applyOverride(target, this.overrideX, this.overrideZ);
            this.active = true;
            return;
        }

        // Best-snapshot selection:
        // Find the oldest snapshot still within attack range.
        // This maximises desync while guaranteeing every hit registers.
        Snapshot best = null;
        double attackRange = this.maxDistance.getValue();
        for (Snapshot s : this.snapshots) {
            if (now - s.ts > this.delay.getValue()) {
                // Check if this historical position is still in attack range
                double hx = s.x - mc.thePlayer.posX;
                double hz = s.z - mc.thePlayer.posZ;
                double hdist = Math.sqrt(hx * hx + hz * hz);
                if (hdist <= attackRange) best = s;
            } else {
                break;
            }
        }

        if (best == null) {
            // Not enough history or no reachable snapshot — stay at real
            this.overrideX = this.realX;
            this.overrideZ = this.realZ;
            this.active = false;
            return;
        }

        // Adaptive lerp — converges faster when the gap is large,
        // stays silky smooth when already close to the snapshot.
        double gap = Math.sqrt(
                Math.pow(best.x - this.overrideX, 2)
              + Math.pow(best.z - this.overrideZ, 2));
        double lerpFactor = LERP_BASE + Math.min(gap * 0.08, 0.25);

        this.overrideX += (best.x - this.overrideX) * lerpFactor;
        this.overrideZ += (best.z - this.overrideZ) * lerpFactor;

        this.applyOverride(target, this.overrideX, this.overrideZ);
        this.active = true;
    }

    /**
     * Applies the XZ override to the entity.
     * Shifts posX/Z, prevPosX/Z, and lastTickPosX/Z by the same delta
     * so Minecraft's render interpolation sees a consistent offset —
     * preventing model stretching between frames.
     * Y is never touched.
     */
    private void applyOverride(EntityLivingBase e, double ox, double oz) {
        double dx = ox - e.posX;
        double dz = oz - e.posZ;
        if (Math.abs(dx) < DEAD_ZONE && Math.abs(dz) < DEAD_ZONE) return;

        e.posX         += dx; e.posZ         += dz;
        e.prevPosX     += dx; e.prevPosZ     += dz;
        e.lastTickPosX += dx; e.lastTickPosZ += dz;
        e.setEntityBoundingBox(e.getEntityBoundingBox().offset(dx, 0.0, dz));
    }

    // ── Packet handler ────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        if (event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.resetState();
            return;
        }

        // S19 opcode 2: confirmed hit — engage
        if (event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus s19 = (S19PacketEntityStatus) event.getPacket();
            if (s19.getOpCode() == 2 && mc.theWorld != null && this.pendingTargetId != -1) {
                Entity hit = s19.getEntity(mc.theWorld);
                if (hit != null && hit.getEntityId() == this.pendingTargetId) {
                    if (this.targetId != -1 && this.targetId != this.pendingTargetId) {
                        this.snapshots.clear();
                        this.active = false;
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

        // C02 attack: register pending target
        if (event.getType() == EventType.SEND
                && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;
            if (mc.theWorld == null) return;
            Entity entity = use.getEntityFromWorld(mc.theWorld);
            if (!(entity instanceof EntityLivingBase)) return;
            double d = mc.thePlayer.getDistanceToEntity(entity);
            if (d < this.minDistance.getValue() || d > this.maxDistance.getValue()) return;
            this.pendingTargetId = entity.getEntityId();
        }
    }

    // ── KillAura sync API ─────────────────────────────────────────────────

    public void onKillAuraBurstComplete() {
        if (!this.isEnabled()) return;
        this.releasing = true; // smooth lerp back to real during pause
    }

    public void onKillAuraResuming() {
        if (!this.isEnabled()) return;
        this.releasing = false;
        this.snapshots.clear(); // fresh window for next burst
    }

    public void syncTarget(int entityId) {
        if (!this.isEnabled()) return;
        if (this.targetId != entityId) {
            this.snapshots.clear();
            this.active = false;
        }
        this.pendingTargetId = entityId;
        this.targetId        = entityId;
        this.engaged         = true;
        this.releasing       = false;
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
        this.releasing       = false;
        this.targetId        = -1;
        this.pendingTargetId = -1;
        this.prevYTracked    = false;
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
        return new String[]{String.format("%dms", this.delay.getValue())};
    }
}
