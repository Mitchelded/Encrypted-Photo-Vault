package io.github.epv.vault;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Manual, pure-JVM interoperability check of the production Android EpvCore.java. */
public final class JvmInteropTest {
    public static byte[] rgbFromPng(File f) throws Exception {
        BufferedImage im = ImageIO.read(f);
        if (im == null) throw new IllegalArgumentException("Not PNG");
        byte[] b = new byte[im.getWidth() * im.getHeight() * 3];
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                int i = (y * im.getWidth() + x) * 3;
                int p = im.getRGB(x, y);
                b[i] = (byte)(p >>> 16);
                b[i+1] = (byte)(p >>> 8);
                b[i+2] = (byte)p;
            }
        }
        return b;
    }

    public static void pngFromPayload(byte[] b, File f) throws Exception {
        final int w = 512;
        int h = Math.max(512, (b.length + w * 3 - 1) / (w * 3));
        if (h > 512) throw new IllegalArgumentException("Too large for interop sample");
        byte[] full = new byte[w * h * 3];
        System.arraycopy(b, 0, full, 0, b.length);
        System.arraycopy(EpvCore.random(full.length - b.length), 0, full, b.length, full.length - b.length);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int i=(y*w+x)*3;
            im.setRGB(x, y, 0xff000000 | ((full[i] & 255)<<16) | ((full[i+1]&255)<<8) | (full[i+2]&255));
        }
        ImageIO.write(im,"png",f);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: JvmInteropTest python.png java.png");
        String pass = "Cross device 🍀 passphrase";
        byte[] input = rgbFromPng(new File(args[0]));
        EpvCore.Header hdr = EpvCore.readHeader(input);
        byte[] key = EpvCore.deriveKey(pass, hdr.salt);
        EpvCore.Part part = EpvCore.decryptPart(key, input);
        if (!part.metadataJson.contains("interop-python.txt")) throw new AssertionError("Python metadata mismatch");
        if (!Arrays.equals(part.data, "Python-Java test!".getBytes(StandardCharsets.UTF_8)))
            throw new AssertionError("Python->Java content mismatch");
        Arrays.fill(key,(byte)0);

        byte[] data = "Hello from JVM -> Python!".getBytes(StandardCharsets.UTF_8);
        byte[] id = EpvCore.random(16), salt = EpvCore.random(16);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        String meta = "{\"n\":\"java-roundtrip.txt\",\"s\":" + data.length +
                ",\"h\":\""+EpvCore.hex(digest)+"\",\"m\":\"text/plain\"}";
        byte[] k = EpvCore.deriveKey(pass, salt);
        byte[] encrypted = EpvCore.encryptPart(k,id,salt,0,1,meta,data);
        Arrays.fill(k,(byte)0);
        pngFromPayload(encrypted, new File(args[1]));
        System.out.println("JVM decoded Python EPV1 and encoded a Java EPV1 PNG successfully.");
    }
}
