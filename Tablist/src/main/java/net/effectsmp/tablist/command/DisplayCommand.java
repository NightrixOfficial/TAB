package net.effectsmp.tablist.command;

import net.effectsmp.tablist.TablistPlugin;
import net.effectsmp.tablist.tab.TabListManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * /display lp tab <enable|disable>
 * /display lp name <enable|disable>
 */
public class DisplayCommand implements CommandExecutor, TabCompleter {

    private final TablistPlugin plugin;
    private final TabListManager tabListManager;

    public DisplayCommand(TablistPlugin plugin, TabListManager tabListManager) {
        this.plugin = plugin;
        this.tabListManager = tabListManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("tablist.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        if (args.length != 3 || !args[0].equalsIgnoreCase("lp")
                || !(args[1].equalsIgnoreCase("tab") || args[1].equalsIgnoreCase("name"))
                || !(args[2].equalsIgnoreCase("enable") || args[2].equalsIgnoreCase("disable"))) {
            sendUsage(sender, label);
            return true;
        }

        if (!tabListManager.hasLuckPerms()) {
            sender.sendMessage(Component.text("LuckPerms isn't installed (or didn't hook in) - "
                    + "this won't have any visible effect until it is.", NamedTextColor.YELLOW));
        }

        boolean enable = args[2].equalsIgnoreCase("enable");
        if (args[1].equalsIgnoreCase("tab")) {
            tabListManager.setTabPrefixEnabled(enable);
            sender.sendMessage(Component.text(
                    "LuckPerms prefixes in the tab list are now " + (enable ? "enabled." : "disabled."),
                    NamedTextColor.LIGHT_PURPLE));
        } else {
            tabListManager.setNamePrefixEnabled(enable);
            sender.sendMessage(Component.text(
                    "LuckPerms prefixes above player heads are now " + (enable ? "enabled." : "disabled."),
                    NamedTextColor.LIGHT_PURPLE));
        }
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.RED));
        sender.sendMessage(Component.text("/" + label + " lp tab <enable|disable>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " lp name <enable|disable>", NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("lp");
        }
        if (args.length == 2) {
            return List.of("tab", "name");
        }
        if (args.length == 3) {
            return List.of("enable", "disable");
        }
        return List.of();
    }
}
