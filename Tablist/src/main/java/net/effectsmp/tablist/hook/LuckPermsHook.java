package net.effectsmp.tablist.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.entity.Player;

/**
 * Every reference to a LuckPerms class lives in this file. TablistPlugin
 * only ever constructs it after confirming the LuckPerms plugin is
 * installed, so if it isn't, this class is simply never loaded and there's
 * no NoClassDefFoundError.
 * <p>
 * Both lookups below return {@code null} when the player's LuckPerms data
 * isn't available *right now* (as opposed to "available, and genuinely
 * empty/zero") - callers should treat a null as "try again next tick",
 * not as "they have no prefix/no groups", otherwise a single slow lookup
 * makes a player's prefix/rank flicker off for a tick.
 */
public class LuckPermsHook {

    private final LuckPerms luckPerms;

    public LuckPermsHook() {
        this.luckPerms = LuckPermsProvider.get();
    }

    /**
     * @return the player's LuckPerms prefix (legacy '&' formatted), an
     * empty string if they genuinely have none, or {@code null} if their
     * data isn't loaded/available right now.
     */
    public String getPrefix(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return prefix == null ? "" : prefix;
    }

    /**
     * Looks at every group the player belongs to (directly - not further
     * up the inheritance chain) and returns the highest "weight" set on
     * any of them, i.e. whichever one counts as their highest role. Groups
     * with no weight set count as 0.
     *
     * @return the highest weight, 0 if they have no weighted groups, or
     * {@code null} if their data isn't loaded/available right now.
     */
    public Integer getHighestWeight(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }

        int highest = 0;
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            Group group = luckPerms.getGroupManager().getGroup(node.getGroupName());
            if (group == null) {
                continue;
            }
            int weight = group.getWeight().orElse(0);
            if (weight > highest) {
                highest = weight;
            }
        }
        return highest;
    }
}
