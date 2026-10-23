package com.msv.pte.download;

import com.msv.pte.download.AttachmentDownloadService.*;
import microsoft.exchange.webservices.data.core.*;
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion;
import microsoft.exchange.webservices.data.core.enumeration.property.*;
import microsoft.exchange.webservices.data.core.enumeration.search.*;
import microsoft.exchange.webservices.data.core.exception.http.HttpErrorException;
import microsoft.exchange.webservices.data.core.exception.service.remote.ServiceResponseException;
import microsoft.exchange.webservices.data.core.service.folder.Folder;
import microsoft.exchange.webservices.data.core.service.item.Item;
import microsoft.exchange.webservices.data.core.service.schema.*;
import microsoft.exchange.webservices.data.credential.WebCredentials;
import microsoft.exchange.webservices.data.property.complex.*;
import microsoft.exchange.webservices.data.search.*;
import microsoft.exchange.webservices.data.search.filter.SearchFilter;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;

import javax.net.ssl.SSLException;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;

/** Exchange adapter. Only reads folders, messages and attachments. */
public final class EwsMailSession implements MailSession {
    private final ControlledService service;
    private final Map<String, FileAttachment> attachmentObjects = new HashMap<>();
    private String cachedMessage;

    public EwsMailSession(DownloadProfile profile, char[] password) throws IOException {
        service = new ControlledService(profile.timeoutSeconds() * 1000);
        try {
            String username = profile.username().strip(), domain = "";
            int slash = username.indexOf('\\');
            if (slash > 0) { domain = username.substring(0, slash); username = username.substring(slash + 1); }
            service.setCredentials(new WebCredentials(username, new String(password), domain));
            service.setUrl(profile.serverUri());
            service.setTraceEnabled(false);
        } catch (Exception error) { service.close(); throw failure(error); }
    }

