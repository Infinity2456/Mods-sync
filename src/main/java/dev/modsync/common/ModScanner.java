package dev.modsync.common;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Сканирует папку mods: sha256, modId, версия, признак "только для сервера". */
public final class ModScanner {
    private static final Logger LOG = LoggerFactory.getLogger("ModSync/Scanner");

    public record Jar(Path path, String fileName, String sha256, long size,
                      Set<String> modIds, String version, boolean serverOnly) {}

    private record Meta(Set<String> ids, String version, boolean serverOnly) {}

    private static final Pattern KV = Pattern.compile("^\\s*([A-Za-z_]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern NAME = Pattern.compile(
            "^[^/\\\\:*?\"<>|\\x00-\\x1f.][^/\\\\:*?\"<>|\\x00-\\x1f]{0,200}\\.jar$");

    private ModScanner() {}

    public static boolean validFileName(String n) {
        return n != null && !n.contains("..") && NAME.matcher(n).matches();
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public static String sha256(Path p) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static List<Jar> scan(Path dir) {
        List<Jar> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    Meta m = readMeta(p);
                    out.add(new Jar(p, p.getFileName().toString(), sha256(p), Files.size(p),
                            m.ids(), m.version(), m.serverOnly()));
                } catch (Exception e) {
                    LOG.warn("Не удалось прочитать {}: {}", p.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            LOG.warn("Не удалось просканировать {}: {}", dir, e.toString());
        }
        out.sort(Comparator.comparing(Jar::fileName));
        return out;
    }

    private static Meta readMeta(Path jar) throws IOException {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry("META-INF/neoforge.mods.toml");
            if (e == null) e = zf.getEntry("META-INF/mods.toml");
            if (e == null) return new Meta(Set.of(), "", false);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8))) {
                return parseToml(r);
            }
        }
    }

    /** Минимальный разбор TOML: достаточно для modId/version и side зависимостей neoforge/minecraft. */
    static Meta parseToml(BufferedReader r) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        String version = "";
        boolean sawServer = false, sawOther = false;
        String section = "";
        String depModId = null, depSide = null;
        boolean inMulti = false;
        String line;
        while ((line = r.readLine()) != null) {
            int q = count(line, "'''") + count(line, "\"\"\"");
            if (q > 0) {
                if (q % 2 == 1) inMulti = !inMulti;
                continue;
            }
            if (inMulti) continue;
            String t = line.trim();
            if (t.startsWith("[")) {
                if (isOwnDepSection(section) && depModId != null
                        && (depModId.equals("neoforge") || depModId.equals("minecraft"))) {
                    if ("SERVER".equalsIgnoreCase(depSide)) sawServer = true; else sawOther = true;
                }
                section = t.replace("[", "").replace("]", "").trim();
                depModId = null;
                depSide = null;
                continue;
            }
            Matcher m = KV.matcher(line);
            if (!m.find()) continue;
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            if (section.equals("mods")) {
                if (key.equals("modId")) ids.add(val.toLowerCase(Locale.ROOT));
                else if (key.equals("version") && version.isEmpty()) version = val;
            } else if (isOwnDepSection(section)) {
                if (key.equals("modId")) depModId = val;
                else if (key.equals("side")) depSide = val;
            }
        }
        if (isOwnDepSection(section) && depModId != null
                && (depModId.equals("neoforge") || depModId.equals("minecraft"))) {
            if ("SERVER".equalsIgnoreCase(depSide)) sawServer = true; else sawOther = true;
        }
        return new Meta(ids, version, sawServer && !sawOther);
    }

    private static boolean isOwnDepSection(String s) {
        return s.startsWith("dependencies.");
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }

    /** Кэш сканирования локальной папки mods (клиент). */
    public static final class Local {
        private static CompletableFuture<List<Jar>> future;

        private Local() {}

        public static synchronized CompletableFuture<List<Jar>> get() {
            if (future == null) {
                future = CompletableFuture.supplyAsync(() -> scan(FMLPaths.MODSDIR.get()));
            }
            return future;
        }

        public static void warmUp() {
            get();
        }
    }
}
