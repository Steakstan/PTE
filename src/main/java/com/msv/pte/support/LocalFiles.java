package com.msv.pte.support;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

public final class LocalFiles {
    private LocalFiles() { }

    public static void privateDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
    }

    public static Properties read(Path path) throws IOException {
        Properties values = new Properties();
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) { values.load(input); }
            catch (IllegalArgumentException error) { throw new IOException("Lokale Einstellungsdatei ist beschädigt: " + path, error); }
        }
        return values;
    }

    public static void write(Path path, Properties values) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".settings-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                values.store(output, "PDF to Excel");
                output.getChannel().force(true);
            }
            replace(temporary, path);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void replace(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static String hash(Path path) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            for (int count; (count = input.read(buffer)) >= 0;) { if (count > 0) digest.update(buffer, 0, count); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
