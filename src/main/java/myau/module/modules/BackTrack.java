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

    public final BooleanProperty legit        = new BooleanProperty("legit", false);
    public final BooleanProperty releaseOnHit = new BooleanProperty("release-on-hit", true, this.legit::getValue);

    /**
     * Maximum ms to hold packets per cycle.
     * Capped at 150ms — Hypixel flags higher values reliably.
     */
    public final IntProperty delay = new IntProperty("delay", 100, 0, 150);

    public final FloatProperty hitRange    = new FloatProperty("range", 3.0F, 3.0F, 10.0F);
    public final BooleanProperty useKillAura = new BooleanProperty("use-killaura", true);
    public final BooleanProperty botCheck    = new BooleanProperty("bot-check", true);
    public final BooleanProperty teams       = new BooleanProperty("teams", true);

    /**
     * Smooth release: trickle packets out over several ticks instead of
     * a single-frame dump. Looks more natural to observers and AC.
     */
    public final BooleanProperty smoothRelease    = new BooleanProperty("smooth-release", true);
    public final IntProperty     smoothReleaseRate = new IntProperty("smooth-rate", 3, 1, 10,
            this.smoothRelease::getValue);

    /** Hard cap on queue size. Prevents buildup and limits snap distance. */
    public final IntProperty maxQueueSize = new IntProperty("max-queue", 20, 5, 50);

    /**
     * Release when i-frames are about to expire (hurtResistantTime <= iFrameThreshold).
     * This times the release so the second hit from KillAura lands the instant
     * the target becomes vulnerable again — guaranteeing a double damage window.
     * Lower threshold = more aggressive timing (release closer to expiry).
     */
    public final IntProperty iFrameThreshold = new IntProperty("iframe-threshold", 2, 1, 5);

    /**
     * Double hit on jump peak: also releases when target transitions from
     * ascending to descending, independent of i-frame state.
     */
    public final BooleanProperty jumpPeakRelease = new BooleanProperty("jump-peak", true);

    public final ModeProperty showPosition = new ModeProperty("show-position", 1,
            new String[]{"NONE", "DEFAULT", "HUD"});

    // -------------------------------------------------------
    // Internal state
    // -------------------------------------------------------

    private final Queue<Packet>          incomingPackets  = new LinkedList<>();
    private final Queue<Packet>          outgoingPackets  = new LinkedList<>();
    private final Map<Integer, Vec3>     realPositions    = new HashMap<>();
    private final Map<Integer, Vec3>     lastRealPositions = new HashMap<>();
    private final Map<Integer, Double>   yVelocities      = new HashMap<>();

    private KillAura          killAura;
    private EntityLivingBase  target;
    private Vec3               lastRealPos;
    private Vec3               currentRealPos;
    private long               lastReleaseTime;
    private boolean            releasing       = false;
    private boolean            firstHitLanded  = false;

    public BackTrack() {
        super("BackTrack", false);
    }

    // -------------------------------------------------------
    // Target helpers
    // -------------------------------------------------------

    private boolean isValidTarget(EntityLivingBase e) {
        if (e == mc.thePlayer || e == mc.thePlayer.ridingEntity) return false;
        if (e == mc.getRenderViewEntity() || e == mc.getRenderViewEntity().ridingEntity) return false;
        if (e.deathTime > 0) return false;
        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            if (TeamUtil.isFriend(p)) return false;
            return (!this.teams.getValue() || !TeamUtil.isSameTeam(p))
                    && (!this.botCheck.getValue() || !TeamUtil.isBot(p));
        }
        return true;
    }

    private EntityLivingBase getTargetEntity() {
        if (this.useKillAura.getValue() && this.killAura != null) {
            return this.killAura.getTarget();
        }
        EntityLivingBase closest   = null;
        double           closestDist = this.hitRange.getValue();
        for (Object obj : mc.theWorld.loadedEntityList) {
            if (!(obj instanceof EntityLivingBase)) continue;
            EntityLivingBase e = (EntityLivingBase) obj;
            if (!this.isValidTarget(e)) continue;
            double dist = mc.thePlayer.getDistanceToEntity(e);
            if (dist < closestDist) { closest = e; closestDist = dist; }
        }
        return closest;
    }

    // -------------------------------------------------------
    // Position / velocity helpers
    // -------------------------------------------------------

    private boolean isAtJumpPeak(int id) {
        Vec3    cur   = this.realPositions.get(id);
        Vec3    last  = this.lastRealPositions.get(id);
        Double  lastDy = this.yVelocities.get(id);
        if (cur == null || last == null || lastDy == null) return false;
        double curDy = cur.yCoord - last.yCoord;
        return lastDy > 0.01 && curDy <= 0.01;
    }

    // -------------------------------------------------------
    // Queue gate
    // -------------------------------------------------------

    private boolean shouldQueue() {
        if (this.target == null)       return false;
        if (!this.firstHitLanded)      return false;
        if (this.releasing)            return false;

        Vec3 real = this.realPositions.get(this.target.getEntityId());
        if (real == null) return false;

        // Hard time cap
        if (System.currentTimeMillis() - this.lastReleaseTime > this.delay.getValue()) return false;

        // Only hold while target's real pos is further than frozen pos —
        // the moment they're closer there's no advantage to holding
        double distReal    = mc.thePlayer.getDistance(real.xCoord, real.yCoord, real.zCoord);
        double distCurrent = mc.thePlayer.getDistanceToEntity(this.target);
        return distReal < distCurrent;
    }

    // -------------------------------------------------------
    // Release helpers
    // -------------------------------------------------------

    private void releaseIncoming() {
        if (mc.getNetHandler() == null) return;
        if (this.smoothRelease.getValue()) {
            int rate = this.smoothReleaseRate.getValue();
            for (int i = 0; i < rate && !this.incomingPackets.isEmpty(); i++) {
                this.incomingPackets.poll().processPacket(mc.getNetHandler());
            }
            if (this.incomingPackets.isEmpty()) this.releasing = false;
        } else {
            while (!this.incomingPackets.isEmpty())
                this.incomingPackets.poll().processPacket(mc.getNetHandler());
            this.releasing = false;
        }
        this.lastReleaseTime = System.currentTimeMillis();
    }

    private void releaseOutgoing() {
        if (this.smoothRelease.getValue()) {
            int rate = this.smoothReleaseRate.getValue();
            for (int i = 0; i < rate && !this.outgoingPackets.isEmpty(); i++)
                PacketUtil.sendPacketNoEvent(this.outgoingPackets.poll());
        } else {
            while (!this.outgoingPackets.isEmpty())
                PacketUtil.sendPacketNoEvent(this.outgoingPackets.poll());
        }
        this.lastReleaseTime = System.currentTimeMillis();
    }

    /** Initiates a release cycle, respecting smooth mode. */
    private void releaseAll() {
        this.releasing = true;
        this.releaseIncoming();
        this.releaseOutgoing();
    }

    /** Instant flush regardless of smooth mode. Used for timing-critical releases. */
    private void forceReleaseAll() {
        if (mc.getNetHandler() != null)
            while (!this.incomingPackets.isEmpty())
                this.incomingPackets.poll().processPacket(mc.getNetHandler());
        while (!this.outgoingPackets.isEmpty())
            PacketUtil.sendPacketNoEvent(this.outgoingPackets.poll());
        this.releasing       = false;
        this.lastReleaseTime = System.currentTimeMillis();
    }

    /**
     * Force-releases and immediately re-arms for the next hold cycle.
     * This is what turns a single double hit into a continuous loop —
     * every release is followed instantly by a new hold window so the
     * next KillAura hit can be double-hit again.
     */
    private void releaseAndRearm() {
        this.forceReleaseAll();
        // Re-arm immediately so the next hit starts a new hold cycle
        this.firstHitLanded = true;
    }

    // -------------------------------------------------------
    // Packet classification
    // -------------------------------------------------------

    private boolean blockIncoming(Packet<?> p) {
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

        // Always track real positions regardless of queue state
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            Entity e = p.getEntity(mc.theWorld);
            if (e != null) {
                int  id      = e.getEntityId();
                Vec3 current = this.realPositions.getOrDefault(id, new Vec3(0, 0, 0));
                this.lastRealPositions.put(id, current);
                Vec3 next = current.addVector(
                        p.func_149062_c() / 32.0,
                        p.func_149061_d() / 32.0,
                        p.func_149064_e() / 32.0
                );
                this.yVelocities.put(id, next.yCoord - current.yCoord);
                this.realPositions.put(id, next);
            }
        }

        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            int  id      = p.getEntityId();
            Vec3 current = this.realPositions.get(id);
            if (current != null) this.lastRealPositions.put(id, current);
            Vec3 next = new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0);
            if (current != null) this.yVelocities.put(id, next.yCoord - current.yCoord);
            this.realPositions.put(id, next);
        }

        if (this.shouldQueue()) {
            if (this.blockIncoming(packet)) {
                if (this.incomingPackets.size() >= this.maxQueueSize.getValue())
                    this.incomingPackets.poll(); // drop oldest
                this.incomingPackets.add(packet);
                event.setCancelled(true);
            }
        } else {
            this.releaseAll();
        }
    }

    private void handleOutgoing(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        // Arm on first outgoing attack
        if (packet instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK
                && !this.firstHitLanded) {
            this.firstHitLanded = true;
        }

        if (!this.legit.getValue()) return;

        if (this.shouldQueue()) {
            if (this.blockOutgoing(packet)) {
                if (this.outgoingPackets.size() >= this.maxQueueSize.getValue())
                    this.outgoingPackets.poll();
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
        if (m instanceof KillAura) this.killAura = (KillAura) m;
        this.clearState();
    }

    @Override
    public void onDisabled() {
        this.forceReleaseAll();
        this.clearState();
    }

    private void clearState() {
        this.incomingPackets.clear();
        this.outgoingPackets.clear();
        this.realPositions.clear();
        this.lastRealPositions.clear();
        this.yVelocities.clear();
        this.lastRealPos     = null;
        this.currentRealPos  = null;
        this.releasing       = false;
        this.firstHitLanded  = false;
        this.lastReleaseTime = System.currentTimeMillis();
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

        if (this.useKillAura.getValue() && this.killAura != null)
            this.target = this.killAura.getTarget();

        if (event.getType() == EventType.RECEIVE)     this.handleIncoming(event);
        else if (event.getType() == EventType.SEND)   this.handleOutgoing(event);
    }

    @EventTarget(Priority.LOW)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        switch (event.getType()) {
            case PRE:
                EntityLivingBase newTarget = this.getTargetEntity();

                if (this.target != newTarget) {
                    this.releaseAll();
                    this.lastRealPos    = null;
                    this.currentRealPos = null;
                    this.firstHitLanded = false;
                }

                this.target = newTarget;

                // Continue draining queue tick-by-tick during smooth release
                if (this.releasing) {
                    this.releaseIncoming();
                    this.releaseOutgoing();
                }

                if (this.target == null || this.incomingPackets.isEmpty()) break;

                Vec3 real = this.realPositions.get(this.target.getEntityId());
                if (real == null) break;

                double distReal    = mc.thePlayer.getDistance(real.xCoord, real.yCoord, real.zCoord);
                double distCurrent = mc.thePlayer.getDistanceToEntity(this.target);

                // ── Primary double-hit trigger: i-frame threshold ──────────────
                // When hurtResistantTime drops to the threshold, the target is
                // about to become vulnerable. Release now so the queued position
                // snap and KillAura's next hit both land in the same vulnerability
                // window — producing a reliable double hit every i-frame cycle.
                if (this.firstHitLanded
                        && this.target.hurtResistantTime > 0
                        && this.target.hurtResistantTime <= this.iFrameThreshold.getValue()) {
                    this.releaseAndRearm();
                    break;
                }

                // ── Secondary trigger: jump peak ───────────────────────────────
                // Release at apex so hit 1 (frozen position) and hit 2 (real
                // peak position) land in rapid succession. Also re-arms so the
                // next descending-phase hit starts a new hold cycle.
                if (this.jumpPeakRelease.getValue()
                        && this.isAtJumpPeak(this.target.getEntityId())) {
                    this.releaseAndRearm();
                    break;
                }

                // ── Safety releases ────────────────────────────────────────────

                // Hard delay cap — never exceed configured ms
                if (System.currentTimeMillis() - this.lastReleaseTime > this.delay.getValue()) {
                    this.releaseAndRearm();
                    break;
                }

                // Real position walked out of hit range — no point holding
                if (distReal > this.hitRange.getValue()) {
                    this.releaseAll();
                    break;
                }

                // Target's real position is now closer — holding is no longer useful
                if (distCurrent <= distReal) {
                    this.releaseAll();
                    break;
                }

                // Target is moving toward us — release before they walk through
                if (this.lastRealPos != null) {
                    double lastDist = mc.thePlayer.getDistance(
                            this.lastRealPos.xCoord,
                            this.lastRealPos.yCoord,
                            this.lastRealPos.zCoord
                    );
                    if (distReal < lastDist) {
                        this.releaseAll();
                        break;
                    }
                }

                // We took damage — release immediately to avoid being combo'd
                // while our own position packets are held
                if (mc.thePlayer.maxHurtTime > 0
                        && mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime) {
                    this.releaseAll();
                    break;
                }

                // Legit mode: release when our hit registers
                if (this.legit.getValue() && this.releaseOnHit.getValue()
                        && this.target.hurtTime == 1) {
                    this.releaseAll();
                }
                break;

            case POST:
                Vec3 saved = this.realPositions.get(
                        this.target != null ? this.target.getEntityId() : -1);
                if (this.currentRealPos == null) this.lastRealPos = saved;
                else this.lastRealPos = this.currentRealPos;
                this.currentRealPos = saved;
        }
    }

    @EventTarget(Priority.HIGH)
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || this.showPosition.getValue() == 0 || this.target == null) return;

        Vec3 real = this.realPositions.get(this.target.getEntityId());
        if (real == null || this.lastRealPos == null || this.currentRealPos == null) return;

        Color color;
        switch (this.showPosition.getValue()) {
            case 1:
                color = (this.target instanceof EntityPlayer)
                        ? TeamUtil.getTeamColor((EntityPlayer) this.target, 1.0F)
                        : new Color(255, 0, 0);
                break;
            case 2:
                color = ((HUD) Myau.moduleManager.modules.get(HUD.class))
                        .getColor(System.currentTimeMillis());
                break;
            default:
                return;
        }

        double x = RenderUtil.lerpDouble(this.currentRealPos.xCoord, this.lastRealPos.xCoord, event.getPartialTicks());
        double y = RenderUtil.lerpDouble(this.currentRealPos.yCoord, this.lastRealPos.yCoord, event.getPartialTicks());
        double z = RenderUtil.lerpDouble(this.currentRealPos.zCoord, this.lastRealPos.zCoord, event.getPartialTicks());

        float size = this.target.getCollisionBorderSize();
        AxisAlignedBB aabb = new AxisAlignedBB(
                x - this.target.width / 2.0, y, z - this.target.width / 2.0,
                x + this.target.width / 2.0, y + this.target.height, z + this.target.width / 2.0
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
