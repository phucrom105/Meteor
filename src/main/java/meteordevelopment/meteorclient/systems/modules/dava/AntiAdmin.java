/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Nuker;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Suspends active modules while a configured admin is online or appears to be
 * vanished, then restores the modules when every tracked admin has left.
 */
public class AntiAdmin extends Module {
    private final SettingGroup sgDetection = settings.getDefaultGroup();
    private final SettingGroup sgResponse = settings.createGroup("Response");

    private final Setting<List<String>> adminRoles = sgDetection.add(new StringListSetting.Builder()
        .name("admin-roles")
        .description("Roles shown in the tab list or scoreboard prefix that are treated as admins.")
        .defaultValue("admin", "owner")
        .build()
    );

    private final Setting<List<String>> admins = sgDetection.add(new StringListSetting.Builder()
        .name("admins")
        .description("Optional admin names or UUIDs to monitor when their role is not visible.")
        .build()
    );

    private final Setting<Integer> logoutConfirmationTicks = sgDetection.add(new IntSetting.Builder()
        .name("logout-confirmation-ticks")
        .description("Ticks an admin must be absent from both the tab list and world before being assumed logged out. Fully hidden vanish cannot be distinguished from logout.")
        .defaultValue(60)
        .range(10, 400)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Boolean> disableAllModules = sgResponse.add(new BoolSetting.Builder()
        .name("disable-all-modules")
        .description("Disables every active module except Anti Admin while an admin is present.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Module>> modulesToDisable = sgResponse.add(new ModuleListSetting.Builder()
        .name("modules-to-disable")
        .description("Modules to suspend when disable-all-modules is off.")
        .defaultValue(Nuker.class, AutoHarvest.class, AutoPlant.class, AutoMine.class, AutoSell.class, AutoFix.class)
        .visible(() -> !disableAllModules.get())
        .build()
    );

    private final Map<UUID, AdminPresence> activeAdmins = new HashMap<>();
    private final Queue<AdminListSignal> pendingSignals = new ConcurrentLinkedQueue<>();
    private final Set<Module> suspendedModules = Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean lockedDown;

    public AntiAdmin() {
        super(Categories.Dava, "anti-admin", "Suspends active modules while an admin role is online or vanished.");
    }

    @Override
    public void onActivate() {
        activeAdmins.clear();
        pendingSignals.clear();
        suspendedModules.clear();
        lockedDown = false;
    }

    @Override
    public void onDeactivate() {
        activeAdmins.clear();
        pendingSignals.clear();
        restoreModules(false);
    }

    @Override
    public String getInfoString() {
        return lockedDown ? "LOCKED " + activeAdmins.size() : null;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet
            && packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
            for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
                if (entry.profile() == null) continue;
                pendingSignals.add(new AdminListSignal(entry.profile().id(), entry.profile().name(), SignalType.Added));
            }
        } else if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            for (UUID uuid : packet.profileIds()) {
                String name = "";
                if (mc.getNetworkHandler() != null) {
                    PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(uuid);
                    if (entry != null && entry.getProfile() != null) name = entry.getProfile().name();
                }

                pendingSignals.add(new AdminListSignal(uuid, name, SignalType.Removed));
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate() || mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        processSignals();

        Set<UUID> tabAdmins = scanTabList();
        Set<UUID> worldAdmins = scanWorld(tabAdmins);
        expireMissingAdmins(tabAdmins, worldAdmins);

        if (activeAdmins.isEmpty()) {
            if (lockedDown) restoreModules(true);
        } else if (!lockedDown) {
            suspendModules();
        } else {
            enforceLockdown();
        }
    }

    private void processSignals() {
        AdminListSignal signal;
        while ((signal = pendingSignals.poll()) != null) {
            boolean tracked = activeAdmins.containsKey(signal.uuid());
            if (!tracked && !isConfiguredAdmin(signal.uuid(), signal.name())) continue;

            AdminPresence presence = activeAdmins.get(signal.uuid());
            if (presence == null) {
                presence = new AdminPresence(displayName(signal), Presence.Unknown);
                activeAdmins.put(signal.uuid(), presence);
            }

            if (!signal.name().isBlank()) presence.name = signal.name();
            presence.missingTicks = 0;
            presence.state = signal.type() == SignalType.Added ? Presence.Online : Presence.Unknown;
        }
    }

    private Set<UUID> scanTabList() {
        Set<UUID> found = new HashSet<>();

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() == null) continue;

            UUID uuid = entry.getProfile().id();
            String name = entry.getProfile().name();
            if (!isConfiguredAdmin(uuid, name) && !hasAdminRole(entry)) continue;

            found.add(uuid);
            markPresent(uuid, name, Presence.Online);
        }

