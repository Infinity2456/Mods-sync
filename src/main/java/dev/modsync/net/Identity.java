package dev.modsync.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Долговременный ключ сервера (Ed25519). Сервер подписывает им каждое рукопожатие, а клиент запоминает
 * отпечаток ключа при первом подключении (как SSH) и бьёт тревогу, если он изменился.
 */
public final class Identity {
    private static final byte[] PREFIX = "modsync-identity-v1".getBytes(StandardCharsets.US_ASCII);

    private Identity() {}

    /** Читает ключ из файла или создаёт новый (две строки: PKCS#8 и X.509 в Base64). */
    public static KeyPair loadOrCreate(Path file) throws IOException {
        try {
            if (Files.isRegularFile(file)) {
                List<String> l = Files.readAllLines(file, StandardCharsets.US_ASCII);
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(l.get(0).trim())));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(l.get(1).trim())));
                return new KeyPair(pub, priv);
            }
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(file.getParent());
            String text = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n"
                    + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n";
            Files.writeString(file, text, StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows и т.п.
            }
            return kp;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("cannot load server identity key " + file + ": " + e, e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] transcript) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(PREFIX);
            s.update(transcript);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(byte[] pubEncoded, byte[] transcript, byte[] sig) {
        try {
            PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(pubEncoded));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(PREFIX);
            s.update(transcript);
            return s.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** SHA-256 открытого ключа, hex. */
    public static String fingerprint(byte[] pubEncoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pubEncoded));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
