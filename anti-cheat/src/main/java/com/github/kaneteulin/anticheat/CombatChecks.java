package com.github.kaneteulin.anticheat;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * 전투 검사: 공격 거리(reach), 벽 너머 공격(wallhit), 치명타 조작(criticals), 오토클릭(autoclicker), 활 연사(fastbow).
 */
public final class CombatChecks implements Listener {

    private final AntiCheatPlugin plugin;
    private final Violations violations;

    public CombatChecks(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.violations = plugin.violations();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Entity target = event.getEntity();
        BoundingBox box = target.getBoundingBox();
        PlayerData data = plugin.data(player);

        // 공격 거리: 눈에서 대상의 몸(상자)까지. 핑만큼 대상이 움직였을 수 있어 여유를 준다
        double distance = Physics.distance(player.getEyeLocation().toVector(), box);
        double range = attribute(player, Attribute.ENTITY_INTERACTION_RANGE, 3.0);
        double limit = range + 0.5 + Math.min(player.getPing(), 500) / 1000.0 * 4.0;
        if (distance > limit) {
            violations.flag(player, "reach", 1 + (distance - limit),
                    String.format(Locale.ROOT, "거리 %.2f칸 / 허용 %.2f칸", distance, limit));
        }

        // 벽 너머 공격: 대상의 어느 부분도 보이지 않고, 조준선도 블록에 막힘
        if (!Physics.anyVisible(player, box, null) && !Physics.crosshairVisible(player, box)) {
            violations.flag(player, "wallhit", 1, "대상 " + target.getType().name().toLowerCase(Locale.ROOT));
        }

        // 치명타 조작: 점프하거나 떨어지지 않았는데 치명타
        if (event.isCritical()) {
            double ascent = data.maxY - data.groundY;
            double drop = data.maxY - player.getLocation().getY();
            if (data.airMoves == 0 || (ascent < 0.2 && drop < 0.2)) {
                violations.flag(player, "criticals", 1,
                        String.format(Locale.ROOT, "점프 높이 %.2f칸, 낙하 %.2f칸", Math.max(ascent, 0), Math.max(drop, 0)));
            }
        }
    }

    /** 팔 휘두르기 = 왼쪽 클릭. 블록을 캐는 중에는 계속 휘두르므로 제외 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        PlayerData data = plugin.data(event.getPlayer());
        if (!data.digging) {
            data.swings.addLast(System.currentTimeMillis());
        }
    }

    /** 1초마다 호출: 초당 클릭 수가 2초 연속 기준을 넘으면 기록 */
    void tickClicks(Player player, PlayerData data) {
        int cps = PlayerData.countSince(data.swings, System.currentTimeMillis() - 1000);
        int max = violations.check("autoclicker").getInt("max-cps", 25);
        if (cps > max) {
            if (++data.highCpsSeconds >= 2) {
                data.highCpsSeconds = 0;
                violations.flag(player, "autoclicker", 1, "초당 " + cps + "회 클릭 / 허용 " + max + "회");
            }
        } else {
            data.highCpsSeconds = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack bow = event.getBow();
        if (bow == null || bow.getType() != Material.BOW) {
            return; // 쇠뇌는 장전 시간이 있어 제외
        }

        PlayerData data = plugin.data(player);
        long now = System.currentTimeMillis();
        data.bowShots.addLast(now);
        int shots = PlayerData.countSince(data.bowShots, now - 1000);
        int max = violations.check("fastbow").getInt("max-per-second", 10);
        if (shots > max) {
            data.bowShots.clear();
            violations.flag(player, "fastbow", 1, "1초에 " + shots + "발 / 허용 " + max + "발");
        }
    }

    static double attribute(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance != null ? instance.getValue() : fallback;
    }
}
