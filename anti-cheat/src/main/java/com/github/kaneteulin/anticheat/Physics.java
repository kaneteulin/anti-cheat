package com.github.kaneteulin.anticheat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Set;

/**
 * 서버 쪽에서 직접 계산하는 땅/주변 블록/시야 판정.
 */
final class Physics {

    /** 근처에 있으면 낙하/상승이 정상과 달라지는 블록 (이동 검사 제외) */
    private static final Set<Material> SPECIAL = EnumSet.of(
            Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN,
            Material.COBWEB, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.SCAFFOLDING);

    private Physics() {
    }

    /** 발밑(0.08칸 아래까지)에 블록이나 보트 같은 단단한 엔티티가 있는지 */
    static boolean grounded(Location at) {
        double x = at.getX(), y = at.getY(), z = at.getZ();
        BoundingBox feet = new BoundingBox(x - 0.299, y - 0.08, z - 0.299, x + 0.299, y + 0.01, z + 0.299);
        return at.getWorld().hasCollisionsIn(feet);
    }

    /** 물, 사다리, 거미줄, 슬라임 등 움직임이 특수한 블록이 몸 근처에 있는지 */
    static boolean nearSpecial(Location at) {
        World world = at.getWorld();
        int minX = floor(at.getX() - 0.65), maxX = floor(at.getX() + 0.65);
        int minY = floor(at.getY() - 0.6), maxY = floor(at.getY() + 2.1);
        int minZ = floor(at.getZ() - 0.65), maxZ = floor(at.getZ() + 0.65);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (SPECIAL.contains(type) || Tag.CLIMBABLE.isTagged(type) || block.isLiquid()) {
                        return true;
                    }
                    BlockData data = block.getBlockData();
                    if (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 발밑 블록이 튀어 오르게 하는 블록(슬라임, 침대)인지 */
    static boolean bouncy(Location at) {
        Material below = at.clone().subtract(0, 0.2, 0).getBlock().getType();
        return below == Material.SLIME_BLOCK || Tag.BEDS.isTagged(below);
    }

    /** 발밑이 얼음 종류인지 (미끄러짐) */
    static boolean onIce(Location at) {
        return at.clone().subtract(0, 0.2, 0).getBlock().getType().name().contains("ICE");
    }

    /** 이동 검사를 하지 않는 상태 */
    static boolean movementExempt(Player player, PlayerData data) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return true;
        }
        // 효과가 끝난 직후의 관성도 제외
        if (player.isGliding() || player.isRiptiding()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            data.exempt(2000);
            return true;
        }
        return player.getAllowFlight() || player.isFlying() || player.isInsideVehicle()
                || player.isSleeping() || player.isDead() || player.isSwimming() || player.isInWater()
                || player.isClimbing() || data.isExemptNow()
                || player.hasPermission("anticheat.bypass");
    }

    static int jumpBoost(Player player) {
        PotionEffect effect = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    /** 눈에서 point 까지 블록에 막히지 않았는지 (클라이언트처럼 블록 외곽선 기준) */
    static boolean visible(Player player, Vector point, Block target) {
        Location eye = player.getEyeLocation();
        Vector direction = point.clone().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 0.01) {
            return true;
        }
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction.normalize(), distance,
                FluidCollisionMode.NEVER, false);
        if (hit == null || hit.getHitBlock() == null) {
            return true;
        }
        return target != null && hit.getHitBlock().equals(target);
    }

    /** 상자의 어느 한 점이라도 보이는지 (가운데 + 테두리 안쪽 8개 꼭짓점) */
    static boolean anyVisible(Player player, BoundingBox box, Block target) {
        Vector center = box.getCenter();
        if (visible(player, center, target)) {
            return true;
        }
        double sx = box.getWidthX() * 0.4, sy = box.getHeight() * 0.4, sz = box.getWidthZ() * 0.4;
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dy = -1; dy <= 1; dy += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    if (visible(player, center.clone().add(new Vector(dx * sx, dy * sy, dz * sz)), target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 조준선(보고 있는 방향)이 대상 상자에 닿고, 그 사이에 블록이 없는지.
     * 바닐라 클라이언트도 조준선으로 공격 대상을 고르므로, 계단 틈처럼 몸 일부만 보여도 공격할 수 있다.
     * 서버가 아는 시선은 한 틱 늦을 수 있어 상자를 조금 키워서 본다.
     */
    static boolean crosshairVisible(Player player, BoundingBox box) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult onBox = box.clone().expand(0.2).rayTrace(eye.toVector(), direction, 8.0);
        if (onBox == null) {
            return false;
        }
        double distance = onBox.getHitPosition().distance(eye.toVector());
        if (distance < 0.01) {
            return true;
        }
        RayTraceResult block = player.getWorld().rayTraceBlocks(eye, direction, distance,
                FluidCollisionMode.NEVER, false);
        return block == null || block.getHitBlock() == null;
    }

    /** 점에서 상자까지 가장 가까운 거리 */
    static double distance(Vector point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
