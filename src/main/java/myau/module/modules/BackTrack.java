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
import net.minecraft.util.AxisAlignedBB;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * BackTrack — XZ-only position snapshot desync.
 *
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  NO packets are ever cancelled or buffered.                            ║
 * ║  Entity position packets all process normally — Y is always live.      ║
 * ║                                                                        ║
 * ║  Instead, each tick we manually override the target entity's posX/posZ ║
 * ║  to an older snapshot, while posY is always the real current value.    ║
 * ║  KillAura attacks the overridden XZ position.                          ║
 * ║  Your own movement is completely untouched.                            ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * Why this fixes the visual glitch:
 *   Previous approach cancelled S14/S18 packets entirely, which froze Y too.
 *   Jumping targets appeared underground; targets on slopes appeared floating.
 *   This approach never touches packets — Y updates normally every tick,
 *   only XZ is rewound to an older snapshot.
 *
 * Desync mechanism:
 *   Every tick we record the entity's real (posX, posZ).
 *   We then set entity.posX/posZ to the snapshot from delayMs ago.
 *   The server knows where they actually are. Your C02 attack packet
 *   references the overridden (older) XZ position, giving effective reach.
 *   After the tick, we restore real posX/posZ so nothing else breaks.
 *
 * Double-hit cycle:
 *   When hurtResistantTime <= iFrameThreshold, we stop overriding XZ —
 *   entity snaps to real position, KillAura fires at the real position
 *   as i-frames expire. Buffer immediately rearms for the next cycle.
 */
