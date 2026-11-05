package dev.modsync.client;

import dev.modsync.ModSync;
import dev.modsync.ModSyncConfig;
import dev.modsync.common.ModEntry;
import dev.modsync.common.ModScanner;
import dev.modsync.net.Proto;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Клиентская часть: при запуске игры идёт на адрес из конфига (client.serverAddress),
 * получает манифест, сравнивает с локальной папкой mods, докачивает недостающее и готовит перезапуск.
 * Состояние читают экраны (см. {@link ClientScreens}).
 */
public final class ClientSync {
    public enum State { IDLE, DISABLED, CHECKING, DOWNLOADING, UP_TO_DATE, RESTART, FAILED }

    private static final int READ_TIMEOUT_MS = 30_000;

    private static volatile State state = State.IDLE;
    private static volatile String error = "";
    private static volatile Restarter.Plan plan;
    private static volatile String address = "";
    private static volatile String currentName = "";
    private static volatile long done;
    private static volatile long total;
    private static volatile int filesDone;
    private static volatile int filesTotal;
    private static volatile boolean cancelled;
    private static volatile Socket socket;

    private ClientSync() {}

    public static State state() { return state; }
    public static String error() { return error; }
    public static Restarter.Plan plan() { return plan; }
    public static String address() { return address; }
    public static String currentName() { return currentName; }
    public static long done() { return done; }
    public static long total() { return total; }
    public static int filesDone() { return filesDone; }
    public static int filesTotal() { return filesTotal; }

