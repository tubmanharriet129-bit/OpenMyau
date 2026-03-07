package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil timer = new TimerUtil();
    private AttackData target = null;
    private int switchTick = 0;
    private boolean hitRegistered = false;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    private int blockTick = 0;
    private int lastTickProcessed;

    // -------------------------------------------------------
    // Mid Trade — Burst Engine state
    // -------------------------------------------------------

    /** Current phase of the burst state machine. */
    private enum BurstPhase { IDLE, BURST, PAUSE }
    private BurstPhase burstPhase = BurstPhase.IDLE;

    /** Wall-clock ms when the current burst window opened. */
    private long burstWindowStart = 0L;

    /**
     * Wall-clock ms when the last burst ended.
     * Pause check: if (now - lastBurstEnd < pauseDurationMs) → cancel.
     * Pure wall clock — no ping, no TPS.
     */
    private long lastBurstEnd = 0L;

    /** Wall-clock ms of the last generated click attempt. */
    private long lastClickAttempt = 0L;

    /** Interval (ms) until the next click attempt — re-randomised after each attempt. */
    private long nextClickInterval = 0L;

    /**
     * ticksExisted value when the last attack packet was sent.
     * Enforces one packet per 50ms tick regardless of APS rate.
     */
    private int lastAttackTick = -1;

    /** Whether the target entity changed since last tick. */
    private boolean freshTarget = false;

    /** Entity ID of current target for change detection. */
    private int lastTargetId = -1;

    /** Timestamp of last target switch (for BackTrack sync). */
    long lastTargetSwitchTime = 0L;

    public final ModeProperty mode;
    public final ModeProperty sort;
    public final ModeProperty autoBlock;
    public final BooleanProperty autoBlockRequirePress;
    public final FloatProperty autoBlockMinCPS;
    public final FloatProperty autoBlockMaxCPS;
    public final FloatProperty autoBlockRange;
    public final FloatProperty swingRange;
    public final FloatProperty attackRange;
    public final IntProperty fov;
    public final IntProperty minCPS;
    public final IntProperty maxCPS;
    public final IntProperty switchDelay;
    public final ModeProperty rotations;
    public final ModeProperty moveFix;
    public final PercentProperty smoothing;
    public final IntProperty angleStep;
    public final BooleanProperty throughWalls;
    public final BooleanProperty requirePress;
    public final BooleanProperty allowMining;
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty allowTools;
    public final BooleanProperty inventoryCheck;
    public final BooleanProperty botCheck;
    public final BooleanProperty players;
    public final BooleanProperty bosses;
    public final BooleanProperty mobs;
    public final BooleanProperty animals;
    public final BooleanProperty golems;
    public final BooleanProperty silverfish;
    public final BooleanProperty teams;
    public final ModeProperty showTarget;
    public final ModeProperty debugLog;

    // -------------------------------------------------------
    // Mid Trade properties
    // -------------------------------------------------------

    /** Enables the burst click engine. */
    public final BooleanProperty midTrade;

    /**
     * Minimum attacks per second for the raw click generator.
     * The generator fires at a random interval between 1000/maxAPS and 1000/minAPS ms.
     * Even at high APS values, the burst/pause cycle keeps average CPS at ~7-8.
     */
    public final IntProperty minAPS;

    /**
     * Maximum attacks per second for the raw click generator.
     * Higher maxAPS = denser clicks within each burst window.
     */
    public final IntProperty maxAPS;

    /**
     * Duration of each burst window in ms.
     * Clicks are only allowed inside this window.
     * Should be sized to fit 2-4 clicks at your target APS:
     *   e.g. 200ms window at 15 APS = 200/67ms = ~3 clicks
     */
    public final IntProperty burstWindow;

    /**
     * Pause duration after each burst in ms.
     * No clicks are sent during this phase.
     * Controls average CPS: longer pause = lower average APS.
     */
    public final IntProperty midTradePause;

    // -------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------

    private long getAttackDelay() {
        return this.isBlocking
                ? (long) (1000.0F / RandomUtil.nextLong(this.autoBlockMinCPS.getValue().longValue(), this.autoBlockMaxCPS.getValue().longValue()))
                : 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
    }

    // -------------------------------------------------------
    // Mid Trade — Burst Engine
    // -------------------------------------------------------

    /**
     * Burst click gate — pure time-based, zero network dependency.
     *
     * State machine:
     *   IDLE  → no target, resets cleanly on new engagement
     *   BURST → APS-driven click attempts allowed inside burst window
     *   PAUSE → all clicks blocked for exactly pauseDurationMs (wall clock only)
     *
     * Pause contract (from spec):
     *   if (currentTime - lastBurstEnd < pauseDurationMs) → cancel click
     *   No ping. No TPS. No latency scaling. Pure wall clock.
     *
     * Tick alignment:
     *   Only one attack packet allowed per 50ms tick regardless of APS.
     *   Prevents redundant hits and keeps server behavior consistent.
     *
     * Returns true to suppress this click attempt, false to allow it.
     */
    private boolean isHitSelectPaused() {
        if (this.target == null) return false;
        if (!this.midTrade.getValue()) return false;

        long now = System.currentTimeMillis();

        // Fresh target: reset to IDLE cleanly
        if (this.freshTarget) {
            this.freshTarget       = false;
            this.burstPhase        = BurstPhase.IDLE;
            this.burstWindowStart  = 0L;
            this.lastBurstEnd      = 0L;
            this.lastClickAttempt  = 0L;
            this.lastAttackTick    = -1;
            this.nextClickInterval = this.randomClickInterval();
            BackTrack bt = (BackTrack) Myau.moduleManager.getModule(BackTrack.class);
            if (bt != null && bt.isEnabled()) bt.onKillAuraResuming();
        }

        switch (this.burstPhase) {

            case IDLE:
                // Open first burst immediately on engagement
                this.burstPhase       = BurstPhase.BURST;
                this.burstWindowStart = now;
                this.lastClickAttempt = now - this.nextClickInterval; // fire first click now
                return false;

            case BURST: {
                // ── Pause check (spec contract) ───────────────────────────
                // Not applicable in BURST — but guard against stale state
                // where lastBurstEnd was set without transitioning (shouldn't happen)

                // ── Burst window expired → enter pause ────────────────────
                if (now - this.burstWindowStart >= this.burstWindow.getValue()) {
                    this.burstPhase  = BurstPhase.PAUSE;
                    this.lastBurstEnd = now;
                    BackTrack bt = (BackTrack) Myau.moduleManager.getModule(BackTrack.class);
                    if (bt != null && bt.isEnabled()) bt.onKillAuraBurstComplete();
                    return true;
                }

                // ── Tick alignment: one packet per 50ms tick ──────────────
                int currentTick = mc.thePlayer != null ? mc.thePlayer.ticksExisted : -1;
                if (currentTick != -1 && currentTick == this.lastAttackTick) return true;

                // ── APS-based click interval gate ─────────────────────────
                if (now - this.lastClickAttempt < this.nextClickInterval) return true;

                // Attempt ready — will be consumed by performAttack
                return false;
            }

            case PAUSE:
                // ── Pure time-based pause (spec contract) ─────────────────
                // if (currentTime - lastBurstEnd < pauseDurationMs) → cancel
                if (now - this.lastBurstEnd < this.midTradePause.getValue()) return true;

                // Pause expired → reopen burst window
                this.burstPhase       = BurstPhase.BURST;
                this.burstWindowStart = now;
                this.lastClickAttempt = now - this.nextClickInterval; // fire first click immediately
                this.nextClickInterval = this.randomClickInterval();
                BackTrack bt = (BackTrack) Myau.moduleManager.getModule(BackTrack.class);
                if (bt != null && bt.isEnabled()) bt.onKillAuraResuming();
                return false;

            default:
                return false;
        }
    }

    /**
     * Returns a randomized interval in ms between clicks.
     * Derived strictly from minAPS/maxAPS:
     *   intervalMin = 1000 / maxAPS
     *   intervalMax = 1000 / minAPS
     */
    private long randomClickInterval() {
        long lo = 1000L / Math.max(1, this.maxAPS.getValue());
        long hi = 1000L / Math.max(1, this.minAPS.getValue());
        if (lo >= hi) return lo;
        return lo + (long)(Math.random() * (hi - lo));
    }

    /** Called after every confirmed attack packet is sent. */
    private void armHitSelect() {
        long now = System.currentTimeMillis();
        this.lastClickAttempt  = now;
        this.nextClickInterval = this.randomClickInterval();
        if (mc.thePlayer != null) this.lastAttackTick = mc.thePlayer.ticksExisted;
    }

    private void notifyTargetChanged(int newEntityId) {
        if (newEntityId != this.lastTargetId) {
            this.lastTargetSwitchTime = System.currentTimeMillis();
            this.lastTargetId         = newEntityId;
            this.freshTarget          = true;
            BackTrack bt = (BackTrack) Myau.moduleManager.getModule(BackTrack.class);
            if (bt != null && bt.isEnabled()) bt.syncTarget(newEntityId);
        }
    }

    // -------------------------------------------------------
    // Core attack
    // -------------------------------------------------------

    private boolean performAttack(float yaw, float pitch) {
        if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
            if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1) {
                return false;
            } else if (this.attackDelayMS > 0L && !this.midTrade.getValue()) {
                // CPS timer bypassed when midTrade is active — midTrade owns timing
                return false;
            } else if (this.isHitSelectPaused()) {
                return false;
            } else {
                if (!this.midTrade.getValue()) {
                    this.attackDelayMS = this.attackDelayMS + this.getAttackDelay();
                }
                mc.thePlayer.swingItem();
                if ((this.rotations.getValue() != 0 || !this.isBoxInAttackRange(this.target.getBox()))
                        && RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, this.attackRange.getValue()) == null) {
                    return false;
                } else {
                    AttackEvent attackEvent = new AttackEvent(this.target.getEntity());
                    EventManager.call(attackEvent);
                    if (attackEvent.isCancelled()) {
                        return false;
                    }
                    ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                    PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.ATTACK));
                    if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                        PlayerUtil.attackEntity(this.target.getEntity());
                    }
                    this.hitRegistered = true;
                    this.armHitSelect();
                    return true;
                }
            }
        } else {
            return false;
        }
    }

    private void sendUseItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private void startBlock(ItemStack itemStack) {
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    private void interactAttack(float yaw, float pitch) {
        if (this.target != null) {
            MovingObjectPosition mop = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, 8.0);
            if (mop != null) {
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                PacketUtil.sendPacket(
                        new C02PacketUseEntity(
                                this.target.getEntity(),
                                new Vec3(mop.hitVec.xCoord - this.target.getX(), mop.hitVec.yCoord - this.target.getY(), mop.hitVec.zCoord - this.target.getZ())
                        )
                );
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.INTERACT));
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                this.blockingState = true;
            }
        }
    }

    private boolean canAutoBlock() {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        } else {
            return !this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem();
        }
    }

    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .anyMatch(
                        entity -> entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInBlockRange((EntityLivingBase) entity)
                );
    }

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (!mc.theWorld.loadedEntityList.contains(entityLivingBase)) {
            return false;
        } else if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.thePlayer.ridingEntity) {
            if (entityLivingBase == mc.getRenderViewEntity() || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityLivingBase.deathTime > 0) {
                return false;
            } else if (RotationUtil.angleToEntity(entityLivingBase) > this.fov.getValue().floatValue()) {
                return false;
            } else if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) {
                return false;
            } else if (entityLivingBase instanceof EntityOtherPlayerMP) {
                if (!this.players.getValue()) {
                    return false;
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return false;
                } else {
                    return (!this.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase))
                            && (!this.botCheck.getValue() || !TeamUtil.isBot((EntityPlayer) entityLivingBase));
                }
            } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
                return this.bosses.getValue();
            } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
                if (entityLivingBase instanceof EntityAnimal
                        || entityLivingBase instanceof EntityBat
                        || entityLivingBase instanceof EntitySquid
                        || entityLivingBase instanceof EntityVillager) {
                    return this.animals.getValue();
                } else if (!(entityLivingBase instanceof EntityIronGolem)) {
                    return false;
                } else {
                    return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
                }
            } else if (!(entityLivingBase instanceof EntitySilverfish)) {
                return this.mobs.getValue();
            } else {
                return this.silverfish.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
            }
        } else {
            return false;
        }
    }

    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase) || this.isInSwingRange(entityLivingBase) || this.isInAttackRange(entityLivingBase);
    }

    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }

    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }

    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }

    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.attackRange.getValue();
    }

    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.attackRange.getValue();
    }

    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }

    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && !stack.hasDisplayName()) {
                    return i;
                }
            }
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) {
                    return i;
                }
            }
        }
        return -1;
    }

    // -------------------------------------------------------
    // Constructor
    // -------------------------------------------------------

    public KillAura() {
        super("KillAura", false);
        this.lastTickProcessed = 0;
        this.mode = new ModeProperty("mode", 0, new String[]{"SINGLE", "SWITCH"});
        this.sort = new ModeProperty("sort", 0, new String[]{"DISTANCE", "HEALTH", "HURT_TIME", "FOV"});
        this.autoBlock = new ModeProperty(
                "auto-block", 2, new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK", "INTERACT", "SWAP", "LEGIT", "FAKE"}
        );
        this.autoBlockRequirePress = new BooleanProperty("auto-block-require-press", false);
        this.autoBlockMinCPS = new FloatProperty("auto-block-min-aps", 8.0F, 1.0F, 20.0F);
        this.autoBlockMaxCPS = new FloatProperty("auto-block-max-aps", 10.0F, 1.0F, 20.0F);
        this.autoBlockRange = new FloatProperty("auto-block-range", 6.0F, 3.0F, 8.0F);
        this.swingRange = new FloatProperty("swing-range", 3.5F, 3.0F, 6.0F);
        this.attackRange = new FloatProperty("attack-range", 3.0F, 3.0F, 6.0F);
        this.fov = new IntProperty("fov", 360, 30, 360);
        this.minCPS = new IntProperty("min-aps", 14, 1, 20);
        this.maxCPS = new IntProperty("max-aps", 14, 1, 20);
        this.switchDelay = new IntProperty("switch-delay", 150, 0, 1000);
        this.rotations = new ModeProperty("rotations", 2, new String[]{"NONE", "LEGIT", "SILENT", "LOCK_VIEW"});
        this.moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
        this.smoothing = new PercentProperty("smoothing", 0);
        this.angleStep = new IntProperty("angle-step", 90, 30, 180);
        this.throughWalls = new BooleanProperty("through-walls", true);
        this.requirePress = new BooleanProperty("require-press", false);
        this.allowMining = new BooleanProperty("allow-mining", true);
        this.weaponsOnly = new BooleanProperty("weapons-only", true);
        this.allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
        this.inventoryCheck = new BooleanProperty("inventory-check", true);
        this.botCheck = new BooleanProperty("bot-check", true);
        this.players = new BooleanProperty("players", true);
        this.bosses = new BooleanProperty("bosses", false);
        this.mobs = new BooleanProperty("mobs", false);
        this.animals = new BooleanProperty("animals", false);
        this.golems = new BooleanProperty("golems", false);
        this.silverfish = new BooleanProperty("silverfish", false);
        this.teams = new BooleanProperty("teams", true);
        this.showTarget = new ModeProperty("show-target", 0, new String[]{"NONE", "DEFAULT", "HUD"});
        this.debugLog = new ModeProperty("debug-log", 0, new String[]{"NONE", "HEALTH"});

        // ── Mid Trade ─────────────────────────────────────────────────────────
        this.midTrade  = new BooleanProperty("mid-trade", false);
        this.minAPS    = new IntProperty("min-aps", 12, 6, 20,
                this.midTrade::getValue);
        this.maxAPS    = new IntProperty("max-aps", 20, 10, 30,
                this.midTrade::getValue);
        this.burstWindow = new IntProperty("burst-window", 200, 100, 350,
                this.midTrade::getValue);
        this.midTradePause = new IntProperty("burst-pause", 300, 100, 600,
                this.midTrade::getValue);
    }

    // -------------------------------------------------------
    // Public API
    // -------------------------------------------------------

    public EntityLivingBase getTarget() {
        return this.target != null ? this.target.getEntity() : null;
    }

    public long getLastTargetSwitchTime() {
        return this.lastTargetSwitchTime;
    }

    public boolean isAttackAllowed() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled()) {
            return false;
        } else if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            return !this.requirePress.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            return false;
        }
    }

    public boolean shouldAutoBlock() {
        if (this.isPlayerBlocking() && this.isBlocking) {
            return !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava() && (this.autoBlock.getValue() == 3
                    || this.autoBlock.getValue() == 4
                    || this.autoBlock.getValue() == 5
                    || this.autoBlock.getValue() == 6
                    || this.autoBlock.getValue() == 7);
        } else {
            return false;
        }
    }

    public boolean isBlocking() {
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }

    public boolean isPlayerBlocking() {
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.attackDelayMS > 0L) {
                if (this.midTrade.getValue()) {
                    // midTrade owns all timing — never let CPS debt stall attacks
                    this.attackDelayMS = 0L;
                } else {
                    this.attackDelayMS -= 50L;
                }
            }
            boolean attack = this.target != null && this.isAttackAllowed();
            boolean block = attack && this.canAutoBlock();
            if (!block) {
                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                this.isBlocking = false;
                this.fakeBlockState = false;
                this.blockTick = 0;
            }
            if (attack) {
                boolean swap = false;
                boolean blocked = false;
                if (block) {
                    switch (this.autoBlock.getValue()) {
                        case 0: // NONE
                            if (PlayerUtil.isUsingItem()) {
                                this.isBlocking = true;
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                            } else {
                                this.isBlocking = false;
                                if (this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    this.stopBlock();
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.fakeBlockState = false;
                            break;
                        case 1: // VANILLA
                            if (this.hasValidTarget()) {
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 2: // SPOOF
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (Myau.playerStateManager.digging
                                        || Myau.playerStateManager.placing
                                        || mc.thePlayer.inventory.currentItem != item
                                        || this.isPlayerBlocking() && this.blockTick != 0
                                        || this.attackDelayMS > 0L && this.attackDelayMS <= 50L) {
                                    this.blockTick = 0;
                                } else {
                                    int slot = this.findEmptySlot(item);
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
                                    swap = true;
                                    this.blockTick = 1;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 3: // HYPIXEL
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            blocked = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                if (Myau.moduleManager.modules.get(NoSlow.class).isEnabled()) {
                                                    int randomSlot = new Random().nextInt(9);
                                                    while (randomSlot == mc.thePlayer.inventory.currentItem) {
                                                        randomSlot = new Random().nextInt(9);
                                                    }
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                                }
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 4: // BLINK
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 5: // INTERACT
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                int slot = this.findEmptySlot(item);
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                                ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(slot);
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 6: // SWAP
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            int slot = this.findSwordSlot(item);
                                            if (slot != -1) {
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blockTick = 1;
                                            }
                                            break;
                                        case 1:
                                            int swordsSlot = this.findSwordSlot(item);
                                            if (swordsSlot == -1) {
                                                this.blockTick = 0;
                                            } else if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            } else if (this.attackDelayMS <= 50L) {
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(swordsSlot));
                                                ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(swordsSlot);
                                                this.startBlock(mc.thePlayer.inventory.getStackInSlot(swordsSlot));
                                                attack = false;
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                    break;
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            break;
                        case 7: // LEGIT
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 8: // FAKE
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = this.hasValidTarget();
                            if (PlayerUtil.isUsingItem()
                                    && !this.isPlayerBlocking()
                                    && !Myau.playerStateManager.digging
                                    && !Myau.playerStateManager.placing) {
                                swap = true;
                            }
                    }
                }
                boolean attacked = false;
                if (this.isBoxInSwingRange(this.target.getBox())) {
                    if (this.rotations.getValue() == 2 || this.rotations.getValue() == 3) {
                        float[] rotations = RotationUtil.getRotationsToBox(
                                this.target.getBox(),
                                event.getYaw(),
                                event.getPitch(),
                                (float) this.angleStep.getValue() + RandomUtil.nextFloat(-5.0F, 5.0F),
                                (float) this.smoothing.getValue() / 100.0F
                        );
                        event.setRotation(rotations[0], rotations[1], 1);
                        if (this.rotations.getValue() == 3) {
                            Myau.rotationManager.setRotation(rotations[0], rotations[1], 1, true);
                        }
                        if (this.moveFix.getValue() != 0 || this.rotations.getValue() == 3) {
                            event.setPervRotation(rotations[0], 1);
                        }
                    }
                    if (attack) {
                        attacked = this.performAttack(event.getNewYaw(), event.getNewPitch());
                    }
                }
                if (swap) {
                    if (attacked) {
                        this.interactAttack(event.getNewYaw(), event.getNewPitch());
                    } else {
                        this.sendUseItem();
                    }
                }
                if (blocked) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled()) {
            switch (event.getType()) {
                case PRE:
                    if (this.target == null
                            || !this.isValidTarget(this.target.getEntity())
                            || !this.isBoxInAttackRange(this.target.getBox())
                            || !this.isBoxInSwingRange(this.target.getBox())
                            || this.timer.hasTimeElapsed(this.switchDelay.getValue().longValue())) {
                        this.timer.reset();
                        ArrayList<EntityLivingBase> targets = new ArrayList<>();
                        for (Entity entity : mc.theWorld.loadedEntityList) {
                            if (entity instanceof EntityLivingBase
                                    && this.isValidTarget((EntityLivingBase) entity)
                                    && this.isInRange((EntityLivingBase) entity)) {
                                targets.add((EntityLivingBase) entity);
                            }
                        }
                        if (targets.isEmpty()) {
                            this.target = null;
                        } else {
                            if (targets.stream().anyMatch(this::isInSwingRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInSwingRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isInAttackRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInAttackRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isPlayerTarget)) {
                                targets.removeIf(entityLivingBase -> !this.isPlayerTarget(entityLivingBase));
                            }
                            targets.sort(
                                    (entityLivingBase1, entityLivingBase2) -> {
                                        int sortBase = 0;
                                        switch (this.sort.getValue()) {
                                            case 1:
                                                sortBase = Float.compare(TeamUtil.getHealthScore(entityLivingBase1), TeamUtil.getHealthScore(entityLivingBase2));
                                                break;
                                            case 2:
                                                sortBase = Integer.compare(entityLivingBase1.hurtResistantTime, entityLivingBase2.hurtResistantTime);
                                                break;
                                            case 3:
                                                sortBase = Float.compare(
                                                        RotationUtil.angleToEntity(entityLivingBase1),
                                                        RotationUtil.angleToEntity(entityLivingBase2)
                                                );
                                        }
                                        return sortBase != 0
                                                ? sortBase
                                                : Double.compare(RotationUtil.distanceToEntity(entityLivingBase1), RotationUtil.distanceToEntity(entityLivingBase2));
                                    }
                            );
                            if (this.mode.getValue() == 1 && this.hitRegistered) {
                                this.hitRegistered = false;
                                this.switchTick++;
                            }
                            if (this.mode.getValue() == 0 || this.switchTick >= targets.size()) {
                                this.switchTick = 0;
                            }
                            EntityLivingBase chosen = targets.get(this.switchTick);
                            this.notifyTargetChanged(chosen.getEntityId());
                            this.target = new AttackData(chosen);
                        }
                    }
                    if (this.target != null) {
                        this.target = new AttackData(this.target.getEntity());
                    }
                    break;
                case POST:
                    if (this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                    }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && !event.isCancelled() && mc.thePlayer != null && mc.theWorld != null) {
            if (event.getPacket() instanceof C07PacketPlayerDigging) {
                C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
                if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    this.blockingState = false;
                }
            }
            if (event.getPacket() instanceof C09PacketHeldItemChange) {
                this.blockingState = false;
                if (this.isBlocking) {
                    mc.thePlayer.stopUsingItem();
                }
            }
            if (this.debugLog.getValue() == 1 && this.isAttackAllowed()) {
                if (event.getPacket() instanceof S06PacketUpdateHealth) {
                    float packet = ((S06PacketUpdateHealth) event.getPacket()).getHealth() - mc.thePlayer.getHealth();
                    if (packet != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                        this.lastTickProcessed = mc.thePlayer.ticksExisted;
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                        Myau.clientName,
                                        packet > 0.0F ? "&a" : "&c",
                                        df.format(packet),
                                        mc.thePlayer.ticksExisted
                                )
                        );
                    }
                }
                if (event.getPacket() instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) event.getPacket();
                    if (packet.getEntityId() == mc.thePlayer.getEntityId()) {
                        for (WatchableObject watchableObject : packet.func_149376_c()) {
                            if (watchableObject.getDataValueId() == 6) {
                                float diff = (Float) watchableObject.getObject() - mc.thePlayer.getHealth();
                                if (diff != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                                    this.lastTickProcessed = mc.thePlayer.ticksExisted;
                                    ChatUtil.sendFormatted(
                                            String.format(
                                                    "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                                    Myau.clientName,
                                                    diff > 0.0F ? "&a" : "&c",
                                                    df.format(diff),
                                                    mc.thePlayer.ticksExisted
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && this.rotations.getValue() != 3
                    && RotationState.isActived()
                    && RotationState.getPriority() == 1.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
            if (this.shouldAutoBlock()) {
                mc.thePlayer.movementInput.jump = false;
            }
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (this.isEnabled() && target != null) {
            if (this.showTarget.getValue() != 0
                    && TeamUtil.isEntityLoaded(this.target.getEntity())
                    && this.isAttackAllowed()) {
                Color color = new Color(-1);
                switch (this.showTarget.getValue()) {
                    case 1:
                        if (this.target.getEntity().hurtTime > 0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                        break;
                    case 2:
                        color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
                }
                RenderUtil.enableRenderState();
                RenderUtil.drawEntityBox(this.target.getEntity(), color.getRed(), color.getGreen(), color.getBlue());
                RenderUtil.disableRenderState();
            }
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.isAttackAllowed()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.isAttackAllowed()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.isAttackAllowed()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onCancelUse(CancelUseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    @Override
    public void onEnabled() {
        this.target = null;
        this.switchTick = 0;
        this.hitRegistered = false;
        this.attackDelayMS = 0L;
        this.blockTick = 0;
        this.burstPhase        = BurstPhase.IDLE;
        this.burstWindowStart  = 0L;
        this.lastBurstEnd      = 0L;
        this.lastClickAttempt  = 0L;
        this.nextClickInterval = 0L;
        this.lastAttackTick    = -1;
        this.freshTarget       = false;
        this.lastTargetSwitchTime = 0L;
        this.lastTargetId = -1;
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.burstPhase        = BurstPhase.IDLE;
        this.burstWindowStart  = 0L;
        this.lastBurstEnd      = 0L;
        this.lastClickAttempt  = 0L;
        this.nextClickInterval = 0L;
        this.lastAttackTick    = -1;
        this.freshTarget       = false;
        this.lastTargetSwitchTime = 0L;
        this.lastTargetId = -1;
    }

    @Override
    public void verifyValue(String value) {
        boolean badCps = this.autoBlock.getValue() == 2
                || this.autoBlock.getValue() == 3
                || this.autoBlock.getValue() == 4
                || this.autoBlock.getValue() == 5
                || this.autoBlock.getValue() == 6
                || this.autoBlock.getValue() == 7;
        if (!this.autoBlock.getName().equals(value)) {
            if (this.swingRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.attackRange.setValue(this.swingRange.getValue());
                }
            } else if (this.attackRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.swingRange.setValue(this.attackRange.getValue());
                }
            } else if (this.minCPS.getName().equals(value)) {
                if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.maxCPS.setValue(this.minCPS.getValue());
                }
            } else if (this.autoBlockMinCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMaxCPS.setValue(this.autoBlockMinCPS.getValue());
                }
                if (autoBlockMinCPS.getValue() > 10.0F && badCps) {
                    autoBlockMinCPS.setValue(10.0F);
                }
            } else if (this.autoBlockMaxCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMinCPS.setValue(this.autoBlockMaxCPS.getValue());
                }
                if (autoBlockMaxCPS.getValue() > 10.0F && badCps) {
                    autoBlockMaxCPS.setValue(10.0F);
                }
            } else if (this.minAPS.getName().equals(value)) {
                if (this.minAPS.getValue() > this.maxAPS.getValue())
                    this.maxAPS.setValue(this.minAPS.getValue());
            } else if (this.maxAPS.getName().equals(value)) {
                if (this.minAPS.getValue() > this.maxAPS.getValue())
                    this.minAPS.setValue(this.maxAPS.getValue());
            } else {
                if (this.maxCPS.getName().equals(value) && this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.minCPS.setValue(this.maxCPS.getValue());
                }
            }
        } else {
            if (badCps && (this.autoBlockMinCPS.getValue() > 10.0F || this.autoBlockMaxCPS.getValue() > 10.0F)) {
                this.autoBlockMinCPS.setValue(8.0F);
                this.autoBlockMaxCPS.setValue(10.0F);
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    // -------------------------------------------------------
    // AttackData
    // -------------------------------------------------------

    public static class AttackData {
        private final EntityLivingBase entity;
        private final AxisAlignedBB box;
        private final double x;
        private final double y;
        private final double z;

        public AttackData(EntityLivingBase entityLivingBase) {
            this.entity = entityLivingBase;
            double collisionBorderSize = entityLivingBase.getCollisionBorderSize();
            this.box = entityLivingBase.getEntityBoundingBox().expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);
            this.x = entityLivingBase.posX;
            this.y = entityLivingBase.posY;
            this.z = entityLivingBase.posZ;
        }

        public EntityLivingBase getEntity() {
            return this.entity;
        }

        public AxisAlignedBB getBox() {
            return this.box;
        }

        public double getX() {
            return this.x;
        }

        public double getY() {
            return this.y;
        }

        public double getZ() {
            return this.z;
        }
    }
}
