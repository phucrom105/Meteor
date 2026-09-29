/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.pathing.NopPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
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

public class AutoPlant extends Module {
    private static final int TICKS_PER_SECOND = 20;
    private static final int PENDING_TICKS = 40;
    private static final int PLANT_PROTECTION_TICKS = 100;
    private static final int MOVEMENT_SETTLE_TICKS = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWarehouse = settings.createGroup("Warehouse");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Where crops should be planted.")
        .defaultValue(Mode.Both)
        .build()
    );

    private final Setting<List<Item>> crops = sgGeneral.add(new ItemListSetting.Builder()
        .name("crops")
        .description("The crops that can be planted. Replanted crops keep their previous type.")
        .defaultValue(
            Items.WHEAT_SEEDS,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.MELON_SEEDS
        )
        .filter(AutoPlant::isSupportedSeed)
        .build()
    );

    private final Setting<Boolean> autoMove = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-move")
        .description("Walks to farmland that needs planting when it is outside interaction range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<MovementEngine> movementEngine = sgGeneral.add(new EnumSetting.Builder<MovementEngine>()
        .name("movement-engine")
        .description("Auto prefers Baritone, Direct walks normally, and Fly uses server-granted flight.")
        .defaultValue(MovementEngine.Auto)
        .visible(autoMove::get)
        .build()
    );

    private final Setting<Boolean> directSprint = sgGeneral.add(new BoolSetting.Builder()
        .name("direct-sprint")
        .description("Uses faster horizontal movement with the Direct or Fly engine.")
        .defaultValue(true)
        .visible(() -> autoMove.get() && movementEngine.get() != MovementEngine.Baritone)
        .build()
    );

    private final Setting<Boolean> directAutoJump = sgGeneral.add(new BoolSetting.Builder()
        .name("direct-auto-jump")
        .description("Jumps when direct movement meets a solid obstacle.")
        .defaultValue(true)
        .visible(() -> autoMove.get() && movementEngine.get() != MovementEngine.Baritone && movementEngine.get() != MovementEngine.Fly)
        .build()
    );

    private final Setting<Integer> flyHeight = sgGeneral.add(new IntSetting.Builder()
        .name("fly-height")
        .description("Blocks above crop level used while flying. Two keeps an 11x11 work area within interaction range.")
        .defaultValue(2)
        .range(1, 2)
        .sliderRange(1, 2)
        .visible(() -> autoMove.get() && movementEngine.get() == MovementEngine.Fly)
        .build()
    );

    private final Setting<FarmLayout> farmLayout = sgGeneral.add(new EnumSetting.Builder<FarmLayout>()
        .name("farm-layout")
        .description("Movement layout. Irrigated 11x11 uses waterlogged stone-brick stairs as the center of each plot.")
        .defaultValue(FarmLayout.Irrigated11x11)
        .visible(autoMove::get)
        .build()
    );

    private final Setting<Integer> searchRange = sgGeneral.add(new IntSetting.Builder()
        .name("search-range")
        .description("Horizontal range used to find the next farming position.")
        .defaultValue(32)
        .range(8, 64)
        .sliderRange(8, 64)
        .visible(autoMove::get)
        .build()
    );

    private final Setting<Integer> moveScanDelay = sgGeneral.add(new IntSetting.Builder()
        .name("move-scan-delay")
        .description("Ticks between searches for the next distant farming position.")
        .defaultValue(20)
        .range(5, 100)
        .sliderRange(5, 100)
        .visible(autoMove::get)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far around you to search for farmland.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between planting actions.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> plantsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("plants-per-tick")
        .description("Maximum planting attempts per action tick.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hotbar slot temporarily used for seeds withdrawn into the inventory.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side toward the farmland before planting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings the hand when planting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoWithdraw = sgWarehouse.add(new BoolSetting.Builder()
        .name("auto-withdraw")
        .description("Uses /kho withdraw ITEM when the required crop is missing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> withdrawAmount = sgWarehouse.add(new IntSetting.Builder()
        .name("withdraw-amount")
        .description("Amount of the priority seed to withdraw. Default is 320 (5 stacks); set to 0 to use /kho withdraw ITEM and take all.")
        .defaultValue(320)
        .range(0, 64000)
        .sliderRange(0, 3200)
        .visible(autoWithdraw::get)
        .build()
    );

    private final Setting<Integer> withdrawRetryDelay = sgWarehouse.add(new IntSetting.Builder()
        .name("withdraw-retry-delay")
        .description("Seconds to wait before retrying a warehouse withdrawal.")
        .defaultValue(5)
        .range(5, 30)
        .sliderRange(5, 30)
        .visible(autoWithdraw::get)
        .build()
    );

    private final Setting<Boolean> returnExtras = sgWarehouse.add(new BoolSetting.Builder()
        .name("return-extras")
        .description("Drops leftover crops withdrawn from the warehouse after planting finishes.")
        .defaultValue(true)
        .visible(autoWithdraw::get)
        .build()
    );

    private final Setting<Integer> finishDelay = sgWarehouse.add(new IntSetting.Builder()
        .name("finish-delay")
        .description("Ticks without a planting target before leftover withdrawn crops are dropped.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 100)
        .visible(() -> autoWithdraw.get() && returnExtras.get())
        .build()
    );

    private final Setting<Integer> dropDelay = sgWarehouse.add(new IntSetting.Builder()
        .name("drop-delay")
        .description("Ticks between dropping crop stacks onto the ground.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
        .visible(() -> autoWithdraw.get() && returnExtras.get())
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the farmland positions waiting to be planted.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How target positions are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The target fill color.")
        .defaultValue(new SettingColor(60, 180, 75, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The target line color.")
        .defaultValue(new SettingColor(60, 180, 75, 255))
        .visible(render::get)
        .build()
    );

    private final List<PlantTarget> targets = new ArrayList<>();
    private final Map<BlockPos, Item> rememberedCrops = new HashMap<>();
    private final Map<BlockPos, Integer> pendingPositions = new HashMap<>();
    private final Map<BlockPos, Integer> plantProtection = new HashMap<>();
    private final Map<BlockPos, Boolean> plantedPositions = new HashMap<>();

    private Item activeItem;
    private boolean warehouseManaged;
    private boolean waitingForWithdraw;
    private boolean returningExtras;
    private boolean pathingByModule;
    private boolean warnedNoPathManager;
    private int movedFromSlot;
    private int movedHotbarSlot;
    private int placeTimer;
    private int movementSettleTicks;
    private int withdrawCooldown;
    private int withdrawWaitTimer;
    private int finishTimer;
    private int dropTimer;
    private int moveScanTimer;
    private int lootPickupWaitTicks;
    private int lootTravelTicks;
    private int ignoredLootId;
    private int ignoredLootTicks;
    private BlockPos movementTarget;
    private BlockPos directMovementPoint;
    private BlockPos plantingReadyTarget;
    private BlockPos lootMovementPoint;
    private ItemEntity lootTarget;
    private boolean directMoving;

    public AutoPlant() {
        super(Categories.Dava, "auto-plant", "Plants the current harvest crop and collects dropped crops, pumpkins, and melons.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton withdrawButton = list.add(theme.button(withdrawButtonText())).expandX().widget();
        withdrawButton.action = () -> {
            autoWithdraw.set(!autoWithdraw.get());
            withdrawButton.set(withdrawButtonText());
        };
        return list;
    }

    private String withdrawButtonText() {
        return autoWithdraw.get() ? "Disable /kho withdraw" : "Enable /kho withdraw";
    }

    @Override
    public void onActivate() {
        targets.clear();
        rememberedCrops.clear();
        pendingPositions.clear();
        plantProtection.clear();
        plantedPositions.clear();
        activeItem = null;
        warehouseManaged = false;
        waitingForWithdraw = false;
        returningExtras = false;
        pathingByModule = false;
        warnedNoPathManager = false;
        movedFromSlot = -1;
        movedHotbarSlot = -1;
        placeTimer = 0;
        movementSettleTicks = 0;
        withdrawCooldown = 0;
        withdrawWaitTimer = 0;
        finishTimer = 0;
        dropTimer = 0;
        moveScanTimer = moveScanDelay.get();
        lootPickupWaitTicks = 0;
        lootTravelTicks = 0;
        ignoredLootId = -1;
        ignoredLootTicks = 0;
        movementTarget = null;
        directMovementPoint = null;
        plantingReadyTarget = null;
        lootMovementPoint = null;
        lootTarget = null;
        directMoving = false;
    }

    @Override
    public void onDeactivate() {
        restoreHotbarSlot();
        stopPathing();
        targets.clear();
        rememberedCrops.clear();
        pendingPositions.clear();
        plantProtection.clear();
        plantedPositions.clear();
        activeItem = null;
        warehouseManaged = false;
        waitingForWithdraw = false;
        returningExtras = false;
        movementTarget = null;
        directMovementPoint = null;
        plantingReadyTarget = null;
        lootMovementPoint = null;
        lootTarget = null;
        lootPickupWaitTicks = 0;
        lootTravelTicks = 0;
        ignoredLootId = -1;
        ignoredLootTicks = 0;
        directMoving = false;
        movementSettleTicks = 0;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos.toImmutable();
        Item newCrop = getSeedForBlock(event.newState.getBlock());
        if (newCrop != null) {
            rememberedCrops.put(pos, newCrop);
            if (plantedPositions.containsKey(pos) && !isMatureCrop(event.newState)) plantedPositions.put(pos, true);
            return;
        }

        Item oldCrop = getSeedForBlock(event.oldState.getBlock());
        if (oldCrop != null) {
            rememberedCrops.put(pos, oldCrop);
            if (event.newState.isAir()) {
                plantProtection.remove(pos);
                plantedPositions.remove(pos);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.player == null || mc.world == null) return;

        tickTimers();
        if (movementSettleTicks > 0) movementSettleTicks--;
        updatePendingPositions();
        updatePlantProtection();
        updatePlantedPositions();
        pruneRememberedCrops();

        if (plantingReadyTarget != null && !isPlantTarget(plantingReadyTarget)) plantingReadyTarget = null;

        if (mc.currentScreen != null) {
            stopPathing();
            targets.clear();
            plantingReadyTarget = null;
            return;
        }

        if (crops.get().isEmpty()) {
            stopPathing();
            targets.clear();
            plantingReadyTarget = null;
            if (activeItem != null) finishActiveItem(true);
            return;
        }

        AutoHarvest autoHarvest = Modules.get().get(AutoHarvest.class);
        if (autoHarvest != null && autoHarvest.isActive()) {
            Item priority = autoHarvest.getActiveCrop();
            if (priority == null || !crops.get().contains(priority)) {
                stopPathing();
                targets.clear();
                clearLootTarget();
                plantingReadyTarget = null;
                if (activeItem != null) {
                    restoreHotbarSlot();
                    resetActiveItem();
                }
                return;
            }

            if (activeItem != priority) {
                stopPathing();
                restoreHotbarSlot();
                resetActiveItem();
                activeItem = priority;
            }
        }

        if (autoHarvest != null && autoHarvest.isActive() && autoHarvest.isDepositingInventory()) {
            clearLootTarget();
            stopPathing();
            targets.clear();
            plantingReadyTarget = null;
            return;
        }

        if (!autoWithdraw.get()) {
            waitingForWithdraw = false;
            withdrawWaitTimer = 0;
            returningExtras = false;
            warehouseManaged = false;
        }

        // Fruit drops need pickup even while seeds are available or /kho supplies them.
        boolean harvestingFruit = autoHarvest != null && autoHarvest.isActive()
            && (activeItem == Items.PUMPKIN_SEEDS || activeItem == Items.MELON_SEEDS);
        if (activeItem != null && autoMove.get()
            && (autoHarvest == null || !autoHarvest.isActive() || !autoHarvest.isWaitingForServerAction())
            && (harvestingFruit || !autoWithdraw.get()
                && (!findUsablePlantingItem(activeItem).found() || autoHarvest == null || !autoHarvest.isActive()
                    || !autoHarvest.hasHarvestTargetInSearchRange()))
            && collectDroppedFarmItem(autoHarvest)) return;

        if (!autoWithdraw.get()) {
            if (activeItem != null && !findUsablePlantingItem(activeItem).found()) {
                clearLootTarget();
                stopPathing();
                targets.clear();
                plantingReadyTarget = null;
                return;
            }
        }
        clearLootTarget();

        // Both modules share the path manager and movement keys. Let AutoHarvest
        // finish its route before AutoPlant starts another one.
        if (autoHarvest != null && autoHarvest.isActive() && autoHarvest.isBusyForAutoPlant()) {
            // Release Direct/Fly keys as well as Baritone. Keeping movementTarget
            // here makes AutoHarvest see us as busy and both modules yield forever.
            stopPathing();
            targets.clear();
            plantingReadyTarget = null;
            return;
        }

        if (returningExtras && autoHarvest != null && autoHarvest.isActive()
            && autoHarvest.hasHarvestTargetInSearchRange()) {
            // Resume farming; surplus seeds can be returned after this crop pass.
            returningExtras = false;
            stopPathing();
        }

        if (returningExtras) {
            collectTargets(activeItem);
            if (!targets.isEmpty()) {
                returningExtras = false;
                finishTimer = 0;
                stopPathing();
            } else {
                if (handleMovement()) return;
                stopPathing();
                returnOneStack();
                return;
            }
        }

        collectTargets(activeItem);

        if (activeItem == null && !targets.isEmpty()) {
            activeItem = targets.getFirst().item();
            collectTargets(activeItem);
        }

        if (activeItem == null) {
            handleMovement();
            return;
        }

        if (!crops.get().contains(activeItem)) {
            stopPathing();
            finishActiveItem(true);
            return;
        }

        if (targets.isEmpty()) {
            if (handleMovement()) {
                // Keep the finish timer while a search is merely waiting for its
                // next scan. A real movement target resets it below, so an empty
                // farm can still advance to the next crop.
                if (movementTarget != null) finishTimer = 0;
                return;
            }

            if (++finishTimer >= finishDelay.get()) finishActiveItem(true);
            return;
        }

        // Every target collected here is inside the safe interaction radius. Any
        // farther farmland is handled by handleMovement() before it reaches this
        // planting section.
        stopPathing();
        finishTimer = 0;

        FindItemResult available = findUsablePlantingItem(activeItem);
        if (!available.found()) {
            restoreHotbarSlot();
            tryWithdraw();
            // Waiting for /kho does not own the movement controller.
            targets.clear();
            plantingReadyTarget = null;
            return;
        }

        waitingForWithdraw = false;
        withdrawWaitTimer = 0;

        if (movementSettleTicks > 0) return;
        if (placeTimer++ < placeDelay.get()) return;
        placeTimer = 0;

        int seedSlot = ensureSeedInHotbar();
        if (seedSlot == -1) return;

        int planted = 0;
        int limit = plantsPerTick.get();
        for (PlantTarget target : targets) {
            if (planted >= limit) break;
            if (isPlantPending(target.pos())) continue;

            plant(target, seedSlot);
            planted++;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (PlantTarget target : targets) {
            event.renderer.box(target.pos(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    public boolean isWorking() {
        // Auto Harvest only needs to yield while planting owns the player or is
        // actively placing a batch. A selected crop and its server-update grace
        // period can continue in parallel; per-position suppression protects the
        // newly planted block from being harvested too early.
        return returningExtras || lootTarget != null || movementTarget != null || plantingReadyTarget != null || !targets.isEmpty();
    }

    public boolean isWarehouseWithdrawEnabled() {
        return autoWithdraw.get();
    }

    private boolean isCoordinatedSafeMode() {
        AutoHarvest autoHarvest = Modules.get().get(AutoHarvest.class);
        return autoHarvest != null && autoHarvest.isActive() && autoHarvest.isCoordinatedSafeMode();
    }

    public boolean isPlantPending(BlockPos pos) {
        return pendingPositions.containsKey(pos) || plantProtection.containsKey(pos);
    }

    /** Returns true when this module is currently preserving this planting item for the active crop pass. */
    public boolean isPlantingItem(Item item) {
        return isActive() && activeItem == item;
    }

    /**
     * Returns true when an all-items warehouse withdrawal is currently feeding
     * this crop. Auto Harvest may use the island-storage drop path to relieve
     * an overflow in this one case; the priority seed is never moved to a
     * chest.
     */
    public boolean isWithdrawingAllFor(Item item) {
        return isActive() && warehouseManaged && autoWithdraw.get() && withdrawAmount.get() == 0 && activeItem == item;
    }

    public void rememberHarvestedCrop(BlockPos pos, Item crop) {
        if (isSupportedSeed(crop)) rememberedCrops.put(pos.toImmutable(), crop);
    }

    public boolean isPlantSuppressed(BlockPos pos) {
        if (plantProtection.containsKey(pos)) return true;

        Boolean sawYoungCrop = plantedPositions.get(pos);
        if (sawYoungCrop == null) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) {
            plantedPositions.remove(pos);
            return false;
        }

        // Do not harvest a crop that was planted while it still has no confirmed
        // young state. This prevents servers with delayed or instant-looking
        // placement updates from entering a harvest/plant loop.
        if (!sawYoungCrop) return true;
        return !isMatureCrop(state);
    }

    private boolean collectDroppedFarmItem(AutoHarvest autoHarvest) {
        ItemEntity item = lootTarget;
        if (!isLootCandidate(item)) {
            clearLootTarget();
            item = null;
            double nearestDistance = Double.MAX_VALUE;

            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof ItemEntity candidate) || !isLootCandidate(candidate)) continue;
                double distance = candidate.squaredDistanceTo(mc.player);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    item = candidate;
                }
            }
        }

        if (item == null) return false;

        if (lootTarget != item) {
            lootPickupWaitTicks = 0;
            lootTravelTicks = 0;
        }

        // AutoHarvest may own Baritone or the movement keys from the previous
        // tick. Release them before requesting the dropped item's position.
        if (autoHarvest != null && autoHarvest.isActive() && autoHarvest.isControllingMovement()) {
            autoHarvest.yieldMovementToAutoPlant();
        }
        lootTarget = item;

        if (isWithinPickupRange(item)) {
            if (lootMovementPoint != null) stopPathing();
            lootMovementPoint = null;
            lootTravelTicks = 0;
            if (++lootPickupWaitTicks > 60) {
                ignoredLootId = item.getId();
                ignoredLootTicks = 100;
                clearLootTarget();
                return false;
            }
            return true;
        }
        lootPickupWaitTicks = 0;
        if (++lootTravelTicks > 400) {
            ignoredLootId = item.getId();
            ignoredLootTicks = 100;
            clearLootTarget();
            return false;
        }

        BlockPos itemPos = item.getBlockPos();
        if (!itemPos.equals(lootMovementPoint) || (!directMoving && (!pathingByModule || !PathManagers.get().isPathing()))) {
            stopPathing();
            lootMovementPoint = itemPos.toImmutable();
            if (usesDirectMovement()) {
                // Loot must be reached at ground level, including in Fly mode.
                directMovementPoint = lootMovementPoint;
                directMoving = true;
                updateDirectMovement();
            } else {
                PathManagers.get().moveToExact(lootMovementPoint);
                pathingByModule = true;
            }
        } else if (directMoving) {
            updateDirectMovement();
        }

        return true;
    }

    private boolean isLootCandidate(ItemEntity item) {
        if (item == null || !item.isAlive() || (item.getId() == ignoredLootId && ignoredLootTicks > 0)
            || !(isUsablePlantingStack(item.getStack(), activeItem) || isHarvestedFruit(item.getStack()))) return false;

        Vec3d distance = new Vec3d(
            item.getX() - mc.player.getX(),
            item.getY() - mc.player.getY(),
            item.getZ() - mc.player.getZ()
        );
        int horizontalRange = searchRange.get();
        return distance.x * distance.x + distance.z * distance.z <= horizontalRange * horizontalRange
            && Math.abs(distance.y) <= 4
            && hasRoomFor(item.getStack());
    }

    private boolean isHarvestedFruit(ItemStack stack) {
        return activeItem == Items.PUMPKIN_SEEDS && stack.isOf(Items.PUMPKIN)
            || activeItem == Items.MELON_SEEDS && (stack.isOf(Items.MELON_SLICE) || stack.isOf(Items.MELON));
    }

    private boolean hasRoomFor(ItemStack dropped) {
        for (int slot = SlotUtils.HOTBAR_START; slot <= SlotUtils.MAIN_END; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(stack, dropped) && stack.getCount() < stack.getMaxCount()) return true;
        }
        return false;
    }

    private boolean isWithinPickupRange(ItemEntity item) {
        Vec3d distance = new Vec3d(
            item.getX() - mc.player.getX(),
            item.getY() - mc.player.getY(),
            item.getZ() - mc.player.getZ()
        );
        return distance.x * distance.x + distance.z * distance.z <= 0.64 && Math.abs(distance.y) <= 0.9;
    }

    private void clearLootTarget() {
        if (lootMovementPoint != null) stopPathing();
        lootMovementPoint = null;
        lootTarget = null;
        lootPickupWaitTicks = 0;
        lootTravelTicks = 0;
    }

    private boolean handleMovement() {
        AutoHarvest autoHarvest = Modules.get().get(AutoHarvest.class);
        // A distant mature crop takes route priority. Otherwise AutoPlant can
        // hold Baritone indefinitely while AutoHarvest waits for the same path.
        if (autoHarvest != null && autoHarvest.isActive()
            && (autoHarvest.isWaitingForServerAction() || autoHarvest.hasHarvestTargetInSearchRange())) {
            stopPathing();
            plantingReadyTarget = null;
            return true;
        }

        if (!autoMove.get()) {
            stopPathing();
            return false;
        }

        if (activeItem != null && !findUsablePlantingItem(activeItem).found()) {
            stopPathing();
            plantingReadyTarget = null;
            tryWithdraw();
            return true;
        }

        if (movementTarget != null) {
            if (!isPlantTarget(movementTarget)) {
                plantingReadyTarget = null;
                stopPathing();
            } else if (isWithinActionRange(movementTarget)) {
                plantingReadyTarget = movementTarget.toImmutable();
                stopPathing();
                return true;
            } else if (directMoving) {
                if (usesDirectMovement()) updateDirectMovement();
                else stopDirectMovement();
                if (directMoving) return true;
            } else if (pathingByModule && PathManagers.get().isPathing()) {
                return true;
            }
        }

        if (moveScanTimer++ < moveScanDelay.get()) return true;
        moveScanTimer = 0;

        BlockPos target = findNearestFarmingTarget();
        if (target == null) {
            plantingReadyTarget = null;
            stopPathing();
            return false;
        }

        if (isWithinActionRange(target)) {
            plantingReadyTarget = target.toImmutable();
            stopPathing();
            return true;
        }

        if (movementEngine.get() == MovementEngine.Baritone && PathManagers.get() instanceof NopPathManager) {
            if (!warnedNoPathManager) {
                warning("Baritone movement was selected, but Baritone is unavailable. Select Auto or Direct for built-in walking.");
                warnedNoPathManager = true;
            }
            return false;
        }

        moveToTarget(target);
        return true;
    }

    private void moveToTarget(BlockPos workTarget) {
        BlockPos movementPoint = getMovementPoint(workTarget);
        stopPathing();
        plantingReadyTarget = null;
        movementTarget = workTarget.toImmutable();

        if (usesDirectMovement()) {
            directMovementPoint = getDirectMovementPoint(movementPoint);
            directMoving = true;
            updateDirectMovement();
            return;
        }

        // Use the same exact work point as direct movement. An adjacent-block
        // goal can complete on the wrong side and leave the farmland out of
        // placement range, causing the same goal to be requested forever.
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
        mc.options.forwardKey.setPressed(dx * dx + dz * dz > 0.16);
        mc.options.sprintKey.setPressed(directSprint.get());
        mc.options.jumpKey.setPressed(dy > 0.3);
        mc.options.sneakKey.setPressed(dy < -0.3);
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

        // Four work areas around the irrigation point cover every block in an 11x11 plot,
        // including its corners, while keeping the player away from the center water.
        return new BlockPos(center.getX() + offsetX, workTarget.getY(), center.getZ() + offsetZ);
    }

    private BlockPos findIrrigatedPlotCenter(BlockPos workTarget) {
        int plotRadius = 5;
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        // Prefer the stair itself. This also recognizes a waterlogged stair.
        for (int x = workTarget.getX() - plotRadius - 1; x <= workTarget.getX() + plotRadius + 1; x++) {
            for (int z = workTarget.getZ() - plotRadius - 1; z <= workTarget.getZ() + plotRadius + 1; z++) {
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

    private BlockPos findNearestFarmingTarget() {
        BlockPos playerPos = mc.player.getBlockPos();
        int horizontalRange = searchRange.get();
        int verticalRange = Math.min(4, horizontalRange);
        double bestDistance = Double.MAX_VALUE;
        BlockPos bestTarget = null;

        for (int x = playerPos.getX() - horizontalRange; x <= playerPos.getX() + horizontalRange; x++) {
            for (int z = playerPos.getZ() - horizontalRange; z <= playerPos.getZ() + horizontalRange; z++) {
                if (!mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;

                for (int y = playerPos.getY() - verticalRange; y <= playerPos.getY() + verticalRange; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    Item existingCrop = getSeedForBlock(state.getBlock());
                    if (existingCrop != null) rememberedCrops.put(pos, existingCrop);

                    if (!isPlantTarget(pos)) continue;

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

    private boolean isWithinActionRange(BlockPos pos) {
        double actionRange = Math.min(range.get(), 3.75);
        return Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ())
            <= actionRange * actionRange;
    }

    private boolean isPlantTarget(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isAir() || mc.world.getBlockState(pos.down()).getBlock() != Blocks.FARMLAND) return false;
        if (pendingPositions.containsKey(pos) || plantProtection.containsKey(pos)) return false;

        Item item = getItemForEmptyFarmland(pos);
        return item != null && (activeItem == null || item == activeItem);
    }

    private void stopPathing() {
        if (isCoordinatedSafeMode() && (movementTarget != null || pathingByModule || directMoving)) {
            movementSettleTicks = MOVEMENT_SETTLE_TICKS;
        }
        if (pathingByModule) PathManagers.get().stop();
        stopDirectMovement();
        pathingByModule = false;
        movementTarget = null;
        moveScanTimer = 0;
    }

    private void collectTargets(Item filterItem) {
        targets.clear();

        BlockPos playerPos = mc.player.getBlockPos();
        int radius = (int) Math.ceil(range.get());
        double actionRange = Math.min(range.get(), 3.75);
        double rangeSquared = actionRange * actionRange;

        for (int x = playerPos.getX() - radius; x <= playerPos.getX() + radius; x++) {
            for (int y = playerPos.getY() - radius; y <= playerPos.getY() + radius; y++) {
                for (int z = playerPos.getZ() - radius; z <= playerPos.getZ() + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()) > rangeSquared) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    Item existingCrop = getSeedForBlock(state.getBlock());
                    if (existingCrop != null) {
                        rememberedCrops.put(pos, existingCrop);
                        continue;
                    }

                    if (!state.isAir() || mc.world.getBlockState(pos.down()).getBlock() != Blocks.FARMLAND) continue;
                    if (isPlantPending(pos)) continue;

                    Item item = getItemForEmptyFarmland(pos);
                    if (item == null || (filterItem != null && item != filterItem)) continue;

                    targets.add(new PlantTarget(pos, item));
                }
            }
        }

        targets.sort(Comparator.comparingDouble(target -> Vec3d.ofCenter(target.pos()).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
    }

    private Item getItemForEmptyFarmland(BlockPos pos) {
        AutoHarvest autoHarvest = Modules.get().get(AutoHarvest.class);
        if (autoHarvest != null && autoHarvest.isActive()) {
            Item priority = autoHarvest.getActiveCrop();
            if (priority == null || !crops.get().contains(priority)) return null;

            Item remembered = rememberedCrops.get(pos);
            if (remembered != null && remembered != priority) return null;
            if (mode.get() == Mode.Replant && remembered == null) return null;
            return priority;
        }

        Item remembered = rememberedCrops.get(pos);
        if (mode.get() != Mode.EmptySoil && remembered != null && crops.get().contains(remembered)) return remembered;
        if (mode.get() == Mode.Replant) return null;

        return crops.get().getFirst();
    }

    private void plant(PlantTarget target, int seedSlot) {
        BlockPos soilPos = target.pos().down();
        Vec3d hitPos = Vec3d.ofCenter(soilPos).add(0, 0.5, 0);
        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, soilPos, false);
        BlockPos targetPos = target.pos().toImmutable();

        pendingPositions.put(targetPos, PENDING_TICKS);
        plantProtection.put(targetPos, PLANT_PROTECTION_TICKS);
        rememberedCrops.put(targetPos, target.item());
        plantedPositions.put(targetPos, false);

        AutoHarvest autoHarvest = Modules.get().get(AutoHarvest.class);
        if (autoHarvest != null && autoHarvest.isActive()) autoHarvest.rememberPlantedCrop(targetPos, target.item());

        Runnable action = () -> {
            if (mc.world == null || !isWithinActionRange(targetPos) || !mc.world.getBlockState(targetPos).isAir()) {
                if (targetPos.equals(plantingReadyTarget)) plantingReadyTarget = null;
                return;
            }
            Hand hand = seedSlot == SlotUtils.OFFHAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
            if (hand == Hand.MAIN_HAND && !InvUtils.swap(seedSlot, true)) {
                if (targetPos.equals(plantingReadyTarget)) plantingReadyTarget = null;
                return;
            }

            BlockUtils.interact(hitResult, hand, swing.get());
            if (autoHarvest != null && autoHarvest.isActive()) autoHarvest.markCoordinatedAction();
            if (targetPos.equals(plantingReadyTarget)) plantingReadyTarget = null;
            if (hand == Hand.MAIN_HAND) InvUtils.swapBack();
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), -50, action);
        else action.run();
    }

    private int ensureSeedInHotbar() {
        FindItemResult hotbarItem = InvUtils.findInHotbar(stack -> isUsablePlantingStack(stack, activeItem));
        if (hotbarItem.found()) return hotbarItem.slot();

        FindItemResult inventoryItem = findUsablePlantingItem(activeItem);
        if (!inventoryItem.found()) return -1;

        int targetSlot = hotbarSlot.get() - 1;
        if (movedFromSlot == -1) {
            movedFromSlot = inventoryItem.slot();
            movedHotbarSlot = targetSlot;
        }
        InvUtils.move().from(inventoryItem.slot()).toHotbar(targetSlot);

        return isUsablePlantingStack(mc.player.getInventory().getStack(targetSlot), activeItem) ? targetSlot : -1;
    }

    private void restoreHotbarSlot() {
        if (movedFromSlot == -1 || movedHotbarSlot == -1 || mc.player == null) return;

        InvUtils.move().fromHotbar(movedHotbarSlot).to(movedFromSlot);
        movedFromSlot = -1;
        movedHotbarSlot = -1;
    }

    private void tryWithdraw() {
        if (!autoWithdraw.get() || activeItem == null) return;

        if (waitingForWithdraw) {
            if (withdrawWaitTimer > 0) return;
            waitingForWithdraw = false;
        }

        if (withdrawCooldown > 0) return;

        String itemName = Registries.ITEM.getId(activeItem).getPath().toUpperCase(Locale.ROOT);
        int amount = withdrawAmount.get();
        String command = "/kho withdraw " + itemName + (amount > 0 ? " " + amount : "");
        ChatUtils.sendPlayerMsg(command, false);
        if (amount > 0) info("Withdrawing %s %d from /kho.", itemName, amount);
        else info("Withdrawing all %s from /kho.", itemName);

        warehouseManaged = true;
        waitingForWithdraw = true;
        withdrawWaitTimer = withdrawRetryDelay.get() * TICKS_PER_SECOND;
        withdrawCooldown = withdrawRetryDelay.get() * TICKS_PER_SECOND;
    }

    private void finishActiveItem(boolean returnWarehouseItems) {
        finishTimer = 0;
        waitingForWithdraw = false;
        withdrawWaitTimer = 0;

        if (returnWarehouseItems && warehouseManaged && returnExtras.get() && findUsablePlantingItem(activeItem).found()) {
            returningExtras = true;
            dropTimer = 0;
            return;
        }

        restoreHotbarSlot();
        resetActiveItem();
    }

    private void returnOneStack() {
        if (activeItem == null) {
            returningExtras = false;
            return;
        }

        if (dropTimer++ < dropDelay.get()) return;
        dropTimer = 0;

        for (int slot = SlotUtils.HOTBAR_START; slot <= SlotUtils.MAIN_END; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!isUsablePlantingStack(stack, activeItem)) continue;

            InvUtils.drop().slot(slot);
            return;
        }

        if (isUsablePlantingStack(mc.player.getOffHandStack(), activeItem)) {
            InvUtils.drop().slotOffhand();
            return;
        }

        restoreHotbarSlot();
        info("Returned leftover " + Registries.ITEM.getId(activeItem).getPath().toUpperCase(Locale.ROOT) + " to the ground.");
        resetActiveItem();
    }

    private void resetActiveItem() {
        activeItem = null;
        lootTarget = null;
        lootMovementPoint = null;
        lootPickupWaitTicks = 0;
        lootTravelTicks = 0;
        ignoredLootId = -1;
        ignoredLootTicks = 0;
        warehouseManaged = false;
        waitingForWithdraw = false;
        returningExtras = false;
        movedFromSlot = -1;
        movedHotbarSlot = -1;
        plantingReadyTarget = null;
        placeTimer = 0;
        withdrawWaitTimer = 0;
        finishTimer = 0;
        dropTimer = 0;
        targets.clear();
    }

    private void tickTimers() {
        if (withdrawCooldown > 0) withdrawCooldown--;
        if (withdrawWaitTimer > 0) withdrawWaitTimer--;
        if (ignoredLootTicks > 0) ignoredLootTicks--;
    }

    private void updatePendingPositions() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator = pendingPositions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() <= 1) iterator.remove();
            else entry.setValue(entry.getValue() - 1);
        }
    }

    private void updatePlantProtection() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator = plantProtection.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() <= 1) iterator.remove();
            else entry.setValue(entry.getValue() - 1);
        }
    }

    private void updatePlantedPositions() {
        Iterator<Map.Entry<BlockPos, Boolean>> iterator = plantedPositions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Boolean> entry = iterator.next();
            BlockPos pos = entry.getKey();
            BlockState state = mc.world.getBlockState(pos);

            if (state.isAir()) {
                if (!pendingPositions.containsKey(pos)) iterator.remove();
            } else if (getSeedForBlock(state.getBlock()) != null && !isMatureCrop(state)) {
                entry.setValue(true);
            }
        }
    }

    private void pruneRememberedCrops() {
        BlockPos playerPos = mc.player.getBlockPos();
        double keepRange = (autoMove.get() ? Math.max(range.get(), searchRange.get()) : range.get()) + 16;
        double keepRangeSquared = keepRange * keepRange;

        rememberedCrops.keySet().removeIf(pos -> {
            double dx = pos.getX() - playerPos.getX();
            double dy = pos.getY() - playerPos.getY();
            double dz = pos.getZ() - playerPos.getZ();
            return dx * dx + dy * dy + dz * dz > keepRangeSquared;
        });
    }

    private static boolean isSupportedSeed(Item item) {
        return item == Items.WHEAT_SEEDS
            || item == Items.CARROT
            || item == Items.POTATO
            || item == Items.BEETROOT_SEEDS
            || item == Items.PUMPKIN_SEEDS
            || item == Items.MELON_SEEDS;
    }

    private FindItemResult findUsablePlantingItem(Item item) {
        return InvUtils.find(stack -> isUsablePlantingStack(stack, item));
    }

    private static boolean isUsablePlantingStack(ItemStack stack, Item item) {
        return stack.isOf(item) && !isSummerSeed(stack);
    }

    private static boolean isSummerSeed(ItemStack stack) {
        String name = Normalizer.normalize(stack.getName().getString(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        return name.contains("hat giong mua he") || name.contains("summer seed");
    }

    private static Item getSeedForBlock(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (block == Blocks.PUMPKIN_STEM || block == Blocks.ATTACHED_PUMPKIN_STEM) return Items.PUMPKIN_SEEDS;
        if (block == Blocks.MELON_STEM || block == Blocks.ATTACHED_MELON_STEM) return Items.MELON_SEEDS;
        return null;
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMature(state);
    }

    public enum Mode {
        EmptySoil,
        Replant,
        Both
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

    private record PlantTarget(BlockPos pos, Item item) {}
}
