package com.github.kaneteulin.anticheat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ESP / 트레이서 / 이름표 투시 대응: 벽에 완전히 가려진 플레이어, 몹, 떨어진 아이템의 정보를 보내지 않는다.
 * <p>
 * 대상이 보이게 되면 바로 다시 보낸다. 모퉁이에서 갑자기 나타나는 현상을 줄이기 위해
 * 가까운 대상(6칸 이내)은 항상 보이고, 한 번 보인 대상은 1초 동안 계속 보인다.
 * 플레이어를 숨겨도 Tab 목록에서는 사라지지 않게 목록 정보는 다시 보낸다.
 */
final class EntityHider implements Listener, Runnable {

    private static final double ALWAYS_VISIBLE = 6.0;
    private static final double PLAYER_RANGE = 128.0;
    private static final double OTHER_RANGE = 64.0;
    private static final long VISIBLE_GRACE_MS = 1000;

    private final AntiCheatPlugin plugin;
    /** 관찰자 -> 숨긴 대상 */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    /** 관찰자 -> (대상 -> 마지막으로 보인 시각) */
    private final Map<UUID, Map<UUID, Long>> lastVisible = new HashMap<>();

    EntityHider(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            update(viewer, now);
        }
    }

    private void update(Player viewer, long now) {
        Set<UUID> hiddenSet = hidden.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
        Map<UUID, Long> seen = lastVisible.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
        Set<UUID> checked = new HashSet<>();

        // 관전자, 크리에이티브, 제외 권한: 모두 보이게
        boolean seeAll = viewer.getGameMode() == GameMode.SPECTATOR || viewer.getGameMode() == GameMode.CREATIVE
                || viewer.hasPermission("anticheat.bypass");

        if (!seeAll) {
            Vector eye = viewer.getEyeLocation().toVector();
            World world = viewer.getWorld();
            ServerLevel level = ((CraftWorld) world).getHandle();

            for (Entity target : world.getNearbyEntities(viewer.getLocation(), PLAYER_RANGE, PLAYER_RANGE, PLAYER_RANGE)) {
                if (!managed(viewer, target)) {
                    continue;
                }
                double distance = target.getLocation().distance(viewer.getLocation());
                if (!(target instanceof Player) && distance > OTHER_RANGE) {
                    continue;
                }
                checked.add(target.getUniqueId());

                boolean visible = distance <= ALWAYS_VISIBLE || target.isGlowing() || isVisible(level, eye, target.getBoundingBox());
                if (visible) {
                    seen.put(target.getUniqueId(), now);
                } else {
                    Long last = seen.get(target.getUniqueId());
                    visible = last != null && now - last < VISIBLE_GRACE_MS;
                }

                if (visible && hiddenSet.remove(target.getUniqueId())) {
                    viewer.showEntity(plugin, target);
                } else if (!visible && hiddenSet.add(target.getUniqueId())) {
                    hide(viewer, target);
                }
            }
        }

        // 범위를 벗어났거나 검사하지 않은 대상은 다시 보이게 (영원히 숨겨지지 않도록)
        Iterator<UUID> it = hiddenSet.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (checked.contains(id)) {
                continue;
            }
            it.remove();
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) {
                viewer.showEntity(plugin, entity);
            }
        }
        seen.keySet().removeIf(id -> !checked.contains(id) && now - seen.get(id) > VISIBLE_GRACE_MS);
    }

    private void hide(Player viewer, Entity target) {
        viewer.hideEntity(plugin, target);
        // 플레이어를 숨기면 Paper 가 Tab 목록에서도 지우므로, 목록 정보만 다시 보낸다
        if (target instanceof Player player) {
            ((CraftPlayer) viewer).getHandle().connection.send(
                    ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(((CraftPlayer) player).getHandle())));
        }
    }

    /** 숨기기 대상: 다른 플레이어, 몹, 떨어진 아이템 (내가 탄 것/나를 태운 것, 보스는 제외) */
    private static boolean managed(Player viewer, Entity target) {
        if (target == viewer || !(target instanceof LivingEntity || target instanceof Item)) {
            return false;
        }
        if (target instanceof ComplexLivingEntity || target instanceof Wither) {
            return false;
        }
        if (target.getPassengers().contains(viewer) || viewer.getPassengers().contains(target)
                || target.equals(viewer.getVehicle())) {
            return false;
        }
        return !(target instanceof Player player && player.getGameMode() == GameMode.SPECTATOR);
    }

    /** 대상 상자의 가운데나 꼭짓점 중 하나라도 불투명 블록에 막히지 않고 보이는지 */
    private static boolean isVisible(ServerLevel level, Vector eye, BoundingBox box) {
        Vector center = box.getCenter();
        if (rayClear(level, eye, center)) {
            return true;
        }
        double sx = box.getWidthX() / 2, sy = box.getHeight() / 2, sz = box.getWidthZ() / 2;
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dy = -1; dy <= 1; dy += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    if (rayClear(level, eye, center.clone().add(new Vector(dx * sx, dy * sy, dz * sz)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 3D DDA 로 눈에서 점까지 지나가는 블록을 하나씩 확인. 빛을 막는 불투명 블록이 있으면 false (유리, 잎 등은 통과) */
    private static boolean rayClear(ServerLevel level, Vector from, Vector to) {
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-6) {
            return true;
        }
        dx /= length;
        dy /= length;
        dz /= length;

        int x = floor(from.getX()), y = floor(from.getY()), z = floor(from.getZ());
        int endX = floor(to.getX()), endY = floor(to.getY()), endZ = floor(to.getZ());
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - from.getX() : from.getX() - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - from.getY() : from.getY() - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - from.getZ() : from.getZ() - z) * tDeltaZ);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 400; i++) {
            if (x == endX && y == endY && z == endZ) {
                return true; // 대상이 있는 칸에 도착
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > length) return true;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > length) return true;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > length) return true;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
            pos.set(x, y, z);
            if (!level.isLoaded(pos)) {
                return true; // 불러오지 않은 곳은 판단하지 않음 (보이는 것으로)
            }
            if (level.getBlockState(pos).canOcclude()) {
                return false;
            }
        }
        return true;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** 플러그인을 끌 때: 숨긴 대상을 모두 다시 보이게 */
    void showAll() {
        for (Map.Entry<UUID, Set<UUID>> entry : hidden.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }
            for (UUID id : entry.getValue()) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null) {
                    viewer.showEntity(plugin, entity);
                }
            }
        }
        hidden.clear();
        lastVisible.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player quitting = event.getPlayer();
        UUID id = quitting.getUniqueId();
        hidden.remove(id);
        lastVisible.remove(id);
        // 숨긴 채로 나가면 Paper 가 숨김 상태를 기억해 다시 접속해도 안 보일 수 있으므로, 나가기 전에 다시 보이게 한다
        for (Map.Entry<UUID, Set<UUID>> entry : hidden.entrySet()) {
            if (entry.getValue().remove(id)) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null) {
                    viewer.showEntity(plugin, quitting);
                }
            }
        }
    }
}
