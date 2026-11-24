package com.github.kaneteulin.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.spigotmc.SpigotConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 정보 이용형 치트 대응 중 서버 설정으로 처리하는 것.
 * <ul>
 *     <li>X-ray, 상자 ESP: Paper Anti-Xray (월드별 설정, 월드를 불러오기 전에 적용)</li>
 *     <li>시드 크래킹: 구조물 생성 시드를 무작위로 (spigot.yml), 광석·나무 등 생성 시드도 무작위로 (Paper feature-seeds)</li>
 *     <li>소리 추적: 위더 소환, 엔드 포털, 드래곤 죽음 소리가 들리는 범위 제한 (spigot.yml)</li>
 *     <li>장비 엿보기: 다른 플레이어에게 보내는 아이템의 인챈트·내구도 숨김 (Paper item obfuscation)</li>
 * </ul>
 * Paper 전체 설정(paper-global.yml)은 플러그인보다 먼저 읽히므로 바꾼 경우 다음 시작부터 적용된다.
 */
final class ServerSettings {

    /** 구조물 시드 (stronghold 는 기존 월드의 엔더의 눈 방향이 바뀌므로 제외) */
    private static final List<String> STRUCTURE_SEEDS = List.of(
            "seed-village", "seed-desert", "seed-igloo", "seed-jungle", "seed-swamp", "seed-monument",
            "seed-shipwreck", "seed-ocean", "seed-outpost", "seed-endcity", "seed-nether", "seed-mansion",
            "seed-fossil", "seed-portal", "seed-ancientcity", "seed-trailruins", "seed-trialchambers",
            "seed-abandonedcamp", "seed-buriedtreasure", "seed-mineshaft", "seed-slime");

    private static final List<String> SOUND_RADII = List.of(
            "wither-spawn-sound-radius", "end-portal-sound-radius", "dragon-death-sound-radius");

    private ServerSettings() {
    }

    /** 플러그인을 불러올 때 (월드보다 먼저) */
    static void onLoad(AntiCheatPlugin plugin) {
        Logger logger = plugin.getLogger();
        File root = Bukkit.getWorldContainer();
        String level = levelName(root);

        if (option(plugin, "anti-xray") || option(plugin, "seeds")) {
            writeWorldConfig(plugin, new File(root, level + "/dimensions/minecraft/overworld/paper-world.yml"), overworld(plugin), logger);
            writeWorldConfig(plugin, new File(root, level + "/dimensions/minecraft/the_nether/paper-world.yml"), nether(plugin), logger);
            writeWorldConfig(plugin, new File(root, level + "/dimensions/minecraft/the_end/paper-world.yml"), end(plugin), logger);
        }

        applySpigot(plugin, logger);
    }

    /** 플러그인을 켤 때 (월드 생성 후): 처음 실행이라 월드 설정 파일이 없던 경우와 Paper 전체 설정 */
    static void onEnable(AntiCheatPlugin plugin) {
        Logger logger = plugin.getLogger();
        boolean restartNeeded = false;

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            Map<String, Object> values = switch (world.getEnvironment()) {
                case NETHER -> nether(plugin);
                case THE_END -> end(plugin);
                default -> overworld(plugin);
            };
            if ((option(plugin, "anti-xray") || option(plugin, "seeds"))
                    && writeWorldConfig(plugin, new File(world.getWorldFolder(), "paper-world.yml"), values, logger)) {
                restartNeeded = true;
            }
        }

        if (option(plugin, "item-obfuscation")) {
            Map<String, Object> global = new LinkedHashMap<>();
            global.put("anticheat.obfuscation.items.enable-item-obfuscation", true);
            if (write(new File(Bukkit.getWorldContainer(), "config/paper-global.yml"), global, logger)) {
                restartNeeded = true;
            }
        }

