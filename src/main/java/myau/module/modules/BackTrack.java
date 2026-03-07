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
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;

import java.awt.*;
import java.util.UUID;

/**
 * BackTrack — Hybrid Predictive-Adaptive v3
 *
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  LOGICAL  posX/Z = exact snapshot — always a real server position   ║
 * ║           hitbox the server validates matches its own history        ║
 * ║           NEVER interpolated, NEVER synthetic                        ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  VISUAL   prevPosX/Z = lerped toward logical position               ║
 * ║           renderer interpolates prevPos→pos between frames          ║
 * ║           smooth movement with zero jitter regardless of speed      ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * ── Adaptive Delay ───────────────────────────────────────────────────
 *   delay = (baseDelay + velFactor + comboBoost − strafePenalty) × tpsFactor
 *   baseDelay   = smoothPing × 0.55   (EMA-smoothed ping, not raw spike value)
 *   velFactor   = clamp(speed × 35, 0, 35)
 *   comboBoost  = +12ms within 8 ticks of a confirmed hit
 *   strafePenalty = delay × 0.30 on direction flip
 *   tpsFactor   = avgTickInterval / 50ms (scales up when server lags)
 *   Clamped to [45ms, 160ms] for Hypixel compatibility.
 *
 * ── Predictive Snapshot Selection ────────────────────────────────────
 *   targetTick = clientTick − (delay / 50) + clamp(speed × 0.4, 0, 0.8)
 *   Picks the snapshot with the smallest |tick − targetTick|.
 *   Forward bias on fast targets means we select a snapshot slightly
 *   closer to the present, compensating for the target having moved.
 *
 * ── Bounding Box Reconstruction ──────────────────────────────────────
 *   Real bounding box stored each tick from entity dimensions + realXZ.
 *   Override BB = realBB.offset(ox, 0, oz) — computed absolutely, never
 *   accumulated. Eliminates floating-point drift across long engagements.
 *
 * ── Safety Guards ────────────────────────────────────────────────────
 *   Teleport  : movement > 1.5 blocks/tick → clear buffer, re-engage
 *   VelSpike  : Δspeed > 0.9 → suspend 1 tick
 *   Strafe    : dot(prevV, curV) < −0.04 → suspend 2 ticks, −30% delay
 *   MaxOffset : snapshotPos > 3.1 blocks from real → skip frame
 *   Warmup    : 3-tick buffer build after engagement before overriding
 *
 * ── Ring Buffer ──────────────────────────────────────────────────────
 *   128 pre-allocated Snapshot slots, indexed with bit mask (& 127).
 *   Zero allocations inside the hot tick loop — no GC pressure.
 *
 * ── Engagement ───────────────────────────────────────────────────────
 *   Completely inactive until S19 opcode 2 confirms KillAura's first
 *   attack landed. Entity moves completely normally before confirmation.
 */
