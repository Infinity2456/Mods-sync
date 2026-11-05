package dev.modsync.net;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;

/**
 * Шифрование канала синхронизации.
 * <ul>
 *   <li>Обмен ключами: эфемерный X25519 (новые ключи на каждое соединение - forward secrecy).</li>
 *   <li>Общий секрет (PBKDF2) подмешивается в вывод ключей: не зная его, нельзя получить сессионные ключи.</li>
 *   <li>Данные: AES-256-GCM, кадры до {@link #FRAME} байт, счётчик в nonce, отдельный ключ на каждое направление
 *       (защита от подмены, повтора, перестановки и обрезания кадров).</li>
 * </ul>
 */
public final class Crypto {
    public static final int FRAME = 32 * 1024;
    public static final int TAG = 16;
    /** Длина X.509-представления открытого ключа X25519. */
    public static final int PUB_LEN = 44;
    /** Первое сообщение клиента внутри шифрованного канала: сервер не отвечает ничего, пока оно не расшифруется. */
    public static final int HELLO = 0x4D535931;

    private static final byte[] PSK_SALT = "modsync-psk-v2".getBytes(StandardCharsets.US_ASCII);
    private static final int PBKDF2_ITERATIONS = 120_000;

    private Crypto() {}

    /** Расшифровка/проверка кадра не удалась: чаще всего разные секреты. */
    public static final class AuthException extends IOException {
        public AuthException(String m) {
            super(m);
        }
    }

    public record Keys(byte[] clientToServer, byte[] serverToClient) {}

    // ------------------------------------------------------------------ ключи

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Растягивает секрет (медленный хэш против перебора). Пустой секрет даёт пустой массив. */
    public static byte[] stretch(String secret) {
        if (secret == null || secret.isEmpty()) return new byte[0];
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(secret.toCharArray(), PSK_SALT, PBKDF2_ITERATIONS, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] agree(PrivateKey priv, byte[] peerPub) throws IOException {
        try {
            if (peerPub.length != PUB_LEN) throw new IOException("bad public key length");
            PublicKey pub = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(peerPub));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(priv);
            ka.doPhase(pub, true);
            byte[] shared = ka.generateSecret();
            int acc = 0;
            for (byte b : shared) acc |= b;
            if (acc == 0) throw new IOException("bad public key");
            return shared;
        } catch (GeneralSecurityException e) {
            throw new IOException("key agreement failed: " + e.getMessage(), e);
        }
    }

    /** Хэш открытой части рукопожатия (оба nonce и оба открытых ключа): им связываются ключи сессии и подпись сервера. */
    public static byte[] transcript(byte[] cn, byte[] sn, byte[] cpub, byte[] spub) {
        MessageDigest sha = sha256();
        sha.update(cn);
        sha.update(sn);
        sha.update(cpub);
        sha.update(spub);
        return sha.digest();
    }

    public static Keys derive(byte[] shared, byte[] cn, byte[] sn, byte[] cpub, byte[] spub, byte[] psk) {
        byte[] transcript = transcript(cn, sn, cpub, spub);
        byte[] ikm = new byte[shared.length + psk.length];
        System.arraycopy(shared, 0, ikm, 0, shared.length);
        System.arraycopy(psk, 0, ikm, shared.length, psk.length);
        byte[] prk = hmac(transcript, ikm);
        return new Keys(hmac(prk, "modsync c2s\u0001".getBytes(StandardCharsets.US_ASCII)),
                hmac(prk, "modsync s2c\u0001".getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[11 - i] = (byte) (counter >>> (8 * i));
        return n;
    }

    private static Cipher newCipher() {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ потоки

    /** Шифрующий выходной поток: копит данные в кадр и отправляет его при заполнении или flush(). */
    public static final class SecureOut extends OutputStream {
        private final OutputStream out;
        private final SecretKeySpec key;
        private final Cipher cipher = newCipher();
        private final byte[] buf = new byte[FRAME];
        private int n;
        private long counter;

        public SecureOut(OutputStream out, byte[] key) {
            this.out = out;
            this.key = new SecretKeySpec(key, "AES");
        }

        @Override
        public void write(int b) throws IOException {
            buf[n++] = (byte) b;
            if (n == FRAME) seal();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int k = Math.min(len, FRAME - n);
                System.arraycopy(b, off, buf, n, k);
                n += k;
                off += k;
                len -= k;
                if (n == FRAME) seal();
            }
        }

        private void seal() throws IOException {
            if (n == 0) return;
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce(counter++)));
                byte[] ct = cipher.doFinal(buf, 0, n);
                n = 0;
                out.write(ct.length >>> 24);
                out.write(ct.length >>> 16);
                out.write(ct.length >>> 8);
                out.write(ct.length);
                out.write(ct);
            } catch (GeneralSecurityException e) {
                throw new IOException("encryption failed", e);
            }
        }

        @Override
        public void flush() throws IOException {
            seal();
            out.flush();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            } finally {
                out.close();
            }
        }
    }

    /** Расшифровывающий входной поток. При неверном ключе/подмене бросает {@link AuthException}. */
    public static final class SecureIn extends InputStream {
        private final DataInputStream in;
        private final SecretKeySpec key;
        private final Cipher cipher = newCipher();
        private byte[] cur = new byte[0];
        private int pos;
        private long counter;

        public SecureIn(DataInputStream in, byte[] key) {
            this.in = in;
            this.key = new SecretKeySpec(key, "AES");
        }

        private boolean fill() throws IOException {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                return false;
            }
            if (len <= TAG || len > FRAME + TAG) throw new IOException("bad frame length " + len);
            byte[] ct = new byte[len];
            in.readFully(ct);
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce(counter++)));
                cur = cipher.doFinal(ct);
            } catch (AEADBadTagException e) {
                throw new AuthException("decryption failed (wrong secret or tampered data)");
            } catch (GeneralSecurityException e) {
                throw new IOException("decryption failed", e);
            }
            pos = 0;
            return true;
        }

        @Override
        public int read() throws IOException {
            while (pos >= cur.length) {
                if (!fill()) return -1;
            }
            return cur[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= cur.length) {
                if (!fill()) return -1;
            }
            int k = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, k);
            pos += k;
            return k;
        }

        @Override
        public int available() {
            return cur.length - pos;
        }
    }
}