        if (restartNeeded) {
            logger.warning("정보 숨기기 설정을 서버 설정 파일에 적용했습니다. 서버를 한 번 다시 시작하면 적용됩니다.");
        }
    }

    // ---------------- 월드별 Paper 설정 ----------------

    private static Map<String, Object> overworld(AntiCheatPlugin plugin) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (option(plugin, "anti-xray")) {
            values.put("anticheat.anti-xray.enabled", true);
            values.put("anticheat.anti-xray.engine-mode", 2);
            values.put("anticheat.anti-xray.hidden-blocks", List.of(
                    "air",
                    "copper_ore", "deepslate_copper_ore", "raw_copper_block",
                    "diamond_ore", "deepslate_diamond_ore",
                    "gold_ore", "deepslate_gold_ore",
                    "iron_ore", "deepslate_iron_ore", "raw_iron_block",
                    "lapis_ore", "deepslate_lapis_ore",
                    "redstone_ore", "deepslate_redstone_ore",
                    "emerald_ore", "deepslate_emerald_ore"));
            values.put("anticheat.anti-xray.lava-obscures", false);
            values.put("anticheat.anti-xray.max-block-height", 64);
            // 상자류도 가짜 광석 사이에 숨김 (상자 ESP 대응)
            values.put("anticheat.anti-xray.replacement-blocks", List.of(
                    "chest", "trapped_chest", "barrel", "ender_chest", "spawner",
                    "amethyst_block", "andesite", "budding_amethyst", "calcite",
                    "coal_ore", "deepslate_coal_ore", "deepslate", "diorite", "dirt",
                    "granite", "gravel", "oak_planks", "smooth_basalt", "stone", "tuff"));
            values.put("anticheat.anti-xray.update-radius", 2);
            values.put("anticheat.anti-xray.use-permission", false);
        }
        putSeeds(plugin, values);
        return values;
    }

    private static Map<String, Object> nether(AntiCheatPlugin plugin) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (option(plugin, "anti-xray")) {
            values.put("anticheat.anti-xray.enabled", true);
            values.put("anticheat.anti-xray.engine-mode", 2);
            values.put("anticheat.anti-xray.hidden-blocks", List.of("ancient_debris", "nether_gold_ore", "nether_quartz_ore"));
            values.put("anticheat.anti-xray.max-block-height", 128);
            values.put("anticheat.anti-xray.replacement-blocks", List.of(
                    "chest", "basalt", "blackstone", "gravel", "netherrack", "soul_sand", "soul_soil"));
        }
        putSeeds(plugin, values);
        return values;
    }

    private static Map<String, Object> end(AntiCheatPlugin plugin) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (option(plugin, "anti-xray")) {
            values.put("anticheat.anti-xray.enabled", false); // 엔드에는 숨길 광석이 없음
        }
        putSeeds(plugin, values);
        return values;
    }

    private static void putSeeds(AntiCheatPlugin plugin, Map<String, Object> values) {
        if (option(plugin, "seeds")) {
            // 광석, 나무 등 지형 장식의 위치를 월드 시드로 계산할 수 없게 함 (새로 생성되는 곳부터)
            values.put("feature-seeds.generate-random-seeds-for-all", true);
        }
    }

    // ---------------- spigot.yml ----------------

    private static void applySpigot(AntiCheatPlugin plugin, Logger logger) {
        YamlConfiguration spigot = SpigotConfig.config;
        if (spigot == null) {
            return;
        }
        boolean changed = false;

        if (option(plugin, "seeds") && !plugin.getConfig().getBoolean("info-protection.state.structure-seeds-randomized", false)) {
            SecureRandom random = new SecureRandom();
            for (String key : STRUCTURE_SEEDS) {
                spigot.set("world-settings.default." + key, random.nextInt(Integer.MAX_VALUE));
            }
            plugin.getConfig().set("info-protection.state.structure-seeds-randomized", true);
            plugin.saveConfig();
            changed = true;
            logger.info("구조물 생성 시드를 무작위로 바꿨습니다 (새로 생성되는 지형부터 적용).");
        }

        if (option(plugin, "sounds")) {
            for (String key : SOUND_RADII) {
                String path = "world-settings.default." + key;
                if (spigot.getInt(path, 0) == 0) { // 0 = 월드 전체에 들림
                    spigot.set(path, 64);
                    changed = true;
                }
            }
        }

        if (changed) {
            try {
                spigot.save(new File(Bukkit.getWorldContainer(), "spigot.yml"));
            } catch (IOException e) {
                logger.log(Level.WARNING, "spigot.yml 저장 실패", e);
            }
        }
    }

    // ---------------- 공통 ----------------

    private static boolean writeWorldConfig(AntiCheatPlugin plugin, File file, Map<String, Object> values, Logger logger) {
        return !values.isEmpty() && write(file, values, logger);
    }

    /** 파일이 있을 때만 값을 바꾼다 (처음 실행이라 없으면 Paper 가 만든 뒤 onEnable 에서 다시 시도) */
    private static boolean write(File file, Map<String, Object> values, Logger logger) {
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!Objects.equals(yaml.get(entry.getKey()), entry.getValue())) {
                yaml.set(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        try {
            yaml.save(file);
            logger.info("설정 적용: " + file.getPath());
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "설정 저장 실패: " + file, e);
            return false;
        }
    }

    private static String levelName(File root) {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(new File(root, "server.properties"))) {
            properties.load(in);
        } catch (IOException ignored) {
        }
        return properties.getProperty("level-name", "world");
    }

    static boolean option(AntiCheatPlugin plugin, String name) {
        return plugin.getConfig().getBoolean("info-protection." + name, true);
    }
}
