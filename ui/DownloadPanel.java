package com.msv.pte.ui;

import com.msv.pte.config.UserSettingsStore;
import com.msv.pte.download.DownloadProfile;
import com.msv.pte.download.AttachmentDownloadService;
import com.msv.pte.download.AttachmentDownloadService.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DownloadPanel extends JPanel {
    private final UserSettingsStore settings;
    private final AttachmentDownloadService service;
    private final BiConsumer<Path, Path> pathsSelected;
    private final Consumer<Boolean> busyChanged;
    private final JComboBox<DownloadProfile> profiles = new JComboBox<>();
    private final JTextField profileName = new JTextField(), username = new JTextField(), url = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JTextField mailFolder = new JTextField(), directory = new JTextField(), excel = new JTextField();
    private final JSpinner emailLimit = new JSpinner(new SpinnerNumberModel(121, 1, 100_000, 10));
    private final JSpinner attempts = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
    private final JSpinner delay = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1));
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(30, 5, 300, 5));
    private final JCheckBox remember = new JCheckBox("Passwort lokal speichern", true);
    private final JComboBox<Mode> mode = new JComboBox<>(Mode.values());
    private final JButton create = new JButton("Neu"), save = new JButton("Speichern"), delete = new JButton("Löschen");
    private final JButton browseDirectory = new JButton("Durchsuchen …"), browseExcel = new JButton("Durchsuchen …");
    private final JButton start = new JButton("PDFs herunterladen"), cancel = new JButton("Abbrechen");
    private final JLabel status = new JLabel("Bereit");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea summary = new JTextArea(2, 1), log = new JTextArea();
    private final List<JComponent> inputs = new ArrayList<>();
    private String profileId;
    private boolean loading, otherTaskRunning;
    private DownloadWorker worker;

    public DownloadPanel(UserSettingsStore settings, AttachmentDownloadService service,
                         BiConsumer<Path, Path> pathsSelected, Consumer<Boolean> busyChanged) {
        super(new BorderLayout(0, 8));
        this.settings = Objects.requireNonNull(settings); this.service = Objects.requireNonNull(service);
        this.pathsSelected = Objects.requireNonNull(pathsSelected); this.busyChanged = Objects.requireNonNull(busyChanged);
        setBorder(new EmptyBorder(12, 12, 12, 12));
        initialize();
        fill(DownloadProfile.defaults(UserSettingsStore.defaultDownloads()), false);
        try { reloadProfiles(settings.uiSettings().selectedProfile()); }
        catch (IOException error) { append("[ERROR] " + error.getMessage() + "\n"); status.setText("Einstellungen konnten nicht geladen werden"); }
        updateControls();
    }

    private void initialize() {
        JPanel form = new JPanel(new GridBagLayout());
        JPanel profileActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        profileActions.add(create); profileActions.add(save); profileActions.add(delete);
        pair(form, 0, "Profil:", profiles, "", profileActions);
        single(form, 1, "Profilname:", profileName, null);
        pair(form, 2, "Benutzername:", username, "Passwort:", password);
        single(form, 3, "EWS-Adresse:", url, null);
        pair(form, 4, "Mailordner / Pfad:", mailFolder, "Nachrichten:", emailLimit);
        single(form, 5, "PDF-Ordner:", directory, browseDirectory);
        single(form, 6, "Excel-Datei:", excel, browseExcel);
        pair(form, 7, "Verbindungsversuche:", attempts, "Wartezeit (s):", delay);
        pair(form, 8, "Zeitlimit (s):", timeout, "", remember);
        single(form, 9, "Downloadmodus:", mode, null);
        add(form, BorderLayout.NORTH);
        inputs.addAll(List.of(profiles, profileName, username, password, url, mailFolder, directory, excel,
                emailLimit, attempts, delay, timeout, remember, mode, create, save, delete, browseDirectory, browseExcel));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(start); actions.add(cancel);
        status.setFont(status.getFont().deriveFont(Font.BOLD, 15f));
        JPanel state = new JPanel(new BorderLayout(0, 5));
        JPanel heading = new JPanel(new BorderLayout(0, 6));
        heading.add(actions, BorderLayout.NORTH); heading.add(status, BorderLayout.SOUTH);
        state.add(heading, BorderLayout.NORTH);
        progress.setStringPainted(true); progress.setString("Bereit"); state.add(progress, BorderLayout.CENTER);
        summary.setEditable(false); summary.setOpaque(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setFont(UIManager.getFont("Label.font"));
        summary.setText("Fortsetzen verwendet einen offenen Auftrag. Neue Suche übernimmt die aktuelle Nachrichtenzahl.");
        state.add(summary, BorderLayout.SOUTH);
        log.setEditable(false); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setMargin(new Insets(6, 6, 6, 6));
        JScrollPane logs = new JScrollPane(log); logs.setBorder(BorderFactory.createTitledBorder("Downloadprotokoll"));
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(state, BorderLayout.NORTH); center.add(logs, BorderLayout.CENTER); add(center, BorderLayout.CENTER);

        profiles.addActionListener(event -> {
            if (!loading && profiles.getSelectedItem() instanceof DownloadProfile profile) {
                fill(profile, true);
                try { settings.selectProfile(profile.id()); usePaths(profile); }
                catch (IOException error) { showError(error.getMessage()); }
                updateControls();
            }
        });
        create.addActionListener(event -> {
            loading = true; profiles.setSelectedIndex(-1); loading = false;
            DownloadProfile defaults = DownloadProfile.defaults(UserSettingsStore.defaultDownloads());
            fill(defaults, false); profileName.setText("Neues Profil"); updateControls();
        });
        save.addActionListener(event -> saveProfile());
        delete.addActionListener(event -> deleteProfile());
        browseDirectory.addActionListener(event -> chooseDirectory());
        browseExcel.addActionListener(event -> chooseExcel());
        start.addActionListener(event -> startDownload());
        cancel.addActionListener(event -> requestCancellation());
    }

    private static GridBagConstraints cell(int x, int y, int width, double weight) {
        GridBagConstraints value = new GridBagConstraints(); value.gridx = x; value.gridy = y;
        value.gridwidth = width; value.weightx = weight; value.fill = GridBagConstraints.HORIZONTAL;
        value.anchor = GridBagConstraints.WEST; value.insets = new Insets(3, 4, 3, 4); return value;
    }
    private static void single(JPanel form, int row, String label, JComponent input, JButton button) {
        form.add(new JLabel(label), cell(0, row, 1, 0)); form.add(input, cell(1, row, button == null ? 4 : 3, 1));
        if (button != null) form.add(button, cell(4, row, 1, 0));
    }
    private static void pair(JPanel form, int row, String first, JComponent a, String second, JComponent b) {
        form.add(new JLabel(first), cell(0, row, 1, 0)); form.add(a, cell(1, row, 1, .6));
        form.add(new JLabel(second), cell(2, row, 1, 0)); form.add(b, cell(3, row, 2, .4));
    }

    private void fill(DownloadProfile profile, boolean loadPassword) {
        profileId = profile.id(); profileName.setText(profile.name()); username.setText(profile.username());
        url.setText(profile.ewsUrl()); mailFolder.setText(profile.mailFolder());
        directory.setText(profile.downloadDirectory()); excel.setText(profile.excelFile());
        emailLimit.setValue(profile.emailLimit()); attempts.setValue(profile.maxAttempts());
        delay.setValue(profile.retryDelaySeconds()); timeout.setValue(profile.timeoutSeconds());
        password.setText(""); remember.setSelected(true);
        if (loadPassword) {
            char[] secret = null;
            try { secret = settings.password(profile.id()); password.setText(new String(secret)); remember.setSelected(secret.length > 0); }
            catch (IOException error) { append("[WARN] " + error.getMessage() + "\n"); }
            finally { if (secret != null) Arrays.fill(secret, '\0'); }
        }
    }

    private void reloadProfiles(String selected) throws IOException {
        loading = true;
        try {
            List<DownloadProfile> all = settings.profiles();
            profiles.setModel(new DefaultComboBoxModel<>(all.toArray(DownloadProfile[]::new)));
            DownloadProfile chosen = all.stream().filter(profile -> profile.id().equals(selected)).findFirst()
                    .orElse(all.isEmpty() ? null : all.get(0));
            profiles.setSelectedItem(chosen);
            if (chosen != null) fill(chosen, true);
        } finally { loading = false; }
    }

    private DownloadProfile formProfile() throws IOException {
        try { for (JSpinner spinner : List.of(emailLimit, attempts, delay, timeout)) spinner.commitEdit(); }
        catch (java.text.ParseException error) { throw new IOException("Bitte gültige Zahlen eingeben.", error); }
        DownloadProfile profile = new DownloadProfile(profileId, profileName.getText().strip(), url.getText().strip(),
                username.getText().strip(), mailFolder.getText().strip(), ((Number) emailLimit.getValue()).intValue(),
                directory.getText().strip(), ensureXlsx(excel.getText().strip()), ((Number) attempts.getValue()).intValue(),
                ((Number) delay.getValue()).intValue(), ((Number) timeout.getValue()).intValue());
        profile.validate(); return profile;
    }

    private static String ensureXlsx(String value) {
        return value.isBlank() || value.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? value : value + ".xlsx";
    }

    private void usePaths(DownloadProfile profile) throws IOException {
        settings.savePaths(profile.directory().toString(), profile.output().toString());
        pathsSelected.accept(profile.directory(), profile.output());
    }

    private void saveProfile() {
        char[] secret = password.getPassword();
        try {
            DownloadProfile profile = formProfile(); settings.saveProfile(profile, secret, remember.isSelected());
            boolean remembered = remember.isSelected();
            usePaths(profile); reloadProfiles(profile.id());
            if (!remembered) password.setText(new String(secret));
            status.setText("Profil gespeichert");
        } catch (IOException error) { showError(error.getMessage()); }
        finally { Arrays.fill(secret, '\0'); updateControls(); }
    }

    private void deleteProfile() {
        if (!(profiles.getSelectedItem() instanceof DownloadProfile profile)) return;
        if (JOptionPane.showConfirmDialog(this, "Profil '" + profile.name() + "' löschen?", "Profil löschen",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try { settings.deleteProfile(profile.id()); fill(DownloadProfile.defaults(UserSettingsStore.defaultDownloads()), false); reloadProfiles(""); }
        catch (IOException error) { showError(error.getMessage()); }
        updateControls();
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            directory.setText(chooser.getSelectedFile().getAbsolutePath());
            excel.setText(chooser.getSelectedFile().toPath().resolve("BSHG_Ergebnis.xlsx").toString());
        }
    }
    private void chooseExcel() {
        JFileChooser chooser = new JFileChooser(); chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Excel-Dateien (*.xlsx)", "xlsx"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) excel.setText(ensureXlsx(chooser.getSelectedFile().getAbsolutePath()));
    }

    private void startDownload() {
        if (worker != null || otherTaskRunning) return;
        char[] secret = password.getPassword();
        try {
            DownloadProfile profile = formProfile();
            if (secret.length == 0) throw new IOException("Bitte ein Passwort eingeben.");
            boolean remembered = remember.isSelected();
            settings.saveProfile(profile, secret, remembered); usePaths(profile); reloadProfiles(profile.id());
            if (!remembered) password.setText(new String(secret));
            log.setText(""); progress.setValue(0); progress.setString("0 %");
            status.setText("Download läuft …"); status.setForeground(new Color(80, 150, 230));
            summary.setText("Nachrichtenliste und bereits vorhandene PDFs werden geprüft.");
            worker = new DownloadWorker(profile, secret.clone(), (Mode) mode.getSelectedItem());
            updateControls(); busyChanged.accept(true); worker.execute();
        } catch (IOException error) { showError(error.getMessage()); }
        finally { Arrays.fill(secret, '\0'); }
    }

    public boolean isRunning() { return worker != null; }
    JButton startButton() { return start; }
    public void setOtherTaskRunning(boolean value) { otherTaskRunning = value; updateControls(); }
    public void clearPassword() { password.setText(""); }
    public void requestCancellation() {
        if (worker == null || worker.control.isCancelled()) return;
        worker.control.cancel(); status.setText("Download wird beendet …"); progress.setString("Abbruch wird ausgeführt …"); updateControls();
    }
    private void updateControls() {
        boolean busy = worker != null || otherTaskRunning;
        inputs.forEach(input -> input.setEnabled(!busy));
        delete.setEnabled(!busy && profiles.getSelectedItem() != null);
        start.setEnabled(!busy); cancel.setEnabled(worker != null && !worker.control.isCancelled());
    }
    private void append(String message) {
        log.append(message); int excess = log.getDocument().getLength() - 1_000_000;
        if (excess > 0) log.replaceRange("", 0, excess); log.setCaretPosition(log.getDocument().getLength());
    }
    void showError(String message) { JOptionPane.showMessageDialog(this, message, "Download", JOptionPane.ERROR_MESSAGE); }

    private final class DownloadWorker extends SwingWorker<Result, Update> {
        final DownloadProfile profile; final char[] secret; final Mode selectedMode; final Control control = new Control();
        boolean finished;
        DownloadWorker(DownloadProfile profile, char[] secret, Mode mode) { this.profile = profile; this.secret = secret; selectedMode = mode; }
        @Override protected Result doInBackground() throws IOException {
            try { return service.download(profile, secret, selectedMode, control, update -> publish(update)); }
            finally { Arrays.fill(secret, '\0'); }
        }
        @Override protected void process(List<Update> chunks) {
            if (worker != this || finished) return;
            StringBuilder text = new StringBuilder();
            for (Update update : chunks) if (update.message() != null) text.append(update.message()).append('\n');
            Update latest = chunks.get(chunks.size() - 1);
            if (!control.isCancelled()) { progress.setValue(Math.min(99, latest.percent())); progress.setString(progress.getValue() + " %"); }
            summary.setText("Neu gespeichert: " + latest.downloaded() + "  |  Vorhanden geprüft: " + latest.skipped() + "  |  Fehler: " + latest.failed());
            append(text.toString());
        }
        @Override protected void done() {
            if (worker != this) return;
            finished = true;
            try {
                Result result = get();
                String label = result.cancelled() ? "Download abgebrochen – Fortsetzen möglich"
                        : !result.complete() ? "Download unvollständig – Fortsetzen möglich"
                        : result.downloaded() + result.skipped() == 0 ? "Abgeschlossen – keine PDF-Anhänge gefunden" : "Download abgeschlossen";
                status.setText(label);
                status.setForeground(result.complete() ? new Color(60, 180, 100) : new Color(220, 155, 45));
                if (result.complete()) progress.setValue(100);
                progress.setString(result.complete() ? "100 %" : result.cancelled() ? "Abgebrochen" : "Unvollständig");
                summary.setText("Nachrichten vollständig: " + result.completedMessages() + "/" + result.plannedMessages()
                        + "  |  Neu gespeichert: " + result.downloaded() + "  |  Vorhanden geprüft: " + result.skipped()
                        + "  |  Fehler: " + result.failed() + "\nPDF-Ordner und Excel-Ziel sind in der Verarbeitung eingetragen.");
                append("[INFO] " + label + "\n");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); status.setText("Download unterbrochen"); progress.setString("Unterbrochen");
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                status.setText("Download fehlgeschlagen – Fortsetzen möglich"); progress.setString("Fehler");
                append("[ERROR] " + Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()) + "\n");
                showError(Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()));
            } finally {
                Arrays.fill(secret, '\0'); worker = null; mode.setSelectedItem(Mode.RESUME);
                updateControls(); busyChanged.accept(false);
            }
        }
    }
}
