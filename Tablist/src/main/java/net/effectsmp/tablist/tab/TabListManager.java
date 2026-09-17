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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drives everything about the tab list: per-player ping + LuckPerms prefix
 * formatting, the purple header/footer (or the image banner), and the
 * scoreboard teams used both for the above-head name tag and for sorting
 * the tab list by LuckPerms group weight (highest weight = highest role,
 * shown at the top).
 */
public class TabListManager {

    // Highest supported weight for sort-key purposes. Weights above this are
    // just clamped - they'll still sort correctly relative to each other,
    // they just won't get their own distinct rung above this ceiling.
    private static final int MAX_SORT_WEIGHT = 99_999;

    private final TablistPlugin plugin;
    private final LuckPermsHook luckPermsHook;

    // Which weight-based sort team each online player currently sits in, so
    // we can move them to a new one (and clean up the old, now-empty team)
    // if their highest-weight group changes while they're online.
    private final Map<UUID, String> playerTeams = new HashMap<>();

    // Last successfully-read weight/prefix per player. LuckPerms lookups can
    // briefly come back empty right when a player's data is (re)loading -
    // most noticeably when someone else joins/leaves - and if we treated
    // that as "they have no rank" every time, a player's tag and tab
    // position would flicker every tick it happened. Falling back to the
    // last known-good value instead of 0/"" keeps things stable.
    private final Map<UUID, Integer> lastKnownWeight = new HashMap<>();
    private final Map<UUID, String> lastKnownPrefix = new HashMap<>();

    private BukkitTask task;

    private boolean tabPrefixEnabled;
    private boolean namePrefixEnabled;
    private boolean sortByWeight;

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
        this.sortByWeight = config.getBoolean("luckperms.sort-by-weight", true);
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
            prefix = TextUtil.legacy(effectivePrefix(player));
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

        applySortingTeam(player);
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

    /**
     * Players are bucketed into one shared scoreboard team per LuckPerms
     * weight value. Two things ride on this team assignment:
     * <ul>
     *   <li>Sorting - the vanilla client sorts the tab list by team name,
     *       and the team name is built so higher weight sorts first.</li>
     *   <li>The above-head name tag (only when luckperms.name-prefix is on) -
     *       everyone sharing a weight/rank shares the same tag, which is
     *       the normal way a "rank prefix" is expected to look.</li>
     * </ul>
     */
    private void applySortingTeam(Player player) {
        int weight = effectiveWeight(player);
        String newTeamName = teamNameForWeight(weight);

        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        String oldTeamName = playerTeams.get(player.getUniqueId());
        if (oldTeamName != null && !oldTeamName.equals(newTeamName)) {
            unassignFromTeam(scoreboard, oldTeamName, player);
        }

        Team team = scoreboard.getTeam(newTeamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(newTeamName);
        }
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
        playerTeams.put(player.getUniqueId(), newTeamName);

        if (namePrefixEnabled && luckPermsHook != null) {
            // Several players can share this team (same weight = same rank),
            // and a Team only has ONE prefix. Rather than letting whichever
            // player happened to be processed last this tick decide the
            // text (which flickers, since online-player iteration order
            // isn't guaranteed stable), always derive it from the same
            // canonical member of the bucket so it's consistent tick to tick.
            team.prefix(TextUtil.legacy(canonicalPrefixForWeight(weight)));
        } else {
            team.prefix(Component.empty());
        }
    }

    /** Weight for a player, falling back to their last known value if
     * LuckPerms' data for them isn't available on this particular tick. */
    private int effectiveWeight(Player player) {
        if (!sortByWeight || luckPermsHook == null) {
            return 0;
        }
        Integer weight = luckPermsHook.getHighestWeight(player);
        if (weight != null) {
            lastKnownWeight.put(player.getUniqueId(), weight);
            return weight;
        }
        return lastKnownWeight.getOrDefault(player.getUniqueId(), 0);
    }

    /** Prefix for a player, falling back to their last known value if
     * LuckPerms' data for them isn't available on this particular tick. */
    private String effectivePrefix(Player player) {
        if (luckPermsHook == null) {
            return "";
        }
        String prefix = luckPermsHook.getPrefix(player);
        if (prefix != null) {
            lastKnownPrefix.put(player.getUniqueId(), prefix);
            return prefix;
        }
        return lastKnownPrefix.getOrDefault(player.getUniqueId(), "");
    }

    /** The same online player (by UUID order) is always picked to "own" the
     * shared prefix text for a given weight bucket, so it can't flicker
     * between two different players' text from tick to tick. */
    private String canonicalPrefixForWeight(int weight) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> effectiveWeight(p) == weight)
                .min(Comparator.comparing(p -> p.getUniqueId().toString()))
                .map(this::effectivePrefix)
                .orElse("");
    }

    private void unassignFromTeam(Scoreboard scoreboard, String teamName, Player player) {
        Team oldTeam = scoreboard.getTeam(teamName);
        if (oldTeam == null) {
            return;
        }
        oldTeam.removeEntry(player.getName());
        if (oldTeam.getEntries().isEmpty()) {
            oldTeam.unregister();
        }
    }

    /**
     * Builds a scoreboard team name that sorts higher for higher weight
     * (the vanilla client sorts the tab list ascending by team name).
     */
    private String teamNameForWeight(int weight) {
        int clamped = Math.max(0, Math.min(weight, MAX_SORT_WEIGHT));
        int sortKey = MAX_SORT_WEIGHT - clamped;
        return "w" + String.format("%05d", sortKey);
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
        lastKnownWeight.remove(player.getUniqueId());
        lastKnownPrefix.remove(player.getUniqueId());

        String teamName = playerTeams.remove(player.getUniqueId());
        if (teamName == null) {
            return;
        }
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        unassignFromTeam(scoreboard, teamName, player);
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
