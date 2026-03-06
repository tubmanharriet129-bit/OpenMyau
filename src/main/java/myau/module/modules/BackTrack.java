package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.awt.*;
import java.util.*;

public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // -------------------------------------------------------
    // Properties
    // -------------------------------------------------------

    public final BooleanProperty legit = new BooleanProperty("legit", false);
    public final BooleanProperty releaseOnHit = new BooleanProperty("release-on-hit", true, this.legit::getValue);

    /**
     * Maximum ms to hold packets. Hard capped at 150ms — anything above
     * this is consistently detected by Hypixel's anticheat.
     */
    public final IntProperty delay = new IntProperty("delay", 100, 0, 150);

    public final FloatProperty hitRange = new FloatProperty("range", 3.0F, 3.0F, 10.0F);
    public final BooleanProperty adaptive = new BooleanProperty("adaptive", true);
    public final BooleanProperty useKillAura = new BooleanProperty("use-killaura", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final BooleanProperty teams = new BooleanProperty("teams", true);

    /**
     * Gradually releases queued packets over several ticks instead of
     * dumping them all at once. Prevents the client-side teleport snap
     * that occurs when a large queue flushes simultaneously, and looks
     * far more natural to observers and server-side validation.
     */
    public final BooleanProperty smoothRelease = new BooleanProperty("smooth-release", true);

    /**
     * How many packets to release per tick during a smooth release.
     * Lower values = smoother but slower. Higher = faster but snappier.
     */
    public final IntProperty smoothReleaseRate = new IntProperty("smooth-rate", 3, 1, 10,
            this.smoothRelease::getValue);

    /**
     * Cap on how many packets can queue at once. Prevents memory buildup
     * and limits the visual snap distance on release. When the queue hits
     * this limit, oldest packets are dropped rather than processed.
     */
    public final IntProperty maxQueueSize = new IntProperty("max-queue", 20, 5, 50);

    /**
     * Predict target movement using their velocity vector. If the target
     * is moving toward you, release early before their real position
     * reaches you — reducing the chance of them walking through your hitbox
     * while packets are frozen.
     */
    public final BooleanProperty predictiveRelease = new BooleanProperty("predictive-release", true);

    /**
     * Distance threshold for predictive release. If the target's predicted
     * position in the next tick is within this many blocks of you, release.
     */
    public final FloatProperty predictThreshold = new FloatProperty("predict-threshold", 0.5F, 0.1F, 2.0F,
            this.predictiveRelease::getValue);

    public final ModeProperty showPosition = new ModeProperty("show-position", 1, new String[]{"NONE", "DEFAULT", "HUD"});

    /**
     * Double hit mid-air: holds packets while the target is ascending,
     * then releases at the peak of their jump. The server sees hit 1 at
     * the frozen lower position and hit 2 at the real peak position in
     * rapid succession — before i-frames fully expire — producing a double
     * hit that deals knockback twice. Works best combined with KillAura
     * since the second hit needs to fire immediately on release.
     */
    public final BooleanProperty doubleHit = new BooleanProperty("double-hit", false);

    // -------------------------------------------------------
    // Internal state
    // -------------------------------------------------------

    private final Queue<Packet> incomingPackets = new LinkedList<>();
    private final Queue<Packet> outgoingPackets = new LinkedList<>();
    private final Map<Integer, Vec3> realPositions = new HashMap<>();
    private final Map<Integer, Vec3> lastRealPositions = new HashMap<>(); // for velocity estimation
    private final Map<Integer, Double> yVelocities = new HashMap<>();    // for jump peak detection

    private KillAura killAura;
    private EntityLivingBase target;
    private Vec3 lastRealPos;
    private Vec3 currentRealPos;
    private long lastReleaseTime;
    private boolean releasing = false; // smooth release in progress

    public BackTrack() {
        super("BackTrack", false);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (entityLivingBase == mc.thePlayer || entityLivingBase == mc.thePlayer.ridingEntity) {
            return false;
        } else if (entityLivingBase == mc.getRenderViewEntity()
                || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
            return false;
        } else if (entityLivingBase.deathTime > 0) {
            return false;
        } else if (entityLivingBase instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entityLivingBase;
            if (TeamUtil.isFriend(player)) return false;
            return (!this.teams.getValue() || !TeamUtil.isSameTeam(player))
                    && (!this.botCheck.getValue() || !TeamUtil.isBot(player));
        } else {
            return true;
        }
    }

    private EntityLivingBase getTargetEntity() {
        if (this.useKillAura.getValue() && this.killAura != null) {
            return this.killAura.getTarget();
        }

        EntityLivingBase closest = null;
        double closestDist = this.hitRange.getValue();

        for (Object obj : mc.theWorld.loadedEntityList) {
            if (!(obj instanceof EntityLivingBase)) continue;
            EntityLivingBase entity = (EntityLivingBase) obj;
            if (!this.isValidTarget(entity)) continue;
            double dist = mc.thePlayer.getDistanceToEntity(entity);
            if (dist < closestDist) {
                closest = entity;
                closestDist = dist;
            }
        }

        return closest;
    }

    /**
     * Estimate where the target will be next tick using their last two
     * known real positions as a velocity vector. Used by predictive release
     * to bail out early if the target is closing the gap.
     */
    private Vec3 predictNextPosition(int entityId) {
        Vec3 current = this.realPositions.get(entityId);
        Vec3 last    = this.lastRealPositions.get(entityId);
        if (current == null || last == null) return current;

        double dx = current.xCoord - last.xCoord;
        double dy = current.yCoord - last.yCoord;
        double dz = current.zCoord - last.zCoord;

        return new Vec3(
                current.xCoord + dx,
                current.yCoord + dy,
                current.zCoord + dz
        );
    }

    /**
     * Returns true when the target is at the peak of a jump — their Y velocity
     * has just transitioned from positive (ascending) to zero or negative (peak/descending).
     * This is the optimal moment to release: both hits land as close together as
     * possible, and the descending phase may grant a critical hit on the second hit.
     */
    private boolean isAtJumpPeak(int entityId) {
        Vec3 current = this.realPositions.get(entityId);
        Vec3 last    = this.lastRealPositions.get(entityId);
        if (current == null || last == null) return false;

        double currentDy = current.yCoord - last.yCoord;
        Double lastDy    = this.yVelocities.get(entityId);
        if (lastDy == null) return false;

        // Peak: was ascending last tick, now stationary or descending
        return lastDy > 0.01 && currentDy <= 0.01;
    }

    private boolean shouldQueue() {
        if (this.target == null) return false;

        Vec3 real = this.realPositions.get(this.target.getEntityId());
        if (real == null) return false;

        // Hard cutoff: never queue beyond the configured delay cap
        if (System.currentTimeMillis() - this.lastReleaseTime > this.delay.getValue()) {
            return false;
        }

        double distReal    = mc.thePlayer.getDistance(real.xCoord, real.yCoord, real.zCoord);
        double distCurrent = mc.thePlayer.getDistanceToEntity(this.target);

        // Predictive release: if target's next predicted position is very
        // close, release now rather than waiting for them to arrive
        if (this.predictiveRelease.getValue()) {
            Vec3 predicted = this.predictNextPosition(this.target.getEntityId());
            if (predicted != null) {
                double distPredicted = mc.thePlayer.getDistance(
                        predicted.xCoord, predicted.yCoord, predicted.zCoord
                );
                if (distPredicted <= distReal + this.predictThreshold.getValue()) {
                    return false;
                }
            }
        }

        if (!this.adaptive.getValue()) {
            return distReal + 0.15 < distCurrent;
        }

        return distReal < distCurrent;
    }

    // -------------------------------------------------------
    // Packet release
    // -------------------------------------------------------

    /**
     * Processes queued incoming packets. In smooth mode, only releases
     * smoothReleaseRate packets per call. In instant mode, flushes all.
     */
    private void releaseIncoming() {
        if (mc.getNetHandler() == null) return;

        if (this.smoothRelease.getValue()) {
            int rate = this.smoothReleaseRate.getValue();
            for (int i = 0; i < rate && !this.incomingPackets.isEmpty(); i++) {
                this.incomingPackets.poll().processPacket(mc.getNetHandler());
            }
            if (this.incomingPackets.isEmpty()) {
                this.releasing = false;
            }
        } else {
            while (!this.incomingPackets.isEmpty()) {
                this.incomingPackets.poll().processPacket(mc.getNetHandler());
            }
            this.releasing = false;
        }

        this.lastReleaseTime = System.currentTimeMillis();
    }

    private void releaseOutgoing() {
        if (this.smoothRelease.getValue()) {
            int rate = this.smoothReleaseRate.getValue();
            for (int i = 0; i < rate && !this.outgoingPackets.isEmpty(); i++) {
                PacketUtil.sendPacketNoEvent(this.outgoingPackets.poll());
            }
        } else {
            while (!this.outgoingPackets.isEmpty()) {
                PacketUtil.sendPacketNoEvent(this.outgoingPackets.poll());
            }
        }

        this.lastReleaseTime = System.currentTimeMillis();
    }

    /** Triggers a full flush of all queued packets, respecting smooth mode. */
    private void releaseAll() {
        this.releasing = true;
        this.releaseIncoming();
        this.releaseOutgoing();
    }

    /** Hard flush — bypasses smooth mode entirely. Used for emergency clears. */
    private void forceReleaseAll() {
        if (mc.getNetHandler() != null) {
            while (!this.incomingPackets.isEmpty()) {
                this.incomingPackets.poll().processPacket(mc.getNetHandler());
            }
        }
        while (!this.outgoingPackets.isEmpty()) {
            PacketUtil.sendPacketNoEvent(this.outgoingPackets.poll());
        }
        this.releasing = false;
        this.lastReleaseTime = System.currentTimeMillis();
    }

    private boolean blockIncoming(Packet<?> p) {
        if (!this.adaptive.getValue()) {
            if (p instanceof S12PacketEntityVelocity || p instanceof S27PacketExplosion) {
                return false;
            }
            return p instanceof S14PacketEntity
                    || p instanceof S18PacketEntityTeleport
                    || p instanceof S19PacketEntityHeadLook
                    || p instanceof S0FPacketSpawnMob;
        }

        return p instanceof S12PacketEntityVelocity
                || p instanceof S27PacketExplosion
                || p instanceof S14PacketEntity
                || p instanceof S18PacketEntityTeleport
                || p instanceof S19PacketEntityHeadLook
                || p instanceof S0FPacketSpawnMob;
    }

    private boolean blockOutgoing(Packet<?> p) {
        return p instanceof C03PacketPlayer
                || p instanceof C02PacketUseEntity
                || p instanceof C0APacketAnimation
                || p instanceof C0BPacketEntityAction
                || p instanceof C08PacketPlayerBlockPlacement
                || p instanceof C07PacketPlayerDigging
                || p instanceof C09PacketHeldItemChange
                || p instanceof C00PacketKeepAlive
                || p instanceof C01PacketPing;
    }

    // -------------------------------------------------------
    // Packet handlers
    // -------------------------------------------------------

    private void handleIncoming(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        // Track real positions — always process these regardless of queue state
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            Entity e = p.getEntity(mc.theWorld);
            if (e != null) {
                int id = e.getEntityId();
                Vec3 current = this.realPositions.getOrDefault(id, new Vec3(0, 0, 0));
                this.lastRealPositions.put(id, current);
                Vec3 next = current.addVector(
                        p.func_149062_c() / 32.0,
                        p.func_149061_d() / 32.0,
                        p.func_149064_e() / 32.0
                );
                // Record Y velocity for jump peak detection
                this.yVelocities.put(id, next.yCoord - current.yCoord);
                this.realPositions.put(id, next);
            }
        }

        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            int id = p.getEntityId();
            Vec3 current = this.realPositions.get(id);
            if (current != null) this.lastRealPositions.put(id, current);
            Vec3 next = new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0);
            if (current != null) this.yVelocities.put(id, next.yCoord - current.yCoord);
            this.realPositions.put(id, next);
        }

        if (this.shouldQueue()) {
            if (this.blockIncoming(packet)) {
                // Enforce queue size cap — drop oldest if full
                if (this.incomingPackets.size() >= this.maxQueueSize.getValue()) {
                    this.incomingPackets.poll();
                }
                this.incomingPackets.add(packet);
                event.setCancelled(true);
            }
        } else {
            this.releaseAll();
        }
    }

    private void handleOutgoing(PacketEvent event) {
        if (!this.legit.getValue()) return;

        Packet<?> packet = event.getPacket();

        if (this.shouldQueue()) {
            if (this.blockOutgoing(packet)) {
                if (this.outgoingPackets.size() >= this.maxQueueSize.getValue()) {
                    this.outgoingPackets.poll();
                }
                this.outgoingPackets.add(packet);
                event.setCancelled(true);
            }
        } else {
            this.releaseOutgoing();
        }
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    @Override
    public void onEnabled() {
        Module m = Myau.moduleManager.getModule(KillAura.class);
        if (m instanceof KillAura) {
            this.killAura = (KillAura) m;
        }

        this.incomingPackets.clear();
        this.outgoingPackets.clear();
        this.realPositions.clear();
        this.lastRealPositions.clear();
        this.yVelocities.clear();
        this.lastRealPos    = null;
        this.currentRealPos = null;
        this.releasing      = false;
        this.lastReleaseTime = System.currentTimeMillis();
    }

    @Override
    public void onDisabled() {
        this.forceReleaseAll();
        this.incomingPackets.clear();
        this.outgoingPackets.clear();
        this.realPositions.clear();
        this.lastRealPositions.clear();
        this.yVelocities.clear();
        this.lastRealPos    = null;
        this.currentRealPos = null;
        this.releasing      = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        Module scaffold = Myau.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            this.forceReleaseAll();
            this.incomingPackets.clear();
            this.outgoingPackets.clear();
            return;
        }

        if (this.useKillAura.getValue() && this.killAura != null) {
            this.target = this.killAura.getTarget();
        }

        if (event.getType() == EventType.RECEIVE) {
            this.handleIncoming(event);
        } else if (event.getType() == EventType.SEND) {
            this.handleOutgoing(event);
        }
    }

    @EventTarget(Priority.LOW)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        switch (event.getType()) {
            case PRE:
                EntityLivingBase newTarget = this.getTargetEntity();

                if (this.target != newTarget) {
                    // Target changed — smooth-release old queue before switching
                    this.releaseAll();
                    this.lastRealPos    = null;
                    this.currentRealPos = null;
                }

                this.target = newTarget;

                // Continue smooth release tick-by-tick if in progress
                if (this.releasing) {
                    this.releaseIncoming();
                    this.releaseOutgoing();
                }

                if (this.target == null) return;

                Vec3 real = this.realPositions.get(this.target.getEntityId());
                if (real == null) return;

                double distReal    = mc.thePlayer.getDistance(real.xCoord, real.yCoord, real.zCoord);
                double distCurrent = mc.thePlayer.getDistanceToEntity(this.target);

                // Double hit: release at jump peak so both hits land in tight succession.
                // Bypasses smooth release for instant flush — timing is critical here.
                if (this.doubleHit.getValue()
                        && !this.incomingPackets.isEmpty()
                        && this.isAtJumpPeak(this.target.getEntityId())) {
                    this.forceReleaseAll();
                }

                // Release when player takes damage — avoid holding packets while getting combo'd
                if (mc.thePlayer.maxHurtTime > 0 && mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime) {
                    this.releaseAll();
                }

                // Release when delay cap is hit
                if (System.currentTimeMillis() - this.lastReleaseTime > this.delay.getValue()) {
                    this.releaseAll();
                }

                // Release when real position is outside hit range
                if (distReal > this.hitRange.getValue()) {
                    this.releaseAll();
                }

                if (this.adaptive.getValue()) {
                    // Release when target's real position is actually closer than frozen position
                    if (distCurrent <= distReal) {
                        this.releaseAll();
                    }

                    // Release when target is moving toward us (approaching)
                    if (this.lastRealPos != null) {
                        double lastDist = mc.thePlayer.getDistance(
                                this.lastRealPos.xCoord,
                                this.lastRealPos.yCoord,
                                this.lastRealPos.zCoord
                        );
                        if (distReal < lastDist) {
                            this.releaseAll();
                        }
                    }
                }

                // Legit mode: release when hit registers
                if (this.legit.getValue() && this.releaseOnHit.getValue() && this.target.hurtTime == 1) {
                    this.releaseAll();
                }
                break;

            case POST:
                Vec3 savedPosition = this.realPositions.get(
                        this.target != null ? this.target.getEntityId() : -1
                );
                if (this.currentRealPos == null) {
                    this.lastRealPos = savedPosition;
                } else {
                    this.lastRealPos = this.currentRealPos;
                }
                this.currentRealPos = savedPosition;
        }
    }

    @EventTarget(Priority.HIGH)
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled()) return;
        if (this.showPosition.getValue() == 0) return;
        if (this.target == null) return;

        Vec3 real = this.realPositions.get(this.target.getEntityId());
        if (real == null || this.lastRealPos == null || this.currentRealPos == null) return;

        Color color = new Color(-1);
        switch (this.showPosition.getValue()) {
            case 1:
                if (this.target instanceof EntityPlayer) {
                    color = TeamUtil.getTeamColor((EntityPlayer) this.target, 1.0F);
                } else {
                    color = new Color(255, 0, 0);
                }
                break;
            case 2:
                color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
        }

        double x = RenderUtil.lerpDouble(this.currentRealPos.xCoord, this.lastRealPos.xCoord, event.getPartialTicks());
        double y = RenderUtil.lerpDouble(this.currentRealPos.yCoord, this.lastRealPos.yCoord, event.getPartialTicks());
        double z = RenderUtil.lerpDouble(this.currentRealPos.zCoord, this.lastRealPos.zCoord, event.getPartialTicks());

        float size = this.target.getCollisionBorderSize();
        AxisAlignedBB aabb = new AxisAlignedBB(
                x - (double) this.target.width  / 2.0,
                y,
                z - (double) this.target.width  / 2.0,
                x + (double) this.target.width  / 2.0,
                y + (double) this.target.height,
                z + (double) this.target.width  / 2.0
        )
                .expand(size, size, size)
                .offset(
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
