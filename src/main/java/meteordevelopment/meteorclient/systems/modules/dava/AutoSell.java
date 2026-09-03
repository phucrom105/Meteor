/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches the Dava storage menu and sells its contents when the reported usage
 * reaches the configured threshold.
 */
public class AutoSell extends Module {
    private static final String STORAGE_TITLE = "kho chua";
    private static final String STORAGE_INFO_TITLE = "thong tin kho chua";
    private static final String USED_LABEL = "da su dung";
    private static final String FREE_LABEL = "con trong";
    private static final String CAPACITY_LABEL = "dung luong";
    private static final String STATUS_LABEL = "trang thai";

    private static final Pattern USAGE_PATTERN = Pattern.compile(
        "([0-9][0-9.,]*)\\s*/\\s*([0-9]+(?:[.,][0-9]+)?)\\s*%"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9][0-9.,]*");
    private static final Pattern STATS_USED_PATTERN = Pattern.compile(
        "(?i)\\bUsed\\s*:\\s*([0-9][0-9.,]*)\\s*/\\s*\\[?\\s*([0-9][0-9.,]*)\\s*\\]?"
    );
    private static final Pattern STATS_FREE_PATTERN = Pattern.compile(
        "(?i)\\bFree\\s*:\\s*([0-9][0-9.,]*)"
    );
    private static final Pattern STATS_USAGE_PATTERN = Pattern.compile(
        "(?i)\\bUsage\\s*:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*%\\s*/\\s*([0-9]+(?:[.,][0-9]+)?)\\s*%"
    );
    private static final Pattern STATS_STATUS_PATTERN = Pattern.compile(
        "(?i)\\bStatus\\s*:\\s*\\(status\\s*:\\s*([^)]*)\\)"
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> usedThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("used-threshold")
        .description("Sends /kho sellall when the detected storage usage reaches this percentage.")
        .defaultValue(90)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait before another sellall attempt can be made.")
        .defaultValue(40)
        .min(1)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> statsInterval = sgGeneral.add(new IntSetting.Builder()
        .name("stats-interval")
        .description("Ticks between /kho stats requests when the storage menu is closed.")
        .defaultValue(100)
        .min(20)
        .sliderMax(600)
        .build()
    );

    private final Setting<Integer> statsTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("stats-timeout")
        .description("Ticks to wait for the /kho stats response before it is discarded.")
        .defaultValue(40)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> requireActive = sgGeneral.add(new BoolSetting.Builder()
        .name("require-active")
        .description("Only sends the command when the storage status is detected as BẬT.")
        .defaultValue(true)
        .build()
    );

    private int delayLeft;
    private int statsTicks;
    private int statsResponseTicks;
    private boolean statsRequestPending;
    private boolean triggerArmed;
    private WarehouseState state;
    private StatsAccumulator statsAccumulator;

    public AutoSell() {
        super(Categories.Dava, "autosell", "Automatically sends /kho sellall when the Dava storage reaches a usage threshold.", "auto-kho-sellall", "kho-sell-all");
    }

    @Override
    public void onActivate() {
        delayLeft = 0;
        statsTicks = 0;
        statsResponseTicks = 0;
        statsRequestPending = false;
        triggerArmed = true;
        state = null;
        statsAccumulator = null;
    }

    @Override
    public void onDeactivate() {
        state = null;
        statsAccumulator = null;
        statsRequestPending = false;
        statsResponseTicks = 0;
        triggerArmed = true;
        delayLeft = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (delayLeft > 0) delayLeft--;
        if (statsResponseTicks > 0 && --statsResponseTicks == 0) statsRequestPending = false;
        if (!Utils.canUpdate() || mc.player == null) return;

        if (mc.currentScreen instanceof HandledScreen<?> screen) {
            if (!isStorageScreen(screen)) return;

            WarehouseState detected = detectState(screen.getScreenHandler());
            if (detected != null) processState(detected);
            return;
        }

        if (mc.currentScreen != null) return;
        if (statsTicks > 0) statsTicks--;
        else requestStats();
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        if (!statsRequestPending) return;

        String message = event.getMessage().getString();
        String normalized = normalize(message);

        // The header marks the beginning of a response to the request we just sent. The
        // following lines contain Used/Free/Usage/Status and are received as chat messages.
        if (normalized.contains("storage stats")) statsAccumulator = new StatsAccumulator();
        if (statsAccumulator == null) return;

        statsAccumulator.read(message);
        WarehouseState detected = statsAccumulator.toState();
        if (detected == null) return;

        statsRequestPending = false;
        statsResponseTicks = 0;
        processState(detected);
    }

    private void processState(WarehouseState detected) {
        state = detected;

        // Do not send a command while the server reports that the warehouse is disabled.
        if (requireActive.get() && detected.statusKnown() && !detected.active()) return;
        if (requireActive.get() && !detected.statusKnown()) return;

        boolean thresholdReached = detected.usedPercent() >= usedThreshold.get();
        if (!thresholdReached) {
            // Re-arm only after the observed usage falls below the threshold. This prevents
            // repeated commands when /kho sellall has not yet changed the menu contents.
            triggerArmed = true;
            return;
        }

        if (triggerArmed && delayLeft <= 0) {
            ChatUtils.sendPlayerMsg("/kho sellall", false);
            delayLeft = delay.get();
            triggerArmed = false;
        }
    }

    private void requestStats() {
        statsTicks = statsInterval.get();
        statsResponseTicks = statsTimeout.get();
        statsRequestPending = true;
        statsAccumulator = null;
        ChatUtils.sendPlayerMsg("/kho stats", false);
    }

    @Override
    public String getInfoString() {
        return state == null ? "waiting" : String.format(Locale.ROOT, "%.2f%%", state.usedPercent());
    }

    private static boolean isStorageScreen(HandledScreen<?> screen) {
        return normalize(screen.getTitle().getString()).contains(STORAGE_TITLE);
    }

    private static WarehouseState detectState(ScreenHandler handler) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;

            List<String> lines = stackLines(slot.getStack());
            String searchableText = normalize(String.join(" ", lines));
            if (!searchableText.contains(STORAGE_INFO_TITLE)) continue;

            WarehouseState state = parseState(lines);
            if (state != null) return state;
        }

