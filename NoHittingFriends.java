package com.nothittingfriends;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class NotHittingFriends implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("not-hitting-friends");
    public static KeyBinding guiKeyBinding;

    @Override
    public void onInitializeClient() {
        LOGGER.info("âœ“ Not Hitting Friends mod initialized!");
        
        // Initialize data manager
        DataManager.initialize();
        FriendManager.initialize();
        EntityManager.initialize();
        
        // Register keybinding
        guiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nhf.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F,
                "category.nhf"
        ));
        
        // Tick event for keybinding
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (guiKeyBinding.wasPressed()) {
                MinecraftClient.getInstance().setScreen(new NHFScreen(null));
            }
        });
        
        // Register commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager
                    .literal("add-friend")
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(context -> {
                                String playerName = StringArgumentType.getString(context, "name");
                                FriendManager.addFriend(playerName);
                                DataManager.saveFriends();
                                MinecraftClient.getInstance().player.sendMessage(
                                        Text.literal("âœ“ Added " + playerName).formatted(Formatting.GREEN),
                                        false
                                );
                                return 1;
                            })));

            dispatcher.register(ClientCommandManager
                    .literal("remove-friend")
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(context -> {
                                String playerName = StringArgumentType.getString(context, "name");
                                FriendManager.removeFriend(playerName);
                                DataManager.saveFriends();
                                MinecraftClient.getInstance().player.sendMessage(
                                        Text.literal("âœ— Removed " + playerName).formatted(Formatting.RED),
                                        false
                                );
                                return 1;
                            })));

            dispatcher.register(ClientCommandManager
                    .literal("list-friends")
                    .executes(context -> {
                        Set<String> friends = FriendManager.getFriends();
                        MinecraftClient.getInstance().player.sendMessage(
                                Text.literal("Friends: " + (friends.isEmpty() ? "None" : String.join(", ", friends)))
                                        .formatted(Formatting.AQUA),
                                false
                        );
                        return 1;
                    }));
        });
        
        LOGGER.info("âœ“ All systems initialized!");
    }
}

// ============================================================================
// FRIEND MANAGER
// ============================================================================
class FriendManager {
    private static final Set<String> friends = new HashSet<>();

    public static void initialize() {
        DataManager.loadFriends();
    }

    public static void addFriend(String playerName) {
        friends.add(playerName.toLowerCase());
        NotHittingFriends.LOGGER.info("[NHF] Added friend: {}", playerName);
    }

    public static void removeFriend(String playerName) {
        friends.remove(playerName.toLowerCase());
        NotHittingFriends.LOGGER.info("[NHF] Removed friend: {}", playerName);
    }

    public static boolean isFriend(Entity entity) {
        if (!(entity instanceof PlayerEntity)) return false;
        PlayerEntity player = (PlayerEntity) entity;
        return friends.contains(player.getName().getString().toLowerCase());
    }

    public static Set<String> getFriends() {
        return new HashSet<>(friends);
    }

    public static void clearFriends() {
        friends.clear();
    }
}

// ============================================================================
// ENTITY MANAGER
// ============================================================================
class EntityManager {
    private static final Set<String> entities = new HashSet<>();

    public static void initialize() {
        DataManager.loadEntities();
    }

    public static void addEntity(String entityName) {
        entities.add(entityName.toLowerCase());
        NotHittingFriends.LOGGER.info("[NHF] Added entity: {}", entityName);
    }

    public static void removeEntity(String entityName) {
        entities.remove(entityName.toLowerCase());
        NotHittingFriends.LOGGER.info("[NHF] Removed entity: {}", entityName);
    }

    public static boolean isProtectedEntity(Entity entity) {
        String entityType = entity.getClass().getSimpleName().toLowerCase();
        return entities.stream().anyMatch(e -> entityType.contains(e));
    }

    public static Set<String> getEntities() {
        return new HashSet<>(entities);
    }

    public static void clearEntities() {
        entities.clear();
    }
}

