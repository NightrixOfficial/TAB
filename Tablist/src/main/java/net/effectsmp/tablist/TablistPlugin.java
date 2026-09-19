package net.effectsmp.tablist;

import net.effectsmp.tablist.command.DisplayCommand;
import net.effectsmp.tablist.command.TablistCommand;
import net.effectsmp.tablist.hook.LuckPermsHook;
import net.effectsmp.tablist.listener.PlayerConnectionListener;
import net.effectsmp.tablist.listener.SpectatorHideListener;
import net.effectsmp.tablist.resourcepack.PackServer;
import net.effectsmp.tablist.tab.TabListManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class TablistPlugin extends JavaPlugin {

    private LuckPermsHook luckPermsHook;
    private TabListManager tabListManager;
    private PackServer packServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // LuckPerms is optional. We only touch LuckPerms classes from inside
        // LuckPermsHook, and only construct that class when LuckPerms is
        // actually present, so a missing LuckPerms install never throws a
        // NoClassDefFoundError here.
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                // Called whenever LuckPerms recalculates a user's data (e.g. a
                // rank change) - this is how we learn about changes instead of
                // polling LuckPerms every tick, which was causing prefixes and
                // sort order to flicker whenever 2+ players were online.
                this.luckPermsHook = new LuckPermsHook(this, uuid -> {
                    if (tabListManager != null) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null) {
                            tabListManager.updatePlayer(player);
                        }
                    }
                });
                getLogger().info("LuckPerms found - prefix support enabled.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "LuckPerms was detected but failed to hook into it. Prefixes will be disabled.", t);
                this.luckPermsHook = null;
            }
        } else {
            getLogger().info("LuckPerms not found - prefix toggles will do nothing until it's installed.");
        }

        this.tabListManager = new TabListManager(this, luckPermsHook);

        if (getConfig().getBoolean("header-image.enabled", false)) {
            this.packServer = new PackServer(this);
            if (!packServer.start()) {
                getLogger().warning("Could not start the resource pack HTTP server - "
                        + "the image banner header will not be sent to players. "
                        + "Falling back to the plain-text header.");
                this.packServer = null;
            }
        }

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, tabListManager, packServer), this);
        getServer().getPluginManager().registerEvents(new SpectatorHideListener(this), this);

        DisplayCommand displayCommand = new DisplayCommand(this, tabListManager);
        getCommand("display").setExecutor(displayCommand);
        getCommand("display").setTabCompleter(displayCommand);

        TablistCommand tablistCommand = new TablistCommand(tabListManager);
        getCommand("tablist").setExecutor(tablistCommand);
        getCommand("tablist").setTabCompleter(tablistCommand);

        tabListManager.start();

        getLogger().info("Tablist enabled.");
    }

    @Override
    public void onDisable() {
        if (tabListManager != null) {
            tabListManager.stop();
            tabListManager.clearAll();
        }
        if (packServer != null) {
            packServer.stop();
        }
        getLogger().info("Tablist disabled.");
    }

    public LuckPermsHook getLuckPermsHook() {
        return luckPermsHook;
    }

    public TabListManager getTabListManager() {
        return tabListManager;
    }

    public PackServer getPackServer() {
        return packServer;
    }
}
