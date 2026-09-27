package dev.indestructible;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class IndestructiblePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final Component LORE_LINE = Component.text("✦ Indestructible ✦", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false);

    private NamespacedKey markKey;
    private NamespacedKey addedUnbreakableKey;

    // Dropped indestructible items we are currently watching (for void rescue + glow).
    private final Set<UUID> tracked = new HashSet<>();

    // Chroma glow: the glow outline takes the color of the item's scoreboard team,
    // so we keep one team per rainbow color and hop items between them.
    private static final NamedTextColor[] RAINBOW = {
            NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.GREEN,
            NamedTextColor.AQUA, NamedTextColor.BLUE, NamedTextColor.DARK_PURPLE, NamedTextColor.LIGHT_PURPLE
    };
    private static final int TICKS_PER_COLOR = 3; // lower = faster color cycling
    private final List<Team> glowTeams = new ArrayList<>();
    private Scoreboard glowBoard;
    private int tickCounter = 0;
    private int colorIndex = 0;

    @Override
    public void onEnable() {
        markKey = new NamespacedKey(this, "indestructible");
        addedUnbreakableKey = new NamespacedKey(this, "added_unbreakable");

        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("indestructible");
        if (cmd != null) cmd.setExecutor(this);

        setupGlowTeams();

        // Protect any indestructible items already lying on the ground.
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (isIndestructible(item.getItemStack())) protect(item);
            }
        }

        // Every tick: keep dropped indestructible items out of the void and off fire.
        Bukkit.getScheduler().runTaskTimer(this, this::tickTrackedItems, 1L, 1L);

        getLogger().info("Indestructible enabled.");
    }

    @Override
    public void onDisable() {
        for (Team team : glowTeams) {
            try {
                team.unregister();
            } catch (IllegalStateException ignored) {
                // already gone
            }
        }
        glowTeams.clear();
    }

    private void setupGlowTeams() {
        glowBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (int i = 0; i < RAINBOW.length; i++) {
            String name = "indestr_glow_" + i;
            Team team = glowBoard.getTeam(name);
            if (team == null) team = glowBoard.registerNewTeam(name);
            team.color(RAINBOW[i]);
            glowTeams.add(team);
        }
    }

    private void removeFromGlowTeams(String entry) {
        Team team = glowBoard.getEntryTeam(entry);
        if (team != null && glowTeams.contains(team)) team.removeEntry(entry);
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("indestructible.use")) {
            player.sendMessage(Component.text("Only operators can use this command.", NamedTextColor.RED));
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(Component.text("Hold an item in your main hand first.", NamedTextColor.RED));
            return true;
        }

        ItemMeta meta = hand.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());

        if (pdc.has(markKey, PersistentDataType.BYTE)) {
            // Toggle OFF
            pdc.remove(markKey);
            if (pdc.has(addedUnbreakableKey, PersistentDataType.BYTE)) {
                meta.setUnbreakable(false);
                pdc.remove(addedUnbreakableKey);
            }
            lore.removeIf(LORE_LINE::equals);
            meta.lore(lore.isEmpty() ? null : lore);
            hand.setItemMeta(meta);
            player.getInventory().setItemInMainHand(hand);
            player.sendMessage(Component.text("This item is no longer indestructible.", NamedTextColor.YELLOW));
        } else {
            // Toggle ON
            pdc.set(markKey, PersistentDataType.BYTE, (byte) 1);
            if (!meta.isUnbreakable()) {
                meta.setUnbreakable(true); // no durability loss
                pdc.set(addedUnbreakableKey, PersistentDataType.BYTE, (byte) 1);
            }
            lore.add(LORE_LINE);
            meta.lore(lore);
            hand.setItemMeta(meta);
            player.getInventory().setItemInMainHand(hand);
            player.sendMessage(Component.text("This item is now indestructible!", NamedTextColor.GREEN));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean isIndestructible(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(markKey, PersistentDataType.BYTE);
    }

    private void protect(Item item) {
        item.setInvulnerable(true);       // blocks explosions, fire, lava, cactus, lightning
        item.setUnlimitedLifetime(true);  // never despawns
        item.setFireTicks(0);
        item.setGlowing(true);            // glowing outline while on the ground
        glowTeams.get(colorIndex).addEntry(item.getUniqueId().toString()); // chroma color
        tracked.add(item.getUniqueId());
    }

    /** Finds a safe spot above the given location, or the world spawn if there's only void. */
    private Location safeLocation(Location from) {
        World world = from.getWorld();
        int x = from.getBlockX();
        int z = from.getBlockZ();
        int top = world.getHighestBlockYAt(x, z);
        if (top >= world.getMinHeight()) {
            return new Location(world, x + 0.5, top + 1.5, z + 0.5);
        }
        return world.getSpawnLocation().clone().add(0.5, 1.5, 0.5);
    }

    private void tickTrackedItems() {
        boolean nextColor = ++tickCounter % TICKS_PER_COLOR == 0;
        if (nextColor) colorIndex = (colorIndex + 1) % RAINBOW.length;
        Team currentTeam = glowTeams.get(colorIndex);

        Iterator<UUID> it = tracked.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Item item) || !item.isValid() || !isIndestructible(item.getItemStack())) {
                it.remove(); // picked up, unloaded, or unmarked
                removeFromGlowTeams(id.toString());
                continue;
            }
            if (nextColor) currentTeam.addEntry(id.toString()); // moves it to the next rainbow color
            item.setFireTicks(0);
            Location loc = item.getLocation();
            if (loc.getY() < loc.getWorld().getMinHeight()) {
                item.setVelocity(new Vector(0, 0, 0));
                item.teleport(safeLocation(loc));
            }
        }
    }

    // ------------------------------------------------------------------
    // Dropped item protection
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (!isIndestructible(item.getItemStack())) return;

        Location loc = event.getLocation();
        if (loc.getY() < loc.getWorld().getMinHeight()) {
            // Spawned inside the void: re-drop it somewhere safe instead.
            ItemStack copy = item.getItemStack().clone();
            Location safe = safeLocation(loc);
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> safe.getWorld().dropItem(safe, copy));
            return;
        }
        protect(item);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item && isIndestructible(item.getItemStack())) protect(item);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDamage(EntityDamageEvent event) {
        // TNT, end crystals, creepers, fire, lava, cactus, lightning, anvils, void...
        if (event.getEntity() instanceof Item item && isIndestructible(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item && isIndestructible(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDespawn(ItemDespawnEvent event) {
        if (isIndestructible(event.getEntity().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeathInVoid(PlayerDeathEvent event) {
        Location loc = event.getEntity().getLocation();
        World world = loc.getWorld();
        if (loc.getY() >= world.getMinHeight()) return;

        List<ItemStack> rescued = new ArrayList<>();
        event.getDrops().removeIf(stack -> {
            if (isIndestructible(stack)) {
                rescued.add(stack.clone());
                return true;
            }
            return false;
        });
        if (rescued.isEmpty()) return;

        Location safe = safeLocation(loc);
        Bukkit.getScheduler().runTask(this, () -> rescued.forEach(s -> world.dropItem(safe, s)));
    }

    // ------------------------------------------------------------------
    // Ways an item gets used up while in an inventory
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFurnaceFuel(FurnaceBurnEvent event) {
        if (isIndestructible(event.getFuel())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDurability(PlayerItemDamageEvent event) {
        if (isIndestructible(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (isIndestructible(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("Indestructible items can't be placed.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isIndestructible(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack stack : event.getInventory().getMatrix()) {
            if (isIndestructible(stack)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        // The right-hand anvil slot gets consumed, so block that.
        if (isIndestructible(event.getInventory().getItem(1))) event.setResult(null);
    }
}