        return null;
    }

    private static WarehouseState parseState(List<String> lines) {
        long used = -1;
        long free = -1;
        long capacity = -1;
        double usedPercent = -1;
        double freePercent = -1;
        boolean active = false;
        boolean statusKnown = false;

        for (String line : lines) {
            String normalized = normalize(line);

            if (normalized.contains(USED_LABEL)) {
                Matcher matcher = USAGE_PATTERN.matcher(line);
                if (matcher.find()) {
                    used = parseAmount(matcher.group(1));
                    usedPercent = parsePercent(matcher.group(2));
                }
            } else if (normalized.contains(FREE_LABEL)) {
                Matcher matcher = USAGE_PATTERN.matcher(line);
                if (matcher.find()) {
                    free = parseAmount(matcher.group(1));
                    freePercent = parsePercent(matcher.group(2));
                }
            } else if (normalized.contains(CAPACITY_LABEL)) {
                Matcher matcher = NUMBER_PATTERN.matcher(line);
                if (matcher.find()) capacity = parseAmount(matcher.group());
            } else if (normalized.startsWith(STATUS_LABEL)) {
                statusKnown = true;
                String value = normalized.substring(STATUS_LABEL.length()).trim();
                active = value.equals("bat") || value.equals("on") || value.equals("enabled") || value.equals("active");
            }
        }

        if (usedPercent < 0 && used >= 0 && capacity > 0) usedPercent = used * 100.0 / capacity;
        if (freePercent < 0 && free >= 0 && capacity > 0) freePercent = free * 100.0 / capacity;
        if (usedPercent < 0 || used < 0) return null;

        return new WarehouseState(used, free, capacity, usedPercent, freePercent, active, statusKnown);
    }

    private static List<String> stackLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        lines.add(stack.getName().getString());

        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) lines.add(customName.getString());

        Text itemName = stack.get(DataComponentTypes.ITEM_NAME);
        if (itemName != null) lines.add(itemName.getString());

        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) lines.add(line.getString());
        }

        return lines;
    }

    private static long parseAmount(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return -1;

        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static double parsePercent(String value) {
        String normalized = value.trim();
        if (normalized.indexOf(',') >= 0 && normalized.indexOf('.') >= 0) {
            if (normalized.lastIndexOf(',') > normalized.lastIndexOf('.')) {
                normalized = normalized.replace(".", "").replace(',', '.');
            } else {
                normalized = normalized.replace(",", "");
            }
        } else if (normalized.indexOf(',') >= 0) {
            normalized = normalized.replace(',', '.');
        }

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static final class StatsAccumulator {
        private long used = -1;
        private long free = -1;
        private long capacity = -1;
        private double usedPercent = -1;
        private double freePercent = -1;
        private boolean active;
        private boolean statusKnown;

        private void read(String line) {
            Matcher usedMatcher = STATS_USED_PATTERN.matcher(line);
            if (usedMatcher.find()) {
                used = parseAmount(usedMatcher.group(1));
                capacity = parseAmount(usedMatcher.group(2));
            }

            Matcher freeMatcher = STATS_FREE_PATTERN.matcher(line);
            if (freeMatcher.find()) free = parseAmount(freeMatcher.group(1));

            Matcher usageMatcher = STATS_USAGE_PATTERN.matcher(line);
            if (usageMatcher.find()) {
                usedPercent = parsePercent(usageMatcher.group(1));
                freePercent = parsePercent(usageMatcher.group(2));
            }

            Matcher statusMatcher = STATS_STATUS_PATTERN.matcher(line);
            if (statusMatcher.find()) {
                String status = normalize(statusMatcher.group(1));
                active = status.equals("1") || status.equals("true") || status.equals("on")
                    || status.equals("enabled") || status.equals("active");
                statusKnown = true;
            }
        }

        private WarehouseState toState() {
            // The stats response has a dedicated Usage line. Waiting for it keeps the
            // displayed value identical to the server value instead of deriving 98.49%
            // from 98,488 / 100,000 when the server reports 98.48%.
            if (freePercent < 0 && free >= 0 && capacity > 0) freePercent = free * 100.0 / capacity;
            if (used < 0 || usedPercent < 0) return null;

            return new WarehouseState(used, free, capacity, usedPercent, freePercent, active, statusKnown);
        }
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder(decomposed.length());

        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            // Java's NFD form does not decompose Vietnamese Đ/đ.
            if (c == 'đ') c = 'd';
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            result.append(Character.isLetterOrDigit(c) ? c : ' ');
        }

        return result.toString().replaceAll("\\s+", " ").trim();
    }

    private record WarehouseState(long used, long free, long capacity, double usedPercent, double freePercent, boolean active, boolean statusKnown) {
    }
}
