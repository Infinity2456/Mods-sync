package dev.modsync.helper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Отдельный процесс (только JDK, без зависимостей от Minecraft).
 * Аргументы: <pid игры> <файл плана>.
 * Ждёт завершения игры, выполняет MOVE (замена/резервное копирование файлов), затем запускает игру заново.
 */
public final class RestartHelper {
    private RestartHelper() {}

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path planFile = Path.of(args[1]);
        Path logFile = planFile.resolveSibling("helper.log");
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8))) {
            try {
                run(pid, planFile, log);
            } catch (Throwable t) {
                t.printStackTrace(log);
            }
        }
    }

    private static String d(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    private static void run(long pid, Path planFile, PrintWriter log) throws Exception {
        List<String[]> moves = new ArrayList<>();
        List<String> cmd = new ArrayList<>();
        Path dir = null;
        for (String line : Files.readAllLines(planFile, StandardCharsets.UTF_8)) {
            String[] p = line.split(" ");
            if (p.length < 2) continue;
            switch (p[0]) {
                case "MOVE" -> moves.add(new String[]{d(p[1]), d(p[2])});
                case "ARG" -> cmd.add(d(p[1]));
                case "DIR" -> dir = Path.of(d(p[1]));
                default -> { }
            }
        }

        ProcessHandle.of(pid).ifPresent(h -> {
            try {
                h.onExit().get(180, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        });
        Thread.sleep(1500);
        log.println("parent exited");

        for (String[] m : moves) {
            Path src = Path.of(m[0]);
            Path dst = Path.of(m[1]);
            boolean ok = false;
            for (int i = 0; i < 40 && !ok; i++) {
                try {
                    if (!Files.exists(src)) {
                        ok = true;
                        break;
                    }
                    Files.createDirectories(dst.getParent());
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    ok = true;
                } catch (IOException e) {
                    Thread.sleep(500);
                }
            }
            log.println((ok ? "moved " : "FAILED ") + src + " -> " + dst);
        }
        Files.deleteIfExists(planFile);

        if (!cmd.isEmpty()) {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            if (dir != null) pb.directory(dir.toFile());
            pb.start();
            log.println("game relaunched");
        }
    }
}
