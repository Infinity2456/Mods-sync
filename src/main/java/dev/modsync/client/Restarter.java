package dev.modsync.client;

import dev.modsync.ModSync;
import dev.modsync.ModSyncConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Перезапуск клиента: отдельный процесс-хелпер ждёт выхода игры, применяет замены файлов и запускает игру заново. */
public final class Restarter {
    private Restarter() {}

    /** moves: пары {откуда, куда}; relaunch: полная командная строка игры или null. */
    public record Plan(List<String[]> moves, List<String> relaunch) {
        public boolean canRelaunch() {
            return relaunch != null;
        }
    }

    public static Plan plan(List<String[]> moves) {
        List<String> cmd = null;
        if (ModSyncConfig.AUTO_RESTART.get() && ownJar() != null) {
            cmd = currentCommand();
        }
        return new Plan(moves, cmd);
    }

    public static void restart(Plan plan) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Path jar = ownJar();
            if (jar != null && (!plan.moves().isEmpty() || plan.relaunch() != null)) {
                Path dir = FMLPaths.GAMEDIR.get().resolve("modsync");
                Files.createDirectories(dir);
                Path file = dir.resolve("restart-plan.txt");
                List<String> lines = new ArrayList<>();
                for (String[] m : plan.moves()) lines.add("MOVE " + b64(m[0]) + " " + b64(m[1]));
                if (plan.relaunch() != null) {
                    lines.add("DIR " + b64(FMLPaths.GAMEDIR.get().toAbsolutePath().toString()));
                    for (String a : plan.relaunch()) lines.add("ARG " + b64(a));
                }
                Files.write(file, lines, StandardCharsets.UTF_8);
                String java = ProcessHandle.current().info().command()
                        .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                // хелпер запускаем из КОПИИ jar: сам jar может быть заменён (самообновление ModSync),
                // а Windows не даёт заменить файл, из которого работает процесс
                Path helperJar = dir.resolve("helper.jar");
                Files.copy(jar, helperJar, StandardCopyOption.REPLACE_EXISTING);
                new ProcessBuilder(java, "-cp", helperJar.toString(), "dev.modsync.helper.RestartHelper",
                        Long.toString(ProcessHandle.current().pid()), file.toString())
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } else if (!plan.moves().isEmpty()) {
                ModSync.LOGGER.warn("[ModSync] хелпер недоступен: замены/удаление старых модов нужно сделать вручную");
            }
        } catch (Exception e) {
            ModSync.LOGGER.error("[ModSync] не удалось запустить хелпер", e);
        }
        mc.stop();
        Thread w = new Thread(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(0);
        }, "ModSync-ExitWatchdog");
        w.setDaemon(true);
        w.start();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Путь к jar самого мода (в dev-окружении это папка - вернём null). */
    static Path ownJar() {
        try {
            Path p = ModList.get().getModContainerById(ModSync.MODID)
                    .map(c -> c.getModInfo().getOwningFile().getFile().getFilePath()).orElse(null);
            return p != null && Files.isRegularFile(p) ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ командная строка процесса

    static List<String> currentCommand() {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            String cmd = info.command().orElse(null);
            if (cmd == null) return null;
            Optional<String[]> args = info.arguments();
            if (args.isPresent() && args.get().length > 0) {
                List<String> l = new ArrayList<>();
                l.add(cmd);
                l.addAll(Arrays.asList(args.get()));
                return l;
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                // на Windows JDK не отдаёт аргументы - спрашиваем у системы
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                                + "(Get-CimInstance Win32_Process -Filter 'ProcessId=" + ProcessHandle.current().pid() + "').CommandLine")
                        .redirectErrorStream(true).start();
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return null;
                }
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (out.isEmpty()) return null;
                List<String> parts = splitWindows(out);
                return parts.isEmpty() ? null : parts;
            }
        } catch (Exception e) {
            ModSync.LOGGER.warn("[ModSync] не удалось получить командную строку игры: {}", e.toString());
        }
        return null;
    }

    /** Разбор командной строки Windows (правила CommandLineToArgvW). */
    static List<String> splitWindows(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false, has = false;
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                int j = i;
                while (j < n && s.charAt(j) == '\\') j++;
                int bs = j - i;
                has = true;
                if (j < n && s.charAt(j) == '"') {
                    cur.append("\\".repeat(bs / 2));
                    if (bs % 2 == 1) {
                        cur.append('"');
                        i = j + 1;
                    } else {
                        i = j;
                    }
                } else {
                    cur.append("\\".repeat(bs));
                    i = j;
                }
                continue;
            }
            if (c == '"') {
                inQ = !inQ;
                has = true;
                i++;
                continue;
            }
            if (Character.isWhitespace(c) && !inQ) {
                if (has) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
                i++;
                continue;
            }
            cur.append(c);
            has = true;
            i++;
        }
        if (has) out.add(cur.toString());
        return out;
    }
}
