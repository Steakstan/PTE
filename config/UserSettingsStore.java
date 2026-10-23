package com.msv.pte.config;

import com.msv.pte.download.DownloadProfile;
import com.msv.pte.support.LocalFiles;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

/** Per-user files outside the project; passwords use AES-GCM with a local key. */
public final class UserSettingsStore {
    public record UiSettings(String pdfDirectory, String excelFile, String selectedProfile) { }
    private final Path directory, settings;

    public UserSettingsStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.settings = this.directory.resolve("settings.properties");
    }

    public static UserSettingsStore getDefault() {
        String local = System.getenv("LOCALAPPDATA");
        Path directory = System.getProperty("os.name", "").startsWith("Windows") && local != null && !local.isBlank()
                ? Path.of(local, "PdfToExcel") : Path.of(System.getProperty("user.home"), ".pdf-to-excel");
        return new UserSettingsStore(directory);
    }

    public Path directory() { return directory; }
    public static Path defaultDownloads() {
        return Path.of(System.getProperty("user.home"), "Downloads", "BSHG_PDF").toAbsolutePath();
    }

    public synchronized List<DownloadProfile> profiles() throws IOException {
        Properties values = LocalFiles.read(settings);
        List<DownloadProfile> result = new ArrayList<>();
        for (String key : values.stringPropertyNames()) {
            if (!key.startsWith("profile.") || !key.endsWith(".name")) continue;
            String id = key.substring(8, key.length() - 5), prefix = "profile." + id + ".";
            try {
                result.add(new DownloadProfile(id, values.getProperty(prefix + "name"),
                        values.getProperty(prefix + "url"), values.getProperty(prefix + "user"),
                        values.getProperty(prefix + "folder"), Integer.parseInt(values.getProperty(prefix + "limit")),
                        values.getProperty(prefix + "directory"), values.getProperty(prefix + "excel"),
                        Integer.parseInt(values.getProperty(prefix + "attempts")),
                        Integer.parseInt(values.getProperty(prefix + "delay")), Integer.parseInt(values.getProperty(prefix + "timeout"))));
            } catch (RuntimeException error) { throw new IOException("Profil ist beschädigt: " + id, error); }
        }
        result.sort(Comparator.comparing(DownloadProfile::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public synchronized UiSettings uiSettings() throws IOException {
        Properties values = LocalFiles.read(settings);
        Path defaults = defaultDownloads();
        return new UiSettings(values.getProperty("ui.pdf", defaults.toString()),
                values.getProperty("ui.excel", defaults.resolve("BSHG_Ergebnis.xlsx").toString()),
                values.getProperty("ui.profile", ""));
    }

    public void savePaths(String pdf, String excel) throws IOException {
        change(values -> { values.setProperty("ui.pdf", pdf); values.setProperty("ui.excel", excel); });
    }

    public void selectProfile(String id) throws IOException {
        change(values -> values.setProperty("ui.profile", id));
    }

    public void saveProfile(DownloadProfile profile, char[] password, boolean remember) throws IOException {
        profile.validate();
        change(values -> {
            String prefix = "profile." + profile.id() + ".";
            values.setProperty(prefix + "name", profile.name().strip());
            values.setProperty(prefix + "url", profile.ewsUrl().strip());
            values.setProperty(prefix + "user", profile.username().strip());
            values.setProperty(prefix + "folder", profile.mailFolder().strip());
            values.setProperty(prefix + "limit", "" + profile.emailLimit());
            values.setProperty(prefix + "directory", profile.directory().toString());
            values.setProperty(prefix + "excel", profile.output().toString());
            values.setProperty(prefix + "attempts", "" + profile.maxAttempts());
            values.setProperty(prefix + "delay", "" + profile.retryDelaySeconds());
            values.setProperty(prefix + "timeout", "" + profile.timeoutSeconds());
            if (remember) {
                if (password == null || password.length == 0) throw new IOException("Zum Speichern bitte ein Passwort eingeben.");
                values.setProperty(prefix + "password", encrypt(password));
            } else values.remove(prefix + "password");
            values.setProperty("ui.profile", profile.id());
        });
    }

    public void deleteProfile(String id) throws IOException {
        change(values -> {
            String prefix = "profile." + id + ".";
            values.keySet().removeIf(key -> key.toString().startsWith(prefix));
            if (id.equals(values.getProperty("ui.profile"))) values.remove("ui.profile");
        });
    }

    public synchronized char[] password(String id) throws IOException {
        String encrypted = LocalFiles.read(settings).getProperty("profile." + id + ".password");
        if (encrypted == null) return new char[0];
        byte[] plain = null;
        try {
            byte[] data = Base64.getDecoder().decode(encrypted);
            if (data.length < 29 || data[0] != 1) throw new GeneralSecurityException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(data, 1, 13)));
            plain = cipher.doFinal(data, 13, data.length - 13);
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] result = new char[chars.remaining()]; chars.get(result);
            if (chars.hasArray()) Arrays.fill(chars.array(), '\0');
            return result;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("Gespeichertes Passwort konnte nicht gelesen werden. Bitte erneut eingeben und das Profil speichern.", error);
        } finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private String encrypt(char[] password) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] plain = new byte[bytes.remaining()]; bytes.get(plain);
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(13 + encrypted.length)
                    .put((byte) 1).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException error) { throw new IOException("Passwort konnte nicht gespeichert werden.", error); }
        finally {
            Arrays.fill(plain, (byte) 0);
            if (bytes.hasArray()) Arrays.fill(bytes.array(), (byte) 0);
        }
    }

    private byte[] key(boolean create) throws IOException {
        Path path = directory.resolve("credentials.key");
        Properties values = LocalFiles.read(path);
        if (!Files.exists(path)) {
            if (!create) throw new IOException("Lokaler Passwortschlüssel fehlt. Passwort bitte erneut eingeben.");
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            values.setProperty("key", Base64.getEncoder().encodeToString(key));
            LocalFiles.write(path, values);
        }
        try {
            byte[] key = Base64.getDecoder().decode(values.getProperty("key", ""));
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException error) { throw new IOException("Lokaler Passwortschlüssel ist beschädigt.", error); }
    }

    @FunctionalInterface private interface Change { void apply(Properties values) throws IOException; }
    private synchronized void change(Change change) throws IOException {
        LocalFiles.privateDirectory(directory);
        try (FileChannel channel = FileChannel.open(directory.resolve("settings.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Einstellungen werden gerade von einer anderen Instanz bearbeitet.");
            Properties values = LocalFiles.read(settings);
            change.apply(values);
            LocalFiles.write(settings, values);
        } catch (OverlappingFileLockException error) { throw new IOException("Einstellungen sind gerade gesperrt.", error); }
    }
}