    private static final class ControlledService extends ExchangeService {
        ControlledService(int timeout) {
            super(ExchangeVersion.Exchange2010_SP2);
            super.close();
            httpClient = HttpClients.custom().useSystemProperties()
                    .setTargetAuthenticationStrategy(new CookieProcessingTargetAuthenticationStrategy())
                    .disableAutomaticRetries().disableRedirectHandling()
                    .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(timeout)
                            .setSocketTimeout(timeout).setConnectionRequestTimeout(timeout).build()).build();
            httpPoolingClient = null;
            setTimeout(timeout);
        }
    }

    @Override public String findFolder(String name) throws IOException {
        try {
            String[] parts = name.strip().split("/", -1);
            FolderId parent = new FolderId(WellKnownFolderName.MsgFolderRoot);
            for (String part : parts) {
                if (part.isBlank()) throw new RemoteFailure("Bitte einen gültigen Mailordner oder Ordnerpfad eingeben.", false, true);
                FolderView view = new FolderView(2);
                view.setTraversal(parts.length == 1 ? FolderTraversal.Deep : FolderTraversal.Shallow);
                view.setPropertySet(new PropertySet(BasePropertySet.IdOnly, FolderSchema.DisplayName));
                FindFoldersResults found = service.findFolders(parent,
                        new SearchFilter.IsEqualTo(FolderSchema.DisplayName, part.strip()), view);
                if (found.getFolders().isEmpty()) throw new RemoteFailure("Mailordner nicht gefunden: " + part, false, true);
                if (found.getFolders().size() != 1 || found.isMoreAvailable()) {
                    throw new RemoteFailure("Mehrere Mailordner heißen '" + part + "'. Bitte einen vollständigen Pfad wie Posteingang/Unterordner angeben.", false, true);
                }
                parent = found.getFolders().get(0).getId();
            }
            return parent.getUniqueId();
        } catch (Exception error) { throw failure(error); }
    }

    @Override public MessagePage messages(String folder, int offset, int count, Instant upperBound) throws IOException {
        try {
            ItemView view = new ItemView(count, offset);
            view.setPropertySet(new PropertySet(BasePropertySet.IdOnly, ItemSchema.DateTimeReceived));
            view.getOrderBy().add(ItemSchema.DateTimeReceived, SortDirection.Descending);
            FindItemsResults<Item> found = upperBound == null ? service.findItems(new FolderId(folder), view)
                    : service.findItems(new FolderId(folder), new SearchFilter.IsLessThanOrEqualTo(
                            ItemSchema.DateTimeReceived, Date.from(upperBound)), view);
            List<String> ids = new ArrayList<>();
            Instant anchor = upperBound;
            for (Item item : found.getItems()) {
                ids.add(item.getId().getUniqueId());
                if (anchor == null) anchor = item.getDateTimeReceived().toInstant();
            }
            Integer next = found.getNextPageOffset();
            return new MessagePage(ids, next == null ? offset + found.getItems().size() : next,
                    found.isMoreAvailable(), anchor);
        } catch (Exception error) { throw failure(error); }
    }

    @Override public List<RemoteAttachment> attachments(String messageId) throws IOException {
        try {
            Item item = Item.bind(service, new ItemId(messageId), new PropertySet(BasePropertySet.IdOnly, ItemSchema.Attachments));
            attachmentObjects.clear(); cachedMessage = messageId;
            List<RemoteAttachment> result = new ArrayList<>();
            for (Attachment attachment : item.getAttachments()) {
                if (attachment instanceof FileAttachment file && file.getName() != null
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    attachmentObjects.put(file.getId(), file);
                    result.add(new RemoteAttachment(messageId, file.getId(), file.getName(), file.getSize()));
                }
            }
            return List.copyOf(result);
        } catch (Exception error) { throw failure(error); }
    }

    @Override public void download(RemoteAttachment attachment, OutputStream output) throws IOException {
        try {
            if (!attachment.messageId().equals(cachedMessage) || !attachmentObjects.containsKey(attachment.attachmentId())) {
                attachments(attachment.messageId());
            }
            FileAttachment file = attachmentObjects.get(attachment.attachmentId());
            if (file == null) throw new RemoteFailure("PDF-Anhang ist auf dem Server nicht mehr vorhanden: " + attachment.name(), false, false);
            file.load(output);
        } catch (Exception error) { throw failure(error); }
    }

    private static IOException failure(Exception error) {
        if (error instanceof RemoteFailure remote) return remote;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpErrorException http) {
                int status = http.getHttpErrorCode();
                if (status == 401 || status == 403) {
                    return new RemoteFailure("Server lehnt die Anmeldung ab (HTTP " + status + "). Profil und Zugriffsrechte prüfen.", false, true);
                }
                return new RemoteFailure("EWS meldet HTTP " + status + ".",
                        status == 408 || status == 429 || status >= 500, true);
            }
            if (cause instanceof ServiceResponseException response) {
                String code = response.getErrorCode().name();
                boolean retry = Set.of("ErrorServerBusy", "ErrorTimeoutExpired", "ErrorInternalServerTransientError",
                        "ErrorADUnavailable", "ErrorMailboxStoreUnavailable", "ErrorConnectionFailed").contains(code);
                boolean item = Set.of("ErrorItemNotFound", "ErrorInvalidAttachmentId", "ErrorInvalidIdMalformed").contains(code);
                return new RemoteFailure("EWS meldet " + code + ".", retry, !item);
            }
            if (cause instanceof SSLException) return new RemoteFailure("TLS-Verbindung fehlgeschlagen. Serveradresse und Zertifikatsvertrauen prüfen.", false, true);
            if (cause instanceof SocketTimeoutException || cause instanceof SocketException || cause instanceof UnknownHostException
                    || cause instanceof EOFException || cause instanceof org.apache.http.NoHttpResponseException
                    || cause instanceof org.apache.http.ConnectionClosedException
                    || cause instanceof org.apache.http.MalformedChunkCodingException) {
                return new RemoteFailure("Netzwerkverbindung zum EWS-Server unterbrochen (" + cause.getClass().getSimpleName() + ").", true, true);
            }
            if (cause instanceof InterruptedIOException interrupted) return interrupted;
            if (cause.getCause() == cause) break;
        }
        return new RemoteFailure("EWS-Anfrage fehlgeschlagen (" + error.getClass().getSimpleName() + ").", false, true);
    }

    @Override public void close() { service.close(); }
}
