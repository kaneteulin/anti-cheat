package com.github.kaneteulin.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryType;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.Locale;

/**
 * 이동 검사: 비행(fly), 점프 높이/벽타기(jump), 낙하 피해 무시(nofall), 인벤토리 이동(invmove).
 * <p>
 * 서버 시간이 아니라 플레이어가 보낸 이동 정보 단위로 센다. 그래서 컴퓨터가 잠깐 멈췄다가
 * 밀린 정보를 한꺼번에 보내도(저사양 PC, 렉) 공중에 떠 있었던 것으로 잘못 보지 않는다.
 */
public final class MovementChecks implements Listener {

    private final AntiCheatPlugin plugin;
    private final Violations violations;

    public MovementChecks(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.violations = plugin.violations();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.data(player);
        Location from = event.getFrom();
        Location to = event.getTo();

        checkInventoryMove(player, data, from, to);

        boolean moved = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        if (!moved) {
            return;
        }

        if (Physics.movementExempt(player, data) || Physics.nearSpecial(to)) {
            if (Physics.nearSpecial(to) && Physics.bouncy(to)) {
                data.bounce = true;
            }
            data.resetAir(to.getY());
            return;
        }

        double dy = to.getY() - from.getY();

        if (Physics.grounded(to)) {
            data.bounce = Physics.bouncy(to);
            data.resetAir(to.getY());
            return;
        }

        // 착지 순간의 이동 정보는 움직임이 작으면 서버 이벤트로 오지 않을 때가 있다.
        // 그러면 착지를 못 보고 다음 점프를 공중에서 떠오른 것으로 오해하므로,
        // 올라가는 중이고 점프 한 번 높이(0.42칸) 바로 아래가 땅이면 거기서 점프한 것으로 본다.
        // 공중 점프 핵은 그 아래에 땅이 없으므로 여전히 잡힌다.
        if (dy > 0 && !data.bounce) {
            Location jumpStart = to.clone().subtract(0, 0.42 + 0.1 * Physics.jumpBoost(player), 0);
            if (Physics.grounded(jumpStart)) {
                data.resetAir(jumpStart.getY());
            }
        }

        data.airMoves++;
        data.maxY = Math.max(data.maxY, to.getY());

        // 점프 높이 / 벽타기: 땅을 떠난 뒤 정상 점프보다 높이 올라감
        if (!data.bounce && !data.jumpFlagged) {
            double ascent = data.maxY - data.groundY;
            double limit = 1.35 + 0.75 * Physics.jumpBoost(player);
            if (ascent > limit) {
                data.jumpFlagged = true;
                violations.flag(player, "jump", 1,
                        String.format(Locale.ROOT, "상승 %.2f칸 / 허용 %.2f칸", ascent, limit));
            }
        }

        // 비행: 점프 꼭대기(약 6번 이동)를 지났는데도 떨어지지 않음
        if (!data.bounce && data.airMoves > 8 && dy > -0.03) {
            data.hoverMoves++;
            if (data.hoverMoves >= 5) {
                data.hoverMoves = 0;
                violations.flag(player, "fly", 1,
                        String.format(Locale.ROOT, "공중 %d번 이동, 높이 변화 %+.3f", data.airMoves, dy));
            }
        } else if (dy <= -0.03) {
            data.hoverMoves = 0;
        }

        // 낙하 피해 무시: 떨어지는 중인데(발밑이 비어 있는데) 땅에 닿았다고 보냄
        @SuppressWarnings("deprecation")
        boolean claimsGround = player.isOnGround();
        if (claimsGround && dy < -0.05 && !Physics.grounded(from)) {
            data.groundSpoofMoves++;
            if (data.groundSpoofMoves >= 2) {
                data.groundSpoofMoves = 0;
                violations.flag(player, "nofall", 1,
                        String.format(Locale.ROOT, "공중에서 땅 신호, 낙하 %.2f칸", data.maxY - to.getY()));
            }
        } else {
            data.groundSpoofMoves = 0;
        }
    }

    /** 인벤토리 이동: 창이 열린 채 걷거나 시선을 돌림 (바닐라에서는 둘 다 불가능) */
    private void checkInventoryMove(Player player, PlayerData data, Location from, Location to) {
        long now = System.currentTimeMillis();
        // 자기 인벤토리(E 키)는 서버가 열림/닫힘을 모른다. 닫힘 신호를 놓치면 "열림"이 계속 남으므로,
        // 상자 같은 서버 창이 아니면 최근 2초 안에 클릭했을 때만 열린 것으로 본다
        if (data.inventoryOpenSince != 0 && player.getOpenInventory().getType() == InventoryType.CRAFTING
                && now - data.lastInvClick > 2000) {
            data.inventoryOpenSince = 0;
        }
        if (data.inventoryOpenSince == 0 || now - data.inventoryOpenSince < 500) {
            data.invMoveCount = 0;
            data.invRotateCount = 0;
            return;
        }
        if (Physics.movementExempt(player, data) || Physics.nearSpecial(to) || Physics.onIce(to)) {
            data.invMoveCount = 0;
            return;
        }

        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (horizontal > 0.15) {
            if (++data.invMoveCount >= 6) {
                data.invMoveCount = 0;
                violations.flag(player, "invmove", 1,
                        String.format(Locale.ROOT, "인벤토리를 연 채 이동 %.2f칸/회", horizontal));
            }
        } else {
            data.invMoveCount = 0;
        }

        float yaw = Math.abs(wrap(to.getYaw() - from.getYaw()));
        float pitch = Math.abs(to.getPitch() - from.getPitch());
        if (yaw > 5 || pitch > 5) {
            if (++data.invRotateCount >= 4) {
                data.invRotateCount = 0;
                violations.flag(player, "invmove", 1,
                        String.format(Locale.ROOT, "인벤토리를 연 채 시선 회전 %.0f°", Math.max(yaw, pitch)));
            }
        }
    }

    private static float wrap(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    // ---- 이동 검사를 잠시 멈춰야 하는 상황 ----

    private void exempt(Player player, long millis) {
        PlayerData data = plugin.data(player);
        data.exempt(millis);
        data.resetAir(player.getLocation().getY());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        exempt(event.getPlayer(), 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        exempt(event.getPlayer(), 2000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        exempt(event.getPlayer(), 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        exempt(event.getPlayer(), 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        exempt(event.getPlayer(), 2500);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player player) {
            exempt(player, 2500);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            exempt(player, 1500);
        }
    }

    // 바람 돌격, TNT, 엔드 수정 등 폭발: 피해 없이 날아갈 수 있음
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        exemptNearby(event.getLocation(), 10, 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent event) {
        exemptNearby(event.getBlock().getLocation(), 10, 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        exemptNearby(event.getBlock().getLocation(), 4 + event.getBlocks().size(), 1500);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        exemptNearby(event.getBlock().getLocation(), 4 + event.getBlocks().size(), 1500);
    }

    private void exemptNearby(Location center, double radius, long millis) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof Player player) {
                exempt(player, millis);
            }
        }
    }
}
