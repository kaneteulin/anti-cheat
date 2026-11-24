package com.github.kaneteulin.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 정해진 시간마다(기본 4시간) 서버를 백업한다.
 * <p>
 * 1. 모든 월드를 저장하고 자동 저장을 잠시 끈다 (압축 중 파일이 바뀌지 않도록)
 * 2. 서버를 멈추지 않도록 압축은 별도 스레드에서 한다
 * 3. 끝나면 자동 저장을 원래대로 돌린다 (기본은 백업을 지우지 않고 모두 보관, keep 으로 개수 제한 가능)
 * <p>
 * 백업 내용: 월드 폴더 전체, server.properties 등 서버 설정, plugins/AntiCheat (설정, 로그)
 */
final class Backup {

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final List<String> SERVER_FILES = List.of(
            "server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json",
            "bukkit.yml", "spigot.yml", "config");

    private final AntiCheatPlugin plugin;
    private volatile boolean running;

    Backup(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    void schedule() {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
            return;
        }
        double hours = Math.max(0.1, plugin.getConfig().getDouble("backup.interval-hours", 4));
        long ticks = (long) (hours * 60 * 60 * 20);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::start, ticks, ticks);
        plugin.getLogger().info(String.format("%.1f시간마다 서버를 백업합니다.", hours));
    }

    /** 서버(메인) 스레드에서 호출 */
    boolean start() {
        if (running) {
            plugin.getLogger().warning("이전 백업이 아직 진행 중이라 이번 백업은 건너뜁니다.");
            return false;
        }
        running = true;

        // 1. 저장 후 자동 저장 끄기
        Map<World, Boolean> autoSave = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            world.save();
            autoSave.put(world, world.isAutoSave());
            world.setAutoSave(false);
        }

        File root = Bukkit.getWorldContainer().getAbsoluteFile();
        List<File> sources = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            File folder = world.getWorldFolder().getAbsoluteFile();
            // 26.x 는 차원들이 월드 폴더 안(dimensions)에 있으므로 최상위 월드 폴더만 넣으면 됨
            File top = folder;
            while (top.getParentFile() != null && !top.getParentFile().equals(root)) {
                top = top.getParentFile();
            }
            if (!sources.contains(top)) {
                sources.add(top);
            }
        }
        for (String name : SERVER_FILES) {
            File file = new File(root, name);
            if (file.exists()) {
                sources.add(file);
            }
        }
        sources.add(plugin.getDataFolder().getAbsoluteFile());

        File folder = backupFolder();
        File target = new File(folder, "backup-" + LocalDateTime.now().format(NAME) + ".zip");
        plugin.getLogger().info("백업 시작: " + target.getName());
        long startedAt = System.currentTimeMillis();

        // 2. 압축 (별도 스레드)
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = zip(root.toPath(), sources, target);

            // 3. 자동 저장 복구, 오래된 백업 정리 (서버 스레드)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                autoSave.forEach((world, value) -> world.setAutoSave(value));
                running = false;
                if (ok) {
                    plugin.getLogger().info(String.format("백업 완료: %s (%.1f MB, %.1f초)", target.getName(),
                            target.length() / 1024.0 / 1024.0, (System.currentTimeMillis() - startedAt) / 1000.0));
                    cleanup(folder);
                }
            });
        });
        return true;
    }

    private File backupFolder() {
        File folder = new File(Bukkit.getWorldContainer(), plugin.getConfig().getString("backup.folder", "backups"));
        folder.mkdirs();
        return folder.getAbsoluteFile();
    }

    private boolean zip(Path root, List<File> sources, File target) {
        File temp = new File(target.getPath() + ".part");
        Path backupDir = target.getParentFile().toPath().toAbsolutePath();
        try (OutputStream file = Files.newOutputStream(temp.toPath()); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (File source : sources) {
                Path start = source.toPath().toAbsolutePath();
                if (Files.isDirectory(start)) {
                    Files.walkFileTree(start, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            return dir.toAbsolutePath().startsWith(backupDir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                            add(zip, root, path);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else {
                    add(zip, root, start);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "백업 실패", e);
            temp.delete();
            return false;
        }
        return temp.renameTo(target);
    }

    private void add(ZipOutputStream zip, Path root, Path path) {
        String name = root.toAbsolutePath().relativize(path.toAbsolutePath()).toString().replace('\\', '/');
        if (name.endsWith("session.lock")) {
            return; // 서버가 잠가 둔 파일 (읽을 수 없음, 복구에 필요 없음)
        }
        try {
            zip.putNextEntry(new ZipEntry(name));
            Files.copy(path, zip);
            zip.closeEntry();
        } catch (IOException e) {
            plugin.getLogger().warning("백업에서 제외: " + name + " (" + e.getMessage() + ")");
        }
    }

    /** 오래된 백업 지우기 (keep 개만 남김, 0 이면 지우지 않음) */
    private void cleanup(File folder) {
        int keep = plugin.getConfig().getInt("backup.keep", 0);
        if (keep <= 0) {
            return;
        }
        File[] backups = folder.listFiles((dir, name) -> name.startsWith("backup-") && name.endsWith(".zip"));
        if (backups == null || backups.length <= keep) {
            return;
        }
        Arrays.sort(backups, Comparator.comparing(File::getName));
        for (int i = 0; i < backups.length - keep; i++) {
            if (backups[i].delete()) {
                plugin.getLogger().info("오래된 백업 삭제: " + backups[i].getName());
            }
        }
    }
}
