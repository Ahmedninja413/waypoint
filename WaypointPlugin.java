package com.a7a.waypoint;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Waypoint - a simple fast teleportation method.
 * Right-click the item to open a chest menu (works on Java and on Bedrock through Geyser).
 */
public final class WaypointPlugin extends JavaPlugin implements Listener {

    // ---- colors (used for the wool icon and the name color) ----
    private static final String[] COLOR_IDS = {
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"
    };
    private static final int[] COLOR_HEX = {
            0xFFFFFF, 0xFF9B2F, 0xE04DDB, 0x5CC8FF, 0xFFE83B, 0x7CFF3B, 0xFF9EC4, 0x7A7A7A,
            0xBDBDBD, 0x1FB5B5, 0xA24DFF, 0x3B5BFF, 0x9C6B3A, 0x2E9E2E, 0xFF3B3B, 0x3A3A3A
    };

    private static final int MAIN_SIZE = 54;
    private static final int LIST_SLOTS = 45; // slots 0..44 hold waypoints

    private NamespacedKey itemKey;
    private final Map<UUID, List<Waypoint>> data = new HashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private File dataFile;
    private int maxWaypoints;
    private boolean useCustomModel;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxWaypoints = Math.min(LIST_SLOTS, Math.max(1, getConfig().getInt("max-waypoints", 45)));
        useCustomModel = getConfig().getBoolean("custom-model", true);
        itemKey = new NamespacedKey(this, "waypoint_item");
        dataFile = new File(getDataFolder(), "waypoints.yml");
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("waypoint") != null) {
            getCommand("waypoint").setExecutor(this);
        }
        getLogger().info("Waypoint enabled. Use /waypoint give <player>");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ---------------------------------------------------------------- data model

    private static final class Waypoint {
        final String id;
        String name;
        int color;
        String world;
        double x, y, z;
        float yaw, pitch;

        Waypoint(String id, String name, int color, String world,
                 double x, double y, double z, float yaw, float pitch) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private enum PendingKind { NEW, RENAME }

    private static final class Pending {
        final PendingKind kind;
        final int color;
        final String waypointId;
        final Location location;

        Pending(PendingKind kind, int color, String waypointId, Location location) {
            this.kind = kind;
            this.color = color;
            this.waypointId = waypointId;
            this.location = location;
        }
    }

    private enum MenuType { MAIN, ACTIONS, COLORS, CONFIRM_DELETE }

    private enum ColorPurpose { NEW, RECOLOR }

    private static final class Menu implements InventoryHolder {
        final MenuType type;
        final UUID owner;
        String waypointId;
        ColorPurpose purpose;
        boolean edit;
        Inventory inv;

        Menu(MenuType type, UUID owner) {
            this.type = type;
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private List<Waypoint> listOf(UUID id) {
        return data.computeIfAbsent(id, k -> new ArrayList<>());
    }

    private Waypoint find(UUID owner, String id) {
        if (id == null) return null;
        for (Waypoint w : listOf(owner)) {
            if (w.id.equals(id)) return w;
        }
        return null;
    }

    private void loadData() {
        data.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) return;
        for (String uuidKey : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection sec = players.getConfigurationSection(uuidKey);
            if (sec == null) continue;
            List<Waypoint> list = new ArrayList<>();
            for (String id : sec.getKeys(false)) {
                ConfigurationSection w = sec.getConfigurationSection(id);
                if (w == null) continue;
                list.add(new Waypoint(
                        id,
                        w.getString("name", "Waypoint"),
                        Math.floorMod(w.getInt("color", 0), COLOR_IDS.length),
                        w.getString("world", "world"),
                        w.getDouble("x"), w.getDouble("y"), w.getDouble("z"),
                        (float) w.getDouble("yaw"), (float) w.getDouble("pitch")));
            }
            data.put(uuid, list);
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<Waypoint>> e : data.entrySet()) {
            for (Waypoint w : e.getValue()) {
                String base = "players." + e.getKey() + "." + w.id;
                yml.set(base + ".name", w.name);
                yml.set(base + ".color", w.color);
                yml.set(base + ".world", w.world);
                yml.set(base + ".x", w.x);
                yml.set(base + ".y", w.y);
                yml.set(base + ".z", w.z);
                yml.set(base + ".yaw", (double) w.yaw);
                yml.set(base + ".pitch", (double) w.pitch);
            }
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save waypoints.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- the item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("Waypoint", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(text("a simple fast teleportation method", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("Right-click to open", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.setMaxStackSize(1);
        if (useCustomModel) {
            meta.setItemModel(new NamespacedKey("waypoint", "waypoint"));
        }
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWaypointItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("waypoint.give")) {
                sender.sendMessage(text("You don't have permission.", NamedTextColor.RED));
                return true;
            }
            Player target = null;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
            } else if (sender instanceof Player p) {
                target = p;
            }
            if (target == null) {
                sender.sendMessage(text("Usage: /waypoint give <player>", NamedTextColor.RED));
                return true;
            }
            Map<Integer, ItemStack> left = target.getInventory().addItem(createItem());
            for (ItemStack rest : left.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            }
            sender.sendMessage(text("Gave a Waypoint to " + target.getName() + ".", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(text("Usage: /waypoint give <player>", NamedTextColor.YELLOW));
        return true;
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isWaypointItem(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("waypoint.use")) return;
        openMain(p, false);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.getUniqueId().equals(m.owner)) return;
        int slot = e.getRawSlot();

        switch (m.type) {
            case MAIN -> handleMain(p, m, slot);
            case ACTIONS -> handleActions(p, m, slot);
            case COLORS -> handleColors(p, m, slot);
            case CONFIRM_DELETE -> handleConfirm(p, m, slot);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!pending.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> finishPending(p, msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- menu handlers

    private void handleMain(Player p, Menu m, int slot) {
        List<Waypoint> list = listOf(p.getUniqueId());
        if (slot >= 0 && slot < LIST_SLOTS && slot < list.size()) {
            Waypoint wp = list.get(slot);
            if (m.edit) {
                later(() -> openActions(p, wp));
            } else {
                teleport(p, wp);
            }
            return;
        }
        switch (slot) {
            case 49 -> {
                if (list.size() >= maxWaypoints) {
                    p.sendMessage(text("You reached the limit of " + maxWaypoints + " waypoints.", NamedTextColor.RED));
                    return;
                }
                later(() -> openColors(p, ColorPurpose.NEW, null));
            }
            case 51 -> {
                boolean next = !m.edit;
                later(() -> openMain(p, next));
            }
            case 53 -> later(p::closeInventory);
            default -> { }
        }
    }

    private void handleActions(Player p, Menu m, int slot) {
        Waypoint wp = find(p.getUniqueId(), m.waypointId);
        if (wp == null) {
            later(() -> openMain(p, true));
            return;
        }
        switch (slot) {
            case 10 -> teleport(p, wp);
            case 12 -> startPending(p, new Pending(PendingKind.RENAME, wp.color, wp.id, null));
            case 14 -> later(() -> openColors(p, ColorPurpose.RECOLOR, wp.id));
            case 16 -> later(() -> openConfirm(p, wp));
            case 22 -> later(() -> openMain(p, true));
            default -> { }
        }
    }

    private void handleColors(Player p, Menu m, int slot) {
        if (slot == 22) {
            later(() -> {
                if (m.purpose == ColorPurpose.RECOLOR) {
                    Waypoint wp = find(p.getUniqueId(), m.waypointId);
                    if (wp != null) {
                        openActions(p, wp);
                        return;
                    }
                }
                openMain(p, false);
            });
            return;
        }
        int idx = colorIndexForSlot(slot);
        if (idx < 0) return;

        if (m.purpose == ColorPurpose.NEW) {
            startPending(p, new Pending(PendingKind.NEW, idx, null, p.getLocation().clone()));
        } else {
            Waypoint wp = find(p.getUniqueId(), m.waypointId);
            if (wp != null) {
                wp.color = idx;
                saveData();
                p.sendMessage(text("Color changed.", NamedTextColor.GREEN));
                later(() -> openActions(p, wp));
            }
        }
    }

    private void handleConfirm(Player p, Menu m, int slot) {
        Waypoint wp = find(p.getUniqueId(), m.waypointId);
        if (slot == 11 && wp != null) {
            listOf(p.getUniqueId()).remove(wp);
            saveData();
            p.sendMessage(text("Waypoint \"" + wp.name + "\" deleted.", NamedTextColor.YELLOW));
            later(() -> openMain(p, true));
        } else if (slot == 15) {
            if (wp != null) {
                later(() -> openActions(p, wp));
            } else {
                later(() -> openMain(p, true));
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private void teleport(Player p, Waypoint wp) {
        World w = Bukkit.getWorld(wp.world);
        if (w == null) {
            p.sendMessage(text("That world isn't available right now.", NamedTextColor.RED));
            return;
        }
        Location dest = new Location(w, wp.x, wp.y, wp.z, wp.yaw, wp.pitch);
        later(p::closeInventory);
        p.teleportAsync(dest).thenAccept(ok -> {
            if (ok) {
                Bukkit.getScheduler().runTask(this, () -> {
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    p.sendActionBar(text("Teleported to " + wp.name, TextColor.color(COLOR_HEX[wp.color])));
                });
            }
        });
    }

    private void startPending(Player p, Pending pd) {
        pending.put(p.getUniqueId(), pd);
        later(p::closeInventory);
        p.sendMessage(text("Type the waypoint name in chat.", NamedTextColor.AQUA));
        p.sendMessage(text("Type 'skip' for an automatic name, or 'cancel' to cancel.", NamedTextColor.GRAY));
    }

    private void finishPending(Player p, String msg) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null) return;
        String lower = msg.toLowerCase();

        if (lower.equals("cancel")) {
            p.sendMessage(text("Cancelled.", NamedTextColor.YELLOW));
            return;
        }

        if (pd.kind == PendingKind.NEW) {
            List<Waypoint> list = listOf(p.getUniqueId());
            if (list.size() >= maxWaypoints) {
                p.sendMessage(text("You reached the limit of " + maxWaypoints + " waypoints.", NamedTextColor.RED));
                return;
            }
            String name = lower.equals("skip") || msg.isEmpty() ? "Waypoint " + (list.size() + 1) : clip(msg);
            Location l = pd.location;
            String id = UUID.randomUUID().toString().substring(0, 8);
            list.add(new Waypoint(id, name, pd.color, l.getWorld().getName(),
                    l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
            saveData();
            p.sendMessage(text("Saved \"" + name + "\".", TextColor.color(COLOR_HEX[pd.color])));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        } else {
            Waypoint wp = find(p.getUniqueId(), pd.waypointId);
            if (wp == null) return;
            if (lower.equals("skip") || msg.isEmpty()) {
                p.sendMessage(text("Cancelled.", NamedTextColor.YELLOW));
                return;
            }
            wp.name = clip(msg);
            saveData();
            p.sendMessage(text("Renamed to \"" + wp.name + "\".", NamedTextColor.GREEN));
        }
    }

    private static String clip(String s) {
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    // ---------------------------------------------------------------- menu builders

    private void openMain(Player p, boolean edit) {
        Menu m = new Menu(MenuType.MAIN, p.getUniqueId());
        m.edit = edit;
        Inventory inv = Bukkit.createInventory(m, MAIN_SIZE,
                text(edit ? "Waypoints (edit mode)" : "Waypoints", NamedTextColor.DARK_AQUA));
        m.inv = inv;

        List<Waypoint> list = listOf(p.getUniqueId());
        for (int i = 0; i < list.size() && i < LIST_SLOTS; i++) {
            inv.setItem(i, waypointIcon(list.get(i), edit));
        }
        for (int i = LIST_SLOTS; i < MAIN_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(49, button(Material.EMERALD, "Save current location", NamedTextColor.GREEN,
                "Saves where you are standing now",
                list.size() + " / " + maxWaypoints + " used"));
        inv.setItem(51, button(Material.WRITABLE_BOOK, edit ? "Edit mode: ON" : "Edit mode: OFF",
                edit ? NamedTextColor.GOLD : NamedTextColor.GRAY,
                "Turn on to rename, recolor or delete",
                "Turn off to just teleport"));
        inv.setItem(53, button(Material.BARRIER, "Close", NamedTextColor.RED));
        p.openInventory(inv);
    }

    private void openActions(Player p, Waypoint wp) {
        Menu m = new Menu(MenuType.ACTIONS, p.getUniqueId());
        m.waypointId = wp.id;
        Inventory inv = Bukkit.createInventory(m, 27, text(wp.name, TextColor.color(COLOR_HEX[wp.color])));
        m.inv = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, filler());
        inv.setItem(10, button(Material.ENDER_PEARL, "Teleport", NamedTextColor.AQUA));
        inv.setItem(12, button(Material.NAME_TAG, "Rename", NamedTextColor.YELLOW, "Type the new name in chat"));
        inv.setItem(14, button(Material.PAPER, "Change color", NamedTextColor.LIGHT_PURPLE));
        inv.setItem(16, button(Material.TNT, "Delete", NamedTextColor.RED));
        inv.setItem(22, button(Material.ARROW, "Back", NamedTextColor.GRAY));
        p.openInventory(inv);
    }

    private void openColors(Player p, ColorPurpose purpose, String waypointId) {
        Menu m = new Menu(MenuType.COLORS, p.getUniqueId());
        m.purpose = purpose;
        m.waypointId = waypointId;
        Inventory inv = Bukkit.createInventory(m, 27, text("Pick a color", NamedTextColor.DARK_AQUA));
        m.inv = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, filler());
        for (int i = 0; i < COLOR_IDS.length; i++) {
            ItemStack it = new ItemStack(Material.valueOf(COLOR_IDS[i] + "_WOOL"));
            ItemMeta meta = it.getItemMeta();
            meta.displayName(text(prettyColor(i), TextColor.color(COLOR_HEX[i])));
            it.setItemMeta(meta);
            inv.setItem(slotForColorIndex(i), it);
        }
        inv.setItem(22, button(Material.ARROW, "Back", NamedTextColor.GRAY));
        p.openInventory(inv);
    }

    private void openConfirm(Player p, Waypoint wp) {
        Menu m = new Menu(MenuType.CONFIRM_DELETE, p.getUniqueId());
        m.waypointId = wp.id;
        Inventory inv = Bukkit.createInventory(m, 27, text("Delete \"" + wp.name + "\"?", NamedTextColor.RED));
        m.inv = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, filler());
        inv.setItem(11, button(Material.LIME_WOOL, "Yes, delete", NamedTextColor.GREEN));
        inv.setItem(15, button(Material.RED_WOOL, "No, keep it", NamedTextColor.RED));
        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- helpers

    private ItemStack waypointIcon(Waypoint wp, boolean edit) {
        ItemStack it = new ItemStack(Material.valueOf(COLOR_IDS[wp.color] + "_WOOL"));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(text(wp.name, TextColor.color(COLOR_HEX[wp.color])).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(text(wp.world, NamedTextColor.DARK_GRAY));
        lore.add(text("X " + (int) Math.floor(wp.x) + "  Y " + (int) Math.floor(wp.y)
                + "  Z " + (int) Math.floor(wp.z), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text(edit ? "Click to edit" : "Click to teleport", NamedTextColor.YELLOW));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack button(Material mat, String name, TextColor color, String... lines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(text(name, color).decoration(TextDecoration.BOLD, true));
        if (lines.length > 0) {
            List<Component> lore = new ArrayList<>();
            for (String l : lines) lore.add(text(l, NamedTextColor.GRAY));
            meta.lore(lore);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private static int slotForColorIndex(int i) {
        return i < 8 ? 1 + i : 10 + (i - 8);
    }

    private static int colorIndexForSlot(int slot) {
        if (slot >= 1 && slot <= 8) return slot - 1;
        if (slot >= 10 && slot <= 17) return slot - 2;
        return -1;
    }

    private static String prettyColor(int i) {
        String[] parts = COLOR_IDS[i].toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    private static Component text(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }
}
