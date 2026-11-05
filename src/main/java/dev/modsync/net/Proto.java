package dev.modsync.net;

import dev.modsync.common.ModEntry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Протокол синхронизации (обычный TCP, не связан с сетью Minecraft).
 *
 * <pre>
 * C -> S : int MAGIC, int VERSION, byte[16] clientNonce
 * S -> C : byte status (OK / BAD_VERSION); если OK: byte[16] serverNonce
 * C -> S : byte[32] HMAC("C" | clientNonce | serverNonce)       (нули, если секрет не задан)
 * S -> C : byte status (OK / AUTH_FAILED); если OK:
 *          int manifestLen, byte[] manifest, byte[32] HMAC("S" | clientNonce | serverNonce | manifest)
 * C -> S : int n, n * int index          (n = 0 - "всё актуально", соединение закрывается)
 * S -> C : для каждого индекса по порядку - ровно entry.size() байт файла
 * </pre>
 * Целостность файлов проверяется SHA-256 из манифеста, а манифест (при заданном секрете) подписан HMAC.
 */
public final class Proto {
    public static final int MAGIC = 0x4D53594E; // "MSYN"
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 2048;
    public static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    public static final int NONCE = 16;
    public static final int MAC = 32;
    public static final int OK = 0;
    public static final int BAD_VERSION = 1;
    public static final int AUTH_FAILED = 2;
    public static final int DEFAULT_PORT = 25566;

    private static final SecureRandom RND = new SecureRandom();

    private Proto() {}

    public static byte[] nonce() {
        byte[] b = new byte[NONCE];
        RND.nextBytes(b);
        return b;
    }

    /** HMAC-SHA256; при пустом секрете возвращает нули (аутентификация отключена). */
    public static byte[] mac(String secret, String tag, byte[]... parts) {
        if (secret == null || secret.isEmpty()) return new byte[MAC];
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            m.update(tag.getBytes(StandardCharsets.US_ASCII));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encode(List<ModEntry> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(entries.size());
        for (ModEntry e : entries) e.write(out);
        out.flush();
        return bos.toByteArray();
    }

    public static List<ModEntry> decode(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int n = in.readInt();
        if (n < 0 || n > MAX_ENTRIES) throw new IOException("ModSync: bad manifest size " + n);
        List<ModEntry> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(ModEntry.read(in));
        return l;
    }
}
