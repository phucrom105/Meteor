/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Locale;

public class AutoFix extends Module {
    private static final int SUPERMC_INPUT_SLOT = 10;
    private static final int SUPERMC_CONFIRM_SLOT = 49;
    private static final int SUPERMC_MENU_TIMEOUT = 40;
    private static final int SUPERMC_TRANSFER_TIMEOUT = 20;
    private static final int SUPERMC_RESULT_TIMEOUT = 40;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Server> server = sgGeneral.add(new EnumSetting.Builder<Server>()
        .name("server")
        .description("The server workflow to use for repairing items.")
        .defaultValue(Server.MINERUA_COM)
        .onChanged(value -> {
            resetSupermcState();
            delayLeft = 0;
        })
        .build()
    );

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("threshold")
        .description("Starts repairing when the held item's remaining durability reaches this percentage.")
        .defaultValue(20)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How many ticks to wait between repair attempts.")
        .defaultValue(20)
        .min(1)
        .sliderMax(200)
        .build()
    );

    private int delayLeft;
    private SupermcStage supermcStage = SupermcStage.IDLE;
    private int supermcTicks;

    public AutoFix() {
        super(Categories.Dava, "auto-fix", "Automatically repairs the held item using the selected server workflow.");
    }

    @Override
    public void onActivate() {
        delayLeft = 0;
        resetSupermcState();
    }

    @Override
    public void onDeactivate() {
        resetSupermcState();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (server.get() == Server.SUPERMC_VN) {
            tickSupermc();
        } else {
            tickMinerua();
        }
    }

    private void tickMinerua() {
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        ItemStack stack = mc.player.getMainHandStack();
        if (!needsFix(stack)) return;

        ChatUtils.sendPlayerMsg("/fix");
        delayLeft = delay.get();
    }

    private void tickSupermc() {
        if (supermcStage == SupermcStage.IDLE) {
            if (delayLeft > 0) {
                delayLeft--;
                return;
            }

            // /fixitem opens the repair menu, so do not replace an unrelated GUI with it.
            if (mc.currentScreen != null || !needsFix(mc.player.getMainHandStack())) return;

            ChatUtils.sendPlayerMsg("/fixitem");
            supermcStage = SupermcStage.WAITING_FOR_MENU;
            supermcTicks = SUPERMC_MENU_TIMEOUT;
            return;
        }

        GenericContainerScreenHandler handler = getSupermcFixHandler();

        if (supermcStage == SupermcStage.WAITING_FOR_MENU) {
            if (handler == null) {
                if (supermcTicks-- <= 0) stopSupermcAttempt();
                return;
            }

            supermcStage = SupermcStage.PLACING_ITEM;
            supermcTicks = SUPERMC_TRANSFER_TIMEOUT;
        }

        if (handler == null) {
            // The server normally closes the menu after a successful confirmation.
            if (supermcStage == SupermcStage.WAITING_FOR_RESULT) {
                stopSupermcAttempt();
            } else if (supermcTicks-- <= 0) {
                stopSupermcAttempt();
            }
            return;
        }

        switch (supermcStage) {
            case PLACING_ITEM -> placeSupermcItem(handler);
            case WAITING_FOR_CONFIRM -> confirmSupermcItem(handler);
            case WAITING_FOR_RESULT -> {
                if (supermcTicks-- <= 0) stopSupermcAttempt();
            }
            case IDLE, WAITING_FOR_MENU -> {
            }
        }
    }

    private void placeSupermcItem(GenericContainerScreenHandler handler) {
        Slot inputSlot = getContainerSlot(handler, SUPERMC_INPUT_SLOT);
        if (inputSlot == null) {
            stopSupermcAttempt();
            return;
        }

        if (inputSlot.hasStack()) {
            supermcStage = SupermcStage.WAITING_FOR_CONFIRM;
            supermcTicks = SUPERMC_TRANSFER_TIMEOUT;
            return;
        }

        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isEmpty() || !needsFix(mainHand)) {
            stopSupermcAttempt();
            return;
        }

        int sourceId = SlotUtils.indexToId(mc.player.getInventory().getSelectedSlot());
        if (sourceId < 0 || sourceId >= handler.slots.size()) {
            stopSupermcAttempt();
            return;
        }

        Slot sourceSlot = handler.slots.get(sourceId);
        if (!(sourceSlot.inventory instanceof PlayerInventory) || !sourceSlot.hasStack()) {
            stopSupermcAttempt();
            return;
        }

        InvUtils.move().fromId(sourceId).toId(inputSlot.id);
        supermcStage = SupermcStage.WAITING_FOR_CONFIRM;
        supermcTicks = SUPERMC_TRANSFER_TIMEOUT;
    }

    private void confirmSupermcItem(GenericContainerScreenHandler handler) {
        Slot inputSlot = getContainerSlot(handler, SUPERMC_INPUT_SLOT);
        if (inputSlot == null) {
            stopSupermcAttempt();
            return;
        }

        if (!inputSlot.hasStack()) {
            if (supermcTicks-- <= 0) {
                supermcStage = SupermcStage.PLACING_ITEM;
                supermcTicks = SUPERMC_TRANSFER_TIMEOUT;
            }
            return;
        }

        Slot confirmSlot = findSupermcConfirmSlot(handler);
        if (confirmSlot == null) {
            if (supermcTicks-- <= 0) stopSupermcAttempt();
            return;
        }

        click(confirmSlot, handler);
        supermcStage = SupermcStage.WAITING_FOR_RESULT;
        supermcTicks = SUPERMC_RESULT_TIMEOUT;
    }

    private GenericContainerScreenHandler getSupermcFixHandler() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return null;
        if (!(screen.getScreenHandler() instanceof GenericContainerScreenHandler handler)) return null;

        // The /fixitem menu shown by supermc.vn is a six-row chest menu.
        return handler.getRows() == 6 ? handler : null;
    }

    private static Slot getContainerSlot(ScreenHandler handler, int id) {
        if (id < 0 || id >= handler.slots.size()) return null;

        Slot slot = handler.slots.get(id);
        return slot.inventory instanceof PlayerInventory ? null : slot;
    }

    private static Slot findSupermcConfirmSlot(GenericContainerScreenHandler handler) {
        Slot confirmSlot = getContainerSlot(handler, SUPERMC_CONFIRM_SLOT);
        if (isSupermcConfirmButton(confirmSlot)) return confirmSlot;

        // Prefer the button by its material/name if the server changes its position.
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            if (isSupermcConfirmButton(slot)) return slot;
        }

        return null;
    }

    private static boolean isSupermcConfirmButton(Slot slot) {
        if (slot == null || !slot.hasStack()) return false;

        ItemStack stack = slot.getStack();
        String name = stack.getName().getString().toLowerCase(Locale.ROOT);
        return stack.isOf(Items.LIME_CONCRETE) || stack.isOf(Items.GREEN_CONCRETE)
            || name.contains("confirm") || name.contains("xac nhan") || name.contains("x\u00e1c nh\u1eadn")
            || name.contains("repair") || name.contains("fix");
    }

    private void click(Slot slot, ScreenHandler handler) {
        if (mc.interactionManager != null) {
            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
        }
    }

    private void stopSupermcAttempt() {
        resetSupermcState();
        delayLeft = delay.get();
    }

    private void resetSupermcState() {
        supermcStage = SupermcStage.IDLE;
        supermcTicks = 0;
    }

    private boolean needsFix(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) return false;

        int remainingDurability = stack.getMaxDamage() - stack.getDamage();
        return remainingDurability * 100.0 / stack.getMaxDamage() <= threshold.get();
    }

    private enum Server {
        MINERUA_COM("minerua.com"),
        SUPERMC_VN("supermc.vn");

        private final String name;

        Server(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private enum SupermcStage {
        IDLE,
        WAITING_FOR_MENU,
        PLACING_ITEM,
        WAITING_FOR_CONFIRM,
        WAITING_FOR_RESULT
    }
}
