/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.CollectionListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.function.Predicate;

public class BlockListSettingScreen extends CollectionListSettingScreen<Block> {
    public BlockListSettingScreen(GuiTheme theme, BlockListSetting setting) {
        super(theme, "Select Blocks", setting, setting.get(), Registries.BLOCK);
    }

    @Override
    protected boolean includeValue(Block value) {
        if (Registries.BLOCK.getId(value).getPath().endsWith("_wall_banner")) {
            return false;
        }

        Predicate<Block> filter = ((BlockListSetting) setting).filter;

        if (filter == null) return value != Blocks.AIR;
        return filter.test(value);
    }

    @Override
    protected WWidget getValueWidget(Block value) {
        Item displayItem = getDisplayItem(value);
        String displayName = displayItem == Items.AIR ? Names.get(value) : Names.get(displayItem);
        return theme.itemWithLabel(displayItem.getDefaultStack(), displayName);
    }

    @Override
    protected String[] getValueNames(Block value) {
        Item displayItem = getDisplayItem(value);

        return new String[]{
            displayItem == Items.AIR ? Names.get(value) : Names.get(displayItem),
            Names.get(value),
            Registries.BLOCK.getId(value).toString()
        };
    }

    private Item getDisplayItem(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (block == Blocks.PUMPKIN_STEM || block == Blocks.ATTACHED_PUMPKIN_STEM) return Items.PUMPKIN_SEEDS;
        if (block == Blocks.MELON_STEM || block == Blocks.ATTACHED_MELON_STEM) return Items.MELON_SEEDS;

        return block.asItem();
    }

    @Override
    protected Block getAdditionalValue(Block value) {
        String path = Registries.BLOCK.getId(value).getPath();
        if (!path.endsWith("_banner")) return null;

        return Registries.BLOCK.get(Identifier.ofVanilla(path.substring(0, path.length() - 6) + "wall_banner"));
    }
}
