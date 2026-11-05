package dev.modsync.client;

import dev.modsync.ModSync;
import dev.modsync.ModSyncConfig;
import dev.modsync.common.ModEntry;
import dev.modsync.common.ModScanner;
import dev.modsync.net.Crypto;
import dev.modsync.net.Identity;
import dev.modsync.net.Proto;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
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
    private static final int MAX_RETRIES = 3;
    private static volatile boolean identityProblem;
    private static volatile boolean downloadStarted;
    private static volatile int retry;
    private static volatile long startNanos;
    private static volatile String pinKey = "";
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
    /** true, если ошибка - смена ключа сервера (экран предлагает "забыть ключ"). */
    public static boolean identityProblem() { return identityProblem; }
    /** Номер текущей попытки переподключения после обрыва (0 - обрыва не было). */
    public static int retry() { return retry; }
    public static int maxRetries() { return MAX_RETRIES; }
    /** Время начала загрузки (System.nanoTime) для расчёта скорости. */
    public static long startNanos() { return startNanos; }

    /** Забывает запомненный ключ текущего сервера и запускает проверку заново. */
    public static void forgetServerKeyAndRetry() {
        try {
            KnownServers.forget(pinKey);
        } catch (IOException e) {
            ModSync.LOGGER.warn("[ModSync] не удалось забыть ключ: {}", e.toString());
        }
        start();
    }

    private static final class IdentityException extends IOException {
        IdentityException(String m) {
            super(m);
        }
    }

    /** Запускает проверку в фоне (повторный вызов во время работы игнорируется; после ошибки - "повторить"). */
    public static synchronized void start() {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.RESTART) return;
        String addr = ModSyncConfig.SERVER_ADDRESS.get().trim();
        address = addr;
        error = "";
        cancelled = false;
        identityProblem = false;
        retry = 0;
        if (addr.isEmpty()) {
            state = State.DISABLED;
            ModSync.LOGGER.info("[ModSync] client.serverAddress не задан - синхронизация отключена");
            return;
        }
        done = total = 0;
        filesDone = filesTotal = 0;
        currentName = "";
        state = State.CHECKING;
        Thread t = new Thread(ClientSync::runWithRetry, "ModSync-Client");
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

    /** При обрыве во время загрузки переподключается и докачивает (до MAX_RETRIES раз). */
    private static void runWithRetry() {
        while (true) {
            if (!runOnce()) return;
            retry++;
            if (retry > MAX_RETRIES) {
                state = State.FAILED; // error уже заполнен последней ошибкой
                return;
            }
            ModSync.LOGGER.warn("[ModSync] соединение потеряно ({}), переподключаюсь {}/{} и докачиваю", error, retry, MAX_RETRIES);
            state = State.CHECKING;
            for (int i = 0; i < 20 * retry && !cancelled; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            if (cancelled) {
                error = "cancelled";
                state = State.FAILED;
                return;
            }
        }
    }

    /** @return true, если обрыв случился во время загрузки и имеет смысл переподключиться (недокачанное сохранено). */
    private static boolean runOnce() {
        downloadStarted = false;
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

            // --- шаг 1: обновление самого ModSync по замороженному протоколу v1.
            // Если ModSync обновился - сразу на перезапуск; остальное сделает уже новая версия.
            if (Restarter.ownJar() != null && trySelfUpdate(target, secret, local, maxFile, staging)) {
                return false;
            }
            if (cancelled) throw new IOException("cancelled");

            // --- шаг 2: основная синхронизация модов (основной протокол v2, шифрованный)
            socket = s;
            ModSync.LOGGER.info("[ModSync] подключаюсь к серверу синхронизации {}:{}", target.getHostString(), target.getPort());
            s.connect(target, ModSyncConfig.CONNECT_TIMEOUT.get() * 1000);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            DataInputStream rawIn = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            DataOutputStream rawOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));

            // --- открытая часть: обмен эфемерными ключами X25519
            KeyPair kp = Crypto.newKeyPair();
            byte[] cpub = kp.getPublic().getEncoded();
            byte[] cn = Proto.nonce();
            rawOut.writeInt(Proto.MAGIC);
            rawOut.writeInt(Proto.VERSION);
            rawOut.write(cn);
            rawOut.writeInt(cpub.length);
            rawOut.write(cpub);
            rawOut.flush();
            int st = rawIn.readUnsignedByte();
            if (st == Proto.BAD_VERSION) throw new IOException("protocol version mismatch (update ModSync)");
            if (st != Proto.OK) throw new IOException("unexpected handshake status " + st);
            byte[] sn = rawIn.readNBytes(Proto.NONCE);
            if (sn.length != Proto.NONCE || rawIn.readInt() != Crypto.PUB_LEN) throw new IOException("bad handshake");
            byte[] spub = rawIn.readNBytes(Crypto.PUB_LEN);
            if (spub.length != Crypto.PUB_LEN) throw new EOFException();

            Crypto.Keys keys = Crypto.derive(Crypto.agree(kp.getPrivate(), spub), cn, sn, cpub, spub, Crypto.stretch(secret));
            DataInputStream in = new DataInputStream(new Crypto.SecureIn(rawIn, keys.serverToClient()));
            DataOutputStream out = new DataOutputStream(new Crypto.SecureOut(rawOut, keys.clientToServer()));

            // --- шифрованный канал: сначала "привет" (доказывает знание секрета), потом манифест
            out.writeInt(Crypto.HELLO);
            out.flush();
            byte[] mb;
            try {
                int len = in.readInt();
                if (len < 0 || len > Proto.MAX_MANIFEST_BYTES) throw new IOException("bad manifest length " + len);
                mb = in.readNBytes(len);
                if (mb.length != len) throw new EOFException();
            } catch (Crypto.AuthException | EOFException e) {
                // сервер молча закрывает соединение, если секрет не совпал
                throw new IOException("authentication failed (check client.secret / server.secret)");
            }
            Proto.Manifest manifest = Proto.decodeManifest(mb);
            List<ModEntry> es = manifest.entries();
            pinKey = (target.getHostString() + ":" + target.getPort()).toLowerCase(Locale.ROOT);
            verifyIdentity(pinKey, manifest, Crypto.transcript(cn, sn, cpub, spub));

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

            // чёрный список сервера: моды, которые нужно убрать у клиента (в backup, не стирая)
            Set<String> rm = new HashSet<>();
            for (String r : manifest.remove()) rm.add(r.toLowerCase(Locale.ROOT).trim());
            Set<String> servedIds = new HashSet<>();
            for (ModEntry e : es) servedIds.addAll(e.modIdSet());
            List<Path> toRemove = new ArrayList<>();
            for (ModScanner.Jar l : local) {
                if (l.modIds().contains(ModSync.MODID) || serverHashes.contains(l.sha256())) continue;
                if (l.modIds().stream().anyMatch(servedIds::contains)) continue; // раздаваемый сервером мод не удаляем
                boolean hit = rm.contains(l.fileName().toLowerCase(Locale.ROOT)) || l.modIds().stream().anyMatch(rm::contains);
                if (hit) {
                    toRemove.add(l.path());
                    ModSync.LOGGER.info("[ModSync] {} - в чёрном списке сервера, будет убран", l.fileName());
                }
            }

            if (missing.isEmpty() && toRemove.isEmpty()) {
                out.writeInt(0);
                out.flush();
                ModSync.LOGGER.info("[ModSync] моды актуальны ({} шт.)", es.size());
                clearDir(staging);
                state = State.UP_TO_DATE;
                return false;
            }
            if (missing.isEmpty()) {
                out.writeInt(0);
                out.flush();
                plan = install(es, List.of(), Map.of(), toRemove);
                ModSync.LOGGER.info("[ModSync] убрано модов по чёрному списку: {}, нужен перезапуск игры", toRemove.size());
                clearDir(staging);
                state = State.RESTART;
                return false;
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

            LinkedHashSet<Path> obs = new LinkedHashSet<>(obsolete);
            obs.addAll(toRemove);
            obsolete = new ArrayList<>(obs);

            // --- загрузка с докачкой: недокачанные файлы лежат в staging как <sha256>.part
            ModSync.LOGGER.info("[ModSync] нужно скачать {} файл(ов), {}", missing.size(), Fmt.size(need));
            Files.createDirectories(staging);
            Set<String> keep = new HashSet<>();
            for (int i : missing) keep.add(es.get(i).sha256() + ".part");
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(staging)) {
                for (Path p : ds) {
                    if (!keep.contains(p.getFileName().toString())) Files.deleteIfExists(p);
                }
            }
            long[] offsets = new long[missing.size()];
            long resumed = 0;
            for (int k = 0; k < offsets.length; k++) {
                ModEntry e = es.get(missing.get(k));
                Path part = staging.resolve(e.sha256() + ".part");
                long have = Files.isRegularFile(part) ? Files.size(part) : 0;
                if (have > e.size()) {
                    Files.delete(part);
                    have = 0;
                }
                offsets[k] = have;
                resumed += have;
            }
            if (resumed > 0) ModSync.LOGGER.info("[ModSync] докачка: уже есть {}", Fmt.size(resumed));
            total = need;
            done = resumed;
            filesDone = 0;
            filesTotal = missing.size();
            startNanos = System.nanoTime();
            downloadStarted = true;
            state = State.DOWNLOADING;

            out.writeInt(missing.size());
            for (int k = 0; k < offsets.length; k++) {
                out.writeInt(missing.get(k));
                out.writeLong(offsets[k]);
            }
            out.flush();

            Map<Integer, Path> staged = new HashMap<>();
            byte[] buf = new byte[64 * 1024];
            for (int k = 0; k < offsets.length; k++) {
                int idx = missing.get(k);
                ModEntry e = es.get(idx);
                currentName = e.fileName();
                Path part = staging.resolve(e.sha256() + ".part");
                long have = offsets[k];
                MessageDigest md = ModScanner.newDigest();
                if (have > 0) {
                    // хэш уже скачанной части: перечитываем её с диска, дальше продолжаем тот же SHA-256
                    try (InputStream pin = new BufferedInputStream(Files.newInputStream(part), 64 * 1024)) {
                        long rem = have;
                        while (rem > 0) {
                            int r = pin.read(buf, 0, (int) Math.min(buf.length, rem));
                            if (r < 0) throw new EOFException("partial file shrank: " + e.fileName());
                            md.update(buf, 0, r);
                            rem -= r;
                        }
                    }
                }
                long left = e.size() - have;
                try (OutputStream fo = new BufferedOutputStream(Files.newOutputStream(part, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, have > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING))) {
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
                    Files.deleteIfExists(part); // испорченную часть не докачиваем - следующая попытка начнёт файл заново
                    throw new IOException("hash mismatch: " + e.fileName());
                }
                staged.put(idx, part);
                filesDone++;
            }

            // --- установка
            plan = install(es, missing, staged, obsolete);
            ModSync.LOGGER.info("[ModSync] моды установлены, нужен перезапуск игры");
            state = State.RESTART;
            return false;
        } catch (Throwable t) {
            if (cancelled) {
                error = "cancelled";
            } else if (t instanceof IdentityException) {
                identityProblem = true;
                error = t.getMessage();
                ModSync.LOGGER.error("[ModSync] {}", t.getMessage());
            } else {
                ModSync.LOGGER.error("[ModSync] синхронизация не удалась", t);
                error = t instanceof java.net.UnknownHostException ? "unknown host: " + t.getMessage()
                        : t instanceof java.net.SocketTimeoutException ? "timeout"
                        : String.valueOf(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
            // недокачанные файлы сохраняем (staging/<sha256>.part): после обрыва или повторной попытки они будут докачаны
            if (!cancelled && downloadStarted && !(t instanceof IdentityException) && t instanceof IOException) {
                return true;
            }
            state = State.FAILED;
            return false;
        } finally {
            socket = null;
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** TOFU: при первом подключении запоминаем ключ сервера, потом сверяем; подпись привязана к этому рукопожатию. */
    private static void verifyIdentity(String key, Proto.Manifest m, byte[] transcript) throws IOException {
        String pinned = KnownServers.get(key);
        if (m.identityPub().length == 0) {
            if (pinned != null) {
                throw new IdentityException("server no longer presents its identity key (it did before) - possible downgrade attack");
            }
            ModSync.LOGGER.warn("[ModSync] сервер не предъявил ключ (старая версия ModSync) - личность сервера не проверена");
            return;
        }
        if (!Identity.verify(m.identityPub(), transcript, m.identitySig())) {
            throw new IdentityException("server identity signature is invalid - possible man-in-the-middle");
        }
        String fp = Identity.fingerprint(m.identityPub());
        if (pinned == null) {
            KnownServers.put(key, fp);
            ModSync.LOGGER.info("[ModSync] первый вход на {}: запомнен ключ сервера {}", key, fp);
        } else if (!pinned.equals(fp)) {
            throw new IdentityException("SERVER KEY CHANGED for " + key + " (was " + pinned.substring(0, 16)
                    + "..., now " + fp.substring(0, 16) + "...) - someone may be impersonating the server");
        }
    }

    /** @return true, если скачана новая версия ModSync (состояние уже переведено в RESTART). Ошибки не фатальны. */
    private static boolean trySelfUpdate(InetSocketAddress target, String secret, List<ModScanner.Jar> local,
                                         long maxFile, Path staging) throws IOException {
        Socket s1 = new Socket();
        socket = s1;
        try {
            Set<String> localHashes = local.stream().map(ModScanner.Jar::sha256).collect(Collectors.toSet());
            Files.createDirectories(staging);
            Files.deleteIfExists(staging.resolve("self.part"));
            SelfUpdate.Result r = SelfUpdate.check(s1, target, secret, ModSyncConfig.CONNECT_TIMEOUT.get() * 1000,
                    localHashes, maxFile, staging,
                    (name, size) -> {
                        currentName = name;
                        total = size;
                        filesTotal = 1;
                        startNanos = System.nanoTime();
                        state = State.DOWNLOADING;
                    },
                    n -> done += n);
            if (r == null) return false;
            JarTrust.check(Restarter.ownJar(), r.staged(), ModSyncConfig.REQUIRE_SIGNED.get());
            filesDone = 1;
            List<Path> obsolete = new ArrayList<>();
            for (ModScanner.Jar l : local) {
                if (l.modIds().contains(ModSync.MODID) && !l.sha256().equals(r.entry().sha256())) obsolete.add(l.path());
            }
            plan = install(List.of(r.entry()), List.of(0), Map.of(0, r.staged()), obsolete);
            ModSync.LOGGER.info("[ModSync] ModSync обновлён до {}, нужен перезапуск игры", r.entry().fileName());
            state = State.RESTART;
            return true;
        } catch (IOException | RuntimeException e) {
            if (cancelled) throw new IOException("cancelled");
            // самообновление - дополнительная возможность: при сбое просто идём дальше к основной синхронизации
            ModSync.LOGGER.warn("[ModSync] самообновление пропущено: {}", e.toString());
            if (e.getMessage() != null && e.getMessage().contains("rejected")) {
                ModSync.LOGGER.error("[ModSync] ВНИМАНИЕ: обновление ModSync отклонено проверкой подписи. Если вы не ожидали этого, возможно, сервер или канал связи скомпрометированы.");
            }
            Files.deleteIfExists(staging.resolve("self.part"));
            done = total = 0;
            filesDone = filesTotal = 0;
            currentName = "";
            state = State.CHECKING;
            return false;
        } finally {
            try {
                s1.close();
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
            // самообновление ModSync: новый jar кладёт хелпер уже после выхода из игры,
            // чтобы в mods ни при каких условиях не оказалось двух версий мода одновременно
            boolean self = es.get(idx).modIdSet().contains(ModSync.MODID);
            if (self || Files.exists(dst)) {
                if (Files.exists(dst) && backedUp.add(dst)) {
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
        static String time(long sec) {
            if (sec < 0) sec = 0;
            long h = sec / 3600, m = sec % 3600 / 60, s = sec % 60;
            return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%02d:%02d", m, s);
        }

        static String size(long b) {
            if (b < 1024) return b + " B";
            if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
            return String.format(Locale.ROOT, "%.1f MB", b / 1024.0 / 1024.0);
        }
    }
}
