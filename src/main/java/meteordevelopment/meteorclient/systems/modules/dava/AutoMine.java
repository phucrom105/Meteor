/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class AutoMine extends Module {
    private static final List<Block> DEFAULT_MINING_BLOCKS = List.of(
        // Stone and ores
        Blocks.STONE,
        Blocks.GRANITE,
        Blocks.DIORITE,
        Blocks.ANDESITE,
        Blocks.TUFF,
        Blocks.DEEPSLATE,
        Blocks.NETHERRACK,
        Blocks.BASALT,
        Blocks.BLACKSTONE,
        Blocks.COBBLESTONE,
        Blocks.COAL_ORE,
        Blocks.DEEPSLATE_COAL_ORE,
        Blocks.COPPER_ORE,
        Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.DIAMOND_ORE,
        Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE,
        Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.GOLD_ORE,
        Blocks.NETHER_GOLD_ORE,
        Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.IRON_ORE,
        Blocks.DEEPSLATE_IRON_ORE,
        Blocks.LAPIS_ORE,
        Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.REDSTONE_ORE,
        Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.NETHER_QUARTZ_ORE,
        Blocks.ANCIENT_DEBRIS,

        // Resource blocks
        Blocks.COAL_BLOCK,
        Blocks.COPPER_BLOCK,
        Blocks.DIAMOND_BLOCK,
        Blocks.EMERALD_BLOCK,
        Blocks.GOLD_BLOCK,
        Blocks.IRON_BLOCK,
        Blocks.LAPIS_BLOCK,
        Blocks.NETHERITE_BLOCK,
        Blocks.REDSTONE_BLOCK,
        Blocks.RAW_COPPER_BLOCK,
        Blocks.RAW_GOLD_BLOCK,
        Blocks.RAW_IRON_BLOCK,

        // Logs and stems
        Blocks.OAK_LOG,
        Blocks.OAK_WOOD,
        Blocks.STRIPPED_OAK_LOG,
        Blocks.STRIPPED_OAK_WOOD,
        Blocks.SPRUCE_LOG,
        Blocks.SPRUCE_WOOD,
        Blocks.STRIPPED_SPRUCE_LOG,
        Blocks.STRIPPED_SPRUCE_WOOD,
        Blocks.BIRCH_LOG,
        Blocks.BIRCH_WOOD,
        Blocks.STRIPPED_BIRCH_LOG,
        Blocks.STRIPPED_BIRCH_WOOD,
        Blocks.JUNGLE_LOG,
        Blocks.JUNGLE_WOOD,
        Blocks.STRIPPED_JUNGLE_LOG,
        Blocks.STRIPPED_JUNGLE_WOOD,
        Blocks.ACACIA_LOG,
        Blocks.ACACIA_WOOD,
        Blocks.STRIPPED_ACACIA_LOG,
        Blocks.STRIPPED_ACACIA_WOOD,
        Blocks.DARK_OAK_LOG,
        Blocks.DARK_OAK_WOOD,
        Blocks.STRIPPED_DARK_OAK_LOG,
        Blocks.STRIPPED_DARK_OAK_WOOD,
        Blocks.MANGROVE_LOG,
        Blocks.MANGROVE_WOOD,
        Blocks.STRIPPED_MANGROVE_LOG,
        Blocks.STRIPPED_MANGROVE_WOOD,
        Blocks.CHERRY_LOG,
        Blocks.CHERRY_WOOD,
        Blocks.STRIPPED_CHERRY_LOG,
        Blocks.STRIPPED_CHERRY_WOOD,
        Blocks.CRIMSON_STEM,
        Blocks.STRIPPED_CRIMSON_STEM,
        Blocks.CRIMSON_HYPHAE,
        Blocks.STRIPPED_CRIMSON_HYPHAE,
        Blocks.WARPED_STEM,
        Blocks.STRIPPED_WARPED_STEM,
        Blocks.WARPED_HYPHAE,
        Blocks.STRIPPED_WARPED_HYPHAE,

        // Planks
        Blocks.OAK_PLANKS,
        Blocks.SPRUCE_PLANKS,
        Blocks.BIRCH_PLANKS,
        Blocks.JUNGLE_PLANKS,
        Blocks.ACACIA_PLANKS,
        Blocks.DARK_OAK_PLANKS,
        Blocks.CRIMSON_PLANKS,
        Blocks.WARPED_PLANKS,
        Blocks.MANGROVE_PLANKS,
        Blocks.BAMBOO_PLANKS,
        Blocks.CHERRY_PLANKS
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Sends rotation packets to the server when mining.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings the main hand client-side while mining.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("How to filter supported blocks.")
        .defaultValue(ListMode.Whitelist)
        .build()
    );

    private final Setting<List<Block>> whitelist = sgWhitelist.add(new BlockListSetting.Builder()
        .name("whitelist")
        .description("The supported blocks to mine.")
        .defaultValue(DEFAULT_MINING_BLOCKS)
        .visible(() -> listMode.get() == ListMode.Whitelist)
        .build()
    );

    private final Setting<List<Block>> blacklist = sgWhitelist.add(new BlockListSetting.Builder()
        .name("blacklist")
        .description("The supported blocks not to mine.")
        .visible(() -> listMode.get() == ListMode.Blacklist)
        .build()
    );

    public AutoMine() {
        super(Categories.Dava, "auto-mine", "Automatically mines stone, ores, resource blocks and wood directly in front of you.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.interactionManager == null || mc.player.isSpectator()) return;

        // Eating always takes priority over mining. Checking shouldEat as well as
        // eating prevents this module from switching away from food on the tick
        // where Auto Eat is about to start.
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        if (autoEat.isActive() && (autoEat.eating || autoEat.shouldEat())) return;

        if (!(mc.crosshairTarget instanceof BlockHitResult hitResult) || hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos blockPos = hitResult.getBlockPos();
        BlockState blockState = mc.world.getBlockState(blockPos);

        if (!isAllowed(blockState) || !BlockUtils.canBreak(blockPos, blockState)) return;

        // Always use the fastest suitable hotbar tool for the targeted block.
        // Auto Eat restores the previous slot when it finishes; on the following
        // tick this also switches back to the appropriate mining tool.
        FindItemResult tool = InvUtils.findFastestTool(blockState);
        if (tool.found()) InvUtils.swap(tool.slot(), false);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(blockPos), Rotations.getPitch(blockPos), 50, () -> BlockUtils.breakBlock(blockPos, swing.get()));
        } else {
            BlockUtils.breakBlock(blockPos, swing.get());
        }
    }

    private boolean isAllowed(BlockState state) {
        Block block = state.getBlock();

        boolean supported = (state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER))
            || block == Blocks.COBBLESTONE
            || state.isIn(BlockTags.COAL_ORES)
            || state.isIn(BlockTags.COPPER_ORES)
            || state.isIn(BlockTags.DIAMOND_ORES)
            || state.isIn(BlockTags.EMERALD_ORES)
            || state.isIn(BlockTags.GOLD_ORES)
            || state.isIn(BlockTags.IRON_ORES)
            || state.isIn(BlockTags.LAPIS_ORES)
            || state.isIn(BlockTags.REDSTONE_ORES)
            || block == Blocks.NETHER_QUARTZ_ORE
            || block == Blocks.ANCIENT_DEBRIS
            // Resource blocks (and their raw-material variants) are not part of
            // the ore tags, so include them explicitly.
            || block == Blocks.COAL_BLOCK
            || block == Blocks.COPPER_BLOCK
            || block == Blocks.DIAMOND_BLOCK
            || block == Blocks.EMERALD_BLOCK
            || block == Blocks.GOLD_BLOCK
            || block == Blocks.IRON_BLOCK
            || block == Blocks.LAPIS_BLOCK
            || block == Blocks.NETHERITE_BLOCK
            || block == Blocks.REDSTONE_BLOCK
            || block == Blocks.RAW_COPPER_BLOCK
            || block == Blocks.RAW_GOLD_BLOCK
            || block == Blocks.RAW_IRON_BLOCK
            || state.isIn(BlockTags.LOGS)
            || state.isIn(BlockTags.PLANKS);

        if (!supported) return false;

        if (listMode.get() == ListMode.Whitelist) return whitelist.get().contains(block);
        return !blacklist.get().contains(block);
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }
}