        return found;
    }

    private Set<UUID> scanWorld(Set<UUID> tabAdmins) {
        Set<UUID> found = new HashSet<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player instanceof FakePlayerEntity) continue;

            UUID uuid = player.getUuid();
            String name = player.getGameProfile().name();
            if (!activeAdmins.containsKey(uuid) && !isConfiguredAdmin(uuid, name) && !hasAdminRole(player)) continue;

            found.add(uuid);
            boolean vanished = player.isInvisible() || !tabAdmins.contains(uuid);
            markPresent(uuid, name, vanished ? Presence.Vanished : Presence.Online);
        }

        return found;
    }

    private void expireMissingAdmins(Set<UUID> tabAdmins, Set<UUID> worldAdmins) {
        activeAdmins.entrySet().removeIf(entry -> {
            if (tabAdmins.contains(entry.getKey()) || worldAdmins.contains(entry.getKey())) {
                entry.getValue().missingTicks = 0;
                return false;
            }

            entry.getValue().missingTicks++;
            return entry.getValue().missingTicks >= logoutConfirmationTicks.get();
        });
    }

    private void markPresent(UUID uuid, String name, Presence state) {
        AdminPresence presence = activeAdmins.get(uuid);
        boolean becameVanished = presence != null && presence.state != Presence.Vanished && state == Presence.Vanished;

        if (presence == null) {
            presence = new AdminPresence(name, state);
            activeAdmins.put(uuid, presence);
        } else {
            if (!name.isBlank()) presence.name = name;
            presence.state = state;
            presence.missingTicks = 0;
        }

        if (becameVanished && lockedDown) warning("Admin %s may be vanished; keeping modules disabled.", presence.name);
    }

    private void suspendModules() {
        lockedDown = true;
        suspendedModules.clear();

        int disabled = 0;
        for (Module module : protectedModules()) {
            if (module == this || !module.isActive()) continue;

            suspendedModules.add(module);
            module.disable();
            disabled++;
        }

        warning("Admin detected (%s). Disabled %d module%s.", adminNames(), disabled, disabled == 1 ? "" : "s");
    }

    private void enforceLockdown() {
        for (Module module : protectedModules()) {
            if (module != this && module.isActive()) module.disable();
        }
    }

    private void restoreModules(boolean adminLoggedOut) {
        if (!lockedDown) {
            suspendedModules.clear();
            return;
        }

        lockedDown = false;
        List<Module> toRestore = new ArrayList<>(suspendedModules);
        suspendedModules.clear();

        int restored = 0;
        for (Module module : toRestore) {
            if (module == this || module.isActive()) continue;

            module.enable();
            restored++;
        }

        if (adminLoggedOut) {
            info("All monitored admins logged out. Restored %d module%s.", restored, restored == 1 ? "" : "s");
        }
    }

    private Iterable<Module> protectedModules() {
        return disableAllModules.get() ? Modules.get().getAll() : modulesToDisable.get();
    }

    private boolean isConfiguredAdmin(UUID uuid, String name) {
        for (String configured : admins.get()) {
            if (configured == null) continue;

            String value = configured.trim();
            if (value.isEmpty()) continue;
            if (value.equalsIgnoreCase(name) || value.equalsIgnoreCase(uuid.toString())) return true;
        }

        return false;
    }

    private boolean hasAdminRole(PlayerListEntry entry) {
        StringBuilder decorations = new StringBuilder();
        appendText(decorations, entry.getDisplayName());
        appendTeamDecorations(decorations, entry.getProfile().name());
        return containsConfiguredRole(decorations.toString(), entry.getProfile().name());
    }

    private boolean hasAdminRole(PlayerEntity player) {
        String name = player.getGameProfile().name();
        StringBuilder decorations = new StringBuilder();
        appendText(decorations, player.getDisplayName());
        appendTeamDecorations(decorations, name);
        return containsConfiguredRole(decorations.toString(), name);
    }

    private void appendTeamDecorations(StringBuilder decorations, String playerName) {
        Team team = mc.world.getScoreboard().getScoreHolderTeam(playerName);
        if (team == null) return;

        appendText(decorations, team.getPrefix());
        appendText(decorations, team.getSuffix());
    }

    private boolean containsConfiguredRole(String decorations, String playerName) {
        String normalized = normalize(decorations);
        String normalizedName = normalize(playerName);

        // Remove only the last username occurrence, leaving a same-named role tag intact.
        int nameIndex = normalized.lastIndexOf(normalizedName);
        if (!normalizedName.isEmpty() && nameIndex >= 0) {
            normalized = normalized.substring(0, nameIndex) + ' ' + normalized.substring(nameIndex + normalizedName.length());
        }

        for (String configuredRole : adminRoles.get()) {
            String role = normalize(configuredRole);
            if (!role.isEmpty() && containsWholeRole(normalized, role)) return true;
        }

        return false;
    }

    private static boolean containsWholeRole(String text, String role) {
        int fromIndex = 0;
        while (fromIndex <= text.length() - role.length()) {
            int index = text.indexOf(role, fromIndex);
            if (index < 0) return false;

            int end = index + role.length();
            boolean startsAtBoundary = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
            boolean endsAtBoundary = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startsAtBoundary && endsAtBoundary) return true;

            fromIndex = index + 1;
        }

        return false;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toUpperCase(Locale.ROOT);
    }

    private static void appendText(StringBuilder builder, Text text) {
        if (text == null) return;

        String value = text.getString();
        if (!value.isBlank()) builder.append(' ').append(value);
    }

    private String adminNames() {
        return String.join(", ", activeAdmins.values().stream().map(admin -> admin.name).distinct().toList());
    }

    private static String displayName(AdminListSignal signal) {
        return signal.name().isBlank() ? signal.uuid().toString() : signal.name();
    }

    private enum Presence {
        Online,
        Vanished,
        Unknown
    }

    private enum SignalType {
        Added,
        Removed
    }

    private static class AdminPresence {
        private String name;
        private Presence state;
        private int missingTicks;

        private AdminPresence(String name, Presence state) {
            this.name = name;
            this.state = state;
        }
    }

    private record AdminListSignal(UUID uuid, String name, SignalType type) {}
}
