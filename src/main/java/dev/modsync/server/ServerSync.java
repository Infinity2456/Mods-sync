package dev.modsync.server;

import dev.modsync.ModSync;
import dev.modsync.ModSyncConfig;
import dev.modsync.common.ModEntry;
import dev.modsync.common.ModScanner;
import dev.modsync.net.Crypto;
import dev.modsync.net.Identity;
import dev.modsync.net.Proto;
import dev.modsync.net.ProtoV1;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Серверная часть: считает манифест из папки mods и поднимает отдельный TCP-порт,
 * по которому клиенты забирают список модов и сами файлы (см. {@link Proto}).
 */
public final class ServerSync {
    private static final int HANDSHAKE_TIMEOUT_MS = 30_000;
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static volatile List<ModScanner.Jar> jars = List.of();
    private static volatile List<ModEntry> entries = List.of();

    private static ServerSocket listener;
    private static ExecutorService pool;
    private static Semaphore slots;
    private static boolean hasSecret;
    private static volatile String rawSecret = "";
    /** Только для протокола v1: jar самого ModSync. */
    private static volatile ModScanner.Jar selfJar;
    private static volatile ModEntry selfEntry;
    private static volatile List<String> removeList = List.of();
    private static volatile KeyPair identity;
    private static volatile byte[] psk = new byte[0];
    private static final Set<Conn> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ModSync-Watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        TIMER.scheduleWithFixedDelay(ServerSync::sweep, 10, 10, TimeUnit.SECONDS);
    }

    private ServerSync() {}

    // ------------------------------------------------------------------ запуск / остановка

    public static synchronized void start() {
        stop();
        if (!ModSyncConfig.SERVER_ENABLED.get()) {
            jars = List.of();
            entries = List.of();
            ModSync.LOGGER.info("[ModSync] раздача модов отключена в конфиге");
            return;
        }
        rebuild();
        try {
            identity = Identity.loadOrCreate(FMLPaths.GAMEDIR.get().resolve("modsync").resolve("identity.key"));
            ModSync.LOGGER.info("[ModSync] отпечаток ключа сервера (SHA-256): {}", Identity.fingerprint(identity.getPublic().getEncoded()));
        } catch (IOException e) {
            ModSync.LOGGER.error("[ModSync] {}", e.getMessage());
            return;
        }

        int port = ModSyncConfig.SYNC_PORT.get();
        String bind = ModSyncConfig.BIND_ADDRESS.get().trim();
        String secret = ModSyncConfig.SECRET.get();
        hasSecret = !secret.isEmpty();
        rawSecret = secret;
        psk = Crypto.stretch(secret);
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(bind.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bind, port), 50);
            listener = ss;
        } catch (IOException e) {
            ModSync.LOGGER.error("[ModSync] не удалось открыть порт синхронизации {}: {}", port, e.toString());
            return;
        }
        int max = ModSyncConfig.MAX_CONNECTIONS.get();
        slots = new Semaphore(max);
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ModSync-Conn-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        final ServerSocket ss = listener;
        Thread acceptor = new Thread(() -> acceptLoop(ss), "ModSync-Acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        ModSync.LOGGER.info("[ModSync] порт синхронизации открыт: {}:{} (TCP), одновременных соединений: {}{}",
                bind.isEmpty() ? "*" : bind, port, max,
                hasSecret ? ", шифрование с секретом" : ", секрет НЕ задан (шифрование есть, защиты от подмены нет)");
    }

    public static synchronized void stop() {
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            listener = null;
        }
        for (Conn c : ACTIVE) c.close();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private static void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (!ss.isClosed()) ModSync.LOGGER.warn("[ModSync] accept: {}", e.toString());
                return;
            }
            Semaphore sem = slots;
            ExecutorService ex = pool;
            if (sem == null || ex == null || !sem.tryAcquire()) {
                closeQuietly(s);
                continue;
            }
            try {
                ex.execute(() -> {
                    try {
                        handle(s);
                    } finally {
                        sem.release();
                    }
                });
            } catch (RejectedExecutionException e) {
                sem.release();
                closeQuietly(s);
            }
        }
    }

    // ------------------------------------------------------------------ манифест

    public static void rebuild() {
        Set<String> excluded = new HashSet<>();
        for (String s : ModSyncConfig.EXCLUDED.get()) excluded.add(s.toLowerCase(Locale.ROOT).trim());
        long maxFile = ModSyncConfig.MAX_FILE_MB.get() * 1024L * 1024L;

        List<ModScanner.Jar> j = new ArrayList<>();
        List<ModEntry> e = new ArrayList<>();
        ModScanner.Jar self = null;
        long total = 0;
        for (ModScanner.Jar jar : ModScanner.scan(FMLPaths.MODSDIR.get())) {
            if (jar.serverOnly()) {
                ModSync.LOGGER.info("[ModSync] {} - только серверный, не отправляется", jar.fileName());
                continue;
            }
            if (jar.modIds().contains(ModSync.MODID)) {
                // сам ModSync отдаётся только по замороженному протоколу v1 (см. ProtoV1), в основной манифест не попадает
                boolean skip = excluded.contains(ModSync.MODID) || excluded.contains(jar.fileName().toLowerCase(Locale.ROOT))
                        || !ModScanner.validFileName(jar.fileName()) || jar.size() > maxFile;
                if (!skip) {
                    if (self != null) ModSync.LOGGER.warn("[ModSync] в mods несколько jar ModSync ({} и {}) - оставьте один", self.fileName(), jar.fileName());
                    self = jar;
                }
                continue;
            }
            if (excluded.contains(jar.fileName().toLowerCase(Locale.ROOT))
                    || jar.modIds().stream().anyMatch(excluded::contains)) {
                ModSync.LOGGER.info("[ModSync] {} - в списке исключений", jar.fileName());
                continue;
            }
            if (!ModScanner.validFileName(jar.fileName()) || jar.size() > maxFile) {
                ModSync.LOGGER.warn("[ModSync] {} пропущен (недопустимое имя или слишком большой)", jar.fileName());
                continue;
            }
            j.add(jar);
            e.add(new ModEntry(jar.fileName(), String.join(",", jar.modIds()), jar.version(), jar.sha256(), jar.size()));
            total += jar.size();
        }
        if (e.size() > Proto.MAX_ENTRIES) {
            ModSync.LOGGER.warn("[ModSync] слишком много модов ({}), лимит {}", e.size(), Proto.MAX_ENTRIES);
            j = new ArrayList<>(j.subList(0, Proto.MAX_ENTRIES));
            e = new ArrayList<>(e.subList(0, Proto.MAX_ENTRIES));
        }
        List<String> rm = new ArrayList<>();
        for (String r : ModSyncConfig.REMOVE_MODS.get()) {
            String t = r.toLowerCase(Locale.ROOT).trim();
            if (t.isEmpty() || t.length() > 256 || t.equals(ModSync.MODID) || rm.contains(t)) continue;
            if (rm.size() < Proto.MAX_REMOVE) rm.add(t);
        }
        removeList = List.copyOf(rm);
        if (!rm.isEmpty()) ModSync.LOGGER.info("[ModSync] чёрный список (клиенты удалят): {}", rm);
        jars = List.copyOf(j);
        entries = List.copyOf(e);
        selfJar = self;
        selfEntry = self == null ? null
                : new ModEntry(self.fileName(), String.join(",", self.modIds()), self.version(), self.sha256(), self.size());
        if (self != null) ModSync.LOGGER.info("[ModSync] самообновление клиентов: {} (v{})", self.fileName(), self.version());
        ModSync.LOGGER.info("[ModSync] в манифесте {} модов, {} МБ", e.size(), total / 1024 / 1024);
    }

    // ------------------------------------------------------------------ соединение

    private static void handle(Socket s) {
        Conn conn = new Conn(s);
        ACTIVE.add(conn);
        String who = String.valueOf(s.getRemoteSocketAddress());
        try {
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            DataInputStream rawIn = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            DataOutputStream rawOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));

            // --- открытая часть: обмен эфемерными ключами X25519
            if (rawIn.readInt() != Proto.MAGIC) return; // не наш клиент - молча закрываем
            int ver = rawIn.readInt();
            if (ver == ProtoV1.VERSION) {
                handleV1(rawIn, rawOut, who, conn);
                return;
            }
            if (ver != Proto.VERSION) {
                rawOut.writeByte(Proto.BAD_VERSION);
                rawOut.flush();
                return;
            }
            byte[] cn = rawIn.readNBytes(Proto.NONCE);
            if (cn.length != Proto.NONCE || rawIn.readInt() != Crypto.PUB_LEN) return;
            byte[] cpub = rawIn.readNBytes(Crypto.PUB_LEN);
            if (cpub.length != Crypto.PUB_LEN) return;

            KeyPair kp = Crypto.newKeyPair();
            byte[] spub = kp.getPublic().getEncoded();
            byte[] sn = Proto.nonce();
            rawOut.writeByte(Proto.OK);
            rawOut.write(sn);
            rawOut.writeInt(spub.length);
            rawOut.write(spub);
            rawOut.flush();

            Crypto.Keys keys = Crypto.derive(Crypto.agree(kp.getPrivate(), cpub), cn, sn, cpub, spub, psk);
            DataInputStream in = new DataInputStream(new Crypto.SecureIn(rawIn, keys.clientToServer()));
            DataOutputStream out = new DataOutputStream(new Crypto.SecureOut(rawOut, keys.serverToClient()));

            // --- дальше всё зашифровано. Пока клиент не докажет знание секрета, сервер ничего не отправляет
            if (in.readInt() != Crypto.HELLO) return;

            List<ModEntry> es = entries;
            List<ModScanner.Jar> files = jars;
            KeyPair id = identity;
            byte[] sig = Identity.sign(id.getPrivate(), Crypto.transcript(cn, sn, cpub, spub));
            byte[] mb = Proto.encode(new Proto.Manifest(es, removeList, id.getPublic().getEncoded(), sig));
            out.writeInt(mb.length);
            out.write(mb);
            out.flush();
            conn.touch();

            int n = in.readInt();
            if (n == 0) return;
            if (n < 0 || n > es.size() || es.size() != files.size()) return;
            int[] idx = new int[n];
            long[] off = new long[n];
            Set<Integer> seen = new HashSet<>();
            long skipped = 0;
            for (int i = 0; i < n; i++) {
                idx[i] = in.readInt();
                off[i] = in.readLong();
                if (idx[i] < 0 || idx[i] >= es.size() || !seen.add(idx[i])
                        || off[i] < 0 || off[i] > es.get(idx[i]).size()) {
                    ModSync.LOGGER.warn("[ModSync] {}: некорректный запрос", who);
                    return;
                }
                skipped += off[i];
            }

            ModSync.LOGGER.info("[ModSync] {}: отдаю {} файл(ов){}", who, n,
                    skipped > 0 ? " (докачка, уже есть у клиента: " + skipped / 1024 + " КБ)" : "");
            byte[] buf = new byte[64 * 1024];
            for (int k = 0; k < n; k++) {
                int i = idx[k];
                ModEntry e = es.get(i);
                ModScanner.Jar jar = files.get(i);
                if (Files.size(jar.path()) != e.size()) {
                    ModSync.LOGGER.warn("[ModSync] {} изменился после запуска сервера - клиент не получит его (перезапустите сервер)", e.fileName());
                    return;
                }
                long left = e.size() - off[k];
                try (InputStream fin = Files.newInputStream(jar.path())) {
                    fin.skipNBytes(off[k]);
                    while (left > 0) {
                        int r = fin.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) return; // файл усох - закрываем, клиент увидит обрыв
                        out.write(buf, 0, r);
                        left -= r;
                        conn.touch();
                    }
                }
            }
            out.flush();
            ModSync.LOGGER.info("[ModSync] {}: готово", who);
        } catch (Crypto.AuthException e) {
            ModSync.LOGGER.warn("[ModSync] {}: неверный секрет (или это не клиент ModSync)", who);
        } catch (EOFException | java.net.SocketException ignored) {
            // клиент ушёл - не страшно
        } catch (IOException e) {
            ModSync.LOGGER.debug("[ModSync] {}: {}", who, e.toString());
        } catch (Throwable t) {
            ModSync.LOGGER.warn("[ModSync] {}: ошибка", who, t);
        } finally {
            ACTIVE.remove(conn);
            conn.close();
        }
    }

    /** Замороженный протокол v1 (см. {@link ProtoV1}): отдаёт только jar ModSync. */
    private static void handleV1(DataInputStream in, DataOutputStream out, String who, Conn conn) throws IOException {
        byte[] cn = in.readNBytes(ProtoV1.NONCE);
        if (cn.length != ProtoV1.NONCE) return;
        String secret = rawSecret;
        byte[] sn = Proto.nonce();
        out.writeByte(ProtoV1.OK);
        out.write(sn);
        out.flush();

        byte[] cmac = in.readNBytes(ProtoV1.MAC);
        if (cmac.length != ProtoV1.MAC) return;
        if (!secret.isEmpty() && !MessageDigest.isEqual(cmac, ProtoV1.mac(secret, "C", cn, sn))) {
            ModSync.LOGGER.warn("[ModSync] {}: неверный секрет (v1)", who);
            out.writeByte(ProtoV1.AUTH_FAILED);
            out.flush();
            return;
        }
        out.writeByte(ProtoV1.OK);

        ModEntry e = selfEntry;
        ModScanner.Jar jar = selfJar;
        List<ModEntry> es = e == null ? List.of() : List.of(e);
        byte[] mb = Proto.encode(es);
        out.writeInt(mb.length);
        out.write(mb);
        out.write(ProtoV1.mac(secret, "S", cn, sn, mb));
        out.flush();
        conn.touch();

        int n = in.readInt();
        if (n == 0) return;
        if (n != 1 || e == null || in.readInt() != 0) return;
        if (Files.size(jar.path()) != e.size()) {
            ModSync.LOGGER.warn("[ModSync] {} изменился после запуска сервера (перезапустите сервер)", e.fileName());
            return;
        }
        ModSync.LOGGER.info("[ModSync] {}: отдаю ModSync {} (v1)", who, e.fileName());
        byte[] buf = new byte[64 * 1024];
        long left = e.size();
        try (InputStream fin = Files.newInputStream(jar.path())) {
            while (left > 0) {
                int r = fin.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) return;
                out.write(buf, 0, r);
                left -= r;
                conn.touch();
            }
        }
        out.flush();
    }

    private static void sweep() {
        long now = System.nanoTime();
        for (Conn c : ACTIVE) {
            if (now - c.last > IDLE_NANOS) c.close();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Conn {
        final Socket socket;
        volatile long last = System.nanoTime();

        Conn(Socket socket) {
            this.socket = socket;
        }

        void touch() {
            last = System.nanoTime();
        }

        void close() {
            closeQuietly(socket);
        }
    }
}
