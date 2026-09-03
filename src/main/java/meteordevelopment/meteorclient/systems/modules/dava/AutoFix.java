/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;

public class AutoFix extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("threshold")
        .description("Sends /fix when the held item's remaining durability reaches this percentage.")
        .defaultValue(20)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How many ticks to wait between /fix attempts.")
        .defaultValue(20)
        .min(1)
        .sliderMax(200)
        .build()
    );

    private int delayLeft;

    public AutoFix() {
        super(Categories.Dava, "auto-fix", "Automatically sends /fix when the held item needs repairing.");
    }

    @Override
    public void onActivate() {
        delayLeft = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        ItemStack stack = mc.player.getMainHandStack();
        if (!needsFix(stack)) return;

        ChatUtils.sendPlayerMsg("/fix");
        delayLeft = delay.get();
    }

    private boolean needsFix(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) return false;

        int remainingDurability = stack.getMaxDamage() - stack.getDamage();
        return remainingDurability * 100.0 / stack.getMaxDamage() <= threshold.get();
    }
}
