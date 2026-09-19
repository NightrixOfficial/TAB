package net.effectsmp.tablist.listener;

import net.effectsmp.tablist.TablistPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Keeps anyone in spectator mode out of everyone else's tab list (and out
 * of their view entirely, via Bukkit's normal hidePlayer/showPlayer). A
 * spectator can still see themselves fine - this only hides them from
 * OTHER players. Controlled by the "hide-spectators" config option.
 * <p>
 * Vanilla Minecraft already keeps a spectator's player MODEL invisible to
 * non-spectators in the world - it does NOT remove them from the tab list
 * on its own, which is the part this fills in.
 */
public class SpectatorHideListener implements Listener {

    private final TablistPlugin plugin;

    public SpectatorHideListener(TablistPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("hide-spectators", true);
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getNewGameMode() == GameMode.SPECTATOR) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(player)) {
                    viewer.hidePlayer(plugin, player);
                }
            }
        } else {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(player)) {
                    viewer.showPlayer(plugin, player);
                }
            }
        }
    }

    // MONITOR so this runs after anything else that might affect gamemode
    // or visibility on join, and after Tablist's own join handling.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) {
            return;
        }
        Player joined = event.getPlayer();
        boolean joinedIsSpectating = joined.getGameMode() == GameMode.SPECTATOR;

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(joined)) {
                continue;
            }
            // Existing spectators shouldn't appear for the player who just
            // joined...
            if (other.getGameMode() == GameMode.SPECTATOR) {
                joined.hidePlayer(plugin, other);
            }
            // ...and if the player who just joined is themselves already
            // in spectator mode (e.g. it persisted across a reconnect),
            // nobody already online should see them either.
            if (joinedIsSpectating) {
                other.hidePlayer(plugin, joined);
            }
        }
    }
}
