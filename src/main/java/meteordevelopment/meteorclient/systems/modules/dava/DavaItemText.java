/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

final class DavaItemText {
    private DavaItemText() {}

    static List<String> lines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        lines.add(stack.getName().getString());

        NbtCompound display = stack.getSubNbt("display");
        if (display == null) return lines;

        if (display.contains("Name", NbtElement.STRING_TYPE)) addJsonText(lines, display.getString("Name"));
        if (display.contains("Lore", NbtElement.LIST_TYPE)) {
            NbtList lore = display.getList("Lore", NbtElement.STRING_TYPE);
            for (int i = 0; i < lore.size(); i++) addJsonText(lines, lore.getString(i));
        }
        return lines;
    }

    private static void addJsonText(List<String> lines, String json) {
        try {
            Text text = Text.Serializer.fromJson(json);
            if (text != null) lines.add(text.getString());
        } catch (RuntimeException ignored) {
            lines.add(json);
        }
    }
}
