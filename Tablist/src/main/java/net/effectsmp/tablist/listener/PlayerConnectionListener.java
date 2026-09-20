package net.effectsmp.tablist.listener;

import net.effectsmp.tablist.TablistPlugin;
import net.effectsmp.tablist.resourcepack.PackServer;
import net.effectsmp.tablist.tab.TabListManager;
import net.effectsmp.tablist.util.TextUtil;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.URI;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerConnectionListener implements Listener {

    private final TablistPlugin plugin;
    private final TabListManager tabListManager;
    private final PackServer packServer;

    public PlayerConnectionListener(TablistPlugin plugin, TabListManager tabListManager, PackServer packServer) {
        this.plugin = plugin;
        this.tabListManager = tabListManager;
        this.packServer = packServer;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tabListManager.updatePlayer(player);

        // LuckPerms guarantees the user is loaded by the time PlayerJoinEvent
        // fires, but on a rejoin specifically, its cached prefix/weight data
        // can still be a tick or two behind finishing its own recalculation -
        // which is what showed up as "sorting resets on rejoin" (the player
        // gets treated as weight 0 for a moment, until the 30s safety net
        // timer eventually caught and fixed it). This second, quick re-check
        // catches that within a second instead of waiting on the safety net.
        // It's a no-op (see the guard in applySortingTeam) if the first
        // update already had the right data, so this can't cause flicker.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                tabListManager.updatePlayer(player);
            }
        }, 20L);

        if (packServer != null && plugin.getConfig().getBoolean("header-image.enabled", false)) {
            // Small delay so this doesn't race the client's own join handling.
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendPack(player), 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tabListManager.removePlayer(event.getPlayer());
    }

    private void sendPack(Player player) {
        if (!player.isOnline()) {
            return;
        }
        String url = packServer.buildUrl();
        if (url == null) {
            plugin.getLogger().warning("header-image.enabled is true but no public-address is configured "
                    + "(and the server has no server-ip set either), so the resource pack can't be sent. "
                    + "Set header-image.public-address in config.yml.");
            return;
        }

        try {
            UUID packId = UUID.nameUUIDFromBytes(url.getBytes());
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo()
                    .id(packId)
                    .uri(URI.create(url))
                    .hash(packServer.getSha1Hex())
                    .build();
            boolean required = plugin.getConfig().getBoolean("header-image.required", false);
            String prompt = plugin.getConfig().getString("header-image.prompt", "");

            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .prompt(TextUtil.miniMessage(prompt))
                    .required(required)
                    .replace(false)
                    .build();

            player.sendResourcePacks(request);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to send the resource pack to " + player.getName(), e);
        }
    }
}