public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Settings ──────────────────────────────────────────────────────────

    public final FloatProperty   minDistance   = new FloatProperty("min-dist",  1.0f, 0.5f, 4.0f);
    public final FloatProperty   maxDistance   = new FloatProperty("max-dist",  4.0f, 1.0f, 6.0f);

    /**
     * When hurtResistantTime drops to this value, the override is released
     * so KillAura can fire at the true position as i-frames expire.
     * Lower = more aggressive double-hit timing. 1 = last possible moment.
     */
    public final IntProperty     releaseTiming = new IntProperty("release-timing", 2, 1, 10);
    public final BooleanProperty jumpPeak      = new BooleanProperty("jump-peak",   true);
    public final BooleanProperty strafeGuard   = new BooleanProperty("strafe-guard", true);
    public final ModeProperty    showPosition  = new ModeProperty("show-position", 1,
            new String[]{"NONE", "DEFAULT", "HUD"});

    // ── Ring Buffer ───────────────────────────────────────────────────────

    private static final int  BUFFER_SIZE = 128;
    private static final int  BUFFER_MASK = BUFFER_SIZE - 1;

    /**
     * Pre-allocated snapshot. All fields are primitives — no boxing, no GC.
     */
    private static final class Snapshot {
        double x, y, z;   // real server-authoritative position
        double vx, vz;    // velocity at capture time
        int    tick;       // client tick counter at capture
        long   ts;         // wall clock ms at capture
    }

    private final Snapshot[] ring       = new Snapshot[BUFFER_SIZE];
    private int              writeIndex = 0;
    private int              count      = 0;

    // Pre-allocate all slots once — never allocate inside tick loop
    { for (int i = 0; i < BUFFER_SIZE; i++) ring[i] = new Snapshot(); }

    // ── Ping EMA ─────────────────────────────────────────────────────────

    /**
     * Exponential moving average of ping. Raw getResponseTime() can spike
     * ±30ms between ticks during normal play — smoothing prevents the
     * adaptive delay from jumping erratically.
     */
    private double smoothPing   = 80.0;

    // ── TPS Estimation ────────────────────────────────────────────────────

    /** EMA of measured client tick intervals. Reflects actual server TPS. */
    private double avgTickMs  = 50.0;
    private long   lastTickMs = 0L;

    // ── Adaptive Delay ────────────────────────────────────────────────────

    /** Final computed adaptive delay this tick. Displayed in suffix. */
    private double adaptiveDelay = 100.0;

    /** Ticks since last confirmed hit — drives combo boost. */
    private int ticksSinceHit = 999;

    // ── Override Tracking ─────────────────────────────────────────────────

    /**
     * Offset currently applied to the entity.
     * Invariant: entity.posX = realX + appliedOffsetX at all times.
     */
    private double appliedOffsetX = 0.0;
    private double appliedOffsetZ = 0.0;

    /** Server-authoritative real XZ this tick (before override). */
    private double realX, realZ;

    /**
     * Real bounding box at the true entity position.
     * Stored each tick and used to reconstruct the override BB absolutely,
     * avoiding cumulative floating-point drift from repeated offsetting.
     */
    private AxisAlignedBB realBB = null;

    /** Whether an override is currently applied to the entity. */
    private boolean active = false;

    // ── Velocity / Direction ──────────────────────────────────────────────

    private double  prevVx = 0.0, prevVz = 0.0;
    private double  prevSpeed = 0.0;
    private boolean prevVelTracked = false;

    /** Ticks remaining to suspend override (strafe or velocity spike). */
    private int suspendTicks = 0;

    /**
     * Dot product from this tick's strafe check.
     * Computed once, reused for both the guard and the delay penalty.
     */
    private double strafeDot = 1.0;

    // ── Jump Peak ─────────────────────────────────────────────────────────

    private double  prevTargetY  = 0.0;
    private double  prevTargetDy = 0.0;
    private boolean prevYTracked = false;

    // ── Engagement ────────────────────────────────────────────────────────

    /** True once S19 opcode 2 confirms KillAura's first attack landed. */
    private boolean engaged = false;

    /**
     * How many ticks we've been engaged.
     * Override only activates after WARMUP_TICKS to build snapshot history.
     */
    private int  engageTick      = 0;
    private static final int WARMUP_TICKS = 3;

    /** Entity ID awaiting S19 confirmation. */
    private int pendingTargetId = -1;

    /** Entity ID currently being tracked and overridden. */
    private int targetId = -1;

    /** Client tick counter. */
    private int clientTick = 0;

    /** True during KillAura burst-end release phase. */
    private boolean releasing = false;

    // ── Constructor ───────────────────────────────────────────────────────

    public BackTrack() { super("BackTrack", false); }

    @Override public void onEnabled()  { this.resetState(); }
    @Override public void onDisabled() { this.resetState(); }

    // ── Main Tick ─────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() != EventType.PRE) return;

        // ── Housekeeping (runs even when not engaged) ──────────────────

        long now = System.currentTimeMillis();

        // TPS: measure real tick interval, EMA with GC-pause rejection
        if (this.lastTickMs > 0L) {
            double interval = now - this.lastTickMs;
            if (interval >= 30.0 && interval <= 120.0)
                this.avgTickMs = this.avgTickMs * 0.92 + interval * 0.08;
        }
        this.lastTickMs = now;
        this.clientTick++;
        this.ticksSinceHit = Math.min(this.ticksSinceHit + 1, 999);

        // Ping EMA — smooth raw response time before using in delay formula
        if (mc.getNetHandler() != null) {
            UUID id = mc.thePlayer.getGameProfile().getId();
            net.minecraft.client.network.NetworkPlayerInfo info =
                    mc.getNetHandler().getPlayerInfo(id);
            if (info != null) {
                double rawPing = info.getResponseTime();
                // Clamp raw ping to discard impossible spikes (e.g. 0 or 5000)
                if (rawPing >= 1.0 && rawPing <= 500.0)
                    this.smoothPing = this.smoothPing * 0.88 + rawPing * 0.12;
            }
        }

        if (!this.engaged) return;

        EntityLivingBase target = this.getCurrentTarget();
        if (target == null) { this.resetState(); return; }

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) {
            this.resetState(); return;
        }

        this.engageTick++;

        // ── Recover real position ──────────────────────────────────────
        // Exact recovery via stored offset — no iterative drift
        this.realX = target.posX - this.appliedOffsetX;
        this.realZ = target.posZ - this.appliedOffsetZ;

        // Store the real bounding box absolutely (entity dimensions + realXZ)
        // Used later to reconstruct override BB without cumulative FP error
        double hw  = target.width  / 2.0;
        double h   = target.height;
        float  sz  = target.getCollisionBorderSize();
        this.realBB = new AxisAlignedBB(
                this.realX - hw - sz, target.posY - sz,
                this.realZ - hw - sz,
                this.realX + hw + sz, target.posY + h + sz,
                this.realZ + hw + sz);

        // ── Velocity ──────────────────────────────────────────────────
        double vx = 0.0, vz = 0.0, speed = 0.0;
        if (this.prevVelTracked && this.count > 0) {
            // Derive from last snapshot's real position for accuracy
            Snapshot lastSnap = this.ring[(this.writeIndex - 1) & BUFFER_MASK];
            vx    = this.realX - lastSnap.x;
            vz    = this.realZ - lastSnap.z;
            speed = Math.sqrt(vx * vx + vz * vz);
        }

        // ── Safety: teleport detection ─────────────────────────────────
        if (this.prevVelTracked && speed > 1.5) {
            // Entity teleported — wipe buffer and re-arm
            this.count      = 0;
            this.writeIndex = 0;
            this.clearOverride(target);
            this.prevVelTracked = false;
            return;
        }

        // ── Velocity spike ─────────────────────────────────────────────
        if (this.prevVelTracked && Math.abs(speed - this.prevSpeed) > 0.9)
            this.suspendTicks = Math.max(this.suspendTicks, 1);

        // ── Strafe guard: compute dot product once, reuse in delay ─────
        this.strafeDot = 1.0;
        if (this.strafeGuard.getValue() && this.prevVelTracked
                && speed > 0.02 && this.prevSpeed > 0.02) {
            this.strafeDot = (this.prevVx * vx + this.prevVz * vz)
                    / (this.prevSpeed * speed); // normalised: range [−1, 1]
            if (this.strafeDot < -0.04)
                this.suspendTicks = Math.max(this.suspendTicks, 2);
        }

        // ── Write snapshot BEFORE any override ────────────────────────
        Snapshot slot = this.ring[this.writeIndex];
        slot.x    = this.realX;
        slot.y    = target.posY;
        slot.z    = this.realZ;
        slot.vx   = vx;
        slot.vz   = vz;
        slot.tick = this.clientTick;
        slot.ts   = now;
        this.writeIndex = (this.writeIndex + 1) & BUFFER_MASK;
        this.count      = Math.min(this.count + 1, BUFFER_SIZE);

        // ── Jump peak detection ────────────────────────────────────────
        boolean peakSnap = false;
        if (this.jumpPeak.getValue() && this.prevYTracked) {
            double curDy = target.posY - this.prevTargetY;
            if (this.prevTargetDy > 0.01 && curDy <= 0.01) peakSnap = true;
            this.prevTargetDy = curDy;
        }
        this.prevTargetY  = target.posY;
        this.prevYTracked = true;

        // ── i-frame release ───────────────────────────────────────────
        boolean iframeSnap = target.hurtResistantTime > 0
                && target.hurtResistantTime <= this.releaseTiming.getValue();

        // Store velocity state for next tick (before early returns)
        this.prevVx         = vx;
        this.prevVz         = vz;
        this.prevSpeed      = speed;
        this.prevVelTracked = true;

        // ── Release / suspend ──────────────────────────────────────────
        if (iframeSnap || peakSnap || this.releasing) {
            this.releasing = false;
            this.clearOverride(target);
            this.count      = 0;
            this.writeIndex = 0;
            return;
        }

        if (this.suspendTicks > 0) {
            this.suspendTicks--;
            this.clearOverride(target);
            return;
        }

        // Warmup: let the buffer build a few ticks before we start overriding
        if (this.engageTick <= WARMUP_TICKS || this.count < WARMUP_TICKS + 1) {
            this.clearOverride(target);
            return;
        }

        // ── Adaptive delay calculation ─────────────────────────────────
        double baseDelay     = this.smoothPing * 0.55;
        double velFactor     = Math.min(speed * 35.0, 35.0);
        double comboBoost    = this.ticksSinceHit < 8 ? 12.0 : 0.0;
        // strafeDot < 0 means penalty; normalised so partial strafes scale naturally
        double strafePenalty = this.strafeDot < 0
                ? (baseDelay + velFactor) * 0.30 * Math.min(-this.strafeDot / 0.5, 1.0)
                : 0.0;
        double tpsFactor     = this.avgTickMs / 50.0;

        double raw = (baseDelay + velFactor + comboBoost - strafePenalty) * tpsFactor;
        this.adaptiveDelay = Math.max(45.0, Math.min(raw, 160.0));

        // ── Predictive snapshot selection ──────────────────────────────
        // Convert delay to ticks, add forward bias for moving targets.
        // Bias shifts selection slightly toward the present so hits land
        // ahead of rather than behind a running target.
        double delayTicks     = this.adaptiveDelay / 50.0;
        double predictionBias = Math.min(speed * 0.4, 0.8);
        double targetTickD    = this.clientTick - delayTicks + predictionBias;

        Snapshot best     = null;
        double   bestDiff = Double.MAX_VALUE;

        // Iterate from oldest to newest for correct temporal ordering
        int readStart = this.count < BUFFER_SIZE ? 0 : this.writeIndex;
        for (int i = 0; i < this.count; i++) {
            Snapshot s    = this.ring[(readStart + i) & BUFFER_MASK];
            double   diff = Math.abs(s.tick - targetTickD);
            if (diff < bestDiff) {
                bestDiff = diff;
                best     = s;
            }
            // Once we've passed the target tick and the gap is growing,
            // no later snapshot will be closer — early exit
            if (s.tick > targetTickD && diff > bestDiff) break;
        }

        if (best == null) { this.clearOverride(target); return; }

        // ── Safety: max offset cap ─────────────────────────────────────
        double ox         = best.x - this.realX;
        double oz         = best.z - this.realZ;
        double offsetDist = Math.sqrt(ox * ox + oz * oz);
        if (offsetDist > 3.1) { this.clearOverride(target); return; }

        // ── Safety: snapshot must be within attack range ───────────────
        double px = best.x - mc.thePlayer.posX;
        double pz = best.z - mc.thePlayer.posZ;
        if (Math.sqrt(px * px + pz * pz) > this.maxDistance.getValue()) {
            this.clearOverride(target); return;
        }

        // ── Apply logical hitbox — exact snapshot, never interpolated ──
        this.appliedOffsetX = ox;
        this.appliedOffsetZ = oz;

        target.posX = best.x;
        target.posZ = best.z;

        // Reconstruct override BB from stored real BB — no cumulative drift
        target.setEntityBoundingBox(this.realBB.offset(ox, 0.0, oz));

        // ── Visual smoothing — prevPos lerp only ───────────────────────
        // Rate scales with speed: faster targets need faster convergence
        // so the rendered model stays aligned with the logical hitbox.
        double visualLerp = Math.max(0.24, Math.min(0.24 + speed * 0.38, 0.68));
        target.prevPosX    += (target.posX - target.prevPosX) * visualLerp;
        target.prevPosZ    += (target.posZ - target.prevPosZ) * visualLerp;
        target.lastTickPosX = target.prevPosX;
        target.lastTickPosZ = target.prevPosZ;

        this.active = true;
    }

    // ── Clear Override ────────────────────────────────────────────────────

    /**
     * Restores entity to its real position.
     * prevPos lerps toward real for a smooth visual return — no pop.
     */
    private void clearOverride(EntityLivingBase e) {
        if (!this.active) return;

        e.posX = this.realX;
        e.posZ = this.realZ;

        // Restore BB from real BB if available, otherwise use offset reversal
        if (this.realBB != null) {
            e.setEntityBoundingBox(this.realBB);
        } else {
            e.setEntityBoundingBox(e.getEntityBoundingBox()
                    .offset(-this.appliedOffsetX, 0.0, -this.appliedOffsetZ));
        }

        // Smooth visual return — lerp prevPos toward real
        e.prevPosX     += (this.realX - e.prevPosX) * 0.45;
        e.prevPosZ     += (this.realZ - e.prevPosZ) * 0.45;
        e.lastTickPosX  = e.prevPosX;
        e.lastTickPosZ  = e.prevPosZ;

        this.appliedOffsetX = 0.0;
        this.appliedOffsetZ = 0.0;
        this.active         = false;
    }

    // ── Packet Handler ────────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        // Server teleports us: hard reset — our position history is invalid
        if (event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.resetState();
            return;
        }

        // S19 opcode 2: server confirms damage landed — now engage
        if (event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus s19 = (S19PacketEntityStatus) event.getPacket();
            if (s19.getOpCode() == 2 && mc.theWorld != null
                    && this.pendingTargetId != -1) {
                Entity hit = s19.getEntity(mc.theWorld);
                if (hit != null && hit.getEntityId() == this.pendingTargetId) {
                    if (this.targetId != -1 && this.targetId != this.pendingTargetId) {
                        // Target switched — clear old override cleanly
                        EntityLivingBase old = this.getCurrentTarget();
                        if (old != null) this.clearOverride(old);
                        this.count      = 0;
                        this.writeIndex = 0;
                    }
                    this.targetId        = this.pendingTargetId;
                    this.engaged         = true;
                    this.engageTick      = 0;
                    this.appliedOffsetX  = 0.0;
                    this.appliedOffsetZ  = 0.0;
                    this.prevVelTracked  = false;
                    this.prevYTracked    = false;
                    this.ticksSinceHit   = 0;
                    this.releasing       = false;
                }
            }
            return;
        }

        // C02 attack sent: register pending target, wait for S19 confirmation
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

    // ── KillAura Sync API ─────────────────────────────────────────────────

    /**
     * Called when KillAura's burst ends.
     * Releases the override during the pause window so the target's true
     * position is visible and mid-trade timing can fire cleanly.
     */
    public void onKillAuraBurstComplete() {
        if (!this.isEnabled()) return;
        this.releasing = true;
    }

    /**
     * Called when KillAura's pause ends.
     * Clears the snapshot buffer so the next burst builds fresh history
     * from the target's current position.
     */
    public void onKillAuraResuming() {
        if (!this.isEnabled()) return;
        this.releasing  = false;
        this.count      = 0;
        this.writeIndex = 0;
    }

    /**
     * Called when KillAura switches target.
     * Clears override on old target, resets engagement for new target.
     * Since KillAura has already landed a hit, bypass the S19 warmup.
     */
    public void syncTarget(int entityId) {
        if (!this.isEnabled()) return;
        if (this.targetId != entityId) {
            EntityLivingBase cur = this.getCurrentTarget();
            if (cur != null) this.clearOverride(cur);
            this.count      = 0;
            this.writeIndex = 0;
        }
        this.pendingTargetId = entityId;
        this.targetId        = entityId;
        this.engaged         = true;
        this.engageTick      = 0;
        this.releasing       = false;
        this.prevVelTracked  = false;
        this.prevYTracked    = false;
        this.suspendTicks    = 0;
        this.ticksSinceHit   = 0;
        this.appliedOffsetX  = 0.0;
        this.appliedOffsetZ  = 0.0;
        EntityLivingBase e = this.getCurrentTarget();
        if (e != null) { this.realX = e.posX; this.realZ = e.posZ; }
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

    private void resetState() {
        if (this.engaged && this.targetId != -1) {
            EntityLivingBase e = this.getCurrentTarget();
            if (e != null) this.clearOverride(e);
        }
        this.count           = 0;
        this.writeIndex      = 0;
        this.active          = false;
        this.engaged         = false;
        this.releasing       = false;
        this.engageTick      = 0;
        this.targetId        = -1;
        this.pendingTargetId = -1;
        this.prevVelTracked  = false;
        this.prevYTracked    = false;
        this.prevVx          = 0.0;
        this.prevVz          = 0.0;
        this.prevSpeed       = 0.0;
        this.appliedOffsetX  = 0.0;
        this.appliedOffsetZ  = 0.0;
        this.suspendTicks    = 0;
        this.ticksSinceHit   = 999;
        this.adaptiveDelay   = 100.0;
        this.strafeDot       = 1.0;
        this.realBB          = null;
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
        return new String[]{String.format("%.0fms", this.adaptiveDelay)};
    }
}