// ============================================================================
// CROSSHAIR STATE
// ============================================================================
class CrosshairState {
    private static volatile boolean isFriendTargeted = false;
    private static volatile int friendColor = 0x0000FF; // Blue
    private static volatile int enemyColor = 0xFF0000;  // Red

    public static void setFriendTargeted(boolean targeted) {
        isFriendTargeted = targeted;
    }

    public static boolean isFriendTargeted() {
        return isFriendTargeted;
    }

    public static void setFriendColor(int color) {
        friendColor = color;
    }

    public static void setEnemyColor(int color) {
        enemyColor = color;
    }

    public static int getFriendColor() {
        return friendColor;
    }

    public static int getEnemyColor() {
        return enemyColor;
    }

    public static int getCurrentColor() {
        return isFriendTargeted ? friendColor : enemyColor;
    }
}

// ============================================================================
// DATA MANAGER (Persistence)
// ============================================================================
class DataManager {
    private static final Path MOD_DIR = Paths.get("config/not-hitting-friends");
    private static final Path FRIENDS_FILE = MOD_DIR.resolve("friends.txt");
    private static final Path ENTITIES_FILE = MOD_DIR.resolve("entities.txt");
    private static final Path CONFIG_FILE = MOD_DIR.resolve("config.txt");

    public static void initialize() {
        try {
            Files.createDirectories(MOD_DIR);
        } catch (IOException e) {
            NotHittingFriends.LOGGER.error("Failed to create mod directory", e);
        }
        loadConfig();
    }

    public static void loadFriends() {
        try {
            if (Files.exists(FRIENDS_FILE)) {
                List<String> lines = Files.readAllLines(FRIENDS_FILE);
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        FriendManager.addFriend(line.trim());
                    }
                }
                NotHittingFriends.LOGGER.info("[NHF] Loaded {} friends", lines.size());
            }
        } catch (IOException e) {
            NotHittingFriends.LOGGER.error("Failed to load friends", e);
        }
    }

    public static void saveFriends() {
        try {
            Files.write(FRIENDS_FILE, FriendManager.getFriends());
            NotHittingFriends.LOGGER.info("[NHF] Saved friends");
        } catch (IOException e) {
            NotHittingFriends.LOGGER.error("Failed to save friends", e);
        }
    }

    public static void loadEntities() {
        try {
            if (Files.exists(ENTITIES_FILE)) {
                List<String> lines = Files.readAllLines(ENTITIES_FILE);
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        EntityManager.addEntity(line.trim());
                    }
                }
                NotHittingFriends.LOGGER.info("[NHF] Loaded {} entities", lines.size());
            }
        } catch (IOException e) {
            NotHittingFriends.LOGGER.error("Failed to load entities", e);
        }
    }

    public static void saveEntities() {
        try {
            Files.write(ENTITIES_FILE, EntityManager.getEntities());
            NotHittingFriends.LOGGER.info("[NHF] Saved entities");
        } catch (IOException e) {
            NotHittingFriends.LOGGER.error("Failed to save entities", e);
        }
    }

    public static void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                List<String> lines = Files.readAllLines(CONFIG_FILE);
                for (String line : lines) {
                    if (line.startsWith("friend_color=")) {
                        String hex = line.substring(13);
                        CrosshairState.setFriendColor(Integer.parseInt(hex, 16));
                    } else if (line.startsWith("enemy_color=")) {
                        String hex = line.substring(12);
                        CrosshairState.setEnemyColor(Integer.parseInt(hex, 16));
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            NotHittingFriends.LOGGER.error("Failed to load config", e);
        }
    }

    public static void saveConfig() {
        try {
            List<String> lines = Arrays.asList(
                    "friend_color=" + String.format("%06X", CrosshairState.getFriendColor()),
                    "enemy_color=" + String.format("%06X", CrosshairState.getEnemyColor())
            );
            Files.write(CONFIG_FILE, lines);
            NotHittingFriends.LOGGER.info("[NHF] Saved config");
        } catch (IOException e) {
            NotHittingFriends.LOGGER.error("Failed to save config", e);
        }
    }
}

