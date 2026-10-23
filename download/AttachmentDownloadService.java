package com.msv.pte.download;

import com.msv.pte.support.LocalFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class AttachmentDownloadService {
    public enum Mode {
        RESUME("Fortsetzen / fehlende PDFs"), NEW_SCAN("Neu suchen / vorhandene PDFs prüfen"),
        REPLACE("Neu suchen / PDFs erneut herunterladen");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record RemoteAttachment(String messageId, String attachmentId, String name, long size) {
        public RemoteAttachment {
            if (messageId == null || messageId.isBlank() || attachmentId == null || attachmentId.isBlank() || name == null) {
                throw new IllegalArgumentException("Ungültige Anhangskennung.");
            }
        }
    }
    public record MessagePage(List<String> ids, int nextOffset, boolean more, Instant upperBound) {
        public MessagePage { ids = List.copyOf(ids); }
    }
    public record Update(int percent, int downloaded, int skipped, int failed, String message) { }
    public record Result(Path directory, int plannedMessages, int completedMessages, int downloaded,
                         int skipped, int failed, boolean cancelled, boolean complete, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }
    @FunctionalInterface public interface Listener { void update(Update update); }
    @FunctionalInterface public interface SessionFactory {
        MailSession open(DownloadProfile profile, char[] password) throws IOException;
    }
    public interface MailSession extends Closeable {
        String findFolder(String name) throws IOException;
        MessagePage messages(String folder, int offset, int count, Instant upperBound) throws IOException;
        List<RemoteAttachment> attachments(String messageId) throws IOException;
        void download(RemoteAttachment attachment, OutputStream output) throws IOException;
    }
    public static class RemoteFailure extends IOException {
        public final boolean retryable, stopBatch;
        public RemoteFailure(String message, boolean retryable, boolean stopBatch) {
            super(message); this.retryable = retryable; this.stopBatch = stopBatch;
        }
    }

    /** Cancellation closes the current connection away from the EDT and wakes retry waits. */
    public static final class Control {
        private boolean cancelled;
        private MailSession active;
        public void cancel() {
            MailSession session;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true; session = active; notifyAll();
            }
            if (session != null) {
                Thread closer = new Thread(() -> closeQuietly(session), "ews-cancel");
                closer.setDaemon(true); closer.start();
            }
        }
        public synchronized boolean isCancelled() { return cancelled; }
        public synchronized void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download abgebrochen.");
        }
        private synchronized void register(MailSession session) throws IOException {
            active = session;
            if (cancelled) { closeQuietly(session); check(); }
        }
        private synchronized void clear(MailSession session) { if (active == session) active = null; }
        private synchronized void pause(long milliseconds) throws InterruptedIOException {
            long deadline = System.nanoTime() + milliseconds * 1_000_000L;
            for (;;) {
                check(); long remaining = deadline - System.nanoTime(); if (remaining <= 0) return;
                try { wait(Math.max(1, remaining / 1_000_000L)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); check(); }
            }
        }
    }

    private final SessionFactory factory;
    public AttachmentDownloadService() { this(EwsMailSession::new); }
    public AttachmentDownloadService(SessionFactory factory) { this.factory = Objects.requireNonNull(factory); }

    public Result download(DownloadProfile profile, char[] password, Mode mode, Control control, Listener listener) throws IOException {
        profile.validate();
        if (password == null || password.length == 0) throw new IOException("Bitte ein Passwort eingeben.");
        Run run = new Run(profile, password.clone(), Objects.requireNonNull(control), listener == null ? update -> { } : listener);
        try { return run.execute(Objects.requireNonNull(mode)); }
        finally { run.disconnect(); Arrays.fill(run.password, '\0'); }
    }

    private final class Run {
        final DownloadProfile profile;
        final char[] password;
        final Control control;
        final Listener listener;
        final List<String> problems = new ArrayList<>();
        MailSession session;
        int planned, completed, downloaded, skipped, failed;
        boolean cancelled;
        Run(DownloadProfile profile, char[] password, Control control, Listener listener) {
            this.profile = profile; this.password = password; this.control = control; this.listener = listener;
        }

        Result execute(Mode mode) throws IOException {
            Path directory = profile.directory();
            String account = LocalFiles.hash(profile.serverUri() + "\n" + profile.username().strip().toLowerCase(Locale.ROOT));
            String scope = LocalFiles.hash(account + "\n" + profile.mailFolder().strip());
            try (DownloadLedger ledger = new DownloadLedger(directory)) {
                try {
                    control.check();
                    DownloadLedger.Plan plan = mode == Mode.RESUME ? ledger.unfinished(scope) : null;
                    if (plan == null) {
                        emit("[INFO] Nachrichtenliste wird ermittelt …");
                        String folder = retry("Mailordner suchen", () -> connection().findFolder(profile.mailFolder()));
                        LinkedHashSet<String> ids = new LinkedHashSet<>();
                        int offset = 0; Instant upper = null;
                        while (ids.size() < profile.emailLimit()) {
                            int pageOffset = offset; Instant bound = upper;
                            int count = Math.min(100, profile.emailLimit() - ids.size());
                            MessagePage page = retry("Nachrichtenliste laden", () -> connection().messages(folder, pageOffset, count, bound));
                            if (upper == null) upper = page.upperBound();
                            for (String id : page.ids()) { if (ids.size() < profile.emailLimit()) ids.add(id); }
                            if (!page.more()) break;
                            if (page.ids().isEmpty() || page.nextOffset() <= offset) {
                                throw new RemoteFailure("Server lieferte keine gültige nächste Nachrichtenseite.", false, true);
                            }
                            offset = page.nextOffset();
                        }
                        plan = new DownloadLedger.Plan(UUID.randomUUID().toString(), mode == Mode.REPLACE, List.copyOf(ids), false);
                        ledger.savePlan(scope, plan);
                    } else emit("[INFO] Unvollständiger Auftrag wird fortgesetzt; seine ursprüngliche Nachrichtenliste bleibt erhalten.");
                    planned = plan.messages().size();
                    emit("[INFO] Auftrag enthält " + planned + " Nachrichten.");
                    boolean stop = false;
                    for (String messageId : plan.messages()) {
                        control.check();
                        boolean messageOk = true;
                        try {
                            List<RemoteAttachment> attachments = ledger.attachments(plan, messageId);
                            if (attachments == null) {
                                attachments = retry("Anhänge einer Nachricht laden", () -> connection().attachments(messageId));
                                ledger.attachments(plan, messageId, attachments);
                            }
                            for (RemoteAttachment item : attachments) {
                                control.check();
                                String key = LocalFiles.hash(account + "\n" + item.messageId() + "\n" + item.attachmentId());
                                Path file = ledger.target(key, item.name());
                                if (ledger.verified(key, file, item.size(), plan)) {
                                    skipped++; emit("[INFO] Bereits vollständig vorhanden: " + file.getFileName()); continue;
                                }
                                try {
                                    Path saved = retry("PDF " + item.name(), () -> transfer(item, file));
                                    ledger.completed(key, saved, LocalFiles.hash(saved), plan);
                                    downloaded++; emit("[INFO] Gespeichert: " + file.getFileName());
                                } catch (RemoteFailure error) {
                                    failed++; messageOk = false; problems.add(error.getMessage()); emit("[ERROR] " + error.getMessage());
                                    if (error.stopBatch) { stop = true; break; }
                                }
                            }
                        } catch (RemoteFailure error) {
                            failed++; messageOk = false; problems.add(error.getMessage()); emit("[ERROR] " + error.getMessage());
                            if (error.stopBatch) stop = true;
                        }
                        if (messageOk) completed++;
                        if (stop) break;
                    }
                    if (!stop && failed == 0 && completed == planned) {
                        ledger.savePlan(scope, new DownloadLedger.Plan(plan.id(), plan.replace(), plan.messages(), true));
                    }
                } catch (InterruptedIOException error) {
                    cancelled = true; emit("[WARN] Download abgebrochen. Vollständige Dateien bleiben erhalten.");
                } catch (RemoteFailure error) {
                    failed++; problems.add(error.getMessage()); emit("[ERROR] " + error.getMessage());
                }
            }
            boolean complete = !cancelled && failed == 0 && completed == planned;
            return new Result(directory, planned, completed, downloaded, skipped, failed, cancelled, complete, problems);
        }

        private Path transfer(RemoteAttachment item, Path file) throws IOException {
            Path temporary = Files.createTempFile(profile.directory(), ".download-", ".part");
            IOException[] diskFailure = new IOException[1];
            try {
                try (OutputStream raw = Files.newOutputStream(temporary)) {
                    OutputStream checked = new FilterOutputStream(raw) {
                        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                            control.check();
                            try { out.write(bytes, offset, length); }
                            catch (IOException error) { diskFailure[0] = error; throw error; }
                        }
                    };
                    try { connection().download(item, checked); }
                    catch (IOException error) { if (diskFailure[0] != null) throw diskFailure[0]; control.check(); throw error; }
                }
                control.check();
                if (item.size() > 0 && Files.size(temporary) != item.size()) {
                    throw new RemoteFailure("Unvollständiger Anhang: " + item.name(), true, true);
                }
                try (InputStream input = Files.newInputStream(temporary)) {
                    if (!new String(input.readNBytes(1024), StandardCharsets.ISO_8859_1).contains("%PDF-")) {
                        throw new RemoteFailure("Anhang enthält keinen PDF-Dateikopf: " + item.name(), false, false);
                    }
                }
                LocalFiles.replace(temporary, file);
                return file;
            } finally { Files.deleteIfExists(temporary); }
        }

        private MailSession connection() throws IOException {
            control.check();
            if (session == null) { session = factory.open(profile, password); control.register(session); }
            return session;
        }
        private void disconnect() {
            MailSession old = session; session = null;
            if (old != null) { control.clear(old); closeQuietly(old); }
        }
        private <T> T retry(String operation, IoAction<T> action) throws IOException {
            for (int attempt = 1; ; attempt++) {
                control.check();
                try { return action.run(); }
                catch (RemoteFailure error) {
                    disconnect(); control.check();
                    if (!error.retryable || attempt >= profile.maxAttempts()) throw error;
                    long delay = Math.min(60_000L, profile.retryDelaySeconds() * 1000L * (1L << Math.min(attempt - 1, 10)));
                    emit("[WARN] " + operation + ": erneuter Versuch " + (attempt + 1) + "/" + profile.maxAttempts()
                            + " in " + delay / 1000 + " s. " + error.getMessage());
                    control.pause(delay);
                }
            }
        }
        private void emit(String message) {
            int percent = planned == 0 ? 0 : (int) ((long) completed * 95 / planned);
            listener.update(new Update(percent, downloaded, skipped, failed, message));
        }
    }

    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
    private static void closeQuietly(MailSession session) { try { session.close(); } catch (IOException ignored) { } }
}
