package com.github.kaneteulin.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 핵 의심 행동을 감지해 관리자에게 알리는 안티치트 (처벌 없음).
 */
public final class AntiCheatPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerData> players = new HashMap<>();
    private Violations violations;
    private CombatChecks combat;
    private InventoryChecks inventory;
    private HighRiskChecks highRisk;

    private EntityHider hider;

    /** 월드를 불러오기 전: 월드별 Anti-Xray, 생성 시드, 소리 범위 설정 */
    @Override
    public void onLoad() {
        loadConfigWithDefaults();
        ServerSettings.onLoad(this);
    }

    private void loadConfigWithDefaults() {
        saveDefaultConfig();
        reloadConfig();
        // 업데이트로 새 설정 항목이 생기면 기존 값은 그대로 두고 빠진 항목만 추가
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    @Override
    public void onEnable() {
        loadConfigWithDefaults();
        ServerSettings.onEnable(this);
        violations = new Violations(this);

        combat = new CombatChecks(this);
        inventory = new InventoryChecks(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MovementChecks(this), this);
        getServer().getPluginManager().registerEvents(combat, this);
        getServer().getPluginManager().registerEvents(new BlockChecks(this), this);
        getServer().getPluginManager().registerEvents(inventory, this);
        highRisk = new HighRiskChecks(this);
        getServer().getPluginManager().registerEvents(highRisk, this);

        // 정보 숨기기: 체력, 벽 너머 엔티티
        PacketCounter.hideHealth = ServerSettings.option(this, "health");
        for (Player player : Bukkit.getOnlinePlayers()) {
            PacketCounter.PLAYER_IDS.add(player.getEntityId());
            PacketCounter.inject(player, data(player));
        }
        if (ServerSettings.option(this, "entity-hiding")) {
            hider = new EntityHider(this);
            getServer().getPluginManager().registerEvents(hider, this);
            getServer().getScheduler().runTaskTimer(this, hider, 20L, 3L);
        }

        violations.tickDate(); // 오늘 날짜 로그 폴더
        getServer().getScheduler().runTaskTimer(this, this::everySecond, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (hider != null) {
            hider.showAll();
        }
        PacketCounter.PLAYER_IDS.clear();
    }

    public PlayerData data(Player player) {
        return players.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerData());
    }

    Violations violations() {
        return violations;
    }

    private void everySecond() {
        violations.tickDate();
        int maxPackets = violations.check("packetspam").getInt("max-per-second", 500);

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = data(player);

            combat.tickClicks(player, data);
            inventory.tickClicks(player, data);
            highRisk.tickTimer(player, data);

            int packets = data.packets.getAndSet(0);
            if (packets > maxPackets) {
                violations.flag(player, "packetspam", 1, "1초에 패킷 " + packets + "개 / 허용 " + maxPackets + "개");
            }

            violations.decay(data);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        PacketCounter.PLAYER_IDS.add(event.getPlayer().getEntityId());
        PacketCounter.inject(event.getPlayer(), data(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
        PacketCounter.PLAYER_IDS.remove(event.getPlayer().getEntityId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("콘솔에는 항상 알림이 표시됩니다.");
                    return true;
                }
                boolean on = violations.toggleAlerts(player.getUniqueId());
                sender.sendMessage(Component.text("안티치트 알림: " + (on ? "켜짐" : "꺼짐"), NamedTextColor.YELLOW));
            }
            case "vl" -> {
                if (args.length < 2) {
                    sender.sendMessage("사용법: /" + label + " vl <닉네임>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("접속 중인 플레이어가 아닙니다.", NamedTextColor.RED));
                    return true;
                }
                Map<String, Double> vl = data(target).violations;
                if (vl.isEmpty()) {
                    sender.sendMessage(target.getName() + ": 위반 점수 없음");
                    return true;
                }
                sender.sendMessage(target.getName() + " 의 위반 점수:");
                vl.forEach((id, value) -> sender.sendMessage(String.format(Locale.ROOT, " - %s: %.1f", Violations.displayName(id), value)));
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(Component.text("설정을 다시 읽었습니다.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage("사용법: /" + label + " <alerts|vl <닉네임>|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("alerts", "vl", "reload")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(option);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("vl")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) result.add(player.getName());
            }
        }
        return result;
    }
}
