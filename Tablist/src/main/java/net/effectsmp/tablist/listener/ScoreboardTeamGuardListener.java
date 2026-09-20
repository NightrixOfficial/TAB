package net.effectsmp.tablist.listener;

import net.effectsmp.tablist.tab.TabListManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Re-asserts a player's Tablist-owned scoreboard team (the one behind sort
 * order and the above-head name prefix) right after they move, in case
 * another plugin's own move-triggered scoreboard logic knocked them onto a
 * different team in the meantime.
 * <p>
 * Why this exists: a scoreboard entry can only be on one team at a time,
 * and TabListManager.applySortingTeam() is deliberately NOT re-run on a
 * tight loop - only on join, on a LuckPerms rank change, or on the slow
 * (30s) safety-net timer, since re-sending identical team/prefix state
 * constantly would be wasteful. That's fine as long as nothing else is
 * fighting over the player's team slot. But some plugins (region/claim/
 * faction plugins in particular - see e.g. ClaimPlugin's CollisionManager,
 * which re-evaluates scoreboard team state as part of its own per-move
 * collision checks) reassign scoreboard teams as part of handling player
 * movement, and every such reassignment silently evicts the player from
 * whichever team Tablist last put them on. Without something reacting to
 * that quickly, the visible symptom is exactly "the name-prefix above my
 * head turns off while moving, and only comes back after standing still
 * for a while" - nothing was going to notice and fix that for up to 30
 * seconds otherwise.
 * <p>
 * MONITOR priority so this runs after any other plugin's own move handling
 * has already had its chance to (re)assign a team, meaning we get the last
 * word for that movement. Only reacts when the player's block position
 * actually changed (not pure look-direction changes, which fire
 * PlayerMoveEvent just as often) to keep this very hot event cheap - the
 * reassertion itself is also a near-no-op when nothing has actually
 * changed, via applySortingTeam's own early-return guard.
 */
public class ScoreboardTeamGuardListener implements Listener {

    private final TabListManager tabListManager;

    public ScoreboardTeamGuardListener(TabListManager tabListManager) {
        this.tabListManager = tabListManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            // Pure look-direction change - no reason another plugin's
            // spatial team logic (claims, regions, etc.) would have acted.
            return;
        }

        Player player = event.getPlayer();
        tabListManager.ensureScoreboardTeam(player);
    }
}
