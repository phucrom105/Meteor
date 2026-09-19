/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
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
import net.minecraft.util.Uuids;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suspends active modules while a configured admin is online or appears to be
 * vanished, then restores the modules when every tracked admin has left.
 */
public class AntiAdmin extends Module {
    private static final String ADMIN_RANK_GLYPH = "\uD800\uDFA0";
    private static final String SERVER_JOIN_LEAVE_GLYPH = "\uD800\uDFF1";
    private static final Pattern SERVER_JOIN_LEAVE_PATTERN = Pattern.compile("^\\s*" + Pattern.quote(SERVER_JOIN_LEAVE_GLYPH) + "\\s*([+-])([A-Za-z0-9_]{1,16})\\s*$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final SettingGroup sgDetection = settings.getDefaultGroup();
    private final SettingGroup sgResponse = settings.createGroup("Response");

    private final Setting<List<String>> adminRoles = sgDetection.add(new StringListSetting.Builder()
        .name("admin-roles")
        .description("Role names shown in the tab list that are treated as admins. The admin name automatically matches this server's ADMIN badge.")
        .defaultValue("admin", "owner")
        .build()
    );

    private final Setting<List<String>> admins = sgDetection.add(new StringListSetting.Builder()
        .name("admins")
        .description("Optional admin names or UUIDs to monitor when their role is not visible.")
        .build()
    );

    private final Setting<Integer> vanishConfirmationTicks = sgDetection.add(new IntSetting.Builder()
        .name("vanish-confirmation-ticks")
        .description("Ticks an admin must be absent from both the tab list and world before being marked as vanished. Only an explicit logout notification releases the module lock.")
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> nearbyRange = sgDetection.add(new IntSetting.Builder()
        .name("nearby-range")
        .description("Distance used to warn that a visible or partially vanished admin is nearby. A fully vanished admin can only use their last known distance.")
        .defaultValue(64)
        .range(1, 256)
        .sliderRange(16, 128)
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
        removeLegacyAdminGlyphRole();
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
        if (!lockedDown) return null;

        long vanished = activeAdmins.values().stream().filter(admin -> admin.state == Presence.Vanished).count();
        long nearby = activeAdmins.values().stream().filter(admin -> admin.state == Presence.Vanished && admin.isNearby(nearbyRange.get())).count();
        if (nearby > 0) return "VANISHED NEAR " + nearby;
        if (vanished > 0) return "VANISHED " + vanished;
        return "LOCKED " + activeAdmins.size();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet) {
            boolean added = packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER);
            boolean displayNameUpdated = packet.getActions().contains(PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME);
            if (!added && !displayNameUpdated) return;

            for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
                String name = entry.profile() != null ? entry.profile().name() : getKnownPlayerName(entry.profileId());
                if (name.isBlank()) name = extractPlayerName(entry.displayName());

                SignalType type = displayNameUpdated ? SignalType.RoleUpdated : SignalType.Added;
                pendingSignals.add(new AdminListSignal(entry.profileId(), name, entry.displayName(), type));
            }
        } else if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            for (UUID uuid : packet.profileIds()) {
                pendingSignals.add(new AdminListSignal(uuid, getKnownPlayerName(uuid), null, SignalType.Removed));
            }
        }
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        Matcher matcher = SERVER_JOIN_LEAVE_PATTERN.matcher(event.getMessage().getString());
        if (!matcher.matches()) return;

        String name = matcher.group(2);
        SignalType type = matcher.group(1).equals("+") ? SignalType.Joined : SignalType.Left;
        pendingSignals.add(new AdminListSignal(Uuids.getOfflinePlayerUuid(name), name, null, type));
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
            if (signal.type() == SignalType.Left) {
                UUID uuid = signal.uuid();
                String name = signal.name();
                activeAdmins.entrySet().removeIf(entry ->
                    entry.getKey().equals(uuid) || entry.getValue().name.equalsIgnoreCase(name)
                );
                continue;
            }

            boolean tracked = activeAdmins.containsKey(signal.uuid());
            boolean roleDetected = signal.displayName() != null && hasAdminRole(signal.name(), signal.displayName(), null);
            if (!tracked && !isConfiguredAdmin(signal.uuid(), signal.name()) && !roleDetected) continue;

            AdminPresence presence = activeAdmins.get(signal.uuid());
            if (presence == null) {
                presence = new AdminPresence(displayName(signal), Presence.Unknown);
                activeAdmins.put(signal.uuid(), presence);
            }

            if (!signal.name().isBlank()) presence.name = signal.name();
            presence.missingTicks = 0;
            if (signal.type() == SignalType.Removed) markVanished(presence);
            else presence.state = Presence.Online;
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
            markPresent(uuid, name, Presence.Online, Double.NaN, false);
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
            double distance = mc.player.distanceTo(player);
            markPresent(uuid, name, vanished ? Presence.Vanished : Presence.Online, distance, true);
        }

        return found;
    }

    private void expireMissingAdmins(Set<UUID> tabAdmins, Set<UUID> worldAdmins) {
        for (Map.Entry<UUID, AdminPresence> entry : activeAdmins.entrySet()) {
            if (tabAdmins.contains(entry.getKey()) || worldAdmins.contains(entry.getKey())) {
                entry.getValue().missingTicks = 0;
                continue;
            }

            entry.getValue().missingTicks++;
            if (entry.getValue().missingTicks >= vanishConfirmationTicks.get()) markVanished(entry.getValue());
        }
    }

    private void markPresent(UUID uuid, String name, Presence state, double distance, boolean worldEvidence) {
        AdminPresence presence = activeAdmins.get(uuid);

        if (presence == null) {
            presence = new AdminPresence(name, state);
            activeAdmins.put(uuid, presence);
        } else {
            if (!name.isBlank()) presence.name = name;
            presence.missingTicks = 0;
        }

        boolean wasNearby = presence.isNearby(nearbyRange.get());
        if (Double.isFinite(distance)) presence.lastDistance = distance;

        if (state == Presence.Vanished) {
            boolean firstVanishWarning = !presence.vanishNotified;
            presence.state = Presence.Vanished;

            if (firstVanishWarning) {
                presence.vanishNotified = true;
                warnVanished(presence);
            } else if (!wasNearby && presence.isNearby(nearbyRange.get())) {
                warning("Admin %s is still vanished and is now nearby (%.1f blocks). Modules remain disabled.", presence.name, presence.lastDistance);
            }
        } else {
            presence.state = Presence.Online;

            // Tab-list evidence alone is not enough to clear a vanish warning:
            // an invisible entity can still be listed. A visible world entity is.
            if (worldEvidence) presence.vanishNotified = false;
        }
    }

    private void markVanished(AdminPresence presence) {
        presence.state = Presence.Vanished;
        if (presence.vanishNotified) return;

        presence.vanishNotified = true;
        warnVanished(presence);
    }

    private void warnVanished(AdminPresence presence) {
        if (!Double.isFinite(presence.lastDistance)) {
            warning("Admin %s VANISHED; distance is unknown. Modules remain disabled until the logout notification.", presence.name);
        } else if (presence.isNearby(nearbyRange.get())) {
            warning("Admin %s VANISHED nearby (last known distance: %.1f blocks). Modules remain disabled until logout.", presence.name, presence.lastDistance);
        } else {
            warning("Admin %s VANISHED (last known distance: %.1f blocks). Modules remain disabled until logout.", presence.name, presence.lastDistance);
        }
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

        warning("Admin detected (%s). Disabled %d module%s; they will be restored only after a confirmed logout.", adminNames(), disabled, disabled == 1 ? "" : "s");
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
            info("All monitored admins confirmed logged out. Restored %d module%s.", restored, restored == 1 ? "" : "s");
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

    private String getKnownPlayerName(UUID uuid) {
        if (mc.getNetworkHandler() == null) return "";

        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(uuid);
        return entry == null || entry.getProfile() == null ? "" : entry.getProfile().name();
    }

    private static String extractPlayerName(Text displayName) {
        if (displayName == null) return "";

        Matcher matcher = USERNAME_PATTERN.matcher(displayName.getString());
        String name = "";
        while (matcher.find()) name = matcher.group();
        return name;
    }

    private boolean hasAdminRole(PlayerListEntry entry) {
        String playerName = entry.getProfile().name();
        Team team = entry.getScoreboardTeam();

        // PlayerListEntry#getScoreboardTeam is the same team source used by the
        // vanilla tab list. The world scoreboard is kept as a fallback because
        // some servers send the team packet separately from the player list.
        if (team == null) team = getScoreboardTeam(playerName);

        return hasAdminRole(playerName, entry.getDisplayName(), team);
    }

    private boolean hasAdminRole(PlayerEntity player) {
        String name = player.getGameProfile().name();
        Team team = player.getScoreboardTeam();
        if (team == null) team = getScoreboardTeam(name);

        return hasAdminRole(name, player.getDisplayName(), team);
    }

    private boolean hasAdminRole(String playerName, Text displayName, Team team) {
        StringBuilder decorations = new StringBuilder();
        appendText(decorations, displayName);

        if (team != null) {
            appendText(decorations, team.getPrefix());
            appendText(decorations, team.getSuffix());

            // A few permission/tab-list plugins use the team name rather than
            // the visible prefix to carry the rank.
            appendText(decorations, Text.literal(team.getName()));
        }

        return containsConfiguredRole(decorations.toString(), playerName);
    }

    private Team getScoreboardTeam(String playerName) {
        if (mc.world == null || mc.world.getScoreboard() == null) return null;
        return mc.world.getScoreboard().getScoreHolderTeam(playerName);
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
            if (role.equals("ADMIN") && decorations.contains(ADMIN_RANK_GLYPH)) return true;
            if (!role.isEmpty() && containsWholeRole(normalized, role)) return true;
        }

        return false;
    }

    private void removeLegacyAdminGlyphRole() {
        if (!adminRoles.get().contains(ADMIN_RANK_GLYPH)) return;

        List<String> roles = new ArrayList<>(adminRoles.get());
        roles.removeIf(ADMIN_RANK_GLYPH::equals);
        adminRoles.set(roles);
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
        if (!signal.name().isBlank()) return signal.name();
        if (signal.displayName() != null && !signal.displayName().getString().isBlank()) return signal.displayName().getString();
        return signal.uuid().toString();
    }

    private enum Presence {
        Online,
        Vanished,
        Unknown
    }

    private enum SignalType {
        Added,
        RoleUpdated,
        Joined,
        Removed,
        Left
    }

    private static class AdminPresence {
        private String name;
        private Presence state;
        private int missingTicks;
        private double lastDistance = Double.NaN;
        private boolean vanishNotified;

        private AdminPresence(String name, Presence state) {
            this.name = name;
            this.state = state;
        }

        private boolean isNearby(double range) {
            return Double.isFinite(lastDistance) && lastDistance <= range;
        }
    }

    private record AdminListSignal(UUID uuid, String name, Text displayName, SignalType type) {}
}
