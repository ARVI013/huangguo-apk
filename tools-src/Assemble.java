import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Assembles the APK: copies every entry from the aapt2-linked base.apk, appends extra
 * entries given as "zipName=filePath", and keeps resources.arsc STORED (mmap-able).
 *
 * usage: Assemble base.apk out.apk [zipName=filePath]...
 */
public class Assemble {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Assemble base.apk out.apk [zipName=filePath]...");
            System.exit(2);
        }

        ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(args[1])));
        try {
            ZipInputStream in = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(args[0])));
            try {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    byte[] data = readAll(in);
                    write(out, e.getName(), data, shouldStore(e.getName(), e));
                    in.closeEntry();
                }
            } finally {
                in.close();
            }

            for (int i = 2; i < args.length; i++) {
                int eq = args[i].indexOf('=');
                if (eq < 0) {
                    System.err.println("bad extra entry (expected name=path): " + args[i]);
                    System.exit(2);
                }
                String name = args[i].substring(0, eq);
                byte[] data = Files.readAllBytes(Paths.get(args[i].substring(eq + 1)));
                write(out, name, data, shouldStore(name, null));
            }
        } finally {
            out.close();
        }
    }

    /** resources.arsc must stay uncompressed so AssetManager can mmap it. */
    private static boolean shouldStore(String name, ZipEntry source) {
        if (name.endsWith(".arsc")) {
            return true;
        }
        return source != null && source.getMethod() == ZipEntry.STORED;
    }

    private static void write(ZipOutputStream out, String name, byte[] data, boolean store)
            throws Exception {
        ZipEntry entry = new ZipEntry(name);
        if (store) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }
}
