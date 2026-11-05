package dev.modsync.client;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Запомненные отпечатки ключей серверов (TOFU, как known_hosts в SSH): файл modsync/known_servers.txt, строки "host:port sha256". */
final class KnownServers {
    private KnownServers() {}

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("modsync").resolve("known_servers.txt");
    }

    private static synchronized Map<String, String> load() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(file())) {
                for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length == 2) m.put(p[0], p[1]);
                }
            }
        } catch (IOException ignored) {
        }
        return m;
    }

    private static void save(Map<String, String> m) throws IOException {
        Files.createDirectories(file().getParent());
        List<String> lines = new ArrayList<>();
        m.forEach((k, v) -> lines.add(k + " " + v));
        Files.write(file(), lines, StandardCharsets.UTF_8);
    }

    static synchronized String get(String key) {
        return load().get(key);
    }

    static synchronized void put(String key, String fingerprint) throws IOException {
        Map<String, String> m = load();
        m.put(key, fingerprint);
        save(m);
    }

    static synchronized void forget(String key) throws IOException {
        Map<String, String> m = load();
        if (m.remove(key) != null) save(m);
    }
}
