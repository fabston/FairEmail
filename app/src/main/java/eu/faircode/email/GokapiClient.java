package eu.faircode.email;

/*
    This file is part of FairEmail Plus, a fork of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.
*/

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Uploading to a Gokapi server through its API, and deleting what was uploaded, as Gokapi's own command-line uploader
 * does it (cmd/cli-uploader/cliapi in Gokapi's source). Ported from the Betterbird add-on, and kept free of Android,
 * so the tests can run it against a pretend server.
 * <p>
 * With end-to-end encryption, each file gets a new key and is encrypted here before it's uploaded. Its name and key go
 * only after the # in the link, which browsers never send to the server, and into your list of encrypted files, which
 * is kept on the server encrypted with your end-to-end key, so Gokapi's web page can show you the file's name.
 */
public class GokapiClient {
    static final String WHERE = "Settings → Integrations → Extensions → Gokapi";
    private static final long MEGABYTE = 1024 * 1024;
    // Pieces are kept small, whatever the server allows, so a phone doesn't hold much in memory, and progress and
    // stopping are never more than one piece behind.
    static final int MAX_PIECE_SIZE = 2 * 1024 * 1024;
    // The name Gokapi's own uploader gives an encrypted file on the server, which never sees the real one.
    private static final String ENCRYPTED_NAME = "Encrypted File";
    private static final String UUID_LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Pattern E2E_KEY = Pattern.compile("^[A-Za-z0-9+/]{43}=$");
    private static final Pattern MIME_TYPE = Pattern.compile("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$");

    static class GokapiException extends IOException {
        final int status;
        final boolean stopped;

        GokapiException(String message) {
            this(message, 0, false);
        }

        GokapiException(String message, int status, boolean stopped) {
            super(message);
            this.status = status;
            this.stopped = stopped;
        }
    }

    /** Sends one request and gives back the answer, whatever its status. Throws only if there's no answer. */
    interface Transport {
        ExtHttp.Response send(String method, String url, Map<String, String> headers, byte[] body, ExtHttp.Sending sending) throws IOException;
    }

    /** How far an upload is, and whether it should stop. */
    interface Progress {
        void onProgress(long done, long total);

        boolean isStopped();
    }

    static class Config {
        final long maxSize;
        final long chunkSize;
        final boolean e2e;

        Config(long maxSize, long chunkSize, boolean e2e) {
            this.maxSize = maxSize;
            this.chunkSize = chunkSize;
            this.e2e = e2e;
        }
    }

    static class Upload {
        final String url;
        final String id;
        final boolean encrypted;

        Upload(String url, String id, boolean encrypted) {
            this.url = url;
            this.id = id;
            this.encrypted = encrypted;
        }
    }

    private final String server;
    private final String apiKey;
    private final Transport transport;

    GokapiClient(String server, String apiKey, Transport transport) {
        this.server = server;
        this.apiKey = apiKey;
        this.transport = transport;
    }

    // --- Settings -----------------------------------------------------------------------------------------------

    /** A Gokapi server's address, cleaned up. Null if there's none, or it isn't https, as the API key and files go to it. */
    static String normalizeServer(String value) {
        URI uri;
        try {
            uri = new URI(value == null ? "" : value.trim());
        } catch (URISyntaxException ex) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty() ||
                uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            return null;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String origin = "https://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        return (origin + path).replaceAll("/+$", "");
    }

    /** An end-to-end key as Gokapi shows it, base64 of 32 bytes. Null if it isn't one. */
    static byte[] parseE2eKey(String value) {
        String text = value == null ? "" : value.trim();
        return E2E_KEY.matcher(text).matches() ? Base64.decode(text) : null;
    }

    // --- Requests -----------------------------------------------------------------------------------------------

    private static class Request {
        String method = "GET";
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;
        ExtHttp.Sending sending;
    }

    /** One request to the API, stopped first if asked to. Returns the answer's text, once it's checked. */
    private String call(String path, Request request, Progress progress) throws IOException {
        if (progress != null && progress.isStopped())
            throw stopped();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("apikey", apiKey);
        headers.putAll(request.headers);
        ExtHttp.Response response;
        try {
            response = transport.send(request.method, server + "/api" + path, headers, request.body, request.sending);
        } catch (GokapiException ex) {
            throw ex;
        } catch (IOException ex) {
            if (progress != null && progress.isStopped())
                throw stopped();
            throw new GokapiException(ex.getMessage() == null ? "Can't reach the Gokapi server" : ex.getMessage());
        }
        if (response.status == 401)
            throw new GokapiException("The Gokapi server didn't accept the API key, or it doesn't allow this. Check it under " + WHERE + ".", 401, false);
        if (!response.ok())
            throw new GokapiException(explain(response.status, response.text), response.status, false);
        return response.text;
    }

    /** The same, for an answer that's JSON. */
    private JSONObject json(String path, Request request, Progress progress) throws IOException {
        try {
            return new JSONObject(call(path, request, progress));
        } catch (JSONException ex) {
            throw new GokapiException("Unexpected answer from the Gokapi server");
        }
    }

    private static GokapiException stopped() {
        return new GokapiException("Stopped", 0, true);
    }

    private static String explain(int status, String text) {
        String message = "";
        try {
            // Gokapi's API errors say ErrorMessage, and its end-to-end ones errormessage.
            JSONObject answer = new JSONObject(text);
            message = answer.optString("ErrorMessage", answer.optString("errormessage", ""));
        } catch (JSONException ignored) {
            // Not JSON: only the status is shown.
        }
        if (status == 404)
            return "The Gokapi server doesn't know this address. Check the server under " + WHERE + ".";
        if (status == 413)
            return "This file is too big for the Gokapi server, or for a proxy in front of it.";
        String detail = message.isEmpty() ? "" : ": " + (message.length() > 200 ? message.substring(0, 200) : message);
        return "The Gokapi server turned the request down (error " + status + ")" + detail;
    }

    /** The server's upload settings: sizes in bytes, and whether it uses end-to-end encryption. */
    private Config getConfig(Progress progress) throws IOException {
        JSONObject config = json("/info/config", new Request(), progress);
        double maxSize = config.optDouble("MaxFilesize", 0) * MEGABYTE;
        double chunkSize = config.optDouble("MaxChunksize", 0) * MEGABYTE;
        if (!(maxSize > 0) || !(chunkSize > 0))
            throw new GokapiException("Unexpected answer from the Gokapi server");
        return new Config((long) maxSize, (long) chunkSize, Boolean.TRUE.equals(config.opt("EndToEndEncryptionEnabled")));
    }

    // --- The list of encrypted files ----------------------------------------------------------------------------

    private interface Change {
        /** The changed list to save, or null to leave it as it is. */
        List<GokapiGob.File> change(List<GokapiGob.File> files);
    }

    /**
     * Runs change on your list of encrypted files while the server holds it for you, as Gokapi's web page and
     * uploader do, so neither can overwrite what the other just added. Only up to 30 seconds: then the server lets go
     * anyway.
     * <p>
     * Released Gokapi, up to 2.2.4 at least, can't hold the list: it answers that it doesn't know the address with a
     * 400, where a wrong key would be a 401. Its own web page and uploader then go ahead without, and so does this.
     */
    private void withFileList(byte[] e2eKey, Progress progress, Change change) throws IOException {
        boolean locked;
        try {
            call("/e2e/mutex/lock", new Request(), progress);
            locked = true;
        } catch (GokapiException ex) {
            if (ex.status != 400)
                throw ex;
            locked = false;
        }
        try {
            JSONObject stored = json("/e2e/get", new Request(), progress);
            String content = stored.optString("content", "");
            if (!(stored.optInt("version", 0) > 0) || content.isEmpty() || "null".equals(content))
                throw new GokapiException("End-to-end encryption isn't set up yet. Open Gokapi's upload page in your browser once, set it up there, then try again.");
            byte[] plain;
            try {
                plain = gcm(Cipher.DECRYPT_MODE, e2eKey, Base64.decode(stored.optString("nonce", "")), Base64.decode(content));
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                throw new GokapiException("The end-to-end key doesn't match the server's. Check it under " + WHERE + ".");
            }
            List<GokapiGob.File> files;
            try {
                files = GokapiGob.decodeFiles(plain);
            } catch (GokapiGob.GobException ex) {
                // The key is right, but the list isn't as expected, so it's left as it is rather than overwritten.
                throw new GokapiException("Your list of encrypted files on the server can't be read: " + ex.getMessage());
            }
            List<GokapiGob.File> changed = change.change(files);
            if (changed == null)
                return;
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            byte[] sealed;
            try {
                sealed = gcm(Cipher.ENCRYPT_MODE, e2eKey, nonce, GokapiGob.encodeFiles(changed));
            } catch (GeneralSecurityException ex) {
                throw new GokapiException("Your list of encrypted files couldn't be encrypted: " + ex.getMessage());
            }
            JSONObject info = new JSONObject();
            JSONObject body = new JSONObject();
            try {
                info.put("version", 1);
                info.put("nonce", Base64.encode(nonce));
                info.put("content", Base64.encode(sealed));
                body.put("content", Base64.encode(info.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (JSONException ex) {
                throw new GokapiException(ex.getMessage());
            }
            Request set = new Request();
            set.method = "POST";
            set.headers.put("Content-Type", "application/json");
            set.body = body.toString().getBytes(StandardCharsets.UTF_8);
            call("/e2e/set", set, progress);
        } finally {
            if (locked)
                try {
                    // Not stopped by progress: the server is let go of even after stopping.
                    call("/e2e/mutex/unlock", new Request(), null);
                } catch (IOException ignored) {
                    // It lets go by itself after 30 seconds.
                }
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(data);
    }

    /**
     * The key has to match what the server says it does. Without one, nothing goes to a server that encrypts. With
     * one, nothing goes to a server that says it doesn't, as you'd expect your files encrypted and they wouldn't be.
     */
    private static void checkEncryption(Config config, byte[] e2eKey) throws GokapiException {
        if (config.e2e && e2eKey == null)
            throw new GokapiException("This server uses end-to-end encryption: enter your end-to-end key under " + WHERE + ".");
        if (!config.e2e && e2eKey != null)
            throw new GokapiException("This server says it doesn't use end-to-end encryption, so your files wouldn't be encrypted. " +
                    "To upload them as they are, remove the end-to-end key under " + WHERE + ".");
    }

    /** Checks the API key, and the end-to-end key if the server uses one, without uploading anything. */
    Config check(byte[] e2eKey) throws IOException {
        Config config = getConfig(null);
        checkEncryption(config, e2eKey);
        if (config.e2e)
            withFileList(e2eKey, null, files -> null);
        return config;
    }

    // --- Uploading ----------------------------------------------------------------------------------------------

    /** 30 random letters and digits, as Gokapi's uploader uses to name the chunks of one upload. */
    static String uploadId() {
        SecureRandom random = new SecureRandom();
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 30; i++)
            id.append(UUID_LETTERS.charAt(random.nextInt(UUID_LETTERS.length())));
        return id.toString();
    }

    /** What Gokapi's download page needs after the #: the file's name and key, as base64 of JSON, as Gokapi's uploader writes it. */
    static String linkSecret(String filename, byte[] fileKey) {
        String json = "{\"f\":" + jsonString(filename) + ",\"c\":" + jsonString(Base64.encode(fileKey)) + "}";
        return Base64.encode(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A JSON string as JavaScript's JSON.stringify writes it, as Gokapi's uploader does: only quotes, backslashes and
     * control characters are escaped. Neither Android's nor other org.json versions write it quite the same.
     */
    static String jsonString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    boolean lone = Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(text.charAt(i - 1)));
                    if (c < 0x20 || lone)
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else
                        out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * A link the server gave back, checked to be an https address with no # of its own, so nothing else goes in the
     * email. Not http: the download page gets the file's key from the link, and anyone on the way could read it off a
     * page that isn't encrypted.
     */
    private static String checkLink(String value) throws GokapiException {
        try {
            URI uri = new URI(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null &&
                    uri.getRawFragment() == null && uri.getRawUserInfo() == null)
                return uri.toString();
        } catch (URISyntaxException ignored) {
            // Not an address at all.
        }
        throw new GokapiException("The Gokapi server gave back a download address that isn't an https link. Check its public address in Gokapi's settings.");
    }

    /**
     * Uploads a file of the given size, read from in, encrypted if the server uses end-to-end encryption. Returns its
     * link, with the name and key after the # if it's encrypted, and its id on the server. downloads is how many
     * downloads it allows, 0 for no limit, and days how many days it's kept.
     */
    Upload upload(InputStream in, long size, String name, String type, byte[] e2eKey, int downloads, int days, Progress progress) throws IOException {
        Config config = getConfig(progress);
        checkEncryption(config, e2eKey);
        boolean e2e = config.e2e;
        long total = e2e ? GokapiSio.encryptedSize(size) : size;
        if (total > config.maxSize)
            throw new GokapiException("This file is too big for the Gokapi server, which takes up to " + (config.maxSize / MEGABYTE) + " MB.");
        // Checked before anything is uploaded, so a wrong key doesn't leave a file behind.
        if (e2e)
            withFileList(e2eKey, progress, files -> null);

        String uuid = uploadId();
        byte[] fileKey = e2e ? GokapiSio.newFileKey() : null;
        int pieceSize = (int) Math.min(config.chunkSize, MAX_PIECE_SIZE);
        Pieces pieces;
        try {
            pieces = e2e ? new EncryptedPieces(new GokapiSio.Encryptor(in, size, fileKey, pieceSize)) : new PlainPieces(in, size, pieceSize);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new GokapiException("The file couldn't be encrypted: " + ex.getMessage());
        }
        long offset = 0;
        if (progress != null)
            progress.onProgress(0, total);
        while (pieces.hasNext()) {
            byte[] piece;
            try {
                piece = pieces.next();
            } catch (GeneralSecurityException ex) {
                throw new GokapiException("The file couldn't be encrypted: " + ex.getMessage());
            } catch (IOException ex) {
                throw new GokapiException(ex.getMessage() == null ? "The file couldn't be read" : ex.getMessage());
            }
            final long start = offset;
            Multipart form = new Multipart();
            form.file("file", "uploadedfile", piece);
            form.field("filesize", Long.toString(total));
            form.field("offset", Long.toString(offset));
            form.field("uuid", uuid);
            Request add = new Request();
            add.method = "POST";
            add.headers.put("Content-Type", form.contentType());
            add.body = form.build();
            int overhead = add.body.length - piece.length;
            add.sending = sent -> {
                if (progress == null)
                    return;
                if (progress.isStopped())
                    throw stopped();
                progress.onProgress(start + Math.max(0, Math.min(piece.length, sent - overhead)), total);
            };
            JSONObject answer = json("/chunk/add", add, progress);
            if (!"OK".equals(answer.optString("result")))
                throw new GokapiException("The Gokapi server didn't take part of the file");
            offset += piece.length;
        }
        if (offset != total)
            throw new GokapiException("The file changed while it was being uploaded");
        try {
            if (in.read() >= 0)
                throw new GokapiException("The file changed while it was being uploaded");
        } catch (GokapiException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new GokapiException(ex.getMessage() == null ? "The file couldn't be read" : ex.getMessage());
        }

        Request complete = new Request();
        complete.method = "POST";
        complete.headers.put("uuid", uuid);
        complete.headers.put("filename", e2e ? ENCRYPTED_NAME : "base64:" + Base64.encode(name.getBytes(StandardCharsets.UTF_8)));
        complete.headers.put("filesize", Long.toString(total));
        complete.headers.put("realsize", Long.toString(size));
        complete.headers.put("isE2E", Boolean.toString(e2e));
        complete.headers.put("allowedDownloads", Integer.toString(downloads));
        complete.headers.put("expiryDays", Integer.toString(days));
        // The type comes from another app, and goes in a header, so anything but a plain type is left out.
        complete.headers.put("contenttype", e2e || type == null || !MIME_TYPE.matcher(type).matches() ? "application/octet-stream" : type);
        JSONObject done = json("/chunk/complete", complete, progress);
        JSONObject info = done.optJSONObject("FileInfo");
        String id = info == null ? "" : info.optString("Id", "");
        if (id.isEmpty())
            throw new GokapiException("Unexpected answer from the Gokapi server");
        String link = checkLink(info.optString("UrlDownload", ""));
        if (!e2e)
            return new Upload(link, id, false);

        // So Gokapi's web page shows the file's name, and can give you its link again. The link works without it, so
        // the upload isn't lost if this fails.
        try {
            withFileList(e2eKey, progress, files -> {
                List<GokapiGob.File> changed = new ArrayList<>(files);
                changed.add(new GokapiGob.File(uuid, id, name, fileKey));
                return changed;
            });
        } catch (GokapiException ex) {
            if (ex.stopped) {
                // Nobody gets the link now, so the file isn't left on the server, if the API key may delete it.
                try {
                    delete(id);
                } catch (IOException ignored) {
                    // It expires.
                }
                throw ex;
            }
            // Kept going: the link works.
        }
        return new Upload(link + "#" + linkSecret(name, fileKey), id, true);
    }

    private interface Pieces {
        boolean hasNext();

        byte[] next() throws IOException, GeneralSecurityException;
    }

    private static class EncryptedPieces implements Pieces {
        private final GokapiSio.Encryptor encryptor;

        EncryptedPieces(GokapiSio.Encryptor encryptor) {
            this.encryptor = encryptor;
        }

        @Override
        public boolean hasNext() {
            return encryptor.hasNext();
        }

        @Override
        public byte[] next() throws IOException, GeneralSecurityException {
            return encryptor.next();
        }
    }

    /** A file in pieces of at most pieceSize bytes, read only when needed. An empty file is one empty piece. */
    private static class PlainPieces implements Pieces {
        private final InputStream in;
        private final long size;
        private final int pieceSize;
        private long read = 0;
        private boolean first = true;

        PlainPieces(InputStream in, long size, int pieceSize) {
            this.in = in;
            this.size = size;
            this.pieceSize = pieceSize;
        }

        @Override
        public boolean hasNext() {
            return first || read < size;
        }

        @Override
        public byte[] next() throws IOException {
            first = false;
            byte[] piece = new byte[(int) Math.min(pieceSize, size - read)];
            GokapiSio.readFully(in, piece);
            read += piece.length;
            return piece;
        }
    }

    /** A multipart/form-data body, as a browser sends a form with a file. */
    static class Multipart {
        private final String boundary = "----FairEmailPlus" + uploadId();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        void field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
        }

        void file(String name, String filename, byte[] data) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n");
            out.write(data, 0, data.length);
            write("\r\n");
        }

        byte[] build() {
            write("--" + boundary + "--\r\n");
            return out.toByteArray();
        }

        private void write(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
    }

    // --- Deleting -----------------------------------------------------------------------------------------------

    /** Deletes an uploaded file. One that's already gone counts as deleted. */
    void delete(String id) throws IOException {
        Request request = new Request();
        request.method = "DELETE";
        request.headers.put("id", id);
        try {
            call("/files/delete", request, null);
        } catch (GokapiException ex) {
            if (ex.status != 404)
                throw ex;
        }
    }

    // --- Base64 -------------------------------------------------------------------------------------------------

    /** Standard base64 with padding, as Gokapi and browsers write it. Here as java.util.Base64 needs Android 8. */
    static class Base64 {
        private static final char[] LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        static String encode(byte[] data) {
            StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
            for (int i = 0; i < data.length; i += 3) {
                int b = (data[i] & 0xff) << 16;
                if (i + 1 < data.length)
                    b |= (data[i + 1] & 0xff) << 8;
                if (i + 2 < data.length)
                    b |= data[i + 2] & 0xff;
                out.append(LETTERS[(b >> 18) & 63]).append(LETTERS[(b >> 12) & 63]);
                out.append(i + 1 < data.length ? LETTERS[(b >> 6) & 63] : '=');
                out.append(i + 2 < data.length ? LETTERS[b & 63] : '=');
            }
            return out.toString();
        }

        /** Decodes base64, with or without padding. Throws IllegalArgumentException if it isn't base64. */
        static byte[] decode(String text) {
            String clean = text.trim();
            while (clean.endsWith("="))
                clean = clean.substring(0, clean.length() - 1);
            if (clean.length() % 4 == 1)
                throw new IllegalArgumentException("Not base64");
            ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() * 3 / 4);
            int buffer = 0;
            int bits = 0;
            for (int i = 0; i < clean.length(); i++) {
                int value = value(clean.charAt(i));
                if (value < 0)
                    throw new IllegalArgumentException("Not base64");
                buffer = ((buffer << 6) | value) & 0xffffff;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 0xff);
                }
            }
            return out.toByteArray();
        }

        private static int value(char c) {
            if (c >= 'A' && c <= 'Z')
                return c - 'A';
            if (c >= 'a' && c <= 'z')
                return c - 'a' + 26;
            if (c >= '0' && c <= '9')
                return c - '0' + 52;
            if (c == '+')
                return 62;
            if (c == '/')
                return 63;
            return -1;
        }
    }
}
