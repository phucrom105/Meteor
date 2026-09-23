/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
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
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
    private static final int ADMIN_JOIN_COLOR = 0xFF55FF;
    private static final String SERVER_SPACING = "[\\s\\p{Z}]*";
    private static final Pattern SERVER_JOIN_LEAVE_PATTERN = Pattern.compile("^" + SERVER_SPACING + Pattern.quote(SERVER_JOIN_LEAVE_GLYPH) + SERVER_SPACING + "([+-])" + SERVER_SPACING + "([A-Za-z0-9_]{1,16})" + SERVER_SPACING + "$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final SettingGroup sgDetection = settings.getDefaultGroup();
    private final SettingGroup sgResponse = settings.createGroup("Response");

    private final Setting<List<String>> adminRoles = sgDetection.add(new StringListSetting.Builder()
        .name("admin-roles")
        .description("Role names shown in the tab list that are treated as admins. ADMIN also matches this server's badge and purple join name.")
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
        .description("Grace period after an admin disappears from the tab list or world before marking them vanished, allowing the server logout notification to arrive.")
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
    private final Set<UUID> confirmedLoggedOutIds = new HashSet<>();
    private final Set<String> confirmedLoggedOutNames = new HashSet<>();
    private final Set<String> observedPlayerNames = new HashSet<>();
    private final Map<String, AdminStatus> adminStatuses = new LinkedHashMap<>();

    private boolean lockedDown;
    private boolean observationReady;

    public AntiAdmin() {
        super(Categories.Dava, "anti-admin", "Suspends active modules while an admin role is online or vanished.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton showStatus = list.add(theme.button("Show Admin Status")).expandX().widget();
        WTable statusTable = list.add(theme.table()).expandX().widget();

        showStatus.action = () -> fillAdminStatusTable(theme, statusTable);
        return list;
    }

    @Override
    public void onActivate() {
        removeLegacyAdminGlyphRole();
        activeAdmins.clear();
        pendingSignals.clear();
        suspendedModules.clear();
        confirmedLoggedOutIds.clear();
        confirmedLoggedOutNames.clear();
        observedPlayerNames.clear();
        adminStatuses.clear();
        lockedDown = false;
        observationReady = false;
    }

    @Override
    public void onDeactivate() {
        activeAdmins.clear();
        pendingSignals.clear();
        confirmedLoggedOutIds.clear();
        confirmedLoggedOutNames.clear();
        observedPlayerNames.clear();
        adminStatuses.clear();
        observationReady = false;
        restoreModules(false);
    }

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        restoreModules(false);
        activeAdmins.clear();
        pendingSignals.clear();
        confirmedLoggedOutIds.clear();
        confirmedLoggedOutNames.clear();
        observedPlayerNames.clear();
        adminStatuses.clear();
        observationReady = false;
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
                pendingSignals.add(new AdminListSignal(entry.profileId(), name, entry.displayName(), type, false));
            }
        } else if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            for (UUID uuid : packet.profileIds()) {
                pendingSignals.add(new AdminListSignal(uuid, getKnownPlayerName(uuid), null, SignalType.Removed, false));
            }
        }
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        Matcher matcher = SERVER_JOIN_LEAVE_PATTERN.matcher(event.getMessage().getString());
        if (!matcher.matches()) return;

        String name = matcher.group(2);
        SignalType type = matcher.group(1).equals("+") ? SignalType.Joined : SignalType.Left;
        boolean adminRoleHint = hasAdminRole(name, event.getMessage(), null)
            || type == SignalType.Joined
                && isRoleConfigured("ADMIN")
                && hasAdminJoinColor(event.getMessage(), matcher.start(2), matcher.end(2));
        pendingSignals.add(new AdminListSignal(Uuids.getOfflinePlayerUuid(name), name, null, type, adminRoleHint));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate() || mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (!observationReady) {
            observeCurrentPlayers();
            observationReady = true;
        }

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
        List<AdminListSignal> signals = new ArrayList<>();
        AdminListSignal signal;
        while ((signal = pendingSignals.poll()) != null) signals.add(signal);

        // Non-logout signals prove that a player was visible to this client.
        // Record them before processing logout chat so packet/chat ordering
        // cannot make a normal player look like a previously vanished admin.
        for (AdminListSignal queued : signals) {
            if (queued.type() != SignalType.Left) rememberObservedPlayer(queued.name());
        }

        for (AdminListSignal queued : signals) {
            signal = queued;
            if (signal.type() == SignalType.Left) {
                boolean tracked = findActiveAdmin(signal.uuid(), signal.name()) != null;
                boolean configured = isConfiguredAdmin(signal.uuid(), signal.name());
                boolean hiddenLogout = observationReady && !wasPlayerObserved(signal.name());

                if (tracked || configured || signal.adminRoleHint() || hiddenLogout) {
                    confirmLogout(signal.uuid(), signal.name());
                    if (hiddenLogout && !tracked && !configured) {
                        info("Previously unseen player %s logged out; detected as an admin who was already vanished when you joined.", signal.name());
                    }
                }
                continue;
            }

            if (signal.type() == SignalType.Joined || signal.type() == SignalType.Added) {
                clearConfirmedLogout(signal.uuid(), signal.name());
            } else if (signal.type() == SignalType.Removed && isConfirmedLoggedOut(signal.uuid(), signal.name())) {
                continue;
            }

            Map.Entry<UUID, AdminPresence> trackedEntry = findActiveAdmin(signal.uuid(), signal.name());
            boolean tracked = trackedEntry != null;
            boolean roleDetected = signal.adminRoleHint()
                || signal.displayName() != null && hasAdminRole(signal.name(), signal.displayName(), null);
            if (!tracked && !isConfiguredAdmin(signal.uuid(), signal.name()) && !roleDetected) continue;

            AdminPresence presence = tracked ? trackedEntry.getValue() : null;
            if (presence == null) {
                presence = new AdminPresence(displayName(signal), Presence.Unknown);
                activeAdmins.put(signal.uuid(), presence);
            } else if (!trackedEntry.getKey().equals(signal.uuid()) && signal.type() != SignalType.Joined) {
                activeAdmins.remove(trackedEntry.getKey());
                activeAdmins.put(signal.uuid(), presence);
            }

            if (!signal.name().isBlank()) presence.name = signal.name();
            presence.missingTicks = 0;
            if (signal.type() == SignalType.Removed) {
                // A normal logout removes the player from the tab list before the
                // server chat notification can reach us. Keep the lockdown, but
                // wait before calling it a vanish so that notification can win.
                if (presence.state != Presence.Vanished) {
                    presence.awaitingVanishConfirmation = true;
                    presence.vanishCandidateTicks = 0;
                }
            } else {
                presence.awaitingVanishConfirmation = false;
                presence.vanishCandidateTicks = 0;
                presence.state = Presence.Online;
                rememberAdminStatus(presence.name, Presence.Online);
            }
        }
    }

    private Set<UUID> scanTabList() {
        Set<UUID> found = new HashSet<>();

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() == null) continue;

            UUID uuid = entry.getProfile().id();
            String name = entry.getProfile().name();
            rememberObservedPlayer(name);
            if (isConfirmedLoggedOut(uuid, name)) continue;
            if (findActiveAdmin(uuid, name) == null && !isConfiguredAdmin(uuid, name) && !hasAdminRole(entry)) continue;

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
            rememberObservedPlayer(name);
            if (isConfirmedLoggedOut(uuid, name)) continue;
            Map.Entry<UUID, AdminPresence> trackedEntry = findActiveAdmin(uuid, name);
            if (trackedEntry == null && !isConfiguredAdmin(uuid, name) && !hasAdminRole(player)) continue;

            found.add(uuid);
            boolean vanished = player.isInvisible() || !tabAdmins.contains(uuid);
            if (trackedEntry != null && trackedEntry.getValue().awaitingVanishConfirmation) vanished = false;
            double distance = mc.player.distanceTo(player);
            markPresent(uuid, name, vanished ? Presence.Vanished : Presence.Online, distance, true);
        }

        return found;
    }

    private void expireMissingAdmins(Set<UUID> tabAdmins, Set<UUID> worldAdmins) {
        for (Map.Entry<UUID, AdminPresence> entry : activeAdmins.entrySet()) {
            AdminPresence presence = entry.getValue();

            if (tabAdmins.contains(entry.getKey())) {
                presence.missingTicks = 0;
                presence.awaitingVanishConfirmation = false;
                presence.vanishCandidateTicks = 0;
                continue;
            }

            if (presence.awaitingVanishConfirmation) {
                presence.vanishCandidateTicks++;
                if (presence.vanishCandidateTicks >= vanishConfirmationTicks.get()) markVanished(presence);
                continue;
            }

            if (worldAdmins.contains(entry.getKey())) {
                presence.missingTicks = 0;
                continue;
            }

            presence.missingTicks++;
            if (presence.missingTicks >= vanishConfirmationTicks.get()) markVanished(presence);
        }
    }

    private void markPresent(UUID uuid, String name, Presence state, double distance, boolean worldEvidence) {
        Map.Entry<UUID, AdminPresence> trackedEntry = findActiveAdmin(uuid, name);
        AdminPresence presence = trackedEntry == null ? null : trackedEntry.getValue();

        if (presence == null) {
            presence = new AdminPresence(name, state);
            activeAdmins.put(uuid, presence);
        } else {
            if (!trackedEntry.getKey().equals(uuid)) {
                activeAdmins.remove(trackedEntry.getKey());
                activeAdmins.put(uuid, presence);
            }
            if (!name.isBlank()) presence.name = name;
            presence.missingTicks = 0;
        }

        if (!worldEvidence) {
            presence.awaitingVanishConfirmation = false;
            presence.vanishCandidateTicks = 0;
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

        rememberAdminStatus(presence.name, presence.state);
    }

    private void markVanished(AdminPresence presence) {
        presence.awaitingVanishConfirmation = false;
        presence.vanishCandidateTicks = 0;
        presence.state = Presence.Vanished;
        rememberAdminStatus(presence.name, Presence.Vanished);
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

    private Map.Entry<UUID, AdminPresence> findActiveAdmin(UUID uuid, String name) {
        AdminPresence byUuid = activeAdmins.get(uuid);
        if (byUuid != null) return Map.entry(uuid, byUuid);
        if (name == null || name.isBlank()) return null;

        for (Map.Entry<UUID, AdminPresence> entry : activeAdmins.entrySet()) {
            if (entry.getValue().name.equalsIgnoreCase(name)) return entry;
        }

        return null;
    }

    private void confirmLogout(UUID uuid, String name) {
        confirmedLoggedOutIds.add(uuid);
        if (name != null && !name.isBlank()) confirmedLoggedOutNames.add(normalizeName(name));

        Set<String> loggedOutNames = new HashSet<>();
        if (name != null && !name.isBlank()) loggedOutNames.add(name);
        activeAdmins.entrySet().removeIf(entry -> {
            boolean matches = entry.getKey().equals(uuid)
                || name != null && entry.getValue().name.equalsIgnoreCase(name);
            if (matches) {
                confirmedLoggedOutIds.add(entry.getKey());
                loggedOutNames.add(entry.getValue().name);
            }
            return matches;
        });

        for (String loggedOutName : loggedOutNames) {
            rememberAdminStatus(loggedOutName, Presence.LoggedOut);
        }
    }

    private void clearConfirmedLogout(UUID uuid, String name) {
        confirmedLoggedOutIds.remove(uuid);
        if (name != null && !name.isBlank()) confirmedLoggedOutNames.remove(normalizeName(name));
    }

    private boolean isConfirmedLoggedOut(UUID uuid, String name) {
        return confirmedLoggedOutIds.contains(uuid)
            || name != null && !name.isBlank() && confirmedLoggedOutNames.contains(normalizeName(name));
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private void observeCurrentPlayers() {
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() != null) rememberObservedPlayer(entry.getProfile().name());
        }

        for (PlayerEntity player : mc.world.getPlayers()) {
            rememberObservedPlayer(player.getGameProfile().name());
        }
    }

    private void rememberObservedPlayer(String name) {
        if (name != null && !name.isBlank()) observedPlayerNames.add(normalizeName(name));
    }

    private boolean wasPlayerObserved(String name) {
        return name == null || name.isBlank() || observedPlayerNames.contains(normalizeName(name));
    }

    private void rememberAdminStatus(String name, Presence state) {
        if (name == null || name.isBlank() || state == Presence.Unknown) return;
        adminStatuses.put(normalizeName(name), new AdminStatus(name, state));
    }

    private void fillAdminStatusTable(GuiTheme theme, WTable table) {
        table.clear();
        List<AdminStatus> statuses = new ArrayList<>(adminStatuses.values());

        for (String configured : admins.get()) {
            if (configured == null) continue;

            String name = configured.trim();
            if (name.isEmpty() || hasStatusForConfiguredAdmin(name)) continue;
            statuses.add(new AdminStatus(name, Presence.Unknown));
        }

        statuses.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        if (statuses.isEmpty()) {
            table.add(theme.label("No admin information detected yet."));
            return;
        }

        for (AdminStatus status : statuses) {
            table.add(theme.label(status.name() + ": " + statusText(status.state())));
            table.row();
        }
    }

    private boolean hasStatusForConfiguredAdmin(String value) {
        if (adminStatuses.containsKey(normalizeName(value))) return true;

        try {
            UUID uuid = UUID.fromString(value);
            return activeAdmins.containsKey(uuid) || confirmedLoggedOutIds.contains(uuid);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String statusText(Presence state) {
        return switch (state) {
            case Online -> "LOGGED IN";
            case Vanished -> "VANISHED";
            case LoggedOut -> "LOGGED OUT";
            case Unknown -> "UNKNOWN";
        };
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

    private static boolean hasAdminJoinColor(Text message, int nameStart, int nameEnd) {
        int[] cursor = { 0 };
        int[] coloredNameCharacters = { 0 };

        message.visit((style, string) -> {
            int partStart = cursor[0];
            int partEnd = partStart + string.length();
            int overlapStart = Math.max(partStart, nameStart);
            int overlapEnd = Math.min(partEnd, nameEnd);

            if (overlapStart < overlapEnd && style.getColor() != null && style.getColor().getRgb() == ADMIN_JOIN_COLOR) {
                coloredNameCharacters[0] += overlapEnd - overlapStart;
            }

            cursor[0] = partEnd;
            return java.util.Optional.empty();
        }, Style.EMPTY);

        return coloredNameCharacters[0] == nameEnd - nameStart;
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

    private boolean isRoleConfigured(String roleName) {
        String expected = normalize(roleName);
        return adminRoles.get().stream().anyMatch(role -> normalize(role).equals(expected));
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
        LoggedOut,
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
        private int vanishCandidateTicks;
        private double lastDistance = Double.NaN;
        private boolean vanishNotified;
        private boolean awaitingVanishConfirmation;

        private AdminPresence(String name, Presence state) {
            this.name = name;
            this.state = state;
        }

        private boolean isNearby(double range) {
            return Double.isFinite(lastDistance) && lastDistance <= range;
        }
    }

    private record AdminListSignal(UUID uuid, String name, Text displayName, SignalType type, boolean adminRoleHint) {}

    private record AdminStatus(String name, Presence state) {}
}
