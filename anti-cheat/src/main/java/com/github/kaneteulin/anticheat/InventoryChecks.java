package com.github.kaneteulin.anticheat;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 인벤토리 검사: 인벤토리 열림 상태 추적(invmove 용), 자동 토템(autototem), 클릭 속도(invclick).
 * <p>
 * 플레이어가 E 키로 자기 인벤토리를 열 때는 서버에 알리지 않으므로, 첫 클릭부터 닫을 때까지를 "열림"으로 본다.
 * 상자 등 서버가 여는 창은 열린 순간부터 센다.
 */
public final class InventoryChecks implements Listener {

    private final AntiCheatPlugin plugin;
    private final Violations violations;

    public InventoryChecks(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.violations = plugin.violations();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.data(player).inventoryOpenSince = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.data(player).inventoryOpenSince = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        // 크리에이티브의 블록 집기(휠 클릭) 등은 인벤토리를 닫은 채로도 발생하므로 "열림"으로 보지 않는다
        if (!(event.getWhoClicked() instanceof Player player) || event instanceof InventoryCreativeEvent) {
            return;
        }
        PlayerData data = plugin.data(player);
        long now = System.currentTimeMillis();
        data.lastInvClick = now;

        if (data.inventoryOpenSince == 0) {
            data.inventoryOpenSince = now; // 자기 인벤토리: 첫 클릭부터 열린 것으로 봄
        }

        data.invClicks.addLast(now);

        // 자동 토템: 토템이 터진 뒤 사람이 반응할 수 없는 시간 안에 토템을 옮김
        if (involvesTotem(event.getCurrentItem()) || involvesTotem(event.getCursor())
                || (event.getHotbarButton() >= 0 && involvesTotem(player.getInventory().getItem(event.getHotbarButton())))) {
            checkTotem(player, data, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (involvesTotem(event.getOffHandItem())) {
            checkTotem(event.getPlayer(), plugin.data(event.getPlayer()), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.data(player).resurrectAt = System.currentTimeMillis();
        }
    }

    private void checkTotem(Player player, PlayerData data, long now) {
        if (data.resurrectAt == 0) {
            return;
        }
        long delay = now - data.resurrectAt;
        // 서버가 보기에 반응 시간 = 핑 + 사람의 반응 시간(최소 약 0.15초)
        long limit = Math.min(player.getPing(), 300) + 120;
        if (delay < limit) {
            data.resurrectAt = 0;
            violations.flag(player, "autototem", 1, "토템 발동 후 " + delay + "ms 만에 다시 장착 / 허용 " + limit + "ms");
        }
    }

    /** 1초마다 호출: 인벤토리 클릭 속도가 2초 연속 기준을 넘으면 기록 */
    void tickClicks(Player player, PlayerData data) {
        int clicks = PlayerData.countSince(data.invClicks, System.currentTimeMillis() - 1000);
        int max = violations.check("invclick").getInt("max-per-second", 40);
        if (clicks > max) {
            if (++data.highInvClickSeconds >= 2) {
                data.highInvClickSeconds = 0;
                violations.flag(player, "invclick", 1, "초당 " + clicks + "회 클릭 / 허용 " + max + "회");
            }
        } else {
            data.highInvClickSeconds = 0;
        }
    }

    private static boolean involvesTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }
}