    /** Запускает проверку в фоне (повторный вызов во время работы игнорируется; после ошибки - "повторить"). */
    public static synchronized void start() {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.RESTART) return;
        String addr = ModSyncConfig.SERVER_ADDRESS.get().trim();
        address = addr;
        error = "";
        cancelled = false;
        if (addr.isEmpty()) {
            state = State.DISABLED;
            ModSync.LOGGER.info("[ModSync] client.serverAddress не задан - синхронизация отключена");
            return;
        }
        done = total = 0;
        filesDone = filesTotal = 0;
        currentName = "";
        state = State.CHECKING;
        Thread t = new Thread(ClientSync::run, "ModSync-Client");
        t.setDaemon(true);
        t.start();
    }

    public static void cancel() {
        cancelled = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ основной сценарий

    private static void run() {
        Socket s = new Socket();
        socket = s;
        Path staging = FMLPaths.GAMEDIR.get().resolve("modsync").resolve("staging");
        try {
            long maxFile = ModSyncConfig.MAX_FILE_MB.get() * 1024L * 1024L;
            long maxTotal = ModSyncConfig.MAX_TOTAL_MB.get() * 1024L * 1024L;
            String secret = ModSyncConfig.CLIENT_SECRET.get();

            // локальные хэши считаем ДО подключения, чтобы не держать соединение открытым
            List<ModScanner.Jar> local = ModScanner.Local.get().get(300, TimeUnit.SECONDS);

            InetSocketAddress target = parseAddress(address);
            ModSync.LOGGER.info("[ModSync] подключаюсь к серверу синхронизации {}:{}", target.getHostString(), target.getPort());
            s.connect(target, ModSyncConfig.CONNECT_TIMEOUT.get() * 1000);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 4096));

            // --- рукопожатие
            byte[] cn = Proto.nonce();
            out.writeInt(Proto.MAGIC);
            out.writeInt(Proto.VERSION);
            out.write(cn);
            out.flush();
            int st = in.readUnsignedByte();
            if (st == Proto.BAD_VERSION) throw new IOException("protocol version mismatch (update ModSync)");
            if (st != Proto.OK) throw new IOException("unexpected handshake status " + st);
            byte[] sn = in.readNBytes(Proto.NONCE);
            if (sn.length != Proto.NONCE) throw new EOFException();
            out.write(Proto.mac(secret, "C", cn, sn));
            out.flush();
            st = in.readUnsignedByte();
            if (st == Proto.AUTH_FAILED) throw new IOException("wrong secret");
            if (st != Proto.OK) throw new IOException("unexpected auth status " + st);

            // --- манифест (подпись проверяем, если у нас задан секрет)
            int len = in.readInt();
            if (len < 0 || len > Proto.MAX_MANIFEST_BYTES) throw new IOException("bad manifest length " + len);
            byte[] mb = in.readNBytes(len);
            byte[] mac = in.readNBytes(Proto.MAC);
            if (mb.length != len || mac.length != Proto.MAC) throw new EOFException();
            if (!secret.isEmpty() && !MessageDigest.isEqual(mac, Proto.mac(secret, "S", cn, sn, mb))) {
                throw new IOException("manifest signature mismatch");
            }
            List<ModEntry> es = Proto.decode(mb);

            Set<String> names = new HashSet<>();
            for (ModEntry e : es) {
                if (!ModScanner.validFileName(e.fileName())
                        || !names.add(e.fileName().toLowerCase(Locale.ROOT))
                        || e.size() < 0 || e.size() > maxFile
                        || !e.sha256().matches("[0-9a-f]{64}")) {
                    throw new IOException("bad manifest entry: " + e.fileName());
                }
            }

            Set<String> localHashes = local.stream().map(ModScanner.Jar::sha256).collect(Collectors.toSet());
            Set<String> serverHashes = es.stream().map(ModEntry::sha256).collect(Collectors.toSet());
            List<Integer> missing = new ArrayList<>();
            long need = 0;
            for (int i = 0; i < es.size(); i++) {
                if (!localHashes.contains(es.get(i).sha256())) {
                    missing.add(i);
                    need += es.get(i).size();
                }
            }
            if (missing.isEmpty()) {
                out.writeInt(0);
                out.flush();
                ModSync.LOGGER.info("[ModSync] моды актуальны ({} шт.)", es.size());
                state = State.UP_TO_DATE;
                return;
            }
            if (need > maxTotal) throw new IOException("download too large: " + Fmt.size(need));

            // устаревшие локальные версии (тот же modId / то же имя файла, но другой хэш)
            Set<String> wantedIds = new HashSet<>();
            Set<String> wantedNames = new HashSet<>();
            for (int i : missing) {
                wantedIds.addAll(es.get(i).modIdSet());
                wantedNames.add(es.get(i).fileName().toLowerCase(Locale.ROOT));
            }
            List<Path> obsolete = new ArrayList<>();
            for (ModScanner.Jar l : local) {
                if (serverHashes.contains(l.sha256())) continue;
                boolean sameId = l.modIds().stream().anyMatch(wantedIds::contains);
                boolean sameName = wantedNames.contains(l.fileName().toLowerCase(Locale.ROOT));
                if (sameId || sameName) obsolete.add(l.path());
            }

            // --- загрузка
            ModSync.LOGGER.info("[ModSync] нужно скачать {} файл(ов), {}", missing.size(), Fmt.size(need));
            total = need;
            filesTotal = missing.size();
            state = State.DOWNLOADING;
            Files.createDirectories(staging);
            clearDir(staging);

            out.writeInt(missing.size());
            for (int i : missing) out.writeInt(i);
            out.flush();

            Map<Integer, Path> staged = new HashMap<>();
            byte[] buf = new byte[64 * 1024];
            for (int idx : missing) {
                ModEntry e = es.get(idx);
                currentName = e.fileName();
                Path part = staging.resolve(idx + ".part");
                MessageDigest md = ModScanner.newDigest();
                long left = e.size();
                try (OutputStream fo = new BufferedOutputStream(Files.newOutputStream(part))) {
                    while (left > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) throw new EOFException("server closed connection while sending " + e.fileName());
                        fo.write(buf, 0, r);
                        md.update(buf, 0, r);
                        left -= r;
                        done += r;
                    }
                }
                if (!ModScanner.hex(md.digest()).equalsIgnoreCase(e.sha256())) {
                    throw new IOException("hash mismatch: " + e.fileName());
                }
                staged.put(idx, part);
                filesDone++;
            }

            // --- установка
            plan = install(es, missing, staged, obsolete);
            ModSync.LOGGER.info("[ModSync] моды установлены, нужен перезапуск игры");
            state = State.RESTART;
        } catch (Throwable t) {
            if (cancelled) {
                error = "cancelled";
            } else {
                ModSync.LOGGER.error("[ModSync] синхронизация не удалась", t);
                error = t instanceof java.net.UnknownHostException ? "unknown host: " + t.getMessage()
                        : t instanceof java.net.SocketTimeoutException ? "timeout"
                        : String.valueOf(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
            try {
                clearDir(staging);
            } catch (IOException ignored) {
            }
            state = State.FAILED;
        } finally {
            socket = null;
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Новые файлы кладём в mods сразу; замены и удаление старых версий делает хелпер после выхода из игры. */
    private static Restarter.Plan install(List<ModEntry> es, List<Integer> wanted, Map<Integer, Path> staged,
                                          List<Path> obsolete) throws IOException {
        Path modsDir = FMLPaths.MODSDIR.get();
        Path backup = FMLPaths.GAMEDIR.get().resolve("modsync").resolve("backup");
        String stamp = Long.toString(System.currentTimeMillis());
        List<String[]> moves = new ArrayList<>();
        Set<Path> backedUp = new LinkedHashSet<>(obsolete);
        for (Path p : obsolete) {
            moves.add(new String[]{p.toString(), backup.resolve(p.getFileName() + "." + stamp).toString()});
        }
        for (int idx : wanted) {
            Path src = staged.get(idx);
            Path dst = modsDir.resolve(es.get(idx).fileName());
            if (Files.exists(dst)) {
                if (backedUp.add(dst)) {
                    moves.add(new String[]{dst.toString(), backup.resolve(dst.getFileName() + "." + stamp).toString()});
                }
                moves.add(new String[]{src.toString(), dst.toString()});
            } else {
                Files.move(src, dst);
            }
        }
        return Restarter.plan(moves);
    }

    private static void clearDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) Files.deleteIfExists(p);
        }
    }

    /** "host", "host:port", "[ipv6]:port". */
    static InetSocketAddress parseAddress(String a) throws IOException {
        String host = a.trim();
        int port = Proto.DEFAULT_PORT;
        try {
            if (host.startsWith("[")) {
                int e = host.indexOf(']');
                if (e < 0) throw new IOException("bad address: " + a);
                String rest = host.substring(e + 1);
                if (rest.startsWith(":")) port = Integer.parseInt(rest.substring(1));
                host = host.substring(1, e);
            } else if (host.indexOf(':') >= 0 && host.indexOf(':') == host.lastIndexOf(':')) {
                int c = host.indexOf(':');
                port = Integer.parseInt(host.substring(c + 1).trim());
                host = host.substring(0, c);
            }
        } catch (NumberFormatException e) {
            throw new IOException("bad port in address: " + a);
        }
        if (host.isEmpty() || port < 1 || port > 65535) throw new IOException("bad address: " + a);
        return new InetSocketAddress(host, port);
    }

    static final class Fmt {
        static String size(long b) {
            if (b < 1024) return b + " B";
            if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
            return String.format(Locale.ROOT, "%.1f MB", b / 1024.0 / 1024.0);
        }
    }
}
