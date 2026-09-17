package net.effectsmp.tablist.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/**
 * Every reference to a LuckPerms class lives in this file. TablistPlugin
 * only ever constructs it after confirming the LuckPerms plugin is
 * installed, so if it isn't, this class is simply never loaded and there's
 * no NoClassDefFoundError.
 */
public class LuckPermsHook {

    private final LuckPerms luckPerms;

    public LuckPermsHook() {
        this.luckPerms = LuckPermsProvider.get();
    }

    /**
     * @return the player's LuckPerms prefix (legacy '&' formatted), or an
     * empty string if they have none / their data isn't loaded yet.
     */
    public String getPrefix(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return "";
        }
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return prefix == null ? "" : prefix;
    }
}
