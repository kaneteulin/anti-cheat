package com.github.kaneteulin.anticheat;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * 블록 검사: 빠른 채굴(fastbreak), 대량 채굴(nuker), 벽 너머 상자 열기(wallinteract).
 */
public final class BlockChecks implements Listener {

    private final AntiCheatPlugin plugin;
    private final Violations violations;

    public BlockChecks(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.violations = plugin.violations();
    }

    /** 블록을 캐기 시작함 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStartDigging(BlockDamageEvent event) {
        PlayerData data = plugin.data(event.getPlayer());
        if (event.getInstaBreak()) {
            data.digging = false;
            return;
        }
        data.digBlock = event.getBlock();
        data.digStart = System.currentTimeMillis();
        data.digSpeed = event.getBlock().getBreakSpeed(event.getPlayer());
        data.digging = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbortDigging(BlockDamageAbortEvent event) {
        PlayerData data = plugin.data(event.getPlayer());
        data.digging = false;
        data.digBlock = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            return;
        }

        PlayerData data = plugin.data(player);
        Block block = event.getBlock();
        long now = System.currentTimeMillis();

        // 지금 상태 기준 채굴 속도 (1틱에 쌓이는 진행도). 시작할 때와 지금 중 더 빠른 쪽으로 너그럽게 계산
        float speed = Math.max(block.getBreakSpeed(player), block.equals(data.digBlock) ? data.digSpeed : 0);
        boolean instant = speed >= 1.0f;

        if (!instant) {
            // 대량 채굴: 캐는 데 시간이 걸리는 블록을 1초에 너무 많이 캠
            // (바닐라는 블록 하나를 캔 뒤 다음 블록까지 5틱을 기다려야 해서 초당 3개 정도가 한계)
            data.breaks.addLast(now);
            int count = PlayerData.countSince(data.breaks, now - 1000);
            if (count > 6) {
                data.breaks.clear();
                violations.flag(player, "nuker", 1, "1초에 " + count + "개");
            }

            if (block.equals(data.digBlock) && data.digging) {
                // 빠른 채굴: 정상 채굴 시간의 60% 보다 빨리 캠 (렉 여유 0.1초)
                long expected = (long) Math.ceil(1.0 / speed) * 50L;
                long elapsed = now - data.digStart;
                long minimum = (long) (expected * 0.6) - 100;
                if (elapsed < minimum) {
                    violations.flag(player, "fastbreak", 1, String.format(Locale.ROOT,
                            "%s %dms / 정상 %dms", block.getType().name().toLowerCase(Locale.ROOT), elapsed, expected));
                }
            } else {
                // 캐기 시작한 기록 없이 부서짐 (다른 블록을 캐는 중이거나 시작 신호 없음)
                violations.flag(player, "nuker", 0.5, "캐기 시작 없이 부숨: " + block.getType().name().toLowerCase(Locale.ROOT));
            }
        }

        data.digging = false;
        data.digBlock = null;
    }

    /** 벽 너머 상자 열기: 상자의 어느 부분도 보이지 않는데 염 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Block block = event.getClickedBlock();
        if (!(block.getState(false) instanceof Container) && block.getType() != Material.ENDER_CHEST) {
            return;
        }

        BoundingBox box = BoundingBox.of(block);
        if (!Physics.anyVisible(player, box, block)) {
            violations.flag(player, "wallinteract", 1, block.getType().name().toLowerCase(Locale.ROOT));
        }
    }
}
