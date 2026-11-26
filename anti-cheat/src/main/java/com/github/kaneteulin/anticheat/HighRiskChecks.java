package com.github.kaneteulin.anticheat;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * 오탐 위험이 높은 검사: 타이머(timer), 이동 속도(speed), 넉백 무시(velocity), 킬오라(killaura),
 * 겉날개 비행(elytra), 블록 통과(phase), 자동 다리 놓기(scaffold).
 * <p>
 * 정상 플레이에서도 걸릴 수 있으므로 기준을 넉넉하게 잡았다. 실제 처벌 전에 오탐 테스트가 필요하다.
 */
public final class HighRiskChecks implements Listener {

    private final AntiCheatPlugin plugin;
    private final Violations violations;

    public HighRiskChecks(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.violations = plugin.violations();
    }

    // ---------------- 타이머 ----------------

    /** 1초마다 호출: 네트워크 스레드에서 쌓인 타이머 위반을 기록 */
    void tickTimer(Player player, PlayerData data) {
        int flags = data.timer.takeFlags();
        if (flags > 0) {
            violations.flag(player, "timer", flags, String.format(Locale.ROOT,
                    "클라이언트 틱이 정상보다 %.0fms 앞섬", data.timer.lastBalance()));
        }
    }

    // ---------------- 이동: 속도, 겉날개, 블록 통과 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.data(player);
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()) {
            return;
        }

        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.getAllowFlight()
                || player.isInsideVehicle() || player.hasPermission("anticheat.bypass")) {
            return;
        }

        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);

        if (player.isGliding()) {
            checkElytra(player, data, horizontal, dy);
            return;
        }
        data.glideAscendMoves = 0;
        data.glideHoverMoves = 0;

        if (data.isExemptNow() || player.isRiptiding()) {
            return;
        }

        checkPhase(player, data, from, to, horizontal);

        if (Physics.movementExempt(player, data) || Physics.nearSpecial(to)) {
            data.speedBuffer = 0;
            return;
        }
        checkSpeed(player, data, from, to, horizontal);
    }

    /** 이동 속도: 이동 속도 능력치(달리기, 신속 효과, 영혼 가속 포함)로 계산한 최대 속도를 계속 넘음 */
    private void checkSpeed(Player player, PlayerData data, Location from, Location to, double horizontal) {
        double attribute = CombatChecks.attribute(player, Attribute.MOVEMENT_SPEED, 0.1);
        boolean ground = Physics.grounded(from) && Physics.grounded(to);
        // 땅: 달리기 약 0.28칸/회, 공중: 달리며 점프하는 순간 약 0.61칸/회 (능력치 0.13 기준)
        double limit = attribute / 0.1 * (ground ? 0.32 : 0.65) + 0.05;
        if (Physics.onIce(from) || Physics.onIce(to)) {
            limit *= 2.6;
        }

        if (horizontal > limit) {
            data.speedBuffer += 1;
            if (data.speedBuffer >= 4) {
                data.speedBuffer = 2;
                violations.flag(player, "speed", 1, String.format(Locale.ROOT,
                        "%.2f칸/회 / 허용 %.2f칸/회 (%s)", horizontal, limit, ground ? "땅" : "공중"));
            }
        } else {
            data.speedBuffer = Math.max(0, data.speedBuffer - 0.5);
        }
    }

    /** 블록 통과: 몸이 단단한 블록 안으로 들어가거나, 한 번에 벽을 뚫고 지나감 */
    private void checkPhase(Player player, PlayerData data, Location from, Location to, double horizontal) {
        BoundingBox current = player.getBoundingBox();
        double half = current.getWidthX() / 2 - 0.1;
        double height = current.getHeight() - 0.2;

        BoundingBox before = new BoundingBox(from.getX() - half, from.getY() + 0.1, from.getZ() - half,
                from.getX() + half, from.getY() + 0.1 + height, from.getZ() + half);
        BoundingBox after = new BoundingBox(to.getX() - half, to.getY() + 0.1, to.getZ() - half,
                to.getX() + half, to.getY() + 0.1 + height, to.getZ() + half);

        boolean inside = to.getWorld().hasCollisionsIn(after) && !from.getWorld().hasCollisionsIn(before);

        boolean through = false;
        Vector move = to.toVector().subtract(from.toVector());
        if (move.length() > 1.0) {
            Location start = from.clone().add(0, 0.5, 0);
            RayTraceResult hit = from.getWorld().rayTraceBlocks(start, move.clone().normalize(), move.length(),
                    FluidCollisionMode.NEVER, true);
            through = hit != null && hit.getHitBlock() != null && hit.getHitBlock().getType().isSolid();
        }

        if (inside || through) {
            if (++data.phaseBuffer >= 2) {
                data.phaseBuffer = 0;
                violations.flag(player, "phase", 1, inside ? "몸이 블록 안으로 들어감"
                        : String.format(Locale.ROOT, "한 번에 %.2f칸 이동하며 블록을 통과", move.length()));
            }
        } else {
            data.phaseBuffer = 0;
        }
    }

    /** 겉날개: 폭죽 없이 오래 상승, 비정상적인 속도, 공중 정지 */
    private void checkElytra(Player player, PlayerData data, double horizontal, double dy) {
        if (data.isExemptNow() || player.isRiptiding() || player.isInWater()) {
            data.glideAscendMoves = 0;
            data.glideHoverMoves = 0;
            return;
        }
        boolean boosted = System.currentTimeMillis() < data.elytraBoostUntil;

        if (dy > 0.05 && !boosted) {
            if (++data.glideAscendMoves >= 80) { // 약 4초 이상 폭죽 없이 상승
                data.glideAscendMoves = 0;
                violations.flag(player, "elytra", 1, "폭죽 없이 계속 상승");
            }
        } else {
            data.glideAscendMoves = 0;
        }

        double speed = Math.sqrt(horizontal * horizontal + dy * dy);
        if (speed > 4.2) {
            violations.flag(player, "elytra", 1, String.format(Locale.ROOT, "속도 %.2f칸/회 / 허용 4.20칸/회", speed));
        }

        if (horizontal < 0.05 && Math.abs(dy) < 0.02) {
            if (++data.glideHoverMoves >= 20) {
                data.glideHoverMoves = 0;
                violations.flag(player, "elytra", 1, "활공 중 공중 정지");
            }
        } else {
            data.glideHoverMoves = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        plugin.data(event.getPlayer()).elytraBoostUntil = System.currentTimeMillis() + 4000;
    }

    // ---------------- 넉백 무시 ----------------

    /** 넉백을 받은 뒤 (핑 + 0.4초) 동안 넉백 방향으로 거의 움직이지 않음 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        Vector velocity = event.getVelocity();
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
        if (horizontal < 0.2 || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Vector direction = new Vector(velocity.getX(), 0, velocity.getZ()).normalize();
        Location start = player.getLocation();

        // 바로 뒤에 벽이 있으면 밀려날 수 없으므로 검사하지 않음
        BoundingBox behind = player.getBoundingBox().shift(direction.clone().multiply(0.6)).expand(-0.05);
        if (player.getWorld().hasCollisionsIn(behind)) {
            return;
        }

        long ticks = Math.max(6, (Math.min(player.getPing(), 600) + 400) / 50);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.isDead() || player.getWorld() != start.getWorld()
                    || player.isInsideVehicle() || player.isInWater() || player.isGliding() || player.isFlying()
                    || Physics.nearSpecial(player.getLocation())) {
                return;
            }
            double moved = player.getLocation().toVector().subtract(start.toVector()).dot(direction);
            double required = horizontal * 0.15;
            if (moved < required) {
                violations.flag(player, "velocity", 1, String.format(Locale.ROOT,
                        "넉백 방향 이동 %.2f칸 / 최소 %.2f칸 (넉백 세기 %.2f)", moved, required, horizontal));
            }
        }, ticks);
    }

    // ---------------- 킬오라 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Entity target = event.getEntity();
        PlayerData data = plugin.data(player);
        long now = System.currentTimeMillis();

        // 보고 있는 방향과 대상 방향의 각도 (바로 옆 대상은 제외)
        Location eye = player.getEyeLocation();
        Vector toTarget = target.getBoundingBox().getCenter().subtract(eye.toVector());
        if (toTarget.length() > 1.5) {
            double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
            if (angle > 75) {
                violations.flag(player, "killaura", 1, String.format(Locale.ROOT, "대상과 시선 각도 %.0f° / 허용 75°", angle));
            }
        }

        // 거의 동시에(0.05초 안에) 서로 다른 방향의 대상을 공격.
        // 몹 농장처럼 앞에 몰려 있는 대상을 빠르게 때리면 두 공격이 같은 서버 틱에 처리될 수 있으므로,
        // 두 대상의 방향이 45° 이상 벌어졌을 때만 (주변을 돌아가며 때리는 킬오라) 기록한다
        Vector direction = toTarget.clone().setY(0);
        if (data.lastAttackTarget != 0 && data.lastAttackTarget != target.getEntityId() && now - data.lastAttackAt < 50
                && data.lastAttackDirection != null && direction.lengthSquared() > 1e-6
                && data.lastAttackDirection.lengthSquared() > 1e-6) {
            double spread = Math.toDegrees(direction.angle(data.lastAttackDirection));
            if (spread >= 45) {
                violations.flag(player, "killaura", 0.5,
                        String.format(Locale.ROOT, "0.05초 안에 서로 다른 대상 공격 (방향 차이 %.0f°)", spread));
            }
        }
        data.lastAttackTarget = target.getEntityId();
        data.lastAttackAt = now;
        data.lastAttackDirection = direction;
    }

    // ---------------- 자동 다리 놓기 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        BlockFace face = against.getFace(placed);
        if (face == null || face == BlockFace.SELF) {
            return;
        }

        // 클릭한 면이 시야 밖 (뒤쪽)
        Location eye = player.getEyeLocation();
        Vector clicked = against.getBoundingBox().getCenter().add(face.getDirection().multiply(0.5));
        double angle = Math.toDegrees(eye.getDirection().angle(clicked.subtract(eye.toVector())));
        if (angle > 100) {
            violations.flag(player, "scaffold", 1, String.format(Locale.ROOT, "보지 않는 면에 설치 (각도 %.0f°)", angle));
        }

        // 발밑에 1초에 12개 넘게 설치
        Location feet = player.getLocation();
        if (placed.getY() < feet.getY() && Math.abs(placed.getX() + 0.5 - feet.getX()) < 1.5
                && Math.abs(placed.getZ() + 0.5 - feet.getZ()) < 1.5) {
            long now = System.currentTimeMillis();
            data(player).bridgePlaces.addLast(now);
            int count = PlayerData.countSince(data(player).bridgePlaces, now - 1000);
            if (count > 12) {
                data(player).bridgePlaces.clear();
                violations.flag(player, "scaffold", 1, "발밑에 1초에 " + count + "개 설치");
            }
        }
    }

    private PlayerData data(Player player) {
        return plugin.data(player);
    }
}
