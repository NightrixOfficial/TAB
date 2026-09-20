package net.effectsmp.tablist.command;

import net.effectsmp.tablist.tab.TabListManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * /tablist display <on|off>
 * <p>
 * Turns the purple header/footer, banner, and ping display on/off without
 * touching the LuckPerms prefix toggles (those are still /display lp ...).
 * With it off you get a plain vanilla-looking tab list, except LuckPerms
 * prefixes and weight-sorting still apply if those are separately enabled -
 * this only switches off Tablist's own look, not the LuckPerms hook.
 */
public class TablistCommand implements CommandExecutor, TabCompleter {

    private final TabListManager tabListManager;

    public TablistCommand(TabListManager tabListManager) {
        this.tabListManager = tabListManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("tablist.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        if (args.length != 2 || !args[0].equalsIgnoreCase("display")
                || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            sendUsage(sender, label);
            return true;
        }

        boolean on = args[1].equalsIgnoreCase("on");
        tabListManager.setVanillaMode(!on);
        sender.sendMessage(Component.text(
                on ? "Tablist's custom look is back on." : "Tablist is now showing the plain vanilla tab list "
                        + "(LuckPerms prefixes/sorting still apply if you've got those on).",
                NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.RED));
        sender.sendMessage(Component.text("/" + label + " display <on|off>", NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("display");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("display")) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
