package dev.modsync.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Запись манифеста: один мод-файл, который нужен клиенту. */
public record ModEntry(String fileName, String modIds, String version, String sha256, long size) {

    public void write(DataOutput out) throws IOException {
        out.writeUTF(fileName);
        out.writeUTF(modIds);
        out.writeUTF(version);
        out.writeUTF(sha256);
        out.writeLong(size);
    }

    public static ModEntry read(DataInput in) throws IOException {
        return new ModEntry(bounded(in.readUTF(), 256), bounded(in.readUTF(), 1024),
                bounded(in.readUTF(), 128), bounded(in.readUTF(), 64), in.readLong());
    }

    private static String bounded(String s, int max) throws IOException {
        if (s.length() > max) throw new IOException("ModSync: manifest string too long");
        return s;
    }

    public Set<String> modIdSet() {
        Set<String> s = new HashSet<>();
        for (String p : modIds.split(",")) {
            if (!p.isBlank()) s.add(p.trim());
        }
        return s;
    }
}
