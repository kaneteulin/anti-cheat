package com.github.kaneteulin.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 위반 점수 관리와 알림. 처벌은 하지 않는다.
 */
public final class Violations {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("reach", "공격 거리"),
            Map.entry("fly", "비행"),
            Map.entry("jump", "점프 높이/벽타기"),
            Map.entry("nofall", "낙하 피해 무시"),
            Map.entry("fastbreak", "빠른 채굴"),
            Map.entry("nuker", "대량 채굴"),
            Map.entry("autoclicker", "오토클릭"),
            Map.entry("invmove", "인벤토리 이동"),
            Map.entry("criticals", "치명타 조작"),
            Map.entry("wallhit", "벽 너머 공격"),
            Map.entry("wallinteract", "벽 너머 상자"),
            Map.entry("autototem", "자동 토템"),
            Map.entry("invclick", "인벤토리 클릭 속도"),
            Map.entry("fastbow", "활 연사"),
            Map.entry("packetspam", "패킷 과다"),
            Map.entry("timer", "타이머(게임 속도)"),
            Map.entry("speed", "이동 속도"),
            Map.entry("velocity", "넉백 무시"),
            Map.entry("killaura", "킬오라"),
            Map.entry("elytra", "겉날개 비행"),
            Map.entry("phase", "블록 통과"),
            Map.entry("scaffold", "자동 다리 놓기"));

    private final AntiCheatPlugin plugin;
    /** /anticheat alerts 로 알림을 끈 관리자 */
    private final Set<UUID> alertsOff = new HashSet<>();

    Violations(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    ConfigurationSection check(String id) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("checks." + id);
        return section != null ? section : plugin.getConfig().createSection("checks." + id);
    }

    public boolean enabled(String id) {
        return check(id).getBoolean("enabled", true);
    }

    /** 의심 행동 1건 기록 */
    public void flag(Player player, String id, double amount, String detail) {
        if (!enabled(id) || player.hasPermission("anticheat.bypass")) {
            return;
        }

        PlayerData data = plugin.data(player);
        double vl = data.violations.merge(id, amount, Double::sum);
        double threshold = check(id).getDouble("threshold", 1);
        String name = NAMES.getOrDefault(id, id);

        // 오탐 테스트용: 기준 미만이어도 의심 행동을 모두 기록
        if (plugin.getConfig().getBoolean("alerts.log-file", true) && plugin.getConfig().getBoolean("alerts.log-all", true)) {
            log(String.format(Locale.ROOT, "%s - %s [%s] (VL %.1f / 기준 %.1f, 핑 %dms) %s",
                    player.getName(), name, id, vl, threshold, player.getPing(), detail));
        }

        if (vl < threshold) {
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = (long) (plugin.getConfig().getDouble("alerts.cooldown-seconds", 3) * 1000);
        Long last = data.lastAlert.get(id);
        if (last != null && now - last < cooldown) {
            return;
        }
        data.lastAlert.put(id, now);

        String plain = String.format(Locale.ROOT, "%s - %s (VL %.1f, 핑 %dms) %s",
                player.getName(), name, vl, player.getPing(), detail);

        Component message = Component.text("[AntiCheat] ", NamedTextColor.RED)
                .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" - " + name, NamedTextColor.WHITE))
                .append(Component.text(String.format(Locale.ROOT, " (VL %.1f, 핑 %dms) ", vl, player.getPing()), NamedTextColor.GRAY))
                .append(Component.text(detail, NamedTextColor.DARK_GRAY));

        if (plugin.getConfig().getBoolean("alerts.chat", false)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("anticheat.alerts") && !alertsOff.contains(online.getUniqueId())) {
                    online.sendMessage(message);
                }
            }
        }
        if (plugin.getConfig().getBoolean("alerts.console", false)) {
            plugin.getLogger().warning(plain);
        }
        if (plugin.getConfig().getBoolean("alerts.log-file", true) && !plugin.getConfig().getBoolean("alerts.log-all", true)) {
            log(plain);
        }
    }

    private void log(String line) {
        File file = new File(plugin.getDataFolder(), "violations.log");
        try {
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), LocalDateTime.now().format(TIME) + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write violations.log", e);
        }
    }

    /** 1초마다: 점수를 조금씩 줄인다 */
    void decay(PlayerData data) {
        double factor = plugin.getConfig().getDouble("decay-per-second", 0.97);
        Iterator<Map.Entry<String, Double>> it = data.violations.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Double> entry = it.next();
            double value = entry.getValue() * factor;
            if (value < 0.05) {
                it.remove();
            } else {
                entry.setValue(value);
            }
        }
    }

    boolean toggleAlerts(UUID uuid) {
        if (alertsOff.remove(uuid)) {
            return true;
        }
        alertsOff.add(uuid);
        return false;
    }

    static String displayName(String id) {
        return NAMES.getOrDefault(id, id);
    }
}
