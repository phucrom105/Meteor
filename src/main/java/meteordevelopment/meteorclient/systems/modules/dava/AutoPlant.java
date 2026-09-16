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
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
    private static final int PENDING_TICKS = 10;

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
        .description("Uses the active path manager to walk to farmland that needs planting.")
        .defaultValue(true)
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

    private Item activeItem;
    private boolean warehouseManaged;
    private boolean waitingForWithdraw;
    private boolean returningExtras;
    private boolean pathingByModule;
    private boolean warnedNoPathManager;
    private int movedFromSlot;
    private int movedHotbarSlot;
    private int placeTimer;
    private int withdrawCooldown;
    private int withdrawWaitTimer;
    private int finishTimer;
    private int dropTimer;
    private int moveScanTimer;
    private BlockPos movementTarget;

    public AutoPlant() {
        super(Categories.Dava, "auto-plant", "Plants selected crops, restores harvested crop types, and manages planting items with /kho.");
    }

    @Override
    public void onActivate() {
        targets.clear();
        rememberedCrops.clear();
        pendingPositions.clear();
        activeItem = null;
        warehouseManaged = false;
        waitingForWithdraw = false;
        returningExtras = false;
        pathingByModule = false;
        warnedNoPathManager = false;
        movedFromSlot = -1;
        movedHotbarSlot = -1;
        placeTimer = 0;
        withdrawCooldown = 0;
        withdrawWaitTimer = 0;
        finishTimer = 0;
        dropTimer = 0;
        moveScanTimer = 0;
        movementTarget = null;
    }

    @Override
    public void onDeactivate() {
        restoreHotbarSlot();
        stopPathing();
        targets.clear();
        rememberedCrops.clear();
        pendingPositions.clear();
        activeItem = null;
        warehouseManaged = false;
        waitingForWithdraw = false;
        returningExtras = false;
        movementTarget = null;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        Item newCrop = getSeedForBlock(event.newState.getBlock());
        if (newCrop != null) {
            rememberedCrops.put(event.pos.toImmutable(), newCrop);
            pendingPositions.remove(event.pos);
            return;
        }

        Item oldCrop = getSeedForBlock(event.oldState.getBlock());
        if (oldCrop != null) rememberedCrops.put(event.pos.toImmutable(), oldCrop);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.player == null || mc.world == null) return;

        tickTimers();
        updatePendingPositions();
        pruneRememberedCrops();

        if (mc.currentScreen != null) {
            stopPathing();
            targets.clear();
            return;
        }

        if (crops.get().isEmpty()) {
            stopPathing();
            targets.clear();
            if (activeItem != null) finishActiveItem(true);
            return;
        }

        if (returningExtras) {
            stopPathing();
            collectTargets(activeItem);
            if (!targets.isEmpty()) {
                returningExtras = false;
                finishTimer = 0;
            } else {
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

        stopPathing();

        if (!crops.get().contains(activeItem)) {
            finishActiveItem(true);
            return;
        }

        if (targets.isEmpty()) {
            if (++finishTimer >= finishDelay.get()) finishActiveItem(true);
            return;
        }

        finishTimer = 0;

        FindItemResult available = findUsablePlantingItem(activeItem);
        if (!available.found()) {
            restoreHotbarSlot();
            tryWithdraw();
            return;
        }

        waitingForWithdraw = false;
        withdrawWaitTimer = 0;

        if (placeTimer++ < placeDelay.get()) return;
        placeTimer = 0;

        int seedSlot = ensureSeedInHotbar();
        if (seedSlot == -1) return;

        int planted = 0;
        for (PlantTarget target : targets) {
            if (planted >= plantsPerTick.get()) break;
            if (pendingPositions.containsKey(target.pos())) continue;

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
        return activeItem != null || returningExtras || waitingForWithdraw || movementTarget != null || !targets.isEmpty();
    }

    private void handleMovement() {
        if (!autoMove.get()) {
            stopPathing();
            return;
        }

        if (movementTarget != null && isWithinActionRange(movementTarget)) {
            stopPathing();
            return;
        }

        if (moveScanTimer++ < moveScanDelay.get()) return;
        moveScanTimer = 0;

        BlockPos target = findNearestFarmingTarget();
        if (target == null || isWithinActionRange(target)) {
            stopPathing();
            return;
        }

        if (PathManagers.get() instanceof NopPathManager) {
            if (!warnedNoPathManager) {
                warning("Auto Move requires Baritone or another path manager. Local planting will continue.");
                warnedNoPathManager = true;
            }
            return;
        }

        BlockPos movementPoint = getMovementPoint(target);
        BlockState movementState = mc.world.getBlockState(movementPoint);
        BlockPos pathTarget = movementState.isAir() ? movementPoint.down() : movementPoint;
        boolean targetChanged = movementTarget == null || movementTarget.getSquaredDistance(target) > 4;

        if (targetChanged || !PathManagers.get().isPathing()) {
            if (pathingByModule) PathManagers.get().stop();
            PathManagers.get().moveTo(pathTarget);
            pathingByModule = true;
            movementTarget = target;
        }
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

                    boolean plantTarget = state.isAir()
                        && mc.world.getBlockState(pos.down()).getBlock() == Blocks.FARMLAND
                        && getItemForEmptyFarmland(pos) != null;
                    if (!plantTarget) continue;

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

    private void stopPathing() {
        if (pathingByModule) PathManagers.get().stop();
        pathingByModule = false;
        movementTarget = null;
        moveScanTimer = 0;
    }

    private void collectTargets(Item filterItem) {
        targets.clear();

        BlockPos playerPos = mc.player.getBlockPos();
        int radius = (int) Math.ceil(range.get());
        double rangeSquared = range.get() * range.get();

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
                    if (pendingPositions.containsKey(pos)) continue;

                    Item item = getItemForEmptyFarmland(pos);
                    if (item == null || (filterItem != null && item != filterItem)) continue;

                    targets.add(new PlantTarget(pos, item));
                }
            }
        }

        targets.sort(Comparator.comparingDouble(target -> Vec3d.ofCenter(target.pos()).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
    }

    private Item getItemForEmptyFarmland(BlockPos pos) {
        Item remembered = rememberedCrops.get(pos);
        if (mode.get() != Mode.EmptySoil && remembered != null && crops.get().contains(remembered)) return remembered;
        if (mode.get() == Mode.Replant) return null;

        return crops.get().getFirst();
    }

    private void plant(PlantTarget target, int seedSlot) {
        BlockPos soilPos = target.pos().down();
        Vec3d hitPos = Vec3d.ofCenter(soilPos).add(0, 0.5, 0);
        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, soilPos, false);

        pendingPositions.put(target.pos(), PENDING_TICKS);
        rememberedCrops.put(target.pos(), target.item());

        Runnable action = () -> {
            if (mc.world == null || !mc.world.getBlockState(target.pos()).isAir()) return;
            Hand hand = seedSlot == SlotUtils.OFFHAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
            if (hand == Hand.MAIN_HAND && !InvUtils.swap(seedSlot, true)) return;

            BlockUtils.interact(hitResult, hand, swing.get());
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
        ChatUtils.sendPlayerMsg("/kho withdraw " + itemName, false);
        info("Withdrawing " + itemName + " from /kho.");

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
        warehouseManaged = false;
        waitingForWithdraw = false;
        returningExtras = false;
        movedFromSlot = -1;
        movedHotbarSlot = -1;
        placeTimer = 0;
        withdrawWaitTimer = 0;
        finishTimer = 0;
        dropTimer = 0;
        targets.clear();
    }

    private void tickTimers() {
        if (withdrawCooldown > 0) withdrawCooldown--;
        if (withdrawWaitTimer > 0) withdrawWaitTimer--;
    }

    private void updatePendingPositions() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator = pendingPositions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (!mc.world.getBlockState(entry.getKey()).isAir() || entry.getValue() <= 1) iterator.remove();
            else entry.setValue(entry.getValue() - 1);
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

    public enum Mode {
        EmptySoil,
        Replant,
        Both
    }

    public enum FarmLayout {
        TargetSearch,
        Irrigated11x11
    }

    private record PlantTarget(BlockPos pos, Item item) {}
}
