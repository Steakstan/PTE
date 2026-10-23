package com.msv.pte.download;

import java.io.IOException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

public record DownloadProfile(
        String id, String name, String ewsUrl, String username, String mailFolder,
        int emailLimit, String downloadDirectory, String excelFile,
        int maxAttempts, int retryDelaySeconds, int timeoutSeconds
) {
    public static DownloadProfile defaults(Path directory) {
        return new DownloadProfile(UUID.randomUUID().toString(), "Standard",
                "https://owa.xxxlgroup.com/EWS/Exchange.asmx", "", "BSHG Bestätigung", 121,
                directory.toString(), directory.resolve("BSHG_Ergebnis.xlsx").toString(), 3, 3, 30);
    }

    public URI serverUri() throws IOException {
        try {
            URI uri = URI.create(ewsUrl.strip()).normalize();
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Bitte eine gültige HTTPS-Adresse für EWS angeben.", error);
        }
    }

    public void validate() throws IOException {
        try { UUID.fromString(id); }
        catch (RuntimeException error) { throw new IOException("Ungültige Profil-ID.", error); }
        if (name == null || name.isBlank() || username == null || username.isBlank()
                || mailFolder == null || mailFolder.isBlank()) {
            throw new IOException("Profilname, Benutzername und Mailordner müssen angegeben werden.");
        }
        serverUri();
        directory();
        output();
        if (emailLimit < 1 || emailLimit > 100_000 || maxAttempts < 1 || maxAttempts > 20
                || retryDelaySeconds < 1 || retryDelaySeconds > 60 || timeoutSeconds < 5 || timeoutSeconds > 300) {
            throw new IOException("Ungültige Anzahl, Wiederholungen oder Zeitbegrenzung.");
        }
    }

    public Path directory() throws IOException { return path(downloadDirectory, "Downloadordner"); }

    public Path output() throws IOException {
        Path path = path(excelFile, "Excel-Datei");
        if (path.getFileName() == null || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IOException("Die Excel-Datei muss die Endung .xlsx haben.");
        }
        return path;
    }

    private static Path path(String value, String label) throws IOException {
        if (value == null || value.isBlank()) throw new IOException(label + " muss angegeben werden.");
        try { return Path.of(value.strip()).toAbsolutePath().normalize(); }
        catch (InvalidPathException error) { throw new IOException("Ungültiger Pfad für " + label, error); }
    }

    @Override public String toString() { return name; }
}
