/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.entity.player.BlockBreakingCooldownEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.NopPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AutoHarvest extends Module {
    private static final int NEWLY_PLANTED_LOCK_TICKS = 200;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Item>> cropOrder = sgGeneral.add(new ItemListSetting.Builder()
        .name("crop-order")
        .description("Crops to harvest in order. Only one crop type is harvested at a time.")
        .defaultValue(
            Items.WHEAT_SEEDS,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.MELON_SEEDS
        )
        .filter(AutoHarvest::isSupportedCropChoice)
        .build()
    );

    private final Setting<Integer> finishScans = sgGeneral.add(new IntSetting.Builder()
        .name("finish-scans")
        .description("Failed searches before the module considers the current crop finished and selects the next crop.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<HarvestMode> harvestMode = sgGeneral.add(new EnumSetting.Builder<HarvestMode>()
        .name("harvest-mode")
        .description("Nuker sends instant break packets to several mature crops each tick. Legit uses normal mining.")
        .defaultValue(HarvestMode.Nuker)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Harvesting range around the player.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> breakDelay = sgGeneral.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Ticks between harvesting actions.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Maximum mature crops harvested per action tick.")
        .defaultValue(5)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> targetCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("target-cooldown")
        .description("Ticks to ignore a crop after a break request, preventing repeated packets while the server updates and replants it.")
        .defaultValue(30)
        .range(5, 200)
        .sliderRange(5, 100)
        .build()
    );

    private final Setting<Boolean> packetMine = sgGeneral.add(new BoolSetting.Builder()
        .name("packet-mine")
        .description("Uses start and stop destroy packets in Legit mode. Nuker mode always uses packets.")
        .defaultValue(false)
        .visible(() -> harvestMode.get() == HarvestMode.Legit)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side toward a crop before harvesting it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings the hand when harvesting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoMove = sgMovement.add(new BoolSetting.Builder()
        .name("auto-move")
        .description("Uses the active path manager to find the current crop farther along the farm.")
        .defaultValue(true)
        .build()
    );

    private final Setting<FarmLayout> farmLayout = sgMovement.add(new EnumSetting.Builder<FarmLayout>()
        .name("farm-layout")
        .description("Movement layout. Irrigated 11x11 uses watered stone-brick stairs as plot centers.")
        .defaultValue(FarmLayout.Irrigated11x11)
        .visible(autoMove::get)
        .build()
    );

    private final Setting<MovementEngine> movementEngine = sgMovement.add(new EnumSetting.Builder<MovementEngine>()
        .name("movement-engine")
        .description("Auto prefers Baritone, Direct walks normally, and Fly uses server-granted flight.")
        .defaultValue(MovementEngine.Auto)
        .visible(autoMove::get)
        .build()
    );

    private final Setting<Boolean> directSprint = sgMovement.add(new BoolSetting.Builder()
        .name("direct-sprint")
        .description("Uses faster horizontal movement with the Direct or Fly engine.")
        .defaultValue(true)
        .visible(() -> autoMove.get() && movementEngine.get() != MovementEngine.Baritone)
        .build()
    );

    private final Setting<Boolean> directAutoJump = sgMovement.add(new BoolSetting.Builder()
        .name("direct-auto-jump")
        .description("Jumps when direct movement meets a solid obstacle.")
        .defaultValue(true)
        .visible(() -> autoMove.get() && movementEngine.get() != MovementEngine.Baritone && movementEngine.get() != MovementEngine.Fly)
        .build()
    );

    private final Setting<Integer> flyHeight = sgMovement.add(new IntSetting.Builder()
        .name("fly-height")
        .description("Blocks above crop level used while flying. Two keeps an 11x11 work area within interaction range.")
        .defaultValue(2)
        .range(1, 2)
        .sliderRange(1, 2)
        .visible(() -> autoMove.get() && movementEngine.get() == MovementEngine.Fly)
        .build()
    );

    private final Setting<Integer> searchRange = sgMovement.add(new IntSetting.Builder()
        .name("search-range")
        .description("Horizontal range used to find the current crop type.")
        .defaultValue(32)
        .range(8, 64)
        .sliderRange(8, 64)
        .visible(autoMove::get)
        .build()
    );

    private final Setting<Integer> moveScanDelay = sgMovement.add(new IntSetting.Builder()
        .name("scan-delay")
        .description("Ticks between fast searches for distant crops.")
        .defaultValue(5)
        .range(1, 40)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> autoDeposit = sgInventory.add(new BoolSetting.Builder()
        .name("auto-deposit")
        .description("Drops non-priority planting seeds into island storage and stores server rewards in the configured chest.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BlockPos> storageChest = sgInventory.add(new BlockPosSetting.Builder()
        .name("storage-chest")
        .description("Chest or barrel used when no room remains for another priority seed. Aim at it and press Set Storage Bind to save it.")
        .defaultValue(BlockPos.ORIGIN)
        .visible(autoDeposit::get)
        .build()
    );

    private final Setting<Keybind> setStorageBind = sgInventory.add(new KeybindSetting.Builder()
        .name("set-storage-bind")
        .description("Saves the chest or barrel currently under the crosshair as storage.")
        .defaultValue(Keybind.none())
        .visible(autoDeposit::get)
        .build()
    );

    private final Setting<Integer> emptySlotsAfterDeposit = sgInventory.add(new IntSetting.Builder()
        .name("empty-slots-after-deposit")
        .description("Inventory slots to free before harvesting resumes.")
        .defaultValue(3)
        .range(3, 9)
        .sliderRange(3, 9)
        .visible(autoDeposit::get)
        .build()
    );

    private final Setting<Integer> depositDelay = sgInventory.add(new IntSetting.Builder()
        .name("deposit-delay")
        .description("Ticks between inventory transfers into the storage chest.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
        .visible(autoDeposit::get)
        .build()
    );

    private final Setting<Integer> islandStorageWait = sgInventory.add(new IntSetting.Builder()
        .name("island-storage-wait")
        .description("Ticks to wait after dropping a planting item for island storage to collect it.")
        .defaultValue(20)
        .range(1, 100)
        .sliderRange(5, 40)
        .visible(autoDeposit::get)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders mature crops selected for the current harvesting pass.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How harvesting targets are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Target fill color.")
        .defaultValue(new SettingColor(255, 170, 0, 45))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Target line color.")
        .defaultValue(new SettingColor(255, 170, 0, 255))
        .visible(render::get)
        .build()
    );

    private final List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> targetCooldowns = new HashMap<>();
    private final Map<BlockPos, PlantedCropLock> newlyPlanted = new HashMap<>();

    private Item activeCrop;
    private int cropIndex;
    private int emptyScans;
    private int breakTimer;
    private int moveScanTimer;
    private int depositTimer;
    private int chestOpenTimer;
    private int islandStorageWaitTicks;
    private boolean pathingByModule;
    private boolean depositingInventory;
    private boolean warnedNoPathManager;
    private boolean warnedDepositBlocked;
    private BlockPos movementTarget;
    private BlockPos directMovementPoint;
    private BlockPos flyLandingPoint;
    private boolean directMoving;
    private boolean flyLandingUnavailable;

    public AutoHarvest() {
        super(Categories.Dava, "auto-harvest", "Harvests one selected mature crop type at a time without controlling Nuker.");
    }

    @Override
    public void onActivate() {
        targets.clear();
        targetCooldowns.clear();
        newlyPlanted.clear();
        cropIndex = 0;
        activeCrop = cropOrder.get().isEmpty() ? null : cropOrder.get().getFirst();
        emptyScans = 0;
        breakTimer = 0;
        moveScanTimer = moveScanDelay.get();
        depositTimer = 0;
        chestOpenTimer = 0;
        islandStorageWaitTicks = 0;
        pathingByModule = false;
        depositingInventory = false;
        warnedNoPathManager = false;
        warnedDepositBlocked = false;
        movementTarget = null;
        directMovementPoint = null;
        flyLandingPoint = null;
        directMoving = false;
        flyLandingUnavailable = false;
        announceActiveCrop();
    }

    @Override
    public void onDeactivate() {
        if (depositingInventory && mc.currentScreen instanceof HandledScreen<?>) mc.currentScreen.close();
        stopPathing();
        targets.clear();
        targetCooldowns.clear();
        newlyPlanted.clear();
        activeCrop = null;
        depositingInventory = false;
    }

    @Override
    public String getInfoString() {
        return activeCrop == null ? null : Registries.ITEM.getId(activeCrop).getPath().toUpperCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.player == null || mc.world == null) return;

        tickTargetCooldowns();
        tickNewlyPlanted();

        if (depositingInventory) {
            targets.clear();
            depositInventory();
            return;
        }

        if (mc.currentScreen != null) {
            stopPathing();
            targets.clear();
            return;
        }

        if (!syncActiveCrop()) {
            stopPathing();
            targets.clear();
            return;
        }

        AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
        if (autoPlant != null && autoPlant.isActive() && autoPlant.isWorking()) {
            stopPathing();
            targets.clear();
            return;
        }

        if (autoDeposit.get() && needsSeedStorageSpace() && hasInventoryDepositCandidate()) {
            depositingInventory = true;
            warnedDepositBlocked = false;
            stopPathing();
            targets.clear();
            depositInventory();
            return;
        }

        collectLocalTargets();
        if (!targets.isEmpty()) {
            stopPathing();
            emptyScans = 0;
            harvestTargets();
            return;
        }

        if (searchOrContinuePath()) {
            emptyScans++;
            if (emptyScans >= finishScans.get()) advanceCrop();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (BlockPos target : targets) {
            event.renderer.box(target, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press) saveTargetedStorage();
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Press) saveTargetedStorage();
    }

    private void saveTargetedStorage() {
        if (!setStorageBind.get().isPressed() || mc.currentScreen != null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hitResult)) return;

        BlockPos pos = hitResult.getBlockPos();
        if (!isStorageBlock(mc.world.getBlockState(pos))) {
            warning("Target a chest, trapped chest, or barrel before pressing Set Storage Bind.");
            return;
        }

        storageChest.set(pos.toImmutable());
        warnedDepositBlocked = false;
        info("Storage saved at %d, %d, %d.", pos.getX(), pos.getY(), pos.getZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onBlockBreakingCooldown(BlockBreakingCooldownEvent event) {
        event.cooldown = 0;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos.toImmutable();
        PlantedCropLock lock = newlyPlanted.get(pos);
        if (lock != null) {
            if (isCropForChoice(event.newState, lock.crop)) {
                if (!isMatureCrop(event.newState, lock.crop)) lock.sawYoungState = true;
            } else if (event.newState.isAir()) {
                AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
                if ((autoPlant == null || !autoPlant.isPlantPending(pos)) && lock.ticksRemaining <= 0) newlyPlanted.remove(pos);
            } else {
                newlyPlanted.remove(pos);
            }
        }

        if (targetCooldowns.containsKey(pos)) return;
        if (isMatureCrop(event.oldState, activeCrop) && !isMatureCrop(event.newState, activeCrop)) {
            targetCooldowns.put(pos, targetCooldown.get());
        }
    }

    private boolean syncActiveCrop() {
        List<Item> order = cropOrder.get();
        if (order.isEmpty()) {
            activeCrop = null;
            return false;
        }

        if (activeCrop != null && order.contains(activeCrop)) {
            cropIndex = order.indexOf(activeCrop);
            return true;
        }

        cropIndex = Math.min(cropIndex, order.size() - 1);
        activeCrop = order.get(cropIndex);
        emptyScans = 0;
        moveScanTimer = moveScanDelay.get();
        announceActiveCrop();
        return true;
    }

    private void advanceCrop() {
        List<Item> order = cropOrder.get();
        if (order.isEmpty()) {
            activeCrop = null;
            return;
        }

        stopPathing();
        cropIndex = (cropIndex + 1) % order.size();
        activeCrop = order.get(cropIndex);
        emptyScans = 0;
        moveScanTimer = moveScanDelay.get();
        announceActiveCrop();
    }

    private void announceActiveCrop() {
        if (activeCrop == null) return;
        info("Harvesting only " + Registries.ITEM.getId(activeCrop).getPath().toUpperCase(Locale.ROOT) + ".");
    }

    private void collectLocalTargets() {
        targets.clear();
        BlockPos playerPos = mc.player.getBlockPos();
        int radius = (int) Math.ceil(range.get());
        // Keep local break requests inside a conservative interaction radius. A
        // crop outside this radius must go through the movement path first.
        double actionRange = Math.min(range.get(), 3.75);
        double rangeSquared = actionRange * actionRange;

        for (int x = playerPos.getX() - radius; x <= playerPos.getX() + radius; x++) {
            for (int y = playerPos.getY() - radius; y <= playerPos.getY() + radius; y++) {
                for (int z = playerPos.getZ() - radius; z <= playerPos.getZ() + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()) > rangeSquared) continue;
                    BlockState state = mc.world.getBlockState(pos);
                    if (!isTargetCoolingDown(pos) && !isPlantSuppressed(pos) && isMatureCrop(state, activeCrop) && BlockUtils.canBreak(pos, state)) targets.add(pos);
                }
            }
        }

        targets.sort(Comparator.comparingDouble(pos -> Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
    }

    private void harvestTargets() {
        if (breakTimer++ < breakDelay.get()) return;
        breakTimer = 0;

        int limit = harvestMode.get() == HarvestMode.Nuker ? Math.max(5, blocksPerTick.get()) : blocksPerTick.get();
        int harvested = 0;
        for (BlockPos target : targets) {
            if (harvested >= limit) break;
            if (isTargetCoolingDown(target) || !isHarvestTarget(target)) continue;

            targetCooldowns.put(target.toImmutable(), targetCooldown.get());
            AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
            if (autoPlant != null && autoPlant.isActive()) autoPlant.rememberHarvestedCrop(target, activeCrop);

            Runnable action = () -> {
                BlockState state = mc.world.getBlockState(target);
                if (!isPlantSuppressed(target) && isWithinActionRange(target) && isMatureCrop(state, activeCrop) && BlockUtils.canBreak(target, state)) breakCrop(target);
            };
            if (rotate.get()) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), action);
            else action.run();

            if (render.get()) RenderUtils.renderTickingBlock(target, sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
            harvested++;
        }
    }

    private void breakCrop(BlockPos pos) {
        if (harvestMode.get() == HarvestMode.Nuker || packetMine.get()) {
            mc.interactionManager.sendSequencedPacket(mc.world, sequence -> new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, BlockUtils.getDirection(pos), sequence));

            if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
            else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

            mc.interactionManager.sendSequencedPacket(mc.world, sequence -> new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, BlockUtils.getDirection(pos), sequence));
        } else {
            BlockUtils.breakBlock(pos, swing.get());
        }
    }

    private boolean searchOrContinuePath() {
        if (!autoMove.get()) {
            stopPathing();
            if (moveScanTimer++ < moveScanDelay.get()) return false;
            moveScanTimer = 0;
            return true;
        }

        if (movementTarget != null) {
            if (!isHarvestTarget(movementTarget)) {
                stopPathing();
            } else if (isWithinActionRange(movementTarget)) {
                stopPathing();
                return false;
            } else if (directMoving) {
                if (usesDirectMovement()) updateDirectMovement();
                else moveToTarget(movementTarget);
                emptyScans = 0;
                return false;
            } else if (PathManagers.get().isPathing()) {
                emptyScans = 0;
                return false;
            }
        }

        if (moveScanTimer++ < moveScanDelay.get()) return false;
        moveScanTimer = 0;

        BlockPos target = findNearestDistantTarget();
        if (target == null) {
            stopPathing();
            return true;
        }

        emptyScans = 0;
        if (isWithinActionRange(target)) return false;

        if (movementEngine.get() == MovementEngine.Baritone && PathManagers.get() instanceof NopPathManager) {
            if (!warnedNoPathManager) {
                warning("Baritone movement was selected, but Baritone is unavailable. Select Auto or Direct for built-in walking.");
                warnedNoPathManager = true;
            }
            return false;
        }

        moveToTarget(target);
        return false;
    }

    private BlockPos findNearestDistantTarget() {
        BlockPos playerPos = mc.player.getBlockPos();
        int horizontalRange = searchRange.get();
        // Farm plots are level; keeping this narrow avoids scanning thousands of
        // unrelated blocks every fast search while still covering the stair and water height.
        int verticalRange = Math.min(2, horizontalRange);
        double bestDistance = Double.MAX_VALUE;
        BlockPos bestTarget = null;

        for (int x = playerPos.getX() - horizontalRange; x <= playerPos.getX() + horizontalRange; x++) {
            for (int z = playerPos.getZ() - horizontalRange; z <= playerPos.getZ() + horizontalRange; z++) {
                if (!mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;

                for (int y = playerPos.getY() - verticalRange; y <= playerPos.getY() + verticalRange; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isHarvestTarget(pos)) continue;

                    double distance = pos.getSquaredDistance(playerPos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestTarget = pos;
                    }
                }
            }
        }

        return bestTarget;
    }

    private boolean isHarvestTarget(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !isTargetCoolingDown(pos) && !isPlantSuppressed(pos) && isMatureCrop(state, activeCrop) && BlockUtils.canBreak(pos, state);
    }

    private boolean isPlantSuppressed(BlockPos pos) {
        PlantedCropLock lock = newlyPlanted.get(pos);
        if (lock != null) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) {
                AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
                if (lock.ticksRemaining > 0 || (autoPlant != null && autoPlant.isPlantPending(pos))) return true;
                newlyPlanted.remove(pos);
            } else if (!isCropForChoice(state, lock.crop)) {
                newlyPlanted.remove(pos);
            } else if (!lock.sawYoungState || lock.ticksRemaining > 0 || !isMatureCrop(state, lock.crop)) {
                return true;
            } else {
                // A young state was observed and the crop has now grown. It is
                // safe for AutoHarvest to consider it on the next scan.
                newlyPlanted.remove(pos);
            }
        }

        AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
        return autoPlant != null
            && (autoPlant.isPlantPending(pos) || autoPlant.isPlantSuppressed(pos));
    }

    /** Returns true while this module owns the shared path manager or direct movement keys. */
    public boolean isControllingMovement() {
        return movementTarget != null || pathingByModule || directMoving;
    }

    /** Returns true while AutoPlant must not take control of the player. */
    public boolean isBusyForAutoPlant() {
        return isControllingMovement() || !targets.isEmpty() || depositingInventory;
    }

    /**
     * Called by AutoPlant before it sends the placement interaction. Keeping a
     * lock in AutoHarvest itself prevents a stale or out-of-order block update
     * from making the freshly planted crop a harvest target.
     */
    public void rememberPlantedCrop(BlockPos pos, Item crop) {
        if (isSupportedCropChoice(crop)) newlyPlanted.put(pos.toImmutable(), new PlantedCropLock(crop));
    }

    private void tickNewlyPlanted() {
        AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
        Iterator<Map.Entry<BlockPos, PlantedCropLock>> iterator = newlyPlanted.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PlantedCropLock> entry = iterator.next();
            BlockPos pos = entry.getKey();
            PlantedCropLock lock = entry.getValue();
            BlockState state = mc.world.getBlockState(pos);

            if (state.isAir()) {
                if (lock.ticksRemaining <= 0 && (autoPlant == null || !autoPlant.isPlantPending(pos))) iterator.remove();
                else if (lock.ticksRemaining > 0) lock.ticksRemaining--;
                continue;
            }

            if (!isCropForChoice(state, lock.crop)) {
                iterator.remove();
                continue;
            }

            if (!isMatureCrop(state, lock.crop)) lock.sawYoungState = true;
            if (lock.ticksRemaining > 0) lock.ticksRemaining--;
        }
    }

    private boolean isTargetCoolingDown(BlockPos pos) {
        return targetCooldowns.containsKey(pos);
    }

    private void tickTargetCooldowns() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator = targetCooldowns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) iterator.remove();
            else entry.setValue(remaining);
        }
    }

    private void moveToTarget(BlockPos workTarget) {
        BlockPos movementPoint = getMovementPoint(workTarget);
        stopPathing();
        movementTarget = workTarget;

        if (usesDirectMovement()) {
            directMovementPoint = getDirectMovementPoint(movementPoint);
            directMoving = true;
            updateDirectMovement();
            return;
        }

        // GoalGetToBlock may stop on the far side of this waypoint. That can
        // leave the crop outside interaction range and make Baritone repeatedly
        // select the same already handled plot. Farming needs the player to
        // stand on the calculated work point itself.
        PathManagers.get().moveToExact(movementPoint);
        pathingByModule = true;
    }

    private boolean usesDirectMovement() {
        return movementEngine.get() == MovementEngine.Direct
            || movementEngine.get() == MovementEngine.Fly
            || movementEngine.get() == MovementEngine.Auto && PathManagers.get() instanceof NopPathManager;
    }

    private void updateDirectMovement() {
        if (!directMoving || directMovementPoint == null) return;

        if (movementEngine.get() == MovementEngine.Fly) {
            updateFlyMovement();
            return;
        }

        double dx = directMovementPoint.getX() + 0.5 - mc.player.getX();
        double dz = directMovementPoint.getZ() + 0.5 - mc.player.getZ();
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = mc.player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(desiredYaw - mc.player.getYaw()), -15, 15);

        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(directSprint.get());
        mc.options.jumpKey.setPressed(directAutoJump.get() && mc.player.horizontalCollision && mc.player.isOnGround());
    }

    private void updateFlyMovement() {
        if (!ensureFlying()) {
            releaseMovementKeys();
            return;
        }

        double dx = directMovementPoint.getX() + 0.5 - mc.player.getX();
        double dy = directMovementPoint.getY() - mc.player.getY();
        double dz = directMovementPoint.getZ() + 0.5 - mc.player.getZ();
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = mc.player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(desiredYaw - mc.player.getYaw()), -15, 15);

        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        boolean nearHorizontal = dx * dx + dz * dz <= 0.16;
        mc.options.forwardKey.setPressed(!nearHorizontal);
        mc.options.sprintKey.setPressed(directSprint.get());
        mc.options.jumpKey.setPressed(dy > 0.3);
        mc.options.sneakKey.setPressed(dy < -0.3 || nearHorizontal && !mc.player.isOnGround());
    }

    private void updateLandingMovement() {
        // Never call ensureFlying while landing: /is fly is a toggle command and
        // sending it during a descent could turn flight back on. Vanilla/server
        // flight uses sneak to descend; the same key is also understood by the
        // optional Flight velocity mode.
        if (mc.player.getAbilities().flying) {
            double dx = directMovementPoint.getX() + 0.5 - mc.player.getX();
            double dy = directMovementPoint.getY() - mc.player.getY();
            double dz = directMovementPoint.getZ() + 0.5 - mc.player.getZ();
            float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float yaw = mc.player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(desiredYaw - mc.player.getYaw()), -15, 15);
            boolean nearHorizontal = dx * dx + dz * dz <= 0.16;

            mc.player.setYaw(yaw);
            mc.player.setHeadYaw(yaw);
            mc.options.forwardKey.setPressed(!nearHorizontal);
            mc.options.sprintKey.setPressed(directSprint.get());
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(true);
            return;
        }

        // If the server already turned off flight, finish approaching the safe
        // point on foot while gravity brings the player down naturally.
        double dx = directMovementPoint.getX() + 0.5 - mc.player.getX();
        double dz = directMovementPoint.getZ() + 0.5 - mc.player.getZ();
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = mc.player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(desiredYaw - mc.player.getYaw()), -15, 15);

        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.options.forwardKey.setPressed(dx * dx + dz * dz > 0.16 && mc.player.isOnGround());
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(!mc.player.isOnGround());
    }

    private boolean ensureFlying() {
        return FarmFlightController.ensureFlying(this);
    }

    private BlockPos getDirectMovementPoint(BlockPos movementPoint) {
        return movementEngine.get() == MovementEngine.Fly ? movementPoint.up(flyHeight.get()) : movementPoint;
    }

    private void releaseMovementKeys() {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void stopDirectMovement() {
        if (!directMoving) return;
        releaseMovementKeys();
        directMoving = false;
        directMovementPoint = null;
    }

    private BlockPos getMovementPoint(BlockPos workTarget) {
        if (farmLayout.get() != FarmLayout.Irrigated11x11) return workTarget;

        BlockPos center = findIrrigatedPlotCenter(workTarget);
        if (center == null) return workTarget;

        int offsetX = Integer.signum(workTarget.getX() - center.getX()) * 3;
        int offsetZ = Integer.signum(workTarget.getZ() - center.getZ()) * 3;
        return new BlockPos(center.getX() + offsetX, workTarget.getY(), center.getZ() + offsetZ);
    }

    private BlockPos findIrrigatedPlotCenter(BlockPos workTarget) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int x = workTarget.getX() - 6; x <= workTarget.getX() + 6; x++) {
            for (int z = workTarget.getZ() - 6; z <= workTarget.getZ() + 6; z++) {
                for (int y = workTarget.getY() - 1; y <= workTarget.getY() + 1; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isWateredStoneBrickStair(pos)) continue;

                    double distance = pos.getSquaredDistance(workTarget);
                    if (distance < nearestDistance) {
                        nearest = pos;
                        nearestDistance = distance;
                    }
                }
            }
        }

        return nearest;
    }

    private boolean isWateredStoneBrickStair(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() != Blocks.STONE_BRICK_STAIRS) return false;
        if (state.getFluidState().isIn(FluidTags.WATER)) return true;
        if (mc.world.getFluidState(pos.down()).isIn(FluidTags.WATER)) return true;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (mc.world.getFluidState(pos.offset(direction)).isIn(FluidTags.WATER)) return true;
        }

        return false;
    }

    private boolean isWithinActionRange(BlockPos pos) {
        double actionRange = Math.min(range.get(), 3.75);
        return Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()) <= actionRange * actionRange;
    }

    private void stopPathing() {
        if (pathingByModule) PathManagers.get().stop();
        stopDirectMovement();
        pathingByModule = false;
        movementTarget = null;
    }

    private boolean needsSeedStorageSpace() {
        // Depositing is a seed-capacity decision, not a blanket inventory
        // decision. An empty slot, or a partially filled stack of a priority
        // seed, can still accept the next planting reward and must not trigger
        // chest handling yet.
        for (int slot = SlotUtils.HOTBAR_START; slot <= SlotUtils.MAIN_END; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) return false;
            if (!isServerRewardItem(stack)
                && isActivePlantingItem(stack.getItem())
                && stack.getCount() < stack.getMaxCount()) return false;
        }

        return true;
    }

    private int getEmptySlotCount() {
        int count = 0;
        for (int slot = SlotUtils.HOTBAR_START; slot <= SlotUtils.MAIN_END; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) count++;
        }
        return count;
    }

    private int requiredEmptySlots() {
        // Keep a hard safety floor even for an older saved config that used the
        // former 1- or 2-slot range.
        return Math.max(3, emptySlotsAfterDeposit.get());
    }

    private void depositInventory() {
        if (islandStorageWaitTicks > 0) {
            // Stay on the safe landing point while the server transfers the
            // dropped planting item into island storage.
            releaseMovementKeys();
            islandStorageWaitTicks--;
            return;
        }

        if (getEmptySlotCount() >= requiredEmptySlots()) {
            finishDepositing();
            return;
        }

        // The island storage plugin collects planting items dropped at the
        // player's feet. Only these known seeds/crops may use the drop path;
        // harvested produce, rewards, and unknown items go to the chest.
        if (mc.currentScreen == null && dropFarmItemToIslandStorage()) return;

        // Do not walk to/open a chest when the inventory contains no server
        // reward at all. This is the normal state when every remaining stack is
        // the active priority seed; that seed must stay in the inventory.
        if (mc.currentScreen == null && !hasInventoryStorageCandidate()) {
            if (dropPrioritySeedForWithdrawAll()) return;

            // There is nothing safe to move: only priority seeds and tools are
            // left. Close any previous deposit state and continue farming
            // instead of opening the chest forever for an impossible third slot.
            finishDepositing();
            return;
        }

        BlockPos chestPos = storageChest.get();
        if (chestPos.equals(BlockPos.ORIGIN)) {
            warnDepositBlocked("No room remains for the priority seed, but no storage chest is configured. Aim at a chest and press Set Storage Bind.");
            return;
        }

        if (mc.currentScreen instanceof HandledScreen<?> screen) {
            if (screen.getScreenHandler() instanceof GenericContainerScreenHandler handler) {
                transferToStorage(handler);
            } else {
                warnDepositBlocked("No room remains for the priority seed, but another container screen is open. Close it to continue storage.");
            }
            return;
        }

        if (mc.currentScreen != null) {
            warnDepositBlocked("No room remains for the priority seed. Close the current screen so Auto Harvest can reach storage.");
            return;
        }

        boolean chestChunkLoaded = mc.world.getChunkManager().isChunkLoaded(chestPos.getX() >> 4, chestPos.getZ() >> 4);
        if (chestChunkLoaded && !isStorageBlock(mc.world.getBlockState(chestPos))) {
            stopPathing();
            warnDepositBlocked("The saved storage block is missing. Aim at another chest and press Set Storage Bind.");
            return;
        }

        if (!isWithinStorageRange(chestPos)) {
            moveToStorage(chestPos);
            return;
        }

        stopPathing();
        if (chestOpenTimer++ < 10) return;
        chestOpenTimer = 0;

        Runnable action = () -> BlockUtils.interact(new BlockHitResult(
            Vec3d.ofCenter(chestPos), BlockUtils.getDirection(chestPos), chestPos, false), Hand.MAIN_HAND, swing.get());
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(chestPos), Rotations.getPitch(chestPos), action);
        else action.run();
    }

    private void transferToStorage(GenericContainerScreenHandler handler) {
        if (depositTimer++ < depositDelay.get()) return;
        depositTimer = 0;

        Slot playerSlot = findMainInventorySlot(handler);
        if (playerSlot == null) {
            if (!hasStorageCandidate(handler)) {
                finishDepositing();
            } else {
                warnDepositBlocked("The storage chest is full. Auto Harvest is paused and no items will be dropped.");
            }
            return;
        }

        warnedDepositBlocked = false;
        mc.interactionManager.clickSlot(handler.syncId, playerSlot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    private boolean dropFarmItemToIslandStorage() {
        int slot = findFarmStorageSlot(SlotUtils.MAIN_START, SlotUtils.MAIN_END);
        if (slot == -1) slot = findFarmStorageSlot(SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END);
        return dropItemToIslandStorage(slot);
    }

    private boolean dropPrioritySeedForWithdrawAll() {
        int slot = findPrioritySeedSlot(SlotUtils.MAIN_START, SlotUtils.MAIN_END);
        if (slot == -1) slot = findPrioritySeedSlot(SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END);
        return dropItemToIslandStorage(slot);
    }

    private boolean dropItemToIslandStorage(int slot) {
        if (slot == -1) return false;

        if (movementEngine.get() == MovementEngine.Fly) {
            if (flyLandingUnavailable) return true;

            if (flyLandingPoint == null) {
                flyLandingPoint = findSafeLandingPoint();
                if (flyLandingPoint == null) {
                    flyLandingUnavailable = true;
                    warnDepositBlocked("Farm items cannot be dropped while flying: no safe non-farmland landing position was found.");
                    return true;
                }

                directMovementPoint = flyLandingPoint;
                directMoving = true;
            }

            if (!isAtSafeLandingPoint()) {
                updateLandingMovement();
                return true;
            }

            releaseMovementKeys();
        }

        if (depositTimer++ < depositDelay.get()) return true;
        depositTimer = 0;
        InvUtils.drop().slot(slot);
        islandStorageWaitTicks = islandStorageWait.get();
        return true;
    }

    private BlockPos findSafeLandingPoint() {
        BlockPos origin = mc.player.getBlockPos();
        int radius = 8;
        int bottomY = mc.world.getBottomY() + 1;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                for (int y = origin.getY(); y >= bottomY; y--) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    BlockState feet = mc.world.getBlockState(candidate);
                    BlockState head = mc.world.getBlockState(candidate.up());
                    BlockState floor = mc.world.getBlockState(candidate.down());
                    if (!feet.isAir() || !head.isAir() || floor.isReplaceable()
                        || floor.getBlock() == Blocks.FARMLAND || !floor.getFluidState().isEmpty()
                        || floor.getCollisionShape(mc.world, candidate.down()).isEmpty()) continue;

                    double distance = candidate.getSquaredDistance(mc.player.getEntityPos());
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                    }
                    break;
                }
            }
        }

        return best;
    }

    private boolean isAtSafeLandingPoint() {
        if (flyLandingPoint == null) return false;
        double dx = mc.player.getX() - (flyLandingPoint.getX() + 0.5);
        double dy = mc.player.getY() - flyLandingPoint.getY();
        double dz = mc.player.getZ() - (flyLandingPoint.getZ() + 0.5);
        return dx * dx + dz * dz <= 0.36 && Math.abs(dy) <= 0.35 && mc.player.isOnGround();
    }

    private int findFarmStorageSlot(int from, int to) {
        for (int slot = from; slot <= to; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (isFarmStorageItem(stack) && !isActivePlantingItem(stack.getItem())) return slot;
        }
        return -1;
    }

    private int findPrioritySeedSlot(int from, int to) {
        for (int slot = from; slot <= to; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (isFarmStorageItem(stack) && isWithdrawingAllPrioritySeed(stack.getItem())) return slot;
        }
        return -1;
    }

    private boolean isActivePlantingItem(Item item) {
        if (item == activeCrop) return true;

        AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
        return autoPlant != null && autoPlant.isPlantingItem(item);
    }

    private boolean isWithdrawingAllPrioritySeed(Item item) {
        AutoPlant autoPlant = Modules.get().get(AutoPlant.class);
        return autoPlant != null && autoPlant.isWithdrawingAllFor(item);
    }

    private Slot findMainInventorySlot(ScreenHandler handler) {
        // Named server rewards in the hotbar have priority over ordinary
        // storage candidates so a reward never remains trapped in slots 1-9.
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) || !slot.hasStack()) continue;

            int inventoryIndex = slot.getIndex();
            if (inventoryIndex < 0 || inventoryIndex > 8) continue;
            if (!isServerRewardItem(slot.getStack()) || !isStorageCandidate(slot.getStack())) continue;
            if (canStorageAccept(handler, slot.getStack())) return slot;
        }

        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) || !slot.hasStack()) continue;

            // Planting seeds and damageable tools are handled elsewhere. Other
            // stacks in the main inventory are server rewards for storage.
            int inventoryIndex = slot.getIndex();
            if (inventoryIndex < 9 || inventoryIndex > 35) continue;
            if (!isStorageCandidate(slot.getStack())) continue;
            if (canStorageAccept(handler, slot.getStack())) return slot;
        }

        // Rewards can also land in the hotbar. Do not protect the whole bar:
        // preserve only the active planting seed and damageable tools, while
        // moving ordinary reward stacks (including the custom summer-seed reward).
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) || !slot.hasStack()) continue;

            int inventoryIndex = slot.getIndex();
            if (inventoryIndex < 0 || inventoryIndex > 8) continue;
            if (!isStorageCandidate(slot.getStack())) continue;
            if (canStorageAccept(handler, slot.getStack())) return slot;
        }

        return null;
    }

    private boolean hasStorageCandidate(ScreenHandler handler) {
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) || !slot.hasStack()) continue;

            int inventoryIndex = slot.getIndex();
            if (inventoryIndex < 0 || inventoryIndex > 35) continue;
            if (isStorageCandidate(slot.getStack())) return true;
        }

        return false;
    }

    private boolean hasInventoryStorageCandidate() {
        for (int inventoryIndex = SlotUtils.HOTBAR_START; inventoryIndex <= SlotUtils.MAIN_END; inventoryIndex++) {
            ItemStack stack = mc.player.getInventory().getStack(inventoryIndex);
            if (stack.isEmpty()) continue;

            if (isStorageCandidate(stack)) return true;
        }

        return false;
    }

    private boolean hasInventoryDepositCandidate() {
        return hasInventoryStorageCandidate() || hasInventoryFarmCandidate() || hasPrioritySeedOverflowCandidate();
    }

    private boolean hasInventoryFarmCandidate() {
        for (int inventoryIndex = SlotUtils.HOTBAR_START; inventoryIndex <= SlotUtils.MAIN_END; inventoryIndex++) {
            ItemStack stack = mc.player.getInventory().getStack(inventoryIndex);
            if (isFarmStorageItem(stack) && !isActivePlantingItem(stack.getItem())) return true;
        }

        return false;
    }

    private boolean hasPrioritySeedOverflowCandidate() {
        for (int inventoryIndex = SlotUtils.HOTBAR_START; inventoryIndex <= SlotUtils.MAIN_END; inventoryIndex++) {
            ItemStack stack = mc.player.getInventory().getStack(inventoryIndex);
            if (isFarmStorageItem(stack) && isWithdrawingAllPrioritySeed(stack.getItem())) return true;
        }

        return false;
    }

    private static boolean isStorageCandidate(ItemStack stack) {
        return !stack.isEmpty()
            && !stack.isDamageable()
            && !isFarmStorageItem(stack);
    }

    private boolean canStorageAccept(ScreenHandler handler, ItemStack stack) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.canInsert(stack)) continue;
            if (!slot.hasStack()) return true;

            ItemStack stored = slot.getStack();
            if (ItemStack.areItemsAndComponentsEqual(stored, stack)
                && stored.getCount() < Math.min(stored.getMaxCount(), slot.getMaxItemCount(stack))) return true;
        }

        return false;
    }

    private static boolean isFarmStorageItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // The custom summer seed uses a vanilla seed item underneath its server
        // name. It is a reward, not a planting seed, so it must go to the chest.
        return isSupportedCropChoice(stack.getItem()) && !isServerRewardItem(stack);
    }

    private static boolean isServerRewardItem(ItemStack stack) {
        if (stack.isEmpty()) return false;

        String name = Normalizer.normalize(stack.getName().getString(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        if (name.contains("hat giong mua he") || name.contains("hat mua he") || name.contains("summer seed")
            || name.contains("bo ra dat vang") || name.contains("golden straw bale")
            || name.contains("dong ho sinh hoc") || name.contains("biological clock")
            || name.contains("nguyen to 991") || name.contains("element 991")) return true;

        // Some servers keep the vanilla seed id but add only a custom name or
        // lore. Such a stack is a reward as well, and must not be dropped as a
        // normal planting seed.
        return isSupportedCropChoice(stack.getItem())
            && (stack.contains(DataComponentTypes.CUSTOM_NAME)
            || stack.contains(DataComponentTypes.ITEM_NAME)
            || stack.contains(DataComponentTypes.LORE));
    }

    private void moveToStorage(BlockPos chestPos) {
        if (movementTarget != null && movementTarget.equals(chestPos)) {
            if (directMoving) {
                if (usesDirectMovement()) updateDirectMovement();
                else stopDirectMovement();
                if (directMoving) return;
            } else if (pathingByModule && PathManagers.get().isPathing()) {
                return;
            }
        }

        if (moveScanTimer++ < moveScanDelay.get()) return;
        moveScanTimer = 0;

        if (movementEngine.get() == MovementEngine.Baritone && PathManagers.get() instanceof NopPathManager) {
            if (!warnedNoPathManager) {
                warning("Baritone movement was selected, but Baritone is unavailable. Storage cannot be reached.");
                warnedNoPathManager = true;
            }
            return;
        }

        stopPathing();
        movementTarget = chestPos.toImmutable();

        if (usesDirectMovement()) {
            directMovementPoint = findStorageApproach(chestPos);
            if (directMovementPoint == null) {
                movementTarget = null;
                warnDepositBlocked("No safe standing position was found beside the storage chest. Auto Harvest is paused.");
                return;
            }
            directMovementPoint = getDirectMovementPoint(directMovementPoint);
            directMoving = true;
            updateDirectMovement();
        } else {
            // A normal get-to-block goal is correct here: any reachable side of
            // the chest is close enough to interact with it.
            PathManagers.get().moveTo(chestPos);
            pathingByModule = true;
        }
    }

    private BlockPos findStorageApproach(BlockPos chestPos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int offset = 1; offset <= 2; offset++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos candidate = chestPos.offset(direction, offset);
                BlockState feet = mc.world.getBlockState(candidate);
                BlockState head = mc.world.getBlockState(candidate.up());
                BlockState floor = mc.world.getBlockState(candidate.down());
                if (!feet.isReplaceable() || !head.isReplaceable() || floor.isReplaceable()) continue;

                double distance = candidate.getSquaredDistance(mc.player.getBlockPos());
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }

        return best;
    }

    private boolean isWithinStorageRange(BlockPos pos) {
        double interactionRange = Math.min(4.25, mc.player.getBlockInteractionRange());
        return Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getEyePos()) <= interactionRange * interactionRange;
    }

    private static boolean isStorageBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
    }

    private void warnDepositBlocked(String message) {
        if (warnedDepositBlocked) return;
        warning(message);
        warnedDepositBlocked = true;
    }

    private void finishDepositing() {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.currentScreen.close();
        stopPathing();
        depositingInventory = false;
        warnedDepositBlocked = false;
        depositTimer = 0;
        chestOpenTimer = 0;
        islandStorageWaitTicks = 0;
        flyLandingPoint = null;
        flyLandingUnavailable = false;
        moveScanTimer = moveScanDelay.get();
    }

    private static boolean isMatureCrop(BlockState state, Item cropChoice) {
        if (cropChoice == null) return false;

        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return getCropChoice(block) == cropChoice && crop.isMature(state);
        }

        if (cropChoice == Items.PUMPKIN_SEEDS) return block == Blocks.PUMPKIN;
        if (cropChoice == Items.MELON_SEEDS) return block == Blocks.MELON;
        return false;
    }

    private static Item getCropChoice(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return Items.AIR;
    }

    private static boolean isCropForChoice(BlockState state, Item cropChoice) {
        Block block = state.getBlock();
        if (cropChoice == Items.PUMPKIN_SEEDS) {
            return block == Blocks.PUMPKIN_STEM || block == Blocks.ATTACHED_PUMPKIN_STEM || block == Blocks.PUMPKIN;
        }
        if (cropChoice == Items.MELON_SEEDS) {
            return block == Blocks.MELON_STEM || block == Blocks.ATTACHED_MELON_STEM || block == Blocks.MELON;
        }
        return getCropChoice(block) == cropChoice;
    }

    private static boolean isSupportedCropChoice(Item item) {
        return item == Items.WHEAT_SEEDS
            || item == Items.CARROT
            || item == Items.POTATO
            || item == Items.BEETROOT_SEEDS
            || item == Items.PUMPKIN_SEEDS
            || item == Items.MELON_SEEDS;
    }

    public enum HarvestMode {
        Nuker,
        Legit
    }

    public enum MovementEngine {
        Auto,
        Baritone,
        Direct,
        Fly
    }

    public enum FarmLayout {
        TargetSearch,
        Irrigated11x11
    }

    private static final class PlantedCropLock {
        private final Item crop;
        private int ticksRemaining = NEWLY_PLANTED_LOCK_TICKS;
        private boolean sawYoungState;

        private PlantedCropLock(Item crop) {
            this.crop = crop;
        }
    }
}
