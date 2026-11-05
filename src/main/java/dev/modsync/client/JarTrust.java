package dev.modsync.client;

import dev.modsync.ModSync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Проверка подписи jar (обычная подпись jarsigner). Доверие строится от установленного ModSync:
 * обновление принимается, если оно подписано тем же ключом, что и текущий ModSync.
 * Сроки действия сертификата не проверяются - сравнивается именно открытый ключ.
 */
final class JarTrust {
    private JarTrust() {}

    /**
     * @return отпечаток(и) ключей подписи, общие для ВСЕХ файлов jar, либо null, если jar не подписан,
     * подписан не полностью или содержимое не соответствует подписи.
     */
    static String signerFingerprint(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile(), true)) {
            Set<String> common = null;
            byte[] buf = new byte[16 * 1024];
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory() || isSignatureFile(e.getName())) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    while (in.read(buf) >= 0) {
                        // читаем до конца: именно так JarFile проверяет подпись (SecurityException при несовпадении)
                    }
                }
                CodeSigner[] cs = e.getCodeSigners();
                if (cs == null || cs.length == 0) return null;
                Set<String> fps = new TreeSet<>();
                for (CodeSigner c : cs) {
                    fps.add(fingerprint(c.getSignerCertPath().getCertificates().get(0).getPublicKey().getEncoded()));
                }
                if (common == null) common = fps; else common.retainAll(fps);
                if (common.isEmpty()) return null;
            }
            return common == null ? null : String.join(",", common);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static boolean isSignatureFile(String n) {
        String u = n.toUpperCase(Locale.ROOT);
        return u.equals("META-INF/MANIFEST.MF")
                || (u.startsWith("META-INF/") && u.indexOf('/', 9) < 0
                && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC")));
    }

    private static String fingerprint(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IOException если обновление нельзя принять */
    static void check(Path running, Path candidate, boolean require) throws IOException {
        String cur = signerFingerprint(running);
        String cand = signerFingerprint(candidate);
        if (cur != null) {
            if (!cur.equals(cand)) {
                throw new IOException("the new ModSync is "
                        + (cand == null ? "NOT signed (or the signature is invalid)" : "signed by a DIFFERENT key")
                        + " - the update was rejected");
            }
            return;
        }
        if (require) {
            throw new IOException("client.requireSignedSelfUpdate is on, but the installed ModSync is not signed - "
                    + "install a signed ModSync manually first");
        }
        ModSync.LOGGER.warn("[ModSync] установленный ModSync не подписан - подпись обновления не проверяется (защита только по секрету и хэшу)");
    }
}
