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
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ─────────────────────────────────────────────────────────────
    // Properties
    // ─────────────────────────────────────────────────────────────

    public final IntProperty delay =
            new IntProperty("delay", 50, 10, 300);

    public final IntProperty maxHurtTime =
            new IntProperty("max-hurt-time", 500, 0, 500);

    public final FloatProperty maxRange =
            new FloatProperty("max-range", 4.0f, 2.0f, 10.0f);

    public final FloatProperty minRange =
            new FloatProperty("min-range", 2.0f, 0.0f, 6.0f);

    public final BooleanProperty delayMovement =
            new BooleanProperty("delay-movement", true);

    public final BooleanProperty flushOnHit =
            new BooleanProperty("flush-on-hit", true);

    public final IntProperty maxDelayCap =
            new IntProperty("max-delay-cap", 400, 50, 1000);

    public final BooleanProperty esp =
            new BooleanProperty("esp", true);

    public final ModeProperty espMode =
            new ModeProperty("esp-mode", 0, new String[]{"HITBOX", "NONE"});

    // ─────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────

    private final Queue<TimedPacket> outboundQueue = new LinkedList<>();
    private final Map<Integer, Vec3> serverPositions = new HashMap<>();

    /**
     * TRUE while we are calling sendImmediate().
     * Prevents the SEND packet event from re-intercepting packets we are
     * actively releasing — the root cause of the post-hit crash.
     */
    private boolean releasing = false;

    private long holdStart = -1L;
    private boolean holding = false;
    private EntityLivingBase target = null;
    private KillAura killAura = null;

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
        flush();
        reset();
    }

    private void reset() {
        outboundQueue.clear();
        serverPositions.clear();
        holding = false;
        releasing = false;
        holdStart = -1L;
        target = null;
    }

    // ─────────────────────────────────────────────────────────────
    // Packet interception
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (killAura == null) return;

        // ── Incoming: track server-side positions, never block ──
        if (event.getType() == EventType.RECEIVE) {
            Packet<?> pkt = event.getPacket();

            if (pkt instanceof S14PacketEntity) {
                S14PacketEntity p = (S14PacketEntity) pkt;
                Entity e = p.getEntity(mc.theWorld);
                if (e != null) {
                    int id = e.getEntityId();
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
            return;
        }

        // ── Outgoing: skip entirely if we are the ones sending ──
        // Without this guard, sendImmediate() re-fires this event,
        // re-queues the packet, and causes infinite recursion / CME crash.
        if (releasing) return;

        if (event.getType() != EventType.SEND) return;
        if (!holding) return;

        Packet<?> pkt = event.getPacket();
        boolean isAttack   = pkt instanceof C02PacketUseEntity;
        boolean isMovement = pkt instanceof C03PacketPlayer;

        if (isAttack || (isMovement && delayMovement.getValue())) {
            outboundQueue.add(new TimedPacket(pkt, System.currentTimeMillis()));
            event.setCancelled(true);
        }
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
            if (holding) stopHolding();
            target = newTarget;
        }

        if (target == null) {
            if (holding) stopHolding();
            return;
        }

        // Safety cap.
        if (holding && holdStart != -1L
                && (System.currentTimeMillis() - holdStart) > maxDelayCap.getValue()) {
            stopHolding();
            return;
        }

        // Flush on knockback received.
        if (flushOnHit.getValue()
                && mc.thePlayer.hurtTime > 0
                && mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime) {
            if (holding) stopHolding();
            return;
        }

        if (shouldHold()) {
            if (!holding) {
                holding = true;
                holdStart = System.currentTimeMillis();
            }
        } else {
            if (holding) stopHolding();
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
        if (hurtTimeMs > maxHurtTime.getValue()) return false;

        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // Queue management
    // ─────────────────────────────────────────────────────────────

    private void drainExpired() {
        long now = System.currentTimeMillis();
        int delayMs = delay.getValue();

        // Collect expired packets into a separate list first to avoid
        // any chance of queue mutation while iterating (extra safety).
        ArrayList<TimedPacket> toSend = new ArrayList<>();
        while (!outboundQueue.isEmpty()) {
            TimedPacket head = outboundQueue.peek();
            if (now - head.timestamp >= delayMs) {
                toSend.add(outboundQueue.poll());
            } else {
                break;
            }
        }

        releasing = true;
        try {
            for (TimedPacket tp : toSend) {
                sendImmediate(tp.packet);
            }
        } finally {
            // Always clear the flag even if something throws,
            // so the module doesn't get permanently stuck ignoring packets.
            releasing = false;
        }
    }

    private void flush() {
        ArrayList<TimedPacket> toSend = new ArrayList<>(outboundQueue);
        outboundQueue.clear();

        releasing = true;
        try {
            for (TimedPacket tp : toSend) {
                sendImmediate(tp.packet);
            }
        } finally {
            releasing = false;
        }
    }

    private void stopHolding() {
        flush();
        holding = false;
        holdStart = -1L;
    }

    private void sendImmediate(Packet<?> packet) {
        if (mc.getNetHandler() != null
                && mc.getNetHandler().getNetworkManager() != null) {
            mc.getNetHandler().getNetworkManager().sendPacket(packet);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ESP
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!esp.getValue() || espMode.getValue() != 0) return;
        if (target == null || !holding) return;

        Vec3 real = serverPositions.get(target.getEntityId());
        if (real == null) return;

        double x = real.xCoord - mc.getRenderManager().viewerPosX;
        double y = real.yCoord - mc.getRenderManager().viewerPosY;
        double z = real.zCoord - mc.getRenderManager().viewerPosZ;

        double hw = target.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(
                x - hw, y, z - hw,
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
        final long timestamp;

        TimedPacket(Packet<?> packet, long timestamp) {
            this.packet    = packet;
            this.timestamp = timestamp;
        }
    }
}
