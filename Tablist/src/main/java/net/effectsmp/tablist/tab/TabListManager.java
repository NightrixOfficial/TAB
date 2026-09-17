package net.effectsmp.tablist.tab;

import net.effectsmp.tablist.TablistPlugin;
import net.effectsmp.tablist.hook.LuckPermsHook;
import net.effectsmp.tablist.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Drives everything about the tab list: per-player ping + LuckPerms prefix
 * formatting, the purple header/footer (or the image banner), and the
 * scoreboard-team prefixes used for the above-head name tag.
 */
public class TabListManager {

    private static final String TEAM_PREFIX = "tl";

    private final TablistPlugin plugin;
    private final LuckPermsHook luckPermsHook;

    private BukkitTask task;

    private boolean tabPrefixEnabled;
    private boolean namePrefixEnabled;

    private Component headerImageComponent;

    public TabListManager(TablistPlugin plugin, LuckPermsHook luckPermsHook) {
        this.plugin = plugin;
        this.luckPermsHook = luckPermsHook;
        reloadToggles();
        loadHeaderImageComponent();
    }

    private void reloadToggles() {
        FileConfiguration config = plugin.getConfig();
        this.tabPrefixEnabled = config.getBoolean("luckperms.tab-prefix", false);
        this.namePrefixEnabled = config.getBoolean("luckperms.name-prefix", false);
    }

    private void loadHeaderImageComponent() {
        try (InputStream in = plugin.getResource("pack/header_chars.txt")) {
            if (in == null) {
                this.headerImageComponent = null;
                return;
            }
            String chars = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            this.headerImageComponent = Component.text(chars).font(Key.key("effectsmp", "header"));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled header_chars.txt: " + e.getMessage());
            this.headerImageComponent = null;
        }
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        Component header = buildHeader();
        Component footer = buildFooter();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    /** Applies formatting to a single player immediately (used on join too). */
    public void updatePlayer(Player player) {
        Component prefix = Component.empty();
        if (tabPrefixEnabled && luckPermsHook != null) {
            prefix = TextUtil.legacy(luckPermsHook.getPrefix(player));
        }

        Component ping = buildPingComponent(player);

        String format = plugin.getConfig().getString("format", "<prefix><white><name> <dark_gray>(<ping><dark_gray>)");
        Component listName = MiniMessage.miniMessage().deserialize(
                format,
                Placeholder.component("prefix", prefix),
                Placeholder.unparsed("name", player.getName()),
                Placeholder.component("ping", ping)
        );

        player.playerListName(listName);

        applyNameTagTeam(player);
    }

    private Component buildPingComponent(Player player) {
        int ping = player.getPing();
        FileConfiguration config = plugin.getConfig();
        String colorTag;
        int goodMax = config.getInt("ping.good-max", 100);
        int okMax = config.getInt("ping.ok-max", 250);
        if (ping <= goodMax) {
            colorTag = config.getString("ping.good-color", "<green>");
        } else if (ping <= okMax) {
            colorTag = config.getString("ping.ok-color", "<yellow>");
        } else {
            colorTag = config.getString("ping.bad-color", "<red>");
        }
        return TextUtil.miniMessage(colorTag + ping + "ms");
    }

    private void applyNameTagTeam(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamNameFor(player.getUniqueId());
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        if (namePrefixEnabled && luckPermsHook != null) {
            team.prefix(TextUtil.legacy(luckPermsHook.getPrefix(player)));
        } else {
            team.prefix(Component.empty());
        }
    }

    private String teamNameFor(UUID uuid) {
        // Kept to 16 chars for compatibility with older scoreboard implementations.
        String hex = uuid.toString().replace("-", "");
        return TEAM_PREFIX + hex.substring(0, 14);
    }

    private Component buildHeader() {
        if (plugin.getConfig().getBoolean("header-image.enabled", false) && headerImageComponent != null) {
            return headerImageComponent;
        }
        String fallback = plugin.getConfig().getString("header-image.fallback-header", "");
        return TextUtil.miniMessage(fallback);
    }

    private Component buildFooter() {
        if (!plugin.getConfig().getBoolean("tab-list.footer-enabled", true)) {
            return Component.empty();
        }
        String footer = plugin.getConfig().getString("tab-list.footer", "");
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        return MiniMessage.miniMessage().deserialize(
                footer,
                Placeholder.unparsed("online", String.valueOf(online)),
                Placeholder.unparsed("max", String.valueOf(max))
        );
    }

    public void removePlayer(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(teamNameFor(player.getUniqueId()));
        if (team != null) {
            team.removeEntry(player.getName());
        }
    }

    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePlayer(player);
        }
    }

    public boolean isTabPrefixEnabled() {
        return tabPrefixEnabled;
    }

    public boolean isNamePrefixEnabled() {
        return namePrefixEnabled;
    }

    public void setTabPrefixEnabled(boolean enabled) {
        this.tabPrefixEnabled = enabled;
        plugin.getConfig().set("luckperms.tab-prefix", enabled);
        plugin.saveConfig();
        Bukkit.getOnlinePlayers().forEach(this::updatePlayer);
    }

    public void setNamePrefixEnabled(boolean enabled) {
        this.namePrefixEnabled = enabled;
        plugin.getConfig().set("luckperms.name-prefix", enabled);
        plugin.saveConfig();
        Bukkit.getOnlinePlayers().forEach(this::updatePlayer);
    }

    public boolean hasLuckPerms() {
        return luckPermsHook != null;
    }
}
