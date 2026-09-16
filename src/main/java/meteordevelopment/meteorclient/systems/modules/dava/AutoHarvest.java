/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.entity.player.BlockBreakingCooldownEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.NopPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AutoHarvest extends Module {
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
        .defaultValue(1)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> packetMine = sgGeneral.add(new BoolSetting.Builder()
        .name("packet-mine")
        .description("Harvests using start and stop destroy packets.")
        .defaultValue(false)
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
        .name("move-scan-delay")
        .description("Ticks between searches for distant crops.")
        .defaultValue(20)
        .range(5, 100)
        .sliderRange(5, 100)
        .build()
    );

    private final Setting<Boolean> autoDeposit = sgInventory.add(new BoolSetting.Builder()
        .name("auto-deposit")
        .description("Drops harvested items when the main inventory is full so the server can store them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> emptySlotsAfterDeposit = sgInventory.add(new IntSetting.Builder()
        .name("empty-slots-after-deposit")
        .description("Inventory slots to free before harvesting resumes.")
        .defaultValue(3)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(autoDeposit::get)
        .build()
    );

    private final Setting<Integer> dropDelay = sgInventory.add(new IntSetting.Builder()
        .name("drop-delay")
        .description("Ticks between dropped stacks.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
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

    private Item activeCrop;
    private int cropIndex;
    private int emptyScans;
    private int breakTimer;
    private int moveScanTimer;
    private int dropTimer;
    private boolean pathingByModule;
    private boolean depositingInventory;
    private boolean warnedNoPathManager;
    private boolean warnedNoDepositItems;
    private BlockPos movementTarget;

    public AutoHarvest() {
        super(Categories.Dava, "auto-harvest", "Harvests one selected mature crop type at a time without controlling Nuker.");
    }

    @Override
    public void onActivate() {
        targets.clear();
        cropIndex = 0;
        activeCrop = cropOrder.get().isEmpty() ? null : cropOrder.get().getFirst();
        emptyScans = 0;
        breakTimer = 0;
        moveScanTimer = moveScanDelay.get();
        dropTimer = 0;
        pathingByModule = false;
        depositingInventory = false;
        warnedNoPathManager = false;
        warnedNoDepositItems = false;
        movementTarget = null;
        announceActiveCrop();
    }

    @Override
    public void onDeactivate() {
        stopPathing();
        targets.clear();
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

        if (depositingInventory || (autoDeposit.get() && isInventoryFull())) {
            depositingInventory = true;
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

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onBlockBreakingCooldown(BlockBreakingCooldownEvent event) {
        event.cooldown = 0;
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
        double rangeSquared = range.get() * range.get();

        for (int x = playerPos.getX() - radius; x <= playerPos.getX() + radius; x++) {
            for (int y = playerPos.getY() - radius; y <= playerPos.getY() + radius; y++) {
                for (int z = playerPos.getZ() - radius; z <= playerPos.getZ() + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()) > rangeSquared) continue;
                    BlockState state = mc.world.getBlockState(pos);
                    if (isMatureCrop(state, activeCrop) && BlockUtils.canBreak(pos, state)) targets.add(pos);
                }
            }
        }

        targets.sort(Comparator.comparingDouble(pos -> Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
    }

    private void harvestTargets() {
        if (breakTimer++ < breakDelay.get()) return;
        breakTimer = 0;

        int harvested = 0;
        for (BlockPos target : targets) {
            if (harvested >= blocksPerTick.get()) break;

            Runnable action = () -> breakCrop(target);
            if (rotate.get()) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), action);
            else action.run();

            if (render.get()) RenderUtils.renderTickingBlock(target, sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
            harvested++;
        }
    }

    private void breakCrop(BlockPos pos) {
        if (packetMine.get()) {
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
        if (movementTarget != null) {
            if (!isHarvestTarget(movementTarget)) {
                stopPathing();
            } else if (isWithinActionRange(movementTarget)) {
                stopPathing();
                return false;
            } else if (PathManagers.get().isPathing()) {
                emptyScans = 0;
                return false;
            }
        }

        if (moveScanTimer++ < moveScanDelay.get()) return false;
        moveScanTimer = 0;

        if (!autoMove.get()) return true;

        BlockPos target = findNearestDistantTarget();
        if (target == null) {
            stopPathing();
            return true;
        }

        emptyScans = 0;
        if (isWithinActionRange(target)) return false;

        if (PathManagers.get() instanceof NopPathManager) {
            if (!warnedNoPathManager) {
                warning("Auto Move requires Baritone or another path manager. Local harvesting will continue.");
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
        int verticalRange = Math.min(4, horizontalRange);
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
        return isMatureCrop(state, activeCrop) && BlockUtils.canBreak(pos, state);
    }

    private void moveToTarget(BlockPos workTarget) {
        BlockPos movementPoint = getMovementPoint(workTarget);
        BlockState movementState = mc.world.getBlockState(movementPoint);
        BlockPos pathTarget = movementState.isAir() ? movementPoint.down() : movementPoint;

        if (pathingByModule) PathManagers.get().stop();
        PathManagers.get().moveTo(pathTarget);
        pathingByModule = true;
        movementTarget = workTarget;
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
        pathingByModule = false;
        movementTarget = null;
    }

    private boolean isInventoryFull() {
        for (int slot = SlotUtils.HOTBAR_START; slot <= SlotUtils.MAIN_END; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return false;
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

    private void depositInventory() {
        if (getEmptySlotCount() >= emptySlotsAfterDeposit.get()) {
            depositingInventory = false;
            warnedNoDepositItems = false;
            dropTimer = 0;
            return;
        }

        if (dropTimer++ < dropDelay.get()) return;
        dropTimer = 0;

        int slot = findDepositSlot(false);
        if (slot == -1) slot = findDepositSlot(true);
        if (slot == -1) {
            if (!warnedNoDepositItems) {
                warning("Inventory is full, but no selected harvested items can be deposited.");
                warnedNoDepositItems = true;
            }
            return;
        }

        InvUtils.drop().slot(slot);
    }

    private int findDepositSlot(boolean includePlantingItems) {
        for (int slot = SlotUtils.HOTBAR_START; slot <= SlotUtils.MAIN_END; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!isHarvestedItem(stack)) continue;
            if (!includePlantingItems && isSupportedCropChoice(stack.getItem()) && !isSummerSeed(stack)) continue;
            return slot;
        }
        return -1;
    }

    private boolean isHarvestedItem(ItemStack stack) {
        if (isSummerSeed(stack)) return true;

        Item item = stack.getItem();
        List<Item> selected = cropOrder.get();
        if (selected.contains(Items.WHEAT_SEEDS) && (item == Items.WHEAT || item == Items.WHEAT_SEEDS)) return true;
        if (selected.contains(Items.CARROT) && item == Items.CARROT) return true;
        if (selected.contains(Items.POTATO) && (item == Items.POTATO || item == Items.POISONOUS_POTATO)) return true;
        if (selected.contains(Items.BEETROOT_SEEDS) && (item == Items.BEETROOT || item == Items.BEETROOT_SEEDS)) return true;
        if (selected.contains(Items.PUMPKIN_SEEDS) && (item == Items.PUMPKIN || item == Items.PUMPKIN_SEEDS)) return true;
        return selected.contains(Items.MELON_SEEDS) && (item == Items.MELON_SLICE || item == Items.MELON_SEEDS);
    }

    private static boolean isSummerSeed(ItemStack stack) {
        String name = Normalizer.normalize(stack.getName().getString(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        return name.contains("hat giong mua he") || name.contains("summer seed");
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

    private static boolean isSupportedCropChoice(Item item) {
        return item == Items.WHEAT_SEEDS
            || item == Items.CARROT
            || item == Items.POTATO
            || item == Items.BEETROOT_SEEDS
            || item == Items.PUMPKIN_SEEDS
            || item == Items.MELON_SEEDS;
    }

    public enum FarmLayout {
        TargetSearch,
        Irrigated11x11
    }
}
