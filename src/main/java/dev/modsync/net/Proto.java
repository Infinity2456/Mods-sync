package dev.modsync.net;

import dev.modsync.common.ModEntry;

import java.io.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Протокол синхронизации (обычный TCP, не связан с сетью Minecraft).
 *
 * <pre>
 * открытая часть:
 * C -> S : int MAGIC, int VERSION, byte[16] clientNonce, int 44, byte[44] clientPublicKey (X25519)
 * S -> C : byte status (OK / BAD_VERSION); если OK: byte[16] serverNonce, int 44, byte[44] serverPublicKey
 *
 * дальше всё внутри шифрованного канала (см. {@link Crypto}: AES-256-GCM, ключи из X25519 + общий секрет):
 * C -> S : int Crypto.HELLO                 (сервер молчит, пока не расшифрует это)
 * S -> C : int manifestLen, byte[] manifest
 * C -> S : int n, n * (int index, long offset)   (n = 0 - "всё актуально"; offset - сколько байт файла у клиента уже есть: докачка)
 * S -> C : для каждого индекса по порядку - ровно entry.size() - offset байт файла, начиная с offset
 * </pre>
 * Дополнительно целостность файлов проверяется SHA-256 из манифеста.
 */
public final class Proto {
    public static final int MAGIC = 0x4D53594E; // "MSYN"
    public static final int VERSION = 2;
    public static final int MAX_ENTRIES = 2048;
    public static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    public static final int NONCE = 16;
    public static final int OK = 0;
    public static final int BAD_VERSION = 1;
    public static final int DEFAULT_PORT = 25566;

    private static final SecureRandom RND = new SecureRandom();

    private Proto() {}

    public static byte[] nonce() {
        byte[] b = new byte[NONCE];
        RND.nextBytes(b);
        return b;
    }

    public static final int MAX_REMOVE = 256;

    /**
     * Манифест (v2 и новее). После списка модов идёт необязательный "хвост" (старые клиенты его просто игнорируют):
     * чёрный список модов, которые нужно убрать у клиента, и подпись сервера (долговременный ключ Ed25519) под рукопожатием.
     */
    public record Manifest(List<ModEntry> entries, List<String> remove, byte[] identityPub, byte[] identitySig) {
        public Manifest(List<ModEntry> entries) {
            this(entries, List.of(), new byte[0], new byte[0]);
        }
    }

    /** Манифест без хвоста - формат протокола v1 (заморожен). */
    public static byte[] encode(List<ModEntry> entries) throws IOException {
        return encode(new Manifest(entries));
    }

    public static byte[] encode(Manifest m) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(m.entries().size());
        for (ModEntry e : m.entries()) e.write(out);
        if (!m.remove().isEmpty() || m.identityPub().length > 0) {
            out.writeInt(m.remove().size());
            for (String r : m.remove()) out.writeUTF(r);
            out.writeInt(m.identityPub().length);
            out.write(m.identityPub());
            out.writeInt(m.identitySig().length);
            out.write(m.identitySig());
        }
        out.flush();
        return bos.toByteArray();
    }

    public static List<ModEntry> decode(byte[] data) throws IOException {
        return decodeManifest(data).entries();
    }

    public static Manifest decodeManifest(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int n = in.readInt();
        if (n < 0 || n > MAX_ENTRIES) throw new IOException("ModSync: bad manifest size " + n);
        List<ModEntry> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(ModEntry.read(in));
        List<String> remove = new ArrayList<>();
        byte[] pub = new byte[0], sig = new byte[0];
        if (in.available() > 0) {
            int rc = in.readInt();
            if (rc < 0 || rc > MAX_REMOVE) throw new IOException("ModSync: bad remove list size " + rc);
            for (int i = 0; i < rc; i++) {
                String r = in.readUTF();
                if (r.length() > 256) throw new IOException("ModSync: remove entry too long");
                remove.add(r);
            }
            int pl = in.readInt();
            if (pl < 0 || pl > 128) throw new IOException("ModSync: bad identity key length");
            pub = in.readNBytes(pl);
            int sl = in.readInt();
            if (sl < 0 || sl > 256) throw new IOException("ModSync: bad identity signature length");
            sig = in.readNBytes(sl);
            if (pub.length != pl || sig.length != sl) throw new EOFException();
        }
        return new Manifest(l, remove, pub, sig);
    }
}
