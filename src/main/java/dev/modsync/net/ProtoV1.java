package dev.modsync.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Протокол v1 - ЗАМОРОЖЕН и не меняется. Используется только для обновления самого ModSync:
 * благодаря этому клиент любой версии всегда может скачать новый ModSync, даже если основной протокол (v2 и последующие) изменился.
 * Моды этим протоколом не передаются.
 *
 * <pre>
 * C -> S : int MAGIC, int 1, byte[16] clientNonce
 * S -> C : byte status (OK / BAD_VERSION); если OK: byte[16] serverNonce
 * C -> S : byte[32] HMAC("C" | clientNonce | serverNonce)       (нули, если секрет не задан)
 * S -> C : byte status (OK / AUTH_FAILED); если OK:
 *          int manifestLen, byte[] manifest (только запись ModSync или пусто), byte[32] HMAC("S" | cn | sn | manifest)
 * C -> S : int n, n * int index          (n = 0 - "всё актуально")
 * S -> C : для каждого индекса - ровно entry.size() байт файла
 * </pre>
 * Трафик НЕ шифруется. Целостность обеспечивают подпись манифеста (если задан секрет) и SHA-256 файла из манифеста.
 */
public final class ProtoV1 {
    public static final int VERSION = 1;
    public static final int NONCE = 16;
    public static final int MAC = 32;
    public static final int OK = 0;
    public static final int BAD_VERSION = 1;
    public static final int AUTH_FAILED = 2;

    private ProtoV1() {}

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
}
