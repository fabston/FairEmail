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

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The extensions' requests to their services. The key in a request is only for that service, so a redirect is never
 * followed, and the key is taken out of any error message.
 */
public class ExtHttp {
    public static class Response {
        final int status;
        final String text; // the answer, or the error's details

        Response(int status, String text) {
            this.status = status;
            this.text = text;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** How much of a request's body has been sent, as it's sent. Throwing stops the request. */
    interface Sending {
        void sent(long bytes) throws IOException;
    }

    private static final int SLICE = 64 * 1024;
    // No answer from these services is near this size, so anything longer is cut off, not held in memory.
    private static final int MAX_ANSWER = 16 * 1024 * 1024;

    /** Sends a request and returns the answer, whatever its status. Throws if there's no answer at all. */
    static Response send(Context context, String service, String method, String url, Map<String, String> headers,
                         String body, String secret, int timeout) throws IOException {
        return send(context, service, method, url, headers,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), null, secret, timeout);
    }

    /**
     * The same with a body of bytes, such as a file, streamed rather than buffered a second time, and reported to
     * sending as it goes.
     */
    static Response send(Context context, String service, String method, String url, Map<String, String> headers,
                         byte[] body, Sending sending, String secret, int timeout) throws IOException {
        URL target = new URL(url);
        // A key is never sent unencrypted, whatever the caller passes. Only this phone itself is exempt.
        boolean local = "localhost".equals(target.getHost()) || "127.0.0.1".equals(target.getHost());
        if (!"https".equals(target.getProtocol()) && !local)
            throw new IOException(service + " needs an https address");
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        try {
            try {
                connection.setRequestMethod(method);
            } catch (ProtocolException ex) {
                // Some Android versions can't send PATCH. The services accept it as an override.
                connection.setRequestMethod("POST");
                connection.setRequestProperty("X-HTTP-Method-Override", method);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(timeout * 1000);
            connection.setReadTimeout(timeout * 1000);
            ConnectionHelper.setUserAgent(context, connection);
            for (Map.Entry<String, String> header : headers.entrySet())
                try {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                } catch (IllegalArgumentException ex) {
                    // Its message quotes the value, which can be the key, so only the header's name is passed on.
                    throw new IllegalArgumentException("The " + header.getKey() + " for " + service +
                            " has a character that can't be sent. Enter it again in the settings.");
                }
            if (body != null) {
                connection.setDoOutput(true);
                // Only a file is streamed: a small request stays buffered, so a stale connection is retried quietly.
                if (body.length > SLICE)
                    connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = connection.getOutputStream()) {
                    for (int at = 0; at < body.length; at += SLICE) {
                        os.write(body, at, Math.min(SLICE, body.length - at));
                        if (sending != null)
                            sending.sent(Math.min(at + SLICE, body.length));
                    }
                }
            }
            int status = connection.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            String text;
            try {
                text = read(ok ? connection.getInputStream() : connection.getErrorStream());
            } catch (IOException ex) {
                if (ok)
                    throw ex;
                text = ""; // the details are only a nicer message
            }
            return new Response(status, text);
        } catch (SocketTimeoutException ex) {
            throw new IOException("No answer from " + service + " after " + timeout + " seconds");
        } catch (IOException | IllegalArgumentException ex) {
            // An error can quote the request, so the key is taken out.
            String message = ex.getMessage() == null ? "the connection failed" : ex.getMessage();
            if (secret != null && !secret.isEmpty())
                message = message.replace(secret, "[API key]");
            throw new IOException(ex instanceof IOException ? "Can't reach " + service + ": " + message : message);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream is) throws IOException {
        if (is == null)
            return "";
        try (InputStream in = is) {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buffer = new byte[SLICE];
            for (int len = in.read(buffer); len != -1; len = in.read(buffer)) {
                if (os.size() + len > MAX_ANSWER)
                    throw new IOException("the answer is too long");
                os.write(buffer, 0, len);
            }
            return new String(os.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
