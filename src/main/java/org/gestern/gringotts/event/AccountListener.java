package org.gestern.gringotts.event;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.gestern.gringotts.AccountChest;
import org.gestern.gringotts.Configuration;
import org.gestern.gringotts.Gringotts;
import org.gestern.gringotts.Language;
import org.gestern.gringotts.Util;
import org.gestern.gringotts.currency.GringottsCurrency;

/**
 * Listens for chest creation, destruction and change events.
 *
 * @author jast
 */
public class AccountListener implements Listener {

    private final Pattern VAULT_PATTERN = Pattern.compile(Configuration.CONF.vaultPattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Create an account chest by adding a sign marker over it.
     *
     * @param event Event data.
     */
    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String line0String = ChatColor.stripColor(event.getLine(0)).trim();

        if (line0String == null) {
            return;
        }

        Matcher match = VAULT_PATTERN.matcher(line0String);

        // consider only signs with proper formatting
        if (!match.matches()) {
            return;
        }

        String typeStr = match.group(1).toUpperCase();

        String type;

        // default vault is player
        if (typeStr.isEmpty()) {
            type = "player";
        } else {
            type = typeStr.toLowerCase();
        }

        Optional<Sign> optionalSign = Util.getBlockStateAs(
                event.getBlock(),
                Sign.class
        );

        if (optionalSign.isPresent() && Util.chestBlock(optionalSign.get()) != null) {
            // Block vault creation if the chest already contains non-currency items
            Block chestBlock = Util.chestBlock(optionalSign.get());
            if (chestBlock.getState() instanceof InventoryHolder inventoryHolder) {
                GringottsCurrency currency = Configuration.CONF.getCurrency();
                for (ItemStack item : inventoryHolder.getInventory().getContents()) {
                    if (item != null && item.getType() != Material.AIR && currency.getValue(item) == 0) {
                        event.getPlayer().sendMessage(Language.LANG.vault_onlyCurrency);
                        return;
                    }
                }
            }

            // we made it this far, throw the event to manage vault creation
            final VaultCreationEvent creation = new PlayerVaultCreationEvent(type, event);

            Bukkit.getServer().getPluginManager().callEvent(creation);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignBreak(BlockBreakEvent event) {
        deleteChestIfSign(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            deleteChestIfSign(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            deleteChestIfSign(block);
        }
    }

    private void deleteChestIfSign(Block block) {
        if (Tag.SIGNS.isTagged(block.getType())) {
            Gringotts.instance.getDao().deleteAccountChest(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getLocation() == null || !Util.isValidInventory(event.getInventory().getType())) return;

        AccountChest chest = getAccountChestFromHolder(event.getInventory());
        if (chest == null) return;

        chest.setCachedBalance(chest.balance(true));
        Gringotts.instance.getDao().updateChestBalance(chest, chest.getCachedBalance());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (Util.isValidInventory(event.getSource().getType())) {
            AccountChest chest = getAccountChestFromHolder(event.getSource());
            if (chest != null) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        chest.setCachedBalance(chest.balance(true));
                        Gringotts.instance.getDao().updateChestBalance(chest, chest.getCachedBalance());
                    }
                }.runTask(Gringotts.instance);
            }
        }
        if (event.getDestination() != null && Util.isValidInventory(event.getDestination().getType())) {
            AccountChest chest = getAccountChestFromHolder(event.getDestination());
            if (chest != null) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        chest.setCachedBalance(chest.balance(true));
                        Gringotts.instance.getDao().updateChestBalance(chest, chest.getCachedBalance());
                    }
                }.runTask(Gringotts.instance);
            }
        }
    }

    @EventHandler
    public void onDispenseEvent(BlockDispenseEvent event) {
        if (event.getBlock().getState() instanceof InventoryHolder) {
            AccountChest chest = getAccountChestFromHolder(((InventoryHolder) event.getBlock().getState()).getInventory());
            if (chest != null) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        chest.setCachedBalance(chest.balance(true));
                        Gringotts.instance.getDao().updateChestBalance(chest, chest.getCachedBalance());
                    }
                }.runTask(Gringotts.instance);
            }
        }
    }

    @EventHandler
    public void onSignEdit(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (!Tag.SIGNS.isTagged(event.getClickedBlock().getType())) return;
        for (AccountChest chest : Gringotts.instance.getDao().retrieveChests()) {
            if (!chest.isChestLoaded()) continue;
            if (event.getClickedBlock().getLocation().equals(chest.sign.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!Util.isValidInventory(event.getInventory().getType())) return;
        AccountChest chest = getAccountChestFromHolder(event.getInventory());
        if (chest == null) return;

        GringottsCurrency currency = Configuration.CONF.getCurrency();
        ItemStack itemToPlace = null;

        boolean clickedInVault = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getInventory());
        boolean clickedInPlayer = event.getClickedInventory() != null
                && !event.getClickedInventory().equals(event.getInventory());

        InventoryAction action = event.getAction();
        switch (action) {
            case PLACE_ALL:
            case PLACE_ONE:
            case PLACE_SOME:
            case SWAP_WITH_CURSOR:
                if (clickedInVault) itemToPlace = event.getCursor();
                break;
            case MOVE_TO_OTHER_INVENTORY:
                if (clickedInPlayer) itemToPlace = event.getCurrentItem();
                break;
            case HOTBAR_SWAP:
                if (clickedInVault) {
                    int hotbarSlot = event.getHotbarButton();
                    Player player = (Player) event.getWhoClicked();
                    itemToPlace = hotbarSlot >= 0
                            ? player.getInventory().getItem(hotbarSlot)
                            : player.getInventory().getItemInOffHand();
                }
                break;
            default:
                break;
        }

        if (itemToPlace != null && itemToPlace.getType() != Material.AIR && currency.getValue(itemToPlace) == 0) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Language.LANG.vault_onlyCurrency);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!Util.isValidInventory(event.getInventory().getType())) return;
        AccountChest chest = getAccountChestFromHolder(event.getInventory());
        if (chest == null) return;

        // getInventorySlots() returns only slots in the top (vault) inventory
        if (event.getInventorySlots().isEmpty()) return;

        GringottsCurrency currency = Configuration.CONF.getCurrency();
        ItemStack dragged = event.getOldCursor();
        if (dragged != null && dragged.getType() != Material.AIR && currency.getValue(dragged) == 0) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Language.LANG.vault_onlyCurrency);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryMoveItemBlock(InventoryMoveItemEvent event) {
        if (event.getDestination() == null) return;
        if (!Util.isValidInventory(event.getDestination().getType())) return;
        AccountChest chest = getAccountChestFromHolder(event.getDestination());
        if (chest == null) return;

        GringottsCurrency currency = Configuration.CONF.getCurrency();
        ItemStack item = event.getItem();
        if (item != null && item.getType() != Material.AIR && currency.getValue(item) == 0) {
            event.setCancelled(true);
        }
    }

    /**
     * Get the AccountChest associated with this {@link InventoryHolder}
     * @param holder
     * @return the {@link AccountChest} or null if none was found
     */
    private AccountChest getAccountChestFromHolder(Inventory holder) {
        for (AccountChest chest : Gringotts.instance.getDao().retrieveChests()) {
            if (!chest.isChestLoaded()) continue; // For a chest to be open or interacted with, it needs to be loaded

            if (chest.matchesLocation(holder.getLocation())) {
                return chest;
            }
        }
        return null;
    }
}
