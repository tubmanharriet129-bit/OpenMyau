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

public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random RNG = new Random();

    // ─────────────────────────────────────────────────────────────
    // Properties
    // ─────────────────────────────────────────────────────────────

    public final IntProperty delay =
            new IntProperty("delay", 100, 10, 400);

    public final IntProperty jitter =
            new IntProperty("jitter", 10, 0, 40);

    public final FloatProperty maxDivergence =
            new FloatProperty("max-divergence", 2.5f, 0.5f, 8.0f);

    public final IntProperty maxHurtTime =
            new IntProperty("max-hurt-time", 500, 0, 500);

    public final IntProperty preActivate =
            new IntProperty("pre-activate", 100, 0, 300);

    public final FloatProperty maxRange =
            new FloatProperty("max-range", 4.0f, 2.0f, 10.0f);

    public final FloatProperty minRange =
            new FloatProperty("min-range", 2.0f, 0.0f, 6.0f);

    public final IntProperty maxQueueSize =
            new IntProperty("max-queue-size", 8, 2, 24);

    public final BooleanProperty smoothRelease =
            new BooleanProperty("smooth-release", true);

    public final BooleanProperty releaseOnHit =
            new BooleanProperty("release-on-hit", true);

    public final IntProperty maxDelayCap =
            new IntProperty("max-delay-cap", 500, 50, 1200);

    public final BooleanProperty esp =
            new BooleanProperty("esp", true);

    public final ModeProperty espMode =
            new ModeProperty("esp-mode", 0, new String[]{"HITBOX", "NONE"});

    // ─────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────

    private final Queue<TimedPacket> heldPackets = new LinkedList<>();
    private final Map<Integer, Vec3> serverPositions = new HashMap<>();

    private boolean releasing = false;
    private boolean draining  = false;
    private boolean holding   = false;
    private long    holdStart = -1L;

    private int lastHurtResistantTime = 0;

    private EntityLivingBase target   = null;
    private KillAura         killAura = null;

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
        target    = null;
    }

    // ─────────────────────────────────────────────────────────────
    // processPacket helper
    //
    // Packet<T extends INetHandler>.processPacket(T) cannot accept
    // a raw NetHandlerPlayClient when the packet type is Packet<?>,
    // because Java cannot verify the wildcard capture matches.
    // This generic helper suppresses the unchecked cast safely:
    // we know at runtime that every S** packet accepts
    // NetHandlerPlayClient, so the cast never fails.
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T extends INetHandler> void process(Packet<T> packet) {
        if (mc.getNetHandler() == null) return;
        packet.processPacket((T) mc.getNetHandler());
    }

    // ─────────────────────────────────────────────────────────────
    // Packet interception — INBOUND ONLY
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (killAura == null) return;
        if (event.getType() != EventType.RECEIVE) return;

        // Re-entrancy guard — packets we are releasing must not be re-queued.
        if (releasing) return;

        Packet<?> pkt = event.getPacket();

        // Always track server-side position from every incoming
        // position packet, even ones we are about to hold.
        updateServerPosition(pkt);

        // Velocity packets are never held — holding them desyncs
        // client physics from the server and causes visible snapping.
        if (pkt instanceof S12PacketEntityVelocity) return;

        if (target == null) return;
        if (!isTargetPositionPacket(pkt, target.getEntityId())) return;

        // During smooth drain, pass new packets straight through.
        if (draining) return;
        if (!holding) return;

        // ── Divergence cap ──
        // If the true server position has already drifted past the
        // configured limit, release everything now and pass this
        // packet through — prevents a large visible snap on release.
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

        // ── Queue size cap ──
        // Force-release the oldest packet before adding the new one
        // so the queue never exceeds maxQueueSize, keeping burst size
        // bounded when smooth-release drains the queue on deactivation.
        if (heldPackets.size() >= maxQueueSize.getValue()) {
            TimedPacket oldest = heldPackets.poll();
            if (oldest != null) {
                releasing = true;
                try   { process(oldest.packet); }
                finally { releasing = false; }
            }
        }

        // Assign a per-packet jittered release delay at queue time
        // so the drain cadence mirrors organic network jitter.
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

        if (newTarget != target) {
            if (holding) beginDrain();
            target = newTarget;
            lastHurtResistantTime = 0;
        }

        // Smooth drain in progress — keep draining until the queue empties.
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

        // Hard safety cap.
        if (holding && holdStart != -1L
                && (System.currentTimeMillis() - holdStart) > maxDelayCap.getValue()) {
            beginDrain();
            return;
        }

        // ── Early knockback detection ──
        // Watching hurtResistantTime rise back to maxHurtTime catches
        // the hit one tick earlier than watching hurtTime directly,
        // ensuring the queue flushes before the knockback trajectory
        // is processed server-side.
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

        // ── Per-tick divergence check ──
        // Catches fast-moving or teleporting targets even between packets.
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

        int hurtTimeMs  = target.hurtResistantTime * 50;
        int threshold   = maxHurtTime.getValue() + preActivate.getValue();
        if (hurtTimeMs > threshold) return false;

        return true;
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
            for (TimedPacket tp : toRelease) {
                process(tp.packet);
            }
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
            for (TimedPacket tp : toRelease) {
                process(tp.packet);
            }
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

    private boolean isTargetPositionPacket(Packet<?> pkt, int entityId) {
        if (pkt instanceof S14PacketEntity) {
            Entity e = ((S14PacketEntity) pkt).getEntity(mc.theWorld);
            return e != null && e.getEntityId() == entityId;
        }
        if (pkt instanceof S18PacketEntityTeleport) {
            return ((S18PacketEntityTeleport) pkt).getEntityId() == entityId;
        }
        if (pkt instanceof S19PacketEntityHeadLook) {
            // 1.8.9 MCP: entity id accessor is func_149381_a()
            // getEntityId() does not exist on this packet class in 1.8.9.
            int id = ((S19PacketEntityHeadLook) pkt).func_149381_a();
            Entity e = mc.theWorld.getEntityByID(id);
            return e != null && e.getEntityId() == entityId;
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
                x - hw, y,               z - hw,
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
