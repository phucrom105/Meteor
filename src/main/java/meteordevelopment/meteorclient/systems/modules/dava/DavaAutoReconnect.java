/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Dava's reconnect helper. It deliberately has a different module name and class from the
 * original Misc AutoReconnect module.
 */
public class DavaAutoReconnect extends Module {
    private static final String SECRET_TAG = "dava-login-secret";
    private static final int GCM_IV_LENGTH = 12;
    private static final int RECONNECT_TICKS_PER_SECOND = 20;
    private static final int TRANSFER_TIMEOUT_TICKS = 200;

    private final SettingGroup sgReconnect = settings.createGroup("Reconnect");
    private final SettingGroup sgAutoJoin = settings.createGroup("Auto Join");
    private final SettingGroup sgLogin = settings.createGroup("Login");

    private final Setting<Double> reconnectDelay = sgReconnect.add(new DoubleSetting.Builder()
        .name("delay")
        .description("Seconds to wait before reconnecting to the last server.")
        .defaultValue(3.5)
        .min(0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Boolean> autoJoin = sgAutoJoin.add(new BoolSetting.Builder()
        .name("auto-join")
        .description("Automatically navigates the server menus after joining the lobby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> targetServer = sgAutoJoin.add(new StringSetting.Builder()
        .name("target-server")
        .description("Server name to select from the detected server menu. Use an empty value to choose manually.")
        .defaultValue("Skyblock II")
        .build()
    );

    private final Setting<Integer> menuDelay = sgAutoJoin.add(new IntSetting.Builder()
        .name("menu-delay")
        .description("Ticks to wait between opening each server menu.")
        .defaultValue(10)
        .min(0)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> announceServers = sgAutoJoin.add(new BoolSetting.Builder()
        .name("announce-servers")
        .description("Prints the server entries detected in the server menu so you can choose another target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> retryWhenFull = sgAutoJoin.add(new BoolSetting.Builder()
        .name("retry-when-full")
        .description("Retries the selected server when it is full or does not accept the transfer.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> fullRetryDelay = sgAutoJoin.add(new IntSetting.Builder()
        .name("full-retry-delay")
        .description("Seconds to wait before retrying a full server.")
        .defaultValue(5)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> autoLogin = sgLogin.add(new BoolSetting.Builder()
        .name("auto-login")
        .description("Automatically sends the login command when the server asks for authentication.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> loginSecret = sgLogin.add(new StringSetting.Builder()
        .name("password-token")
        .description("Password or login token. It is masked in the GUI and stored encrypted in the module configuration.")
        .defaultValue("")
        .placeholder("Password / token")
        .renderer(SecretRenderer.class)
        .build()
    );

    private final Setting<Boolean> rememberSecret = sgLogin.add(new BoolSetting.Builder()
        .name("remember-password-token")
        .description("Keeps the login password/token between launches. Disable to keep it only in memory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> loginCommand = sgLogin.add(new StringSetting.Builder()
        .name("login-command")
        .description("Command sent for login. Supported placeholders: {password}, {token}, and {username}.")
        .defaultValue("/login {password}")
        .build()
    );

    private final Setting<Integer> loginDelay = sgLogin.add(new IntSetting.Builder()
        .name("login-delay")
        .description("Ticks to wait after a login prompt before sending the command.")
        .defaultValue(2)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private Pair<ServerAddress, ServerInfo> lastServerConnection;
    private int reconnectTicks = -1;
    private int loginCooldown;
    private int pendingLoginTicks;
    private boolean pendingLogin;

    private JoinStage joinStage = JoinStage.IDLE;
    private int joinTicks;
    private boolean joiningTarget;
    private Screen lastMenuScreen;
    private Screen serverSelectionScreen;
    private String serverSelectionTitle;
    private boolean serversAnnounced;
    private final Set<String> discoveredServers = new LinkedHashSet<>();

    public DavaAutoReconnect() {
        super(Categories.Dava, "dava-auto-reconnect", "Reconnects, logs in, and navigates server menus automatically.", "auto-reconnect-dava", "auto-join-server");

        // The reconnect action must still receive ticks while the client is on DisconnectedScreen.
        runInMainMenu = true;
        MeteorClient.EVENT_BUS.subscribe(new StaticListener());
    }

    @Override
    public void onActivate() {
        reconnectTicks = -1;
        loginCooldown = 0;
        pendingLogin = false;
        joiningTarget = false;

        if (autoJoin.get() && Utils.canUpdate()) beginAutoJoin();
        else joinStage = JoinStage.IDLE;
    }

    @Override
    public void onDeactivate() {
        pendingLogin = false;
        joiningTarget = false;
        joinStage = JoinStage.IDLE;
        lastMenuScreen = null;
        serverSelectionScreen = null;
        serverSelectionTitle = null;
        discoveredServers.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (loginCooldown > 0) loginCooldown--;

        if (pendingLogin) {
            if (pendingLoginTicks > 0) pendingLoginTicks--;
            else sendLogin();
        }

        if (mc.currentScreen instanceof DisconnectedScreen) {
            handleReconnect();
            return;
        }

        if (!Utils.canUpdate() || !autoJoin.get()) return;
        handleAutoJoin();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (joiningTarget) {
            joiningTarget = false;
            joinStage = JoinStage.IDLE;
            lastMenuScreen = null;
            serverSelectionScreen = null;
            serverSelectionTitle = null;
            return;
        }

        if (autoJoin.get()) beginAutoJoin();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.screen instanceof DisconnectedScreen) {
            reconnectTicks = secondsToTicks(reconnectDelay.get());
            joinStage = JoinStage.IDLE;
            joiningTarget = false;
            serverSelectionScreen = null;
            serverSelectionTitle = null;
        }
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        String message = event.getMessage().getString();
        if ((joiningTarget || joinStage == JoinStage.FIND_CONFIRMATION || joinStage == JoinStage.WAITING_FOR_TRANSFER)
            && isServerFullMessage(message)) {
            handleServerFull();
            return;
        }

        if (!autoLogin.get() || loginSecret.get().isBlank() || loginCooldown > 0 || pendingLogin) return;
        if (!isLoginPrompt(message)) return;

        pendingLogin = true;
        pendingLoginTicks = loginDelay.get();
    }

    /**
     * Remembers a command such as /login password or /auth token without ever writing it to chat.
     * The actual value is persisted by toTag() in encrypted form.
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!rememberSecret.get() || !(event.packet instanceof ChatCommandSignedC2SPacket packet)) return;

        String secret = extractLoginSecret(packet.command());
        if (secret != null && !secret.isBlank()) loginSecret.set(secret);
    }

    private void handleReconnect() {
        if (lastServerConnection == null) return;
        if (reconnectTicks < 0) reconnectTicks = secondsToTicks(reconnectDelay.get());
        if (reconnectTicks > 0) {
            reconnectTicks--;
            return;
        }

        reconnectTicks = -1;
        ConnectScreen.connect(new TitleScreen(), mc, lastServerConnection.left(), lastServerConnection.right(), false, null);
    }

    private void beginAutoJoin() {
        joinStage = JoinStage.FIND_SERVER_SELECTOR;
        joinTicks = menuDelay.get();
        joiningTarget = false;
        lastMenuScreen = null;
        serverSelectionScreen = null;
        serverSelectionTitle = null;
        serversAnnounced = false;
        discoveredServers.clear();
    }

    private void handleAutoJoin() {
        if (joinStage == JoinStage.IDLE) return;

        if (joinTicks > 0) {
            joinTicks--;
            return;
        }

        if (joinStage == JoinStage.WAITING_FOR_TRANSFER) {
            if (retryWhenFull.get()) {
                warning("The selected server did not open a new world; retrying in %d seconds.", fullRetryDelay.get());
                scheduleFullRetry();
            } else {
                warning("The selected server did not open a new world; stopping auto join.");
                stopAutoJoin();
            }
            return;
        }

        switch (joinStage) {
            case FIND_SERVER_SELECTOR -> findServerSelector();
            case FIND_SKYBLOCK -> findSkyblockMode();
            case FIND_SERVER -> findTargetServer();
            case FIND_CONFIRMATION -> findConfirmation();
            case RETRY_FULL -> retryFullServer();
            case IDLE, WAITING_FOR_TRANSFER -> {
            }
        }
    }

    private void findServerSelector() {
        HandledScreen<?> screen = getHandledScreen();
        if (screen != null) {
            Slot menuSelector = findSlot(screen.getScreenHandler(), DavaAutoReconnect::isServerSelector, true);
            if (menuSelector != null) {
                rightClick(menuSelector, screen.getScreenHandler());
                advanceTo(JoinStage.FIND_SKYBLOCK);
                return;
            }

            // The selector can be a compass or a custom-named item such as "CHỌN MÁY CHỦ".
            // In the normal player inventory it must be used with the right mouse button.
            if ((screen instanceof InventoryScreen || !hasContainerSlots(screen.getScreenHandler())) && useServerSelector()) {
                advanceTo(JoinStage.FIND_SKYBLOCK);
            }
            return;
        }

        if (useServerSelector()) advanceTo(JoinStage.FIND_SKYBLOCK);
    }

    private void findSkyblockMode() {
        HandledScreen<?> screen = getHandledScreen();
        if (screen == null) return;

        Slot skyblock = findSlot(screen.getScreenHandler(), stack -> containsNormalized(stackText(stack), "skyblock"), true);
        if (skyblock == null) return;

        click(skyblock, screen.getScreenHandler());
        advanceTo(JoinStage.FIND_SERVER);
    }

    private void findTargetServer() {
        HandledScreen<?> screen = getHandledScreen();
        if (screen == null) return;

        if (screen != lastMenuScreen) {
            lastMenuScreen = screen;
            serversAnnounced = false;
            discoveredServers.clear();
        }

        List<MenuOption> options = findServerOptions(screen.getScreenHandler());
        if (options.isEmpty()) return;

        if (announceServers.get() && !serversAnnounced) {
            discoveredServers.addAll(options.stream().map(MenuOption::label).toList());
            info("Detected server entries: %s. Change target-server to choose another one.", String.join(", ", discoveredServers));
            serversAnnounced = true;
        }

        String target = normalize(targetServer.get());
        if (target.isBlank()) return;

        for (MenuOption option : options) {
            if (!option.searchableText().contains(target)) continue;

            if (isServerFullText(option.searchableText())) {
                handleServerFull();
                return;
            }

            click(option.slot(), screen.getScreenHandler());
            joiningTarget = true;
            serverSelectionScreen = screen;
            serverSelectionTitle = normalize(screen.getTitle().getString());
            joinStage = JoinStage.FIND_CONFIRMATION;
            joinTicks = menuDelay.get();
            return;
        }
    }

    private void retryFullServer() {
        HandledScreen<?> screen = getHandledScreen();
        if (screen == null || screen instanceof InventoryScreen) {
            beginAutoJoin();
            return;
        }

        String currentTitle = normalize(screen.getTitle().getString());
        boolean selectionMenu = screen == serverSelectionScreen
            && currentTitle.equals(serverSelectionTitle == null ? "" : serverSelectionTitle);

        if (!selectionMenu) {
            Slot door = findSlot(screen.getScreenHandler(), DavaAutoReconnect::isDoor, true);
            if (door != null) {
                click(door, screen.getScreenHandler());
                joiningTarget = true;
                joinStage = JoinStage.WAITING_FOR_TRANSFER;
                joinTicks = TRANSFER_TIMEOUT_TICKS;
                return;
            }
        }

        if (!findServerOptions(screen.getScreenHandler()).isEmpty()) {
            joinStage = JoinStage.FIND_SERVER;
            joinTicks = menuDelay.get();
            lastMenuScreen = null;
            serversAnnounced = false;
            discoveredServers.clear();
            return;
        }

        // A confirmation menu can take a few ticks to populate its door item.
        joinStage = JoinStage.FIND_CONFIRMATION;
        joinTicks = menuDelay.get();
        serverSelectionScreen = null;
        serverSelectionTitle = null;
    }

    private void handleServerFull() {
        if (!retryWhenFull.get()) {
            warning("The selected server is full; stopping auto join.");
            stopAutoJoin();
            return;
        }

        warning("The selected server is full; retrying in %d seconds.", fullRetryDelay.get());
        scheduleFullRetry();
    }

    private void scheduleFullRetry() {
        joiningTarget = false;
        joinStage = JoinStage.RETRY_FULL;
        joinTicks = secondsToTicks(fullRetryDelay.get());
    }

    private void stopAutoJoin() {
        joiningTarget = false;
        joinStage = JoinStage.IDLE;
        serverSelectionScreen = null;
        serverSelectionTitle = null;
    }

    private void findConfirmation() {
        HandledScreen<?> screen = getHandledScreen();
        if (screen == null) return;

        // The server menu opens a second confirmation screen after the selected server is clicked.
        // Wait until that screen is different from the selection menu before looking for the door;
        // otherwise the target door in the first menu could be clicked twice.
        String currentTitle = normalize(screen.getTitle().getString());
        boolean confirmationOpened = screen != serverSelectionScreen
            || !currentTitle.equals(serverSelectionTitle == null ? "" : serverSelectionTitle);
        if (!confirmationOpened) return;

        Slot door = findSlot(screen.getScreenHandler(), DavaAutoReconnect::isDoor, true);
        if (door == null) return;

        click(door, screen.getScreenHandler());
        joinStage = JoinStage.WAITING_FOR_TRANSFER;
        joinTicks = TRANSFER_TIMEOUT_TICKS;
    }

    private List<MenuOption> findServerOptions(ScreenHandler handler) {
        List<MenuOption> options = new ArrayList<>();

        for (Slot slot : handler.slots) {
            if (!isContainerSlot(slot) || !slot.hasStack() || isMenuDecoration(slot.getStack())) continue;

            ItemStack stack = slot.getStack();
            String searchableText = normalize(stackText(stack));
            if (searchableText.isBlank()) continue;

            options.add(new MenuOption(slot, menuLabel(stack), searchableText));
        }

        return options;
    }

    private void advanceTo(JoinStage nextStage) {
        joinStage = nextStage;
        joinTicks = menuDelay.get();
        lastMenuScreen = null;
        serversAnnounced = false;
        discoveredServers.clear();
    }

    private void click(Slot slot, ScreenHandler handler) {
        click(slot, handler, 0);
    }

    private void rightClick(Slot slot, ScreenHandler handler) {
        click(slot, handler, 1);
    }

    private void click(Slot slot, ScreenHandler handler, int button) {
        if (mc.interactionManager != null) {
            mc.interactionManager.clickSlot(handler.syncId, slot.id, button, SlotActionType.PICKUP, mc.player);
        }
    }

    private boolean useServerSelector() {
        if (mc.interactionManager == null || mc.player == null) return false;

        for (int i = 0; i < 9; i++) {
            if (!isServerSelector(mc.player.getInventory().getStack(i))) continue;

            if (mc.currentScreen != null) mc.currentScreen.close();
            mc.player.getInventory().setSelectedSlot(i);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            return true;
        }

        return false;
    }

    private HandledScreen<?> getHandledScreen() {
        return mc.currentScreen instanceof HandledScreen<?> screen ? screen : null;
    }

    private static Slot findSlot(ScreenHandler handler, Predicate<ItemStack> predicate, boolean containerOnly) {
        for (Slot slot : handler.slots) {
            if (containerOnly && !isContainerSlot(slot)) continue;
            if (slot.hasStack() && predicate.test(slot.getStack())) return slot;
        }

        return null;
    }

    private static boolean hasContainerSlots(ScreenHandler handler) {
        for (Slot slot : handler.slots) {
            if (isContainerSlot(slot)) return true;
        }

        return false;
    }

    private static boolean isContainerSlot(Slot slot) {
        return !(slot.inventory instanceof PlayerInventory);
    }

    private static boolean isServerSelector(ItemStack stack) {
        return stack.isOf(Items.COMPASS) || containsNormalized(stackText(stack), "chon may chu")
            || containsNormalized(stackText(stack), "choose server");
    }

    private static boolean isMenuDecoration(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String path = id.getPath();
        return path.equals("air") || path.equals("barrier") || path.equals("glass_pane") || path.endsWith("stained_glass_pane");
    }

    private static boolean isDoor(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getPath().endsWith("_door");
    }

    private static String stackText(ItemStack stack) {
        StringBuilder text = new StringBuilder(stack.getName().getString());

        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) text.append(' ').append(customName.getString());

        Text itemName = stack.get(DataComponentTypes.ITEM_NAME);
        if (itemName != null) text.append(' ').append(itemName.getString());

        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) text.append(' ').append(line.getString());
        }

        return text.toString();
    }

    private static String menuLabel(ItemStack stack) {
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) return customName.getString();

        Text itemName = stack.get(DataComponentTypes.ITEM_NAME);
        if (itemName != null) return itemName.getString();

        return stack.getName().getString();
    }

    private static boolean isLoginPrompt(String message) {
        String normalized = normalize(message);
        if (normalized.isBlank()) return false;

        if (normalized.contains("logged in") || normalized.contains("login thanh cong")
            || normalized.contains("da dang nhap") || normalized.contains("authenticated")) return false;

        return normalized.contains("please login")
            || normalized.contains("please log in")
            || normalized.contains("type login")
            || normalized.contains("use login")
            || normalized.contains("not logged")
            || normalized.contains("login required")
            || normalized.contains("password")
            || normalized.contains("mat khau")
            || normalized.contains("dang nhap")
            || normalized.contains("authenticate");
    }

    private static boolean isServerFullMessage(String message) {
        return isServerFullText(message);
    }

    private static boolean isServerFullText(String value) {
        String normalized = normalize(value);
        return normalized.contains("server is full")
            || normalized.contains("server full")
            || normalized.contains("server is currently full")
            || normalized.contains("full server")
            || normalized.contains("no available slots")
            || normalized.contains("no slots available")
            || normalized.contains("may chu da day")
            || normalized.contains("may chu day")
            || normalized.contains("khong con cho")
            || normalized.contains("het cho")
            || normalized.contains("da day") && (normalized.contains("may chu") || normalized.contains("server"));
    }

    private static String extractLoginSecret(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 2) return null;

        String name = parts[0].toLowerCase(Locale.ROOT);
        if (!name.equals("login") && !name.equals("l") && !name.equals("auth") && !name.equals("authenticate")) return null;

        return parts[1];
    }

    private void sendLogin() {
        pendingLogin = false;
        if (!autoLogin.get() || loginSecret.get().isBlank() || !Utils.canUpdate() || loginCooldown > 0) return;

        String command = loginCommand.get().trim();
        if (command.isBlank()) command = "/login {password}";

        command = command
            .replace("{password}", loginSecret.get())
            .replace("{token}", loginSecret.get())
            .replace("{username}", mc.getSession().getUsername());

        ChatUtils.sendPlayerMsg(command, false);
        loginCooldown = 40;
    }

    private static int secondsToTicks(double seconds) {
        return Math.max(0, (int) Math.ceil(seconds * RECONNECT_TICKS_PER_SECOND));
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder(decomposed.length());

        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            result.append(Character.isLetterOrDigit(c) ? c : ' ');
        }

        return result.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean containsNormalized(String value, String search) {
        return normalize(value).contains(normalize(search));
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        if (tag == null) return null;

        // StringSetting is still used so it renders normally in the settings GUI. Remove its
        // plaintext representation before saving and replace it with an encrypted module field.
        stripPlaintextSecret(tag);

        if (rememberSecret.get() && !loginSecret.get().isBlank()) {
            String encrypted = encryptSecret(loginSecret.get());
            if (!encrypted.isBlank()) tag.putString(SECRET_TAG, encrypted);
        } else {
            tag.remove(SECRET_TAG);
        }

        return tag;
    }

    @Override
    public DavaAutoReconnect fromTag(NbtCompound tag) {
        super.fromTag(tag);

        String encrypted = tag.getString(SECRET_TAG, "");
        if (!encrypted.isBlank()) {
            String secret = decryptSecret(encrypted);
            if (!secret.isBlank()) loginSecret.set(secret);
        }

        return this;
    }

    private static void stripPlaintextSecret(NbtCompound tag) {
        NbtCompound settingsTag = tag.getCompoundOrEmpty("settings");
        NbtList groups = settingsTag.getListOrEmpty("groups");

        for (NbtElement groupElement : groups) {
            if (!(groupElement instanceof NbtCompound group)) continue;

            NbtList settings = group.getListOrEmpty("settings");
            settings.removeIf(settingElement -> settingElement instanceof NbtCompound setting
                && setting.getString("name", "").equals("password-token"));
        }
    }

    private static String encryptSecret(String secret) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            MeteorClient.LOG.error("Unable to encrypt the Dava login secret.", e);
            return "";
        }
    }

    private static String decryptSecret(String encrypted) {
        try {
            byte[] payload = Base64.getDecoder().decode(encrypted);
            if (payload.length <= GCM_IV_LENGTH) return "";

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[payload.length - GCM_IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            MeteorClient.LOG.warn("Unable to decrypt the Dava login secret; it will be ignored.");
            return "";
        }
    }

    private static SecretKeySpec secretKey() throws GeneralSecurityException {
        String seed = System.getProperty("user.name", "") + "\u0000"
            + System.getProperty("user.home", "") + "\u0000"
            + System.getProperty("os.name", "") + "\u0000dava-auto-reconnect";

        byte[] key = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    public String getInfoString() {
        return targetServer.get();
    }

    private enum JoinStage {
        IDLE,
        FIND_SERVER_SELECTOR,
        FIND_SKYBLOCK,
        FIND_SERVER,
        FIND_CONFIRMATION,
        RETRY_FULL,
        WAITING_FOR_TRANSFER
    }

    private record MenuOption(Slot slot, String label, String searchableText) {
    }

    public static final class SecretRenderer implements WTextBox.Renderer {
        @Override
        public void render(GuiRenderer renderer, double x, double y, String text, meteordevelopment.meteorclient.utils.render.color.Color color) {
            renderer.text("*".repeat(text.length()), x, y, color, false);
        }
    }

    private class StaticListener {
        @EventHandler
        private void onServerConnect(ServerConnectBeginEvent event) {
            lastServerConnection = new ObjectObjectImmutablePair<>(event.address, event.info);
        }
    }
}
