package net.effectsmp.tablist.tab;

import net.effectsmp.tablist.TablistPlugin;
import net.effectsmp.tablist.hook.LuckPermsHook;
import net.effectsmp.tablist.util.PixelWidth;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Drives everything about the tab list: per-player ping + LuckPerms prefix
 * formatting, the purple header/footer (or the image banner), and the
 * scoreboard teams used both for the above-head name tag and for sorting
 * the tab list by LuckPerms group weight (highest weight = highest role,
 * shown at the top).
 * <p>
 * Important design note: every online player gets their OWN scoreboard
 * team (never shared between players), even though several players can
 * have the same weight. This matches how established tab-list plugins do
 * it - sharing one team between players means only one of them can "own"
 * that team's prefix at a time, which is what caused prefixes/sort order
 * to flicker when 2+ players were online. Weight is still encoded as the
 * front of the team name, so same-weight players still sort next to each
 * other; they just don't share the actual Team object.
 * <p>
 * LuckPerms itself is only queried on join, when LuckPerms tells us a
 * user's data changed (see LuckPermsHook), and on a slow safety-net timer -
 * never on the once-a-second tick that redraws ping. That tick only ever
 * reads out of the lastKnownWeight/lastKnownPrefix caches below, so it
 * can't be affected by a LuckPerms lookup being mid-recalculation.
 */
public class TabListManager {

    // Highest supported weight for sort-key purposes. Weights above this are
    // just clamped - they'll still sort correctly relative to each other,
    // they just won't get their own distinct rung above this ceiling.
    private static final int MAX_SORT_WEIGHT = 99_999;

    // Matches exactly what teamNameFor() produces: "w" + 5-digit sort key +
    // 8 hex chars of the player's UUID. Used to recognize a team as one WE
    // registered before we ever remove entries from it or unregister it -
    // see the big comment on isOwnSortTeam() for why this check exists.
    private static final Pattern OWN_SORT_TEAM_NAME = Pattern.compile("^w\\d{5}[0-9a-f]{8}$");

    // How often we re-check LuckPerms for everyone as a safety net, in case
    // an event was ever missed. This is deliberately slow (30s) since it
    // should rarely matter - it's a backstop, not the primary update path.
    private static final long LUCKPERMS_SAFETY_NET_PERIOD_TICKS = 20L * 30L;

    private final TablistPlugin plugin;
    private final LuckPermsHook luckPermsHook;

    // The last value we actually read from LuckPerms for each player. The
    // once-a-second display tick only ever reads these - it never calls
    // LuckPerms itself.
    private final Map<UUID, Integer> lastKnownWeight = new HashMap<>();
    private final Map<UUID, String> lastKnownPrefix = new HashMap<>();

    private BukkitTask displayTask;
    private BukkitTask luckPermsSafetyNetTask;

    // The team/prefix text we actually last SENT for each player, distinct
    // from lastKnownPrefix (which updates on every real LuckPerms read).
    // Used to skip re-sending identical scoreboard state - see
    // applySortingTeam for why that matters.
    private final Map<UUID, String> lastAppliedTeamPrefix = new HashMap<>();

    private boolean tabPrefixEnabled;
    private boolean namePrefixEnabled;
    private boolean sortByWeight;
    private boolean vanillaMode;

    private Component headerImageComponent;
    private Component dividerComponent;

    public TabListManager(TablistPlugin plugin, LuckPermsHook luckPermsHook) {
        this.plugin = plugin;
        this.luckPermsHook = luckPermsHook;
        reloadToggles();
        this.headerImageComponent = loadFontComponent("pack/header_chars.txt", "header");
        this.dividerComponent = loadFontComponent("pack/divider_char.txt", "divider");
    }

    private void reloadToggles() {
        FileConfiguration config = plugin.getConfig();
        this.tabPrefixEnabled = config.getBoolean("luckperms.tab-prefix", false);
        this.namePrefixEnabled = config.getBoolean("luckperms.name-prefix", false);
        this.sortByWeight = config.getBoolean("luckperms.sort-by-weight", true);
        this.vanillaMode = config.getBoolean("vanilla-mode", false);
    }