public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Settings ──────────────────────────────────────────────────────────

    public final IntProperty delayMs = new IntProperty("hold-time", 100, 20, 150);
    public final FloatProperty minDistance = new FloatProperty("min-dist", 1.0f, 0.5f, 4.0f);
    public final FloatProperty maxDistance = new FloatProperty("max-dist", 4.0f, 1.0f, 6.0f);
    public final IntProperty iFrameThreshold = new IntProperty("iframe-threshold", 2, 1, 10);
    public final BooleanProperty jumpPeak = new BooleanProperty("jump-peak", true);
    public final ModeProperty showPosition = new ModeProperty("show-position", 1,
            new String[]{"NONE", "DEFAULT", "HUD"});

    // ── Snapshot record ───────────────────────────────────────────────────

    private static final class Snapshot {
        final double x, z;
        final long timestamp;
        Snapshot(double x, double z, long ts) { this.x = x; this.z = z; this.timestamp = ts; }
    }

    // ── State ─────────────────────────────────────────────────────────────

    /** Circular buffer of XZ snapshots for the target. */
    private final Deque<Snapshot> snapshots = new ArrayDeque<>();

    /** True while we are actively overriding the entity's XZ position. */
    private boolean active = false;

    /** The entity ID we are currently tracking. -1 = none. */
    private int targetId = -1;

    /** Whether the first hit has been detected. */
    private boolean engaged = false;

    /** Real posX/posZ saved before override so we can restore after. */
    private double realX, realZ;
    private double prevRealX, prevRealZ;
    private double lastTickRealX, lastTickRealZ;

    /** Jump peak tracking. */
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
        this.restorePosition();
        this.resetState();
    }

    // ── Per-tick: record snapshot and apply override ───────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() != EventType.PRE) return;

        // Restore real position from last tick before doing anything else
        this.restorePosition();

        if (!this.engaged) return;

        EntityLivingBase target = this.getCurrentTarget();
        if (target == null) {
            this.resetState();
            return;
        }

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) {
            this.resetState();
            return;
        }

        long now = System.currentTimeMillis();

        // Record real XZ snapshot this tick
        this.snapshots.addLast(new Snapshot(target.posX, target.posZ, now));

        // Trim snapshots older than delayMs * 2 to prevent unbounded growth
        while (this.snapshots.size() > 64) this.snapshots.pollFirst();

        // Double-hit triggers — stop overriding so entity snaps to real pos
        boolean shouldOverride = true;

        if (target.hurtResistantTime > 0
                && target.hurtResistantTime <= this.iFrameThreshold.getValue()) {
            // i-frame threshold: let entity snap to real position for double hit
            shouldOverride = false;
        }

        if (this.jumpPeak.getValue() && this.prevYTracked) {
            double curDy = target.posY - this.prevTargetY;
            if (this.prevTargetDy > 0.01 && curDy <= 0.01) {
                shouldOverride = false; // jump peak: snap for double hit
            }
        }

        this.updatePrevY(target);

        if (!shouldOverride) {
            this.active = false;
            this.snapshots.clear(); // rearm: start fresh snapshot buffer
            return;
        }

        // Find the snapshot that is closest to delayMs ago
        Snapshot delayed = null;
        for (Snapshot s : this.snapshots) {
            if (now - s.timestamp >= this.delayMs.getValue()) {
                delayed = s;
            } else {
                break;
            }
        }

        if (delayed == null) {
            // Not enough history yet — no override
            this.active = false;
            return;
        }

        // Save real position so we can restore it next tick
        this.realX          = target.posX;
        this.realZ          = target.posZ;
        this.prevRealX      = target.prevPosX;
        this.prevRealZ      = target.prevPosZ;
        this.lastTickRealX  = target.lastTickPosX;
        this.lastTickRealZ  = target.lastTickPosZ;

        // Override entity XZ to the delayed snapshot — Y is untouched
        target.posX         = delayed.x;
        target.posZ         = delayed.z;
        target.prevPosX     = delayed.x;
        target.prevPosZ     = delayed.z;
        target.lastTickPosX = delayed.x;
        target.lastTickPosZ = delayed.z;

        // Update bounding box so hit detection uses overridden position
        target.setEntityBoundingBox(target.getEntityBoundingBox()
                .offset(delayed.x - this.realX, 0, delayed.z - this.realZ));

        this.active = true;
    }

    // ── Restore position after each tick ─────────────────────────────────

    /**
     * Restores the entity's real XZ position after each tick.
     * Called at the top of every onTick so the override only persists
     * for exactly one tick — preventing permanent position corruption.
     */
    private void restorePosition() {
        if (!this.active) return;
        EntityLivingBase target = this.getCurrentTarget();
        if (target == null) { this.active = false; return; }

        double dx = this.realX - target.posX;
        double dz = this.realZ - target.posZ;

        target.posX         = this.realX;
        target.posZ         = this.realZ;
        target.prevPosX     = this.prevRealX;
        target.prevPosZ     = this.prevRealZ;
        target.lastTickPosX = this.lastTickRealX;
        target.lastTickPosZ = this.lastTickRealZ;

        // Restore bounding box
        target.setEntityBoundingBox(target.getEntityBoundingBox()
                .offset(dx, 0, dz));

        this.active = false;
    }

    // ── Packet handler — only for engagement detection ────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        // S08 teleport (us): reset engagement
        if (event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.restorePosition();
            this.resetState();
            return;
        }

        // Outbound C02 attack: detect engagement and target
        if (event.getType() == EventType.SEND
                && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;
            Entity entity = use.getEntityFromWorld(mc.theWorld);
            if (!(entity instanceof EntityLivingBase)) return;
            double dist = mc.thePlayer.getDistanceToEntity(entity);
            if (dist < this.minDistance.getValue() || dist > this.maxDistance.getValue()) return;

            int id = entity.getEntityId();
            if (!this.engaged || this.targetId != id) {
                if (this.targetId != -1 && this.targetId != id) {
                    this.restorePosition();
                    this.snapshots.clear();
                }
                this.targetId     = id;
                this.engaged      = true;
                this.prevYTracked = false;
            }
        }
    }

    // ── KillAura sync API ─────────────────────────────────────────────────

    /**
     * Called by KillAura when its burst completes and pause begins.
     * Stop overriding so entity snaps to real position during pause.
     */
    public void onKillAuraBurstComplete() {
        if (!this.isEnabled()) return;
        this.restorePosition();
        this.snapshots.clear();
        this.active = false;
    }

    /**
     * Called by KillAura when its pause ends and burst resumes.
     * Snapshots will start accumulating again in the next onTick.
     */
    public void onKillAuraResuming() {
        if (!this.isEnabled()) return;
        this.snapshots.clear(); // fresh window for new burst
    }

    /**
     * Called by KillAura when it switches to a new target.
     */
    public void syncTarget(int entityId) {
        if (!this.isEnabled()) return;
        if (this.targetId != entityId) {
            this.restorePosition();
            this.snapshots.clear();
            this.active = false;
        }
        this.targetId     = entityId;
        this.engaged      = true;
        this.prevYTracked = false;
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
        this.restorePosition();
        this.snapshots.clear();
        this.active       = false;
        this.engaged      = false;
        this.targetId     = -1;
        this.prevYTracked = false;
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