// ============================================================================
// GUI SCREEN
// ============================================================================
class NHFScreen extends net.minecraft.client.gui.screen.Screen {
    private static final int COLOR_WIDTH = 60;
    private static final int COLOR_HEIGHT = 20;
    private String currentTab = "friends"; // friends, entities, colors
    private String searchInput = "";
    private List<String> entitySuggestions = new ArrayList<>();
    private int scrollOffset = 0;

    public NHFScreen(net.minecraft.client.gui.screen.Screen parent) {
        super(Text.literal("Not Hitting Friends"));
    }

    @Override
    protected void init() {
        this.clearChildren();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Tab buttons
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                centerX - 150, 20, 80, 20,
                Text.literal("Friends"),
                btn -> currentTab = "friends"
        ));
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                centerX - 60, 20, 80, 20,
                Text.literal("Entities"),
                btn -> currentTab = "entities"
        ));
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                centerX + 30, 20, 80, 20,
                Text.literal("Colors"),
                btn -> currentTab = "colors"
        ));

        // Close button
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                this.width - 110, 20, 100, 20,
                Text.literal("Close"),
                btn -> this.onClose()
        ));
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        // Background
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x88000000, 0x88000000);

        super.render(guiGraphics, mouseX, mouseY, delta);

        if (currentTab.equals("friends")) {
            renderFriendsTab(guiGraphics, mouseX, mouseY);
        } else if (currentTab.equals("entities")) {
            renderEntitiesTab(guiGraphics, mouseX, mouseY);
        } else if (currentTab.equals("colors")) {
            renderColorsTab(guiGraphics, mouseX, mouseY);
        }
    }

    private void renderFriendsTab(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int y = 60;

        guiGraphics.drawCenteredTextWithShadow(this.textRenderer, "Friends List", centerX, y, 0xFFFFFF);
        y += 25;

        Set<String> friends = FriendManager.getFriends();
        for (String friend : friends) {
            guiGraphics.drawTextWithShadow(this.textRenderer, "â€¢ " + friend, centerX - 100, y, 0x00FFFF);
            this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                    centerX + 80, y - 2, 60, 16,
                    Text.literal("Remove"),
                    btn -> {
                        FriendManager.removeFriend(friend);
                        DataManager.saveFriends();
                    }
            ));
            y += 20;
        }

        y += 10;
        guiGraphics.drawTextWithShadow(this.textRenderer, "Add Friend:", centerX - 100, y, 0xFFFFFF);
        y += 20;
        this.addDrawableChild(new net.minecraft.client.gui.widget.TextFieldWidget(
                this.textRenderer, centerX - 100, y, 150, 20, Text.literal("Friend name")
        ));
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                centerX + 60, y, 40, 20,
                Text.literal("Add"),
                btn -> {
                    // Handle add friend
                }
        ));
    }

    private void renderEntitiesTab(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int y = 60;

        guiGraphics.drawCenteredTextWithShadow(this.textRenderer, "Protected Entities", centerX, y, 0xFFFFFF);
        y += 25;

        Set<String> entities = EntityManager.getEntities();
        for (String entity : entities) {
            guiGraphics.drawTextWithShadow(this.textRenderer, "â€¢ " + entity, centerX - 100, y, 0x00FF00);
            this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                    centerX + 80, y - 2, 60, 16,
                    Text.literal("Remove"),
                    btn -> {
                        EntityManager.removeEntity(entity);
                        DataManager.saveEntities();
                    }
            ));
            y += 20;
        }

        y += 10;
        guiGraphics.drawTextWithShadow(this.textRenderer, "Add Entity:", centerX - 100, y, 0xFFFFFF);
        y += 20;
        guiGraphics.drawTextWithShadow(this.textRenderer, "Search: " + searchInput, centerX - 100, y, 0xAAAAAA);
        y += 20;

        List<String> allEntities = getAllAvailableEntities();
        List<String> filtered = allEntities.stream()
                .filter(e -> e.toLowerCase().contains(searchInput.toLowerCase()))
                .limit(8)
                .toList();

        for (String entity : filtered) {
            guiGraphics.drawTextWithShadow(this.textRenderer, "  > " + entity, centerX - 100, y, 0xFFFF00);
            this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                    centerX + 60, y - 2, 40, 16,
                    Text.literal("Add"),
                    btn -> {
                        EntityManager.addEntity(entity);
                        DataManager.saveEntities();
                        searchInput = "";
                    }
            ));
            y += 18;
        }
    }

    private void renderColorsTab(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int y = 60;

        guiGraphics.drawCenteredTextWithShadow(this.textRenderer, "Crosshair Colors", centerX, y, 0xFFFFFF);
        y += 30;

        // Friend color
        guiGraphics.drawTextWithShadow(this.textRenderer, "Friend Color:", centerX - 100, y, 0xFFFFFF);
        int friendColor = CrosshairState.getFriendColor();
        guiGraphics.fill(centerX + 50, y, centerX + 50 + COLOR_WIDTH, y + COLOR_HEIGHT, friendColor);
        guiGraphics.drawTextWithShadow(this.textRenderer, String.format("%06X", friendColor), centerX + 120, y + 2, 0xFFFFFF);
        
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                centerX - 70, y, 30, 20,
                Text.literal("Edit"),
                btn -> {
                    // Open color picker or text input
                }
        ));
        y += 35;

        // Enemy color
        guiGraphics.drawTextWithShadow(this.textRenderer, "Enemy Color:", centerX - 100, y, 0xFFFFFF);
        int enemyColor = CrosshairState.getEnemyColor();
        guiGraphics.fill(centerX + 50, y, centerX + 50 + COLOR_WIDTH, y + COLOR_HEIGHT, enemyColor);
        guiGraphics.drawTextWithShadow(this.textRenderer, String.format("%06X", enemyColor), centerX + 120, y + 2, 0xFFFFFF);
        
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                centerX - 70, y, 30, 20,
                Text.literal("Edit"),
                btn -> {
                    // Open color picker or text input
                }
        ));

        y += 50;
        this.addDrawableChild(new net.minecraft.client.gui.widget.ButtonWidget(
                centerX - 50, y, 100, 20,
                Text.literal("Save Colors"),
                btn -> DataManager.saveConfig()
        ));
    }

    private List<String> getAllAvailableEntities() {
        return Arrays.asList(
                "Armor Stand", "Boat", "Minecart", "Painting", "ItemFrame",
                "Zombie", "Creeper", "Skeleton", "Spider", "Enderman",
                "Pig", "Cow", "Sheep", "Chicken", "Horse",
                "Villager", "Witch", "Wither", "EnderDragon", "Golem",
                "Bat", "Squid", "Dolphin", "Pufferfish", "Salmon",
                "Cat", "Dog", "Parrot", "Llama", "Bee"
        );
    }

    @Override
    public void onClose() {
        this.client.setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}

