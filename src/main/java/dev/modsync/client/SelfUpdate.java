package dev.modsync.client;

import dev.modsync.ModSync;
import dev.modsync.common.ModEntry;
import dev.modsync.common.ModScanner;
import dev.modsync.net.Proto;
import dev.modsync.net.ProtoV1;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Обновление самого ModSync по замороженному протоколу v1 ({@link ProtoV1}).
 * Выполняется ПЕРЕД основной синхронизацией модов (основной протокол): так клиент любой версии всегда может получить новый ModSync,
 * даже если основной протокол изменился.
 */
final class SelfUpdate {
    /** Скачанный и проверенный jar нового ModSync. */
    record Result(ModEntry entry, Path staged) {}

    private SelfUpdate() {}

    /**
     * @param s           неподключённый сокет (его закрывает вызывающий; через него же работает отмена)
     * @param onStart     вызывается перед загрузкой: имя файла и размер
     * @param onBytes     вызывается по ходу загрузки с числом принятых байт
     * @return null, если сервер не предлагает ModSync или версия уже актуальна
     */
    static Result check(Socket s, InetSocketAddress target, String secret, int connectTimeoutMs,
                        Set<String> localHashes, long maxFile, Path staging,
                        java.util.function.BiConsumer<String, Long> onStart, LongConsumer onBytes) throws IOException {
        s.connect(target, connectTimeoutMs);
        s.setSoTimeout(30_000);
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 4096));

        byte[] cn = Proto.nonce();
        out.writeInt(Proto.MAGIC);
        out.writeInt(ProtoV1.VERSION);
        out.write(cn);
        out.flush();
        int st = in.readUnsignedByte();
        if (st == ProtoV1.BAD_VERSION) throw new IOException("server does not support v1 (self-update unavailable)");
        if (st != ProtoV1.OK) throw new IOException("unexpected handshake status " + st);
        byte[] sn = in.readNBytes(ProtoV1.NONCE);
        if (sn.length != ProtoV1.NONCE) throw new EOFException();
        out.write(ProtoV1.mac(secret, "C", cn, sn));
        out.flush();
        st = in.readUnsignedByte();
        if (st == ProtoV1.AUTH_FAILED) throw new IOException("wrong secret");
        if (st != ProtoV1.OK) throw new IOException("unexpected auth status " + st);

        int len = in.readInt();
        if (len < 0 || len > Proto.MAX_MANIFEST_BYTES) throw new IOException("bad manifest length " + len);
        byte[] mb = in.readNBytes(len);
        byte[] mac = in.readNBytes(ProtoV1.MAC);
        if (mb.length != len || mac.length != ProtoV1.MAC) throw new EOFException();
        if (!secret.isEmpty() && !MessageDigest.isEqual(mac, ProtoV1.mac(secret, "S", cn, sn, mb))) {
            throw new IOException("manifest signature mismatch");
        }
        List<ModEntry> es = Proto.decode(mb);

        ModEntry e = null;
        for (ModEntry x : es) {
            if (x.modIdSet().contains(ModSync.MODID)) {
                e = x;
                break;
            }
        }
        if (e == null || localHashes.contains(e.sha256())) {
            out.writeInt(0);
            out.flush();
            return null; // нечего обновлять
        }
        if (!ModScanner.validFileName(e.fileName()) || e.size() < 0 || e.size() > maxFile
                || !e.sha256().matches("[0-9a-f]{64}")) {
            throw new IOException("bad manifest entry: " + e.fileName());
        }

        ModSync.LOGGER.info("[ModSync] на сервере другая версия ModSync ({}), скачиваю", e.fileName());
        onStart.accept(e.fileName(), e.size());
        out.writeInt(1);
        out.writeInt(0);
        out.flush();

        Files.createDirectories(staging);
        Path part = staging.resolve("self.part");
        MessageDigest md = ModScanner.newDigest();
        long left = e.size();
        byte[] buf = new byte[64 * 1024];
        try (OutputStream fo = new BufferedOutputStream(Files.newOutputStream(part))) {
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new EOFException("server closed connection while sending " + e.fileName());
                fo.write(buf, 0, r);
                md.update(buf, 0, r);
                left -= r;
                onBytes.accept(r);
            }
        }
        if (!ModScanner.hex(md.digest()).equalsIgnoreCase(e.sha256())) {
            Files.deleteIfExists(part);
            throw new IOException("hash mismatch: " + e.fileName());
        }
        return new Result(e, part);
    }
}
