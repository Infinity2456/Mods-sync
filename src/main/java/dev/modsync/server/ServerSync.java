package dev.modsync.server;

import dev.modsync.ModSync;
import dev.modsync.ModSyncConfig;
import dev.modsync.common.ModEntry;
import dev.modsync.common.ModScanner;
import dev.modsync.net.Proto;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
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
    private static String secret = "";
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

        int port = ModSyncConfig.SYNC_PORT.get();
        String bind = ModSyncConfig.BIND_ADDRESS.get().trim();
        secret = ModSyncConfig.SECRET.get();
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
                secret.isEmpty() ? ", секрет НЕ задан" : ", секрет задан");
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
        long total = 0;
        for (ModScanner.Jar jar : ModScanner.scan(FMLPaths.MODSDIR.get())) {
            if (jar.serverOnly()) {
                ModSync.LOGGER.info("[ModSync] {} - только серверный, не отправляется", jar.fileName());
                continue;
            }
            if (jar.modIds().contains(ModSync.MODID)) continue;
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
        jars = List.copyOf(j);
        entries = List.copyOf(e);
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
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 4096));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));

            if (in.readInt() != Proto.MAGIC) return; // не наш клиент - молча закрываем
            int ver = in.readInt();
            byte[] cn = in.readNBytes(Proto.NONCE);
            if (cn.length != Proto.NONCE) return;
            if (ver != Proto.VERSION) {
                out.writeByte(Proto.BAD_VERSION);
                out.flush();
                return;
            }
            byte[] sn = Proto.nonce();
            out.writeByte(Proto.OK);
            out.write(sn);
            out.flush();

            byte[] cmac = in.readNBytes(Proto.MAC);
            if (cmac.length != Proto.MAC) return;
            if (!secret.isEmpty() && !MessageDigest.isEqual(cmac, Proto.mac(secret, "C", cn, sn))) {
                ModSync.LOGGER.warn("[ModSync] {}: неверный секрет", who);
                out.writeByte(Proto.AUTH_FAILED);
                out.flush();
                return;
            }
            out.writeByte(Proto.OK);

            List<ModEntry> es = entries;
            List<ModScanner.Jar> files = jars;
            byte[] mb = Proto.encode(es);
            out.writeInt(mb.length);
            out.write(mb);
            out.write(Proto.mac(secret, "S", cn, sn, mb));
            out.flush();
            conn.touch();

            int n = in.readInt();
            if (n == 0) return;
            if (n < 0 || n > es.size() || es.size() != files.size()) return;
            int[] idx = new int[n];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < n; i++) {
                idx[i] = in.readInt();
                if (idx[i] < 0 || idx[i] >= es.size() || !seen.add(idx[i])) {
                    ModSync.LOGGER.warn("[ModSync] {}: некорректный запрос", who);
                    return;
                }
            }

            ModSync.LOGGER.info("[ModSync] {}: отдаю {} файл(ов)", who, n);
            byte[] buf = new byte[64 * 1024];
            for (int i : idx) {
                ModEntry e = es.get(i);
                ModScanner.Jar jar = files.get(i);
                if (Files.size(jar.path()) != e.size()) {
                    ModSync.LOGGER.warn("[ModSync] {} изменился после запуска сервера - клиент не получит его (перезапустите сервер)", e.fileName());
                    return;
                }
                long left = e.size();
                try (InputStream fin = Files.newInputStream(jar.path())) {
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