    /** Loads a font-keyed component from a bundled resource-pack chars file
     * (e.g. "pack/header_chars.txt" -> font "effectsmp:header"). */
    private Component loadFontComponent(String resourcePath, String fontKey) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return null;
            }
            String chars = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Component.text(chars).font(Key.key("effectsmp", fontKey));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    public void start() {
        this.displayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L);
        if (luckPermsHook != null) {
            this.luckPermsSafetyNetTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::refreshAllFromLuckPerms, LUCKPERMS_SAFETY_NET_PERIOD_TICKS, LUCKPERMS_SAFETY_NET_PERIOD_TICKS);
        }
    }

    public void stop() {
        if (displayTask != null) {
            displayTask.cancel();
            displayTask = null;
        }
        if (luckPermsSafetyNetTask != null) {
            luckPermsSafetyNetTask.cancel();
            luckPermsSafetyNetTask = null;
        }
    }

    /** Cheap, once-a-second redraw: ping plus whatever we already know about
     * everyone's rank. Deliberately does not touch LuckPerms. */
    private void tick() {
        Component header = buildHeader();
        Component footer = buildFooter();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateDisplayName(player);
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    private void refreshAllFromLuckPerms() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    /**
     * Re-reads this player's LuckPerms prefix/weight, updates the cache, and
     * re-applies their sort team and displayed name. Call this when their
     * LuckPerms data has (or might have) actually changed - on join, from
     * the LuckPerms data-changed callback, from the safety-net timer, or
     * from the /display toggles. Do NOT call this from a tight, frequent
     * loop - use updateDisplayName for that instead.
     */
    public void updatePlayer(Player player) {
        if (luckPermsHook != null) {
            String prefix = luckPermsHook.getPrefix(player);
            if (prefix != null) {
                lastKnownPrefix.put(player.getUniqueId(), prefix);
            }
            if (sortByWeight) {
                Integer weight = luckPermsHook.getHighestWeight(player);
                if (weight != null) {
                    lastKnownWeight.put(player.getUniqueId(), weight);
                }
            } else {
                lastKnownWeight.put(player.getUniqueId(), 0);
            }
        }
        applySortingTeam(player);
        updateDisplayName(player);
    }

    /**
     * Cheap safety-net for the scoreboard team specifically (sort order +
     * the above-head name prefix) - unlike updatePlayer(), this never
     * touches LuckPerms, it just re-applies already-cached weight/prefix
     * values via applySortingTeam(). Meant to be called from a hot path
     * (see ScoreboardTeamGuardListener) to catch another plugin's own
     * scoreboard-team logic knocking a player off ours between the rarer
     * updatePlayer() calls (join / LuckPerms change / 30s safety net).
     * applySortingTeam() already bails out immediately once the team and
     * prefix already match what we want, so repeated calls here are a
     * near-no-op in the common case where nothing has interfered.
     */
    public void ensureScoreboardTeam(Player player) {
        if (!sortByWeight && !namePrefixEnabled) {
            // Neither feature that needs an exclusive team is on - don't
            // bother fighting anyone for the player's team slot.
            return;
        }
        applySortingTeam(player);
    }

    /** Rebuilds and sends the visible tab-list name from the ping (live) and
     * cached prefix (not re-queried here). Safe to call every tick. */
    private void updateDisplayName(Player player) {
        FileConfiguration config = plugin.getConfig();
        String rawPrefix = tabPrefixEnabled ? lastKnownPrefix.getOrDefault(player.getUniqueId(), "") : "";
        Component ping = buildPingComponent(player);

        Component listName;
        if (vanillaMode) {
            // Plain vanilla-looking entry: no ping text, no alignment
            // padding - just the LuckPerms prefix (if that's still
            // separately enabled) and the player's name.
            Component prefix = tabPrefixEnabled ? TextUtil.legacy(rawPrefix) : Component.empty();
            listName = MiniMessage.miniMessage().deserialize(
                    "<prefix><white><name>",
                    Placeholder.component("prefix", prefix),
                    Placeholder.unparsed("name", player.getName())
            );
        } else if (config.getBoolean("ping.align", true)) {
            listName = buildAlignedName(player, rawPrefix, ping, config.getInt("ping.align-column", 130));
        } else {
            Component prefix = TextUtil.legacy(rawPrefix);
            listName = MiniMessage.miniMessage().deserialize(
                    getFormat(),
                    Placeholder.component("prefix", prefix),
                    Placeholder.unparsed("name", player.getName()),
                    Placeholder.component("ping", ping)
            );
        }

        player.playerListName(listName);
    }

    private static final String DEFAULT_FORMAT = "<prefix><white><bold><name></bold> <dark_purple>» <ping>";

    private String getFormat() {
        return plugin.getConfig().getString("format", DEFAULT_FORMAT);
    }

    // Width (in-game pixels) of a single plain space in Minecraft's font -
    // that's the smallest unit we can pad with, since we deliberately don't
    // rely on the resource pack being installed for this (see below).
    private static final int SPACE_WIDTH = 4;

    /**
     * Same "format" template used everywhere else (so <bold> around <name>,
     * colors, the separator - all of it - come from that one config option,
     * same as when alignment is off), but with enough spaces spliced in
     * right before <ping> to push it out to roughly alignColumn in-game
     * pixels from the start of the line. That's how the ping ends up in
     * about the same spot regardless of how long any given player's
     * name/prefix is, without needing a separate hardcoded template or a
     * bold on/off setting of its own.
     * <p>
     * This deliberately pads with plain spaces rather than a custom
     * resource-pack character: the divider/banner font is only sent to
     * players when header-image.enabled is on, and this needs to work
     * whether or not that's the case (a missing custom glyph shows as a
     * visible "missing texture" box, which would be worse than imprecise
     * alignment). The trade-off is coarser granularity - it can land a few
     * pixels short of alignColumn, never over.
     * <p>
     * The pixel widths behind this (PixelWidth) are a best-effort
     * approximation of Minecraft's font, not something read back from a
     * real client, so alignColumn (ping.align-column in config) is meant
     * to be nudged a few pixels either way if it's off on your screen -
     * that's a one-line config change, no rebuild needed.
     */
    private Component buildAlignedName(Player player, String rawPrefix, Component ping, int alignColumn) {
        String plainPrefix = PixelWidth.stripCodes(rawPrefix);
        String name = player.getName();
        String format = getFormat();

        int pingIndex = format.indexOf("<ping>");
        String prePingTemplate = pingIndex >= 0 ? format.substring(0, pingIndex) : format;
        // Only <prefix>/<name> matter for measuring - substitute those with
        // plain text and measure whatever MiniMessage tags (<bold> above
        // all) are around them directly from the format string itself.
        String measureText = prePingTemplate.replace("<prefix>", plainPrefix).replace("<name>", name);
        int usedWidth = PixelWidth.widthOfTemplate(measureText);

        int gap = Math.max(SPACE_WIDTH, alignColumn - usedWidth);
        int spaceCount = gap / SPACE_WIDTH;
        Component padding = Component.text(" ".repeat(spaceCount));

        String alignedFormat = pingIndex >= 0
                ? format.substring(0, pingIndex) + "<padding>" + format.substring(pingIndex)
                : format + "<padding><ping>"; // no <ping> in their format - tack it on the end

        Component prefix = TextUtil.legacy(rawPrefix);
        return MiniMessage.miniMessage().deserialize(
                alignedFormat,
                Placeholder.component("prefix", prefix),
                Placeholder.unparsed("name", name),
                Placeholder.component("padding", padding),
                Placeholder.component("ping", ping)
        );
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
        return TextUtil.miniMessage(colorTag + "● " + ping + "ms");
    }

    /**
     * Every player gets their own team (never shared) so that setting this
     * player's prefix can never stomp on another player's. The weight is
     * baked into the front of the team name so the vanilla client - which
     * sorts the tab list ascending by team name - still groups and orders
     * players by weight.
     * <p>
     * This checks the LIVE scoreboard for what team the player is
     * currently actually on (scoreboard.getEntryTeam), not just our own
     * playerTeams cache. A Bukkit scoreboard entry can only be on one team
     * at a time, so if anything else on the server (another plugin using
     * scoreboard teams for its own purposes - nametag colors, factions,
     * whatever) ever puts this player on a DIFFERENT team, our cache would
     * still think they're correctly on ours, and the old
     * cache-only no-op guard would then skip fixing it - which is exactly
     * what was showing up as ranks scrambling every ~30 seconds. Checking
     * the real scoreboard state means the safety-net timer actually
     * re-asserts our team every time something else has knocked a player
     * off it, instead of trusting a cache that can silently go stale.
     * <p>
     * It also uses player.getScoreboard() rather than always the server's
     * main scoreboard. A client only sees/sorts by the teams that exist on
     * WHATEVER scoreboard is currently assigned to it - if any other
     * plugin (a sidebar/HUD plugin, most commonly) ever calls
     * player.setScoreboard(...) with a scoreboard other than the main one,
     * teams we register on the main scoreboard would never reach that
     * player's client at all, and their tab list would silently fall back
     * to plain vanilla alphabetical sorting with no error or warning -
     * which matches "it's just using default vanilla sorting" exactly.
     * player.getScoreboard() always returns whatever's actually currently
     * assigned to them (the main scoreboard by default, same as before,
     * if nothing else has changed it), so this is a strict improvement.
     */
    private void applySortingTeam(Player player) {
        UUID uuid = player.getUniqueId();
        int weight = lastKnownWeight.getOrDefault(uuid, 0);
        String newTeamName = teamNameFor(uuid, weight);
        String desiredPrefix = namePrefixEnabled ? lastKnownPrefix.getOrDefault(uuid, "") : "";

        Scoreboard scoreboard = player.getScoreboard();
        Team currentTeam = scoreboard.getEntryTeam(player.getName());
        String actualTeamName = currentTeam != null ? currentTeam.getName() : null;

        boolean sameTeam = newTeamName.equals(actualTeamName);
        boolean samePrefix = desiredPrefix.equals(lastAppliedTeamPrefix.get(uuid));
        if (sameTeam && samePrefix) {
            // Nothing has actually changed (and nothing else has moved
            // this player off our team) since we last touched the
            // scoreboard for this player - skip re-sending team packets.
            // Without this guard, anything that ends up calling
            // updatePlayer often (e.g. LuckPerms recalculating data for
            // reasons unrelated to rank) would keep re-sending identical
            // team state, which is exactly what shows up in-game as
            // flicker even though the underlying values never changed.
            return;
        }

        if (currentTeam != null && !sameTeam && isOwnSortTeam(actualTeamName)) {
            // Only clean up a team WE previously registered for this
            // player (e.g. their old weight-rung team, now that their
            // weight changed). If the player is currently on some OTHER
            // plugin's team instead (a faction/claim/nametag plugin also
            // using scoreboard teams), we must never touch it here -
            // team.addEntry() below already moves the player off whatever
            // team they're on (that's documented Bukkit behavior: adding
            // an entry to a team removes it from any other team on the
            // same scoreboard), so we don't need to call removeEntry/
            // unregister on a team we don't own to make that happen.
            // Calling unregister() on a foreign team is destructive to
            // whichever plugin created it - if that plugin is still
            // holding a reference to that Team object (e.g. cached
            // elsewhere and checked on a later event), any further call
            // into it throws "Unregistered scoreboard component", which
            // is exactly the crash this guard prevents.
            currentTeam.removeEntry(player.getName());
            if (currentTeam.getEntries().isEmpty()) {
                currentTeam.unregister();
            }
        }

        Team team = scoreboard.getTeam(newTeamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(newTeamName);
        }
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        team.prefix(TextUtil.legacy(desiredPrefix));
        lastAppliedTeamPrefix.put(uuid, desiredPrefix);
    }

    /**
     * Builds a scoreboard team name unique to this player that sorts higher
     * for higher weight (the vanilla client sorts the tab list ascending by
     * team name). Kept to 16 characters for compatibility.
     */
    private String teamNameFor(UUID uuid, int weight) {
        int clamped = Math.max(0, Math.min(weight, MAX_SORT_WEIGHT));
        int sortKey = MAX_SORT_WEIGHT - clamped;
        String uniquePart = uuid.toString().replace("-", "").substring(0, 8);
        return "w" + String.format("%05d", sortKey) + uniquePart;
    }

    /**
     * True if teamName matches the exact shape teamNameFor() produces, i.e.
     * this is a sorting team Tablist itself registered - not some other
     * plugin's team that a player just happens to currently be on. We only
     * ever remove entries from / unregister teams that pass this check;
     * see the comments in applySortingTeam() and removePlayer() for why.
     */
    private boolean isOwnSortTeam(String teamName) {
        return teamName != null && OWN_SORT_TEAM_NAME.matcher(teamName).matches();
    }

    private Component buildHeader() {
        if (vanillaMode) {
            return Component.empty();
        }
        if (plugin.getConfig().getBoolean("header-image.enabled", false) && headerImageComponent != null) {
            if (plugin.getConfig().getBoolean("header-image.show-divider", true) && dividerComponent != null) {
                return headerImageComponent.appendNewline().append(dividerComponent);
            }
            return headerImageComponent;
        }
        String fallback = plugin.getConfig().getString("header-image.fallback-header", "");
        return TextUtil.miniMessage(fallback);
    }

    private Component buildFooter() {
        if (vanillaMode || !plugin.getConfig().getBoolean("tab-list.footer-enabled", true)) {
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
        lastAppliedTeamPrefix.remove(player.getUniqueId());

        // Look up whatever team they're actually on right now (on THEIR
        // scoreboard, not necessarily the main one) rather than trusting
        // the (possibly stale) cache - same reasoning as applySortingTeam
        // above.
        Scoreboard scoreboard = player.getScoreboard();
        Team team = scoreboard.getEntryTeam(player.getName());
        // Same ownership check as applySortingTeam(): only clean up a team
        // that's actually ours. If the player quit while on some other
        // plugin's team, that plugin owns removing/unregistering it, not us.
        if (team == null || !isOwnSortTeam(team.getName())) {
            return;
        }
        team.removeEntry(player.getName());
        if (team.getEntries().isEmpty()) {
            team.unregister();
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

    public boolean isVanillaMode() {
        return vanillaMode;
    }

    /**
     * Turns Tablist's own look (header/footer, ping, bold/alignment) on or
     * off. This intentionally does NOT touch tabPrefixEnabled,
     * namePrefixEnabled or sortByWeight - those are controlled separately
     * with /display, and keep applying (if they were already on) whether
     * vanilla mode is on or off, per the /tablist display command.
     */
    public void setVanillaMode(boolean vanillaMode) {
        this.vanillaMode = vanillaMode;
        plugin.getConfig().set("vanilla-mode", vanillaMode);
        plugin.saveConfig();
        Bukkit.getOnlinePlayers().forEach(this::updatePlayer);
    }
}