// ============================================================================
// MIXIN: GAME RENDERER (Crosshair Detection)
// ============================================================================
@Mixin(GameRenderer.class)
class GameRendererMixin {
    @Shadow private MinecraftClient client;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(net.minecraft.client.render.RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (this.client != null && this.client.player != null) {
            Entity targetEntity = this.client.targetedEntity;
            
            if (targetEntity != null) {
                boolean isFriend = FriendManager.isFriend(targetEntity) || EntityManager.isProtectedEntity(targetEntity);
                CrosshairState.setFriendTargeted(isFriend);
            } else {
                CrosshairState.setFriendTargeted(false);
            }
        }
    }
}

// ============================================================================
// MIXIN: MOUSE MANAGER (Block Attacks)
// ============================================================================
@Mixin(net.minecraft.client.Mouse.class)
class MouseManagerMixin {
    @Shadow private MinecraftClient client;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (button == 0 && action == 1) {
            if (this.client != null && this.client.player != null) {
                Entity targetEntity = this.client.targetedEntity;
                
                if (targetEntity != null) {
                    boolean isFriend = FriendManager.isFriend(targetEntity) || EntityManager.isProtectedEntity(targetEntity);
                    if (isFriend) {
                        ci.cancel();
                    }
                }
            }
        }
    }
}