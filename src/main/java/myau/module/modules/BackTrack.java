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
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

/**
 * BackTrack — hit-triggered inbound packet hold.
 *
 * ACTIVATION MODEL
 * ─────────────────
 * Activates ONLY when KillAura lands a confirmed hit AND the target
 * is within the configured range AND the target's hurtResistantTime
 * is below maxHurtTime (meaning they can be damaged again within
 * that ms window — the damage will actually register).
 *
 * On activation:
 *   1. Target's position is frozen at the moment of impact.
 *   2. Server sends them flying backwards from knockback.
 *   3. Your follow-up hit fires against the frozen position while
 *      they are mid-flight on the server — double hit lands.
 *   4. After `delay` ms the queue drains and BackTrack goes idle
 *      until the next confirmed hit.
 *
 * maxHurtTime gate:
 *   If the target's hurtResistantTime * 50ms > maxHurtTime, the hit
 *   would deal 0 damage (iFrames active). BackTrack only activates
 *   when hurtResistantTime * 50ms <= maxHurtTime, ensuring the freeze
 *   only happens when the hit actually registered damage.
 */
public class BackTrack extends Module {

    private static final Minecraft mc  = Minecraft.getMinecraft();
    private static final Random    RNG = new Random();

    // ─────────────────────────────────────────────────────────────
    // Properties
    // ─────────────────────────────────────────────────────────────

    /**
     * How long (ms) to hold the frozen position after a hit.
     * This is your double-hit window. Match to roughly your ping
     * plus one tick. 100-150ms is a solid starting point.
     */
    public final IntProperty delay =
            new IntProperty("delay", 120, 10, 400);

    /** ±ms random variance per packet. Reduces timing patterns. */
    public final IntProperty jitter =
            new IntProperty("jitter", 10, 0, 40);

    /**
     * Maximum ms the target's iFrame timer may have for BackTrack
     * to activate. Prevents activating on hits that deal no damage.
     * Example: 200ms means the target must be damageable again
     * within 200ms — i.e. hurtResistantTime * 50 <= 200.
     * Set to 500 to always activate regardless of iFrame state.
     */
    public final IntProperty maxHurtTime =
            new IntProperty("max-hurt-time", 200, 0, 500);

    /**
     * Maximum blocks divergence between frozen and real position
     * before force-releasing. Prevents a jarring snap if the target
     * moves very fast.
     */
    public final FloatProperty maxDivergence =
            new FloatProperty("max-divergence", 3.0f, 0.5f, 8.0f);

    /** Minimum distance to target for BackTrack to activate. */
    public final FloatProperty minRange =
            new FloatProperty("min-range", 0.0f, 0.0f, 6.0f);

    /** Maximum distance to target for BackTrack to activate. */
    public final FloatProperty maxRange =
            new FloatProperty("max-range", 4.0f, 1.0f, 10.0f);

    /** Maximum packets held before oldest is force-processed. */
    public final IntProperty maxQueueSize =
            new IntProperty("max-queue-size", 8, 2, 24);

    /**
     * Drain the queue gradually rather than all at once on release.
     * Prevents the target snapping to their real position in one frame.
     */
    public final BooleanProperty smoothRelease =
            new BooleanProperty("smooth-release", true);

    /**
     * Immediately stop holding if YOU take knockback.
     * Prevents your own position from desyncing when you receive hits.
     */
    public final BooleanProperty disableOnHit =
            new BooleanProperty("disable-on-hit", true);

    /** Hard safety cap: force-release after this many ms regardless. */
    public final IntProperty maxDelayCap =
            new IntProperty("max-delay-cap", 500, 50, 1200);

    // ─────────────────────────────────────────────────────────────
    // Real position indicator properties
    // ─────────────────────────────────────────────────────────────

    /**
     * Renders a box at the target's TRUE server-side position while
     * BackTrack is active, showing where they actually are despite
     * being visually frozen on your screen.
     */
    public final BooleanProperty realPosIndicator =
            new BooleanProperty("real-pos", true);

    /** Red component of the real position box color (0-255). */
    public final IntProperty realPosRed =
            new IntProperty("real-pos-red", 255, 0, 255);

    /** Green component of the real position box color (0-255). */
    public final IntProperty realPosGreen =
            new IntProperty("real-pos-green", 80, 0, 255);

    /** Blue component of the real position box color (0-255). */
    public final IntProperty realPosBlue =
            new IntProperty("real-pos-blue", 0, 0, 255);

    /** Alpha/opacity of the real position box (0-255). */
    public final IntProperty realPosAlpha =
            new IntProperty("real-pos-alpha", 180, 0, 255);

