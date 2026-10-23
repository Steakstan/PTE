package com.msv.pte.download;

import com.msv.pte.support.LocalFiles;
import com.msv.pte.download.AttachmentDownloadService.RemoteAttachment;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Durable message snapshot and verified attachment receipts. Never stores credentials. */
final class DownloadLedger implements AutoCloseable {
    record Plan(String id, boolean replace, List<String> messages, boolean complete) { }
    private final Path directory, state;
    private final FileChannel channel;
    private final FileLock lock;

    DownloadLedger(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        state = directory.resolve(".pdf-download-state");
        LocalFiles.privateDirectory(state);
        channel = FileChannel.open(state.resolve("download.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); }
        catch (OverlappingFileLockException error) { channel.close(); throw new IOException("In diesem Ordner läuft bereits ein Download.", error); }
        if (acquired == null) { channel.close(); throw new IOException("In diesem Ordner läuft bereits ein Download."); }
        lock = acquired;
    }

    Plan unfinished(String scope) throws IOException {
        Path path = state.resolve("plan-" + scope + ".properties");
        if (!Files.exists(path)) return null;
        Properties values = LocalFiles.read(path);
        if (Boolean.parseBoolean(values.getProperty("complete"))) return null;
        try {
            String id = UUID.fromString(values.getProperty("id")).toString();
            int count = Integer.parseInt(values.getProperty("count"));
            if (count < 0 || count > 100_000) throw new IllegalArgumentException();
            List<String> messages = new ArrayList<>();
            for (int i = 0; i < count; i++) messages.add(Objects.requireNonNull(values.getProperty("message." + i)));
            return new Plan(id, Boolean.parseBoolean(values.getProperty("replace")), List.copyOf(messages), false);
        } catch (RuntimeException error) { throw new IOException("Gespeicherter Downloadauftrag ist beschädigt. Bitte 'Neu suchen' wählen.", error); }
    }

    void savePlan(String scope, Plan plan) throws IOException {
        Properties values = new Properties();
        values.setProperty("id", plan.id()); values.setProperty("replace", "" + plan.replace());
        values.setProperty("complete", "" + plan.complete()); values.setProperty("count", "" + plan.messages().size());
        for (int i = 0; i < plan.messages().size(); i++) values.setProperty("message." + i, plan.messages().get(i));
        LocalFiles.write(state.resolve("plan-" + scope + ".properties"), values);
    }

    private Path messagePath(Plan plan, String message) {
        return state.resolve("plans").resolve(plan.id()).resolve(LocalFiles.hash(message) + ".properties");
    }

    List<RemoteAttachment> attachments(Plan plan, String message) throws IOException {
        Path path = messagePath(plan, message);
        if (!Files.exists(path)) return null;
        Properties values = LocalFiles.read(path);
        try {
            int count = Integer.parseInt(values.getProperty("count"));
            if (count < 0 || count > 100_000) throw new IllegalArgumentException();
            List<RemoteAttachment> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String prefix = "attachment." + i + ".";
                result.add(new RemoteAttachment(message, Objects.requireNonNull(values.getProperty(prefix + "id")),
                        Objects.requireNonNull(values.getProperty(prefix + "name")), Long.parseLong(values.getProperty(prefix + "size"))));
            }
            return List.copyOf(result);
        } catch (RuntimeException error) { throw new IOException("Gespeicherte Anhangsliste ist beschädigt. Bitte 'Neu suchen' wählen.", error); }
    }

    void attachments(Plan plan, String message, List<RemoteAttachment> attachments) throws IOException {
        Properties values = new Properties(); values.setProperty("count", "" + attachments.size());
        for (int i = 0; i < attachments.size(); i++) {
            RemoteAttachment item = attachments.get(i); String prefix = "attachment." + i + ".";
            values.setProperty(prefix + "id", item.attachmentId());
            values.setProperty(prefix + "name", item.name()); values.setProperty(prefix + "size", "" + item.size());
        }
        LocalFiles.write(messagePath(plan, message), values);
    }

    Path target(String key, String name) {
        String stem = name.replaceAll("(?i)\\.pdf$", "").replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (stem.isBlank()) stem = "PDF";
        stem = stem.substring(0, stem.offsetByCodePoints(0, Math.min(stem.codePointCount(0, stem.length()), 48)));
        return directory.resolve(stem + "--" + key + ".pdf");
    }

    private Path receipt(String key) { return state.resolve("files").resolve(key + ".properties"); }

    boolean verified(String key, Path file, Plan plan) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return false;
        Path record = receipt(key);
        if (!Files.exists(record)) return false;
        Properties values;
        try { values = LocalFiles.read(record); }
        catch (IOException error) { return false; }
        if (!file.getFileName().toString().equals(values.getProperty("file"))) return false;
        if (plan.replace() && !plan.id().equals(values.getProperty("plan"))) return false;
        // Compare with the actual local size saved in the receipt, never Exchange's Size metadata.
        return Long.toString(Files.size(file)).equals(values.getProperty("size"))
                && LocalFiles.hash(file).equals(values.getProperty("sha256"));
    }

    void completed(String key, Path file, String hash, Plan plan) throws IOException {
        Properties values = new Properties();
        values.setProperty("file", file.getFileName().toString());
        values.setProperty("size", "" + Files.size(file)); values.setProperty("sha256", hash);
        values.setProperty("plan", plan.id()); LocalFiles.write(receipt(key), values);
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