    /** Width of the box outline in pixels. */
    public final FloatProperty realPosLineWidth =
            new FloatProperty("real-pos-line-width", 1.5f, 0.5f, 5.0f);

    /**
     * If true, the box is filled with a translucent color.
     * If false, only the outline is drawn.
     */
    public final BooleanProperty realPosFilled =
            new BooleanProperty("real-pos-filled", false);

    /**
     * If true, the box rotates to face the direction the target's
     * head is pointing based on their last known yaw. Gives a better
     * sense of which way they are facing at their real position.
     */
    public final BooleanProperty realPosHeadRotation =
            new BooleanProperty("real-pos-head-rotation", false);

    // ─────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────

    private final Queue<TimedPacket> heldPackets     = new LinkedList<>();
    private final Map<Integer, Vec3> serverPositions = new HashMap<>();
    // Track last known yaw per entity for head rotation rendering.
    private final Map<Integer, Float> serverYaws     = new HashMap<>();

    private boolean releasing = false;
    private boolean draining  = false;
    private boolean holding   = false;
    private long    holdStart = -1L;

    // Previous hurtResistantTime of the PLAYER — for disable-on-hit.
    private int lastPlayerHurtTime = 0;
    // Previous hurtResistantTime of the TARGET — for hit detection.
    private int lastTargetHurtTime = 0;

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
        serverYaws.clear();
        holding   = false;
        releasing = false;
        draining  = false;
        holdStart = -1L;
        lastPlayerHurtTime = 0;
        lastTargetHurtTime = 0;
        target    = null;
    }

    // ─────────────────────────────────────────────────────────────
    // processPacket generic helper
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
        if (releasing) return;

        Packet<?> pkt = event.getPacket();

        // Always track real server positions regardless of hold state.
        updateServerPosition(pkt);

        // Never hold velocity — always pass through immediately.
        if (pkt instanceof S12PacketEntityVelocity) return;

        if (target == null) return;
        if (!isTargetPositionPacket(pkt, target.getEntityId())) return;
        if (draining) return;
        if (!holding) return;

        // Divergence cap — release instead of queuing if gap is too large.
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

        // Target switched — drop the queue and reset.
        if (newTarget != target) {
            if (holding) beginDrain();
            target             = newTarget;
            lastTargetHurtTime = 0;
        }

        // Smooth drain in progress — keep draining until empty.
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

        // ── Safety cap ──
        if (holding && holdStart != -1L
                && (System.currentTimeMillis() - holdStart) > maxDelayCap.getValue()) {
            beginDrain();
            return;
        }

        // ── Disable on hit — player took knockback ──
        int currentPlayerHurt = mc.thePlayer.hurtResistantTime;
        boolean playerTookHit = disableOnHit.getValue()
                && currentPlayerHurt > lastPlayerHurtTime
                && currentPlayerHurt == mc.thePlayer.maxHurtTime
                && mc.thePlayer.maxHurtTime > 0;
        lastPlayerHurtTime = currentPlayerHurt;

        if (playerTookHit && holding) {
            hardRelease();
            holding   = false;
            holdStart = -1L;
            return;
        }

        // ── Per-tick divergence check ──
        if (holding) {
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

        // ── Release after delay window expires ──
        // BackTrack only holds for `delay` ms after each hit trigger.
        // Once the window closes, begin draining so the client catches up.
        if (holding && holdStart != -1L
                && (System.currentTimeMillis() - holdStart) >= delay.getValue()) {
            beginDrain();
            return;
        }

        // ── Hit detection — trigger activation ──
        // A hit registered when the target's hurtResistantTime rises
        // back to maxHurtTime. This is the earliest detectable signal
        // that damage was dealt — fires before any knockback position
        // packets arrive so the freeze is already in place.
        int currentTargetHurt = target.hurtResistantTime;
        boolean hitDetected   = currentTargetHurt > lastTargetHurtTime
                && currentTargetHurt == target.maxHurtTime
                && target.maxHurtTime > 0;
        lastTargetHurtTime = currentTargetHurt;

        if (hitDetected && !holding && !draining) {
            // Only activate if within range AND target's iFrame window
            // is short enough that a follow-up hit will actually land.
            double dist = mc.thePlayer.getDistanceToEntity(target);
            int hurtTimeMs = currentTargetHurt * 50;

            if (dist >= minRange.getValue()
                    && dist <= maxRange.getValue()
                    && hurtTimeMs <= maxHurtTime.getValue()) {
                holding   = true;
                holdStart = System.currentTimeMillis();
            }
        }

        if (holding) drainExpired();
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
                // Track yaw if this packet carries rotation.
                if (e instanceof EntityLivingBase) {
                    serverYaws.put(id, e.rotationYaw);
                }
            }
        } else if (pkt instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) pkt;
            serverPositions.put(p.getEntityId(),
                    new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0));
            serverYaws.put(p.getEntityId(), (float)(p.getYaw() * (360.0 / 256.0)));
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
    // Real position indicator ESP
    // ─────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!realPosIndicator.getValue()) return;
        if (target == null || !(holding || draining)) return;

        Vec3 real = serverPositions.get(target.getEntityId());
        if (real == null) return;

        double rx = real.xCoord - mc.getRenderManager().viewerPosX;
        double ry = real.yCoord - mc.getRenderManager().viewerPosY;
        double rz = real.zCoord - mc.getRenderManager().viewerPosZ;

        double hw = target.width / 2.0;
        double h  = target.height;

        int red   = realPosRed.getValue();
        int green = realPosGreen.getValue();
        int blue  = realPosBlue.getValue();
        int alpha = realPosAlpha.getValue();

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableLighting();

        if (realPosHeadRotation.getValue()) {
            // Rotate the box around the Y axis to face the direction the
            // target's head is pointing at their real server position.
            float yaw = serverYaws.getOrDefault(target.getEntityId(), target.rotationYaw);
            GlStateManager.translate(rx, ry + h / 2.0, rz);
            GlStateManager.rotate(-yaw, 0, 1, 0);
            GlStateManager.translate(-rx, -(ry + h / 2.0), -rz);
        }

        AxisAlignedBB box = new AxisAlignedBB(
                rx - hw, ry,     rz - hw,
                rx + hw, ry + h, rz + hw
        );

        if (realPosFilled.getValue()) {
            // Draw filled translucent box.
            drawFilledBox(box, red, green, blue, alpha / 4);
        }

        // Draw outline.
        GL11.glLineWidth(realPosLineWidth.getValue());
        RenderGlobal.drawOutlinedBoundingBox(box, red, green, blue, alpha);
        GL11.glLineWidth(1.0f);

        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /**
     * Draws a filled axis-aligned box using GL_QUADS.
     * Used for the filled real-position indicator.
     */
    private void drawFilledBox(AxisAlignedBB bb, int r, int g, int b, int a) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(r / 255f, g / 255f, b / 255f, a / 255f);

        // Bottom
        GL11.glVertex3d(bb.minX, bb.minY, bb.minZ);
        GL11.glVertex3d(bb.maxX, bb.minY, bb.minZ);
        GL11.glVertex3d(bb.maxX, bb.minY, bb.maxZ);
        GL11.glVertex3d(bb.minX, bb.minY, bb.maxZ);

        // Top
        GL11.glVertex3d(bb.minX, bb.maxY, bb.minZ);
        GL11.glVertex3d(bb.maxX, bb.maxY, bb.minZ);
        GL11.glVertex3d(bb.maxX, bb.maxY, bb.maxZ);
        GL11.glVertex3d(bb.minX, bb.maxY, bb.maxZ);

        // Front
        GL11.glVertex3d(bb.minX, bb.minY, bb.minZ);
        GL11.glVertex3d(bb.maxX, bb.minY, bb.minZ);
        GL11.glVertex3d(bb.maxX, bb.maxY, bb.minZ);
        GL11.glVertex3d(bb.minX, bb.maxY, bb.minZ);

        // Back
        GL11.glVertex3d(bb.minX, bb.minY, bb.maxZ);
        GL11.glVertex3d(bb.maxX, bb.minY, bb.maxZ);
        GL11.glVertex3d(bb.maxX, bb.maxY, bb.maxZ);
        GL11.glVertex3d(bb.minX, bb.maxY, bb.maxZ);

        // Left
        GL11.glVertex3d(bb.minX, bb.minY, bb.minZ);
        GL11.glVertex3d(bb.minX, bb.minY, bb.maxZ);
        GL11.glVertex3d(bb.minX, bb.maxY, bb.maxZ);
        GL11.glVertex3d(bb.minX, bb.maxY, bb.minZ);

        // Right
        GL11.glVertex3d(bb.maxX, bb.minY, bb.minZ);
        GL11.glVertex3d(bb.maxX, bb.minY, bb.maxZ);
        GL11.glVertex3d(bb.maxX, bb.maxY, bb.maxZ);
        GL11.glVertex3d(bb.maxX, bb.maxY, bb.minZ);

        GL11.glEnd();
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
