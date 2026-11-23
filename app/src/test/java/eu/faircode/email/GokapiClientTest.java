package eu.faircode.email;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GokapiClientTest {
    private static final String SERVER = "https://drop.example";
    private static final String API_KEY = "test-api-key";
    private static final byte[] E2E_KEY = GokapiGobTest.filled(32, 7);
    private static final int MB = 1024 * 1024;
    private static final GokapiGob.File EARLIER = new GokapiGob.File(GokapiGobTest.repeat("x", 30), "old1", "Earlier.zip", GokapiGobTest.filled(32, 3));

    // --- A pretend Gokapi server ----------------------------------------------------------------------------------

    interface Fail {
        ExtHttp.Response answer(String path) throws IOException;
    }

    static class Chunk {
        long offset;
        long filesize;
        byte[] data;
    }

    static class Completed {
        String id;
        String uuid;
        byte[] data;
        Map<String, String> headers;
    }

    static class Request {
        String url;
        String path;
        String method;
        Map<String, String> headers;
        byte[] body;
    }

    /**
     * A pretend Gokapi server, as the Betterbird add-on's tests have it. It checks the API key, holds the list of
     * encrypted files only while it's locked, as the real one does, and puts the chunks of each upload together. With
     * mutex false, it's a released Gokapi, up to 2.2.4, which can't hold the list and doesn't know its addresses.
     */
    static class FakeGokapi implements GokapiClient.Transport {
        boolean mutex = true;
        boolean e2e = true;
        int maxSize = 100;
        int chunkSize = 1;
        Fail fail = path -> null;

        final List<Request> requests = new ArrayList<>();
        boolean locked;
        int locks;
        int unlocks;
        JSONObject info;
        final Map<String, List<Chunk>> chunks = new LinkedHashMap<>();
        final List<Completed> completed = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();

        FakeGokapi(List<GokapiGob.File> files) throws Exception {
            info = sealFileList(files, E2E_KEY);
        }

        FakeGokapi() throws Exception {
            this(Collections.emptyList());
        }

        GokapiClient client(String apiKey) {
            return new GokapiClient(SERVER, apiKey, this);
        }

        GokapiClient client() {
            return client(API_KEY);
        }

        static ExtHttp.Response json(int status, String text) {
            return new ExtHttp.Response(status, text);
        }

        @Override
        public ExtHttp.Response send(String method, String url, Map<String, String> headers, byte[] body, ExtHttp.Sending sending) throws IOException {
            Request request = new Request();
            request.url = url;
            request.path = URI.create(url).getPath();
            request.method = method;
            request.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            request.headers.putAll(headers);
            request.body = body;
            requests.add(request);
            if (body != null && sending != null)
                sending.sent(body.length);
            String path = request.path;
            if (!API_KEY.equals(request.headers.get("apikey")))
                return json(401, "{\"Result\":\"error\",\"ErrorMessage\":\"Unauthorized\"}");
            ExtHttp.Response answer = fail.answer(path);
            if (answer != null)
                return answer;
            if (!mutex && path.startsWith("/api/e2e/mutex/"))
                return json(400, "{\"Result\":\"error\",\"ErrorMessage\":\"Invalid request\",\"ErrorCode\":1}");
            boolean held = locked || !mutex;
            try {
                switch (path) {
                    case "/api/info/config":
                        return json(200, new JSONObject().put("MaxFilesize", maxSize).put("MaxChunksize", chunkSize).put("EndToEndEncryptionEnabled", e2e).toString());
                    case "/api/e2e/mutex/lock":
                        locked = true;
                        locks++;
                        return json(200, "{\"result\":\"OK\"}");
                    case "/api/e2e/mutex/unlock":
                        locked = false;
                        unlocks++;
                        return json(200, "{\"result\":\"OK\"}");
                    case "/api/e2e/get":
                        if (!held)
                            return json(400, "{\"result\":\"error\",\"errormessage\":\"mutex was not acquired or has expired\"}");
                        return json(200, new JSONObject(info.toString()).put("availablefiles", new org.json.JSONArray()).toString());
                    case "/api/e2e/set": {
                        if (!held)
                            return json(400, "{\"result\":\"error\",\"errormessage\":\"mutex was not acquired or has expired\"}");
                        String content = new JSONObject(new String(body, StandardCharsets.UTF_8)).getString("content");
                        info = new JSONObject(new String(GokapiClient.Base64.decode(content), StandardCharsets.UTF_8));
                        return json(200, "{\"result\":\"OK\"}");
                    }
                    case "/api/chunk/add": {
                        assertTrue("chunks are sent as a form", request.headers.get("Content-Type").startsWith("multipart/form-data; boundary="));
                        Map<String, byte[]> form = parseForm(request.headers.get("Content-Type"), body);
                        String uuid = new String(form.get("uuid"), StandardCharsets.UTF_8);
                        assertTrue("the upload's id is long enough", uuid.length() >= 10);
                        Chunk chunk = new Chunk();
                        chunk.data = form.get("file");
                        assertTrue("no chunk is over the server's chunk size", chunk.data.length <= chunkSize * MB);
                        chunk.offset = Long.parseLong(new String(form.get("offset"), StandardCharsets.UTF_8));
                        chunk.filesize = Long.parseLong(new String(form.get("filesize"), StandardCharsets.UTF_8));
                        chunks.computeIfAbsent(uuid, k -> new ArrayList<>()).add(chunk);
                        return json(200, "{\"result\":\"OK\"}");
                    }
                    case "/api/chunk/complete": {
                        String uuid = request.headers.get("uuid");
                        List<Chunk> parts = new ArrayList<>(chunks.getOrDefault(uuid, new ArrayList<>()));
                        parts.sort((a, b) -> Long.compare(a.offset, b.offset));
                        long at = 0;
                        List<byte[]> datas = new ArrayList<>();
                        for (Chunk part : parts) {
                            assertEquals("the chunks follow on from each other", at, part.offset);
                            at += part.data.length;
                            datas.add(part.data);
                        }
                        Completed done = new Completed();
                        done.data = GokapiGobTest.concat(datas);
                        assertEquals("the size given is what was uploaded", Long.parseLong(request.headers.get("filesize")), done.data.length);
                        done.id = "file" + (completed.size() + 1);
                        done.uuid = uuid;
                        done.headers = request.headers;
                        completed.add(done);
                        return json(200, new JSONObject()
                                .put("Result", "OK")
                                .put("FileInfo", new JSONObject().put("Id", done.id).put("Name", request.headers.get("filename"))
                                        .put("UrlDownload", "https://drop.example/d?id=" + done.id))
                                .put("IncludeFilename", false).toString());
                    }
                    case "/api/files/delete":
                        deleted.add(request.headers.get("id"));
                        return json(200, "");
                    default:
                        return json(404, "");
                }
            } catch (org.json.JSONException ex) {
                throw new AssertionError(ex);
            }
        }

        boolean requested(String path) {
            for (Request request : requests)
                if (request.path.equals(path))
                    return true;
            return false;
        }
    }

    /** The fields of a multipart/form-data body, by name. */
    static Map<String, byte[]> parseForm(String contentType, byte[] body) {
        String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + 9);
        byte[] marker = ("\r\n" + boundary).getBytes(StandardCharsets.US_ASCII);
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith(boundary + "\r\n"));
        assertTrue(text.endsWith(boundary + "--\r\n"));
        Map<String, byte[]> fields = new LinkedHashMap<>();
        int at = boundary.length() + 2;
        while (true) {
            int headersEnd = text.indexOf("\r\n\r\n", at);
            String headers = text.substring(at, headersEnd);
            int start = headersEnd + 4;
            int end = text.indexOf(new String(marker, StandardCharsets.ISO_8859_1), start);
            String name = headers.replaceAll("(?s)^.*?; name=\"([^\"]+)\".*$", "$1");
            fields.put(name, Arrays.copyOfRange(body, start, end));
            at = end + marker.length;
            if (text.startsWith("--", at))
                return fields;
            at += 2;
        }
    }

    static JSONObject sealFileList(List<GokapiGob.File> files, byte[] key) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] content = cipher.doFinal(GokapiGob.encodeFiles(files));
        return new JSONObject().put("version", 1).put("nonce", GokapiClient.Base64.encode(nonce)).put("content", GokapiClient.Base64.encode(content));
    }

    static List<GokapiGob.File> openFileList(JSONObject info, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, GokapiClient.Base64.decode(info.getString("nonce"))));
        return GokapiGob.decodeFiles(cipher.doFinal(GokapiClient.Base64.decode(info.getString("content"))));
    }

    /** What's after the # in an encrypted file's link: its name and key. */
    static JSONObject linkSecret(String url) throws Exception {
        return new JSONObject(new String(GokapiClient.Base64.decode(url.substring(url.indexOf('#') + 1)), StandardCharsets.UTF_8));
    }

    static class Steps implements GokapiClient.Progress {
        long done = -1;
        long total = -1;
        boolean stopped;

        @Override
        public void onProgress(long done, long total) {
            assertTrue("progress only goes forward", done >= this.done);
            this.done = done;
            this.total = total;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }
    }

    private static GokapiClient.Upload upload(FakeGokapi gokapi, byte[] data, String name, byte[] e2eKey, int downloads, int days, GokapiClient.Progress progress) throws IOException {
        return gokapi.client().upload(new ByteArrayInputStream(data), data.length, name, "application/pdf", e2eKey, downloads, days, progress);
    }

    private static GokapiClient.Upload upload(FakeGokapi gokapi, byte[] data, String name) throws IOException {
        return upload(gokapi, data, name, E2E_KEY, 5, 3, new Steps());
    }

    private static void assertFails(String expected, Attempt attempt) throws Exception {
        try {
            attempt.run();
            fail("Expected: " + expected);
        } catch (GokapiClient.GokapiException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains(expected));
        }
    }

    interface Attempt {
        void run() throws Exception;
    }

    // --- Settings -------------------------------------------------------------------------------------------------

    @Test
    public void onlyHttpsServersAreUsed() {
        assertNull(GokapiClient.normalizeServer(""));
        assertNull(GokapiClient.normalizeServer(null));
        assertEquals("https://drop.example", GokapiClient.normalizeServer(" https://drop.example/ "));
        assertEquals("https://drop.example:8443", GokapiClient.normalizeServer("https://Drop.Example:8443"));
        assertEquals("https://example.org/gokapi", GokapiClient.normalizeServer("https://example.org/gokapi/"));
        assertNull(GokapiClient.normalizeServer("http://drop.example"));
        assertNull(GokapiClient.normalizeServer("https://user:pass@drop.example"));
        assertNull(GokapiClient.normalizeServer("https://drop.example/?x=1"));
        assertNull(GokapiClient.normalizeServer("https://drop.example/#x"));
        assertNull(GokapiClient.normalizeServer("javascript:alert(1)"));
        assertNull(GokapiClient.normalizeServer("drop.example"));
    }

    @Test
    public void endToEndKeysAreReadAsGokapiShowsThem() {
        assertArrayEquals(E2E_KEY, GokapiClient.parseE2eKey(" " + GokapiClient.Base64.encode(E2E_KEY) + " "));
        assertNull(GokapiClient.parseE2eKey(""));
        assertNull(GokapiClient.parseE2eKey(null));
        assertNull(GokapiClient.parseE2eKey(GokapiClient.Base64.encode(new byte[16])));
        assertNull(GokapiClient.parseE2eKey("not a key"));
    }

    @Test
    public void base64IsWrittenAsBrowsersWriteIt() {
        assertEquals("", GokapiClient.Base64.encode(new byte[0]));
        assertEquals("Zg==", GokapiClient.Base64.encode("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm8=", GokapiClient.Base64.encode("fo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9v", GokapiClient.Base64.encode("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("+/8=", GokapiClient.Base64.encode(new byte[]{(byte) 0xfb, (byte) 0xff}));
        byte[] random = new byte[1000];
        new SecureRandom().nextBytes(random);
        for (int length = 0; length < 50; length++) {
            byte[] part = Arrays.copyOf(random, length);
            assertArrayEquals(part, GokapiClient.Base64.decode(GokapiClient.Base64.encode(part)));
        }
        try {
            GokapiClient.Base64.decode("a*b=");
            fail();
        } catch (IllegalArgumentException expected) {
            // Not base64.
        }
    }

    @Test
    public void theLinksSecretIsWhatTheBetterbirdAddOnWrites() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++)
            key[i] = (byte) (i * 7 + 1);
        // From the add-on's lib/gokapi.js linkSecret, for the same name and key.
        assertEquals("eyJmIjoiUGxhbnMgMjAyNyDigJMgR3LDvMOfZS5wZGYiLCJjIjoiQVFnUEZoMGtLekk1UUVkT1ZWeGphbkY0ZjRhTmxKdWlxYkMzdnNYTTA5bz0ifQ==",
                GokapiClient.linkSecret("Plans 2027 – Grüße.pdf", key));
    }

    @Test
    public void namesAreEscapedAsJavaScriptsJsonStringifyEscapesThem() {
        String name = "a\"b\\c\b\f\n\r\t\u0001\u001f –😀\ud800x\u007f/";
        // Node's JSON.stringify of the same string, as base64.
        assertEquals("ImFcImJcXGNcYlxmXG5cclx0XHUwMDAxXHUwMDFm4oCo4oCT8J+YgFx1ZDgwMHh/LyI=",
                GokapiClient.Base64.encode(GokapiClient.jsonString(name).getBytes(StandardCharsets.UTF_8)));
    }

    // --- Uploading ------------------------------------------------------------------------------------------------

    @Test
    public void aFileIsEncryptedUploadedInChunksAndOnlyTheLinkHoldsItsNameAndKey() throws Exception {
        FakeGokapi gokapi = new FakeGokapi(Collections.singletonList(EARLIER));
        byte[] data = GokapiSioTest.data((int) (2.5 * MB));
        Steps steps = new Steps();
        GokapiClient.Upload result = upload(gokapi, data, "Plans 2027.pdf", E2E_KEY, 5, 3, steps);

        Completed stored = gokapi.completed.get(0);
        assertEquals("file1", result.id);
        assertTrue(result.encrypted);
        assertTrue(result.url, result.url.matches("^https://drop\\.example/d\\?id=file1#[A-Za-z0-9+/=]+$"));
        JSONObject secret = linkSecret(result.url);
        assertEquals("Plans 2027.pdf", secret.getString("f"));
        byte[] fileKey = GokapiClient.Base64.decode(secret.getString("c"));
        assertArrayEquals(data, GokapiSioTest.sioDecrypt(stored.data, fileKey));

        // As Gokapi's own uploader completes an encrypted upload.
        assertEquals("Encrypted File", stored.headers.get("filename"));
        assertEquals(Long.toString(GokapiSio.encryptedSize(data.length)), stored.headers.get("filesize"));
        assertEquals(Integer.toString(data.length), stored.headers.get("realsize"));
        assertEquals("true", stored.headers.get("isE2E"));
        assertEquals("5", stored.headers.get("allowedDownloads"));
        assertEquals("3", stored.headers.get("expiryDays"));
        assertEquals("application/octet-stream", stored.headers.get("contenttype"));
        assertEquals(30, stored.uuid.length());
        assertEquals("split into the server's chunk size", 3, gokapi.chunks.get(stored.uuid).size());
        assertEquals(GokapiSio.encryptedSize(data.length), steps.total);
        assertEquals(steps.total, steps.done);

        // Added to your list of encrypted files, after the ones already there, so Gokapi's page shows its name.
        assertEquals(Arrays.asList(EARLIER, new GokapiGob.File(stored.uuid, "file1", "Plans 2027.pdf", fileKey)), openFileList(gokapi.info, E2E_KEY));
        assertEquals(2, gokapi.locks);
        assertEquals(2, gokapi.unlocks);
        assertFalse(gokapi.locked);

        // Neither the key nor the file's name is ever sent in the clear, and the API key only to this server.
        StringBuilder sent = new StringBuilder();
        for (Request request : gokapi.requests) {
            assertTrue(request.url, request.url.startsWith(SERVER + "/api/"));
            sent.append(request.headers);
            if (request.body != null)
                sent.append(new String(request.body, StandardCharsets.ISO_8859_1));
        }
        assertFalse(sent.toString().contains("Plans 2027"));
        assertFalse(sent.toString().contains(secret.getString("c")));
        assertFalse(sent.toString().contains(new String(fileKey, StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void onAReleasedGokapiWhichCantHoldTheListItGoesAheadWithout() throws Exception {
        FakeGokapi gokapi = new FakeGokapi(Collections.singletonList(EARLIER));
        gokapi.mutex = false;
        assertTrue(gokapi.client().check(E2E_KEY).e2e);
        GokapiClient.Upload result = upload(gokapi, GokapiSioTest.data(10), "a.txt");
        List<GokapiGob.File> files = openFileList(gokapi.info, E2E_KEY);
        assertEquals("Earlier.zip", files.get(0).filename);
        assertEquals("a.txt", files.get(1).filename);
        assertEquals("a.txt", linkSecret(result.url).getString("f"));
        assertFalse("nothing to let go of", gokapi.requested("/api/e2e/mutex/unlock"));
    }

    @Test
    public void anEmptyFileAndUnlimitedDownloadsWorkToo() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        GokapiClient.Upload result = upload(gokapi, new byte[0], "empty.txt", E2E_KEY, 0, 30, new Steps());
        Completed stored = gokapi.completed.get(0);
        assertArrayEquals(new byte[0], GokapiSioTest.sioDecrypt(stored.data, GokapiClient.Base64.decode(linkSecret(result.url).getString("c"))));
        assertEquals("0", stored.headers.get("allowedDownloads"));
        assertEquals("30", stored.headers.get("expiryDays"));
    }

    @Test
    public void onAServerWithoutEndToEndEncryptionTheFileGoesAsItIsWithItsName() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        gokapi.e2e = false;
        byte[] data = GokapiSioTest.data(3 * MB);
        GokapiClient.Upload result = gokapi.client().upload(new ByteArrayInputStream(data), data.length, "Grüße.txt", "text/plain", null, 5, 3, new Steps());
        Completed stored = gokapi.completed.get(0);
        assertEquals("https://drop.example/d?id=file1", result.url);
        assertFalse(result.encrypted);
        assertArrayEquals(data, stored.data);
        assertEquals("base64:" + GokapiClient.Base64.encode("Grüße.txt".getBytes(StandardCharsets.UTF_8)), stored.headers.get("filename"));
        assertEquals("false", stored.headers.get("isE2E"));
        assertEquals("text/plain", stored.headers.get("contenttype"));
        assertEquals(0, gokapi.locks);

        // A type that isn't one, from another app, never gets into the header.
        gokapi.client().upload(new ByteArrayInputStream(data), data.length, "a.txt", "text/plain\r\nid: x", null, 5, 3, null);
        assertEquals("application/octet-stream", gokapi.completed.get(1).headers.get("contenttype"));
    }

    @Test
    public void withAnEndToEndKeyNothingIsUploadedUnencryptedWhateverTheServerSays() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        gokapi.e2e = false;
        assertFails("wouldn't be encrypted", () -> upload(gokapi, GokapiSioTest.data(10), "a.txt"));
        assertFails("wouldn't be encrypted", () -> gokapi.client().check(E2E_KEY));
        assertTrue(gokapi.chunks.isEmpty());
    }

    @Test
    public void nothingIsUploadedWithAWrongOrMissingEndToEndKeyOrBeforeItsSetUp() throws Exception {
        FakeGokapi wrong = new FakeGokapi();
        assertFails("end-to-end key doesn't match", () -> upload(wrong, GokapiSioTest.data(10), "a.txt", new byte[32], 5, 3, null));
        assertTrue(wrong.chunks.isEmpty());
        assertFalse(wrong.locked);

        FakeGokapi missing = new FakeGokapi();
        assertFails("enter your end-to-end key", () -> upload(missing, GokapiSioTest.data(10), "a.txt", null, 5, 3, null));
        assertTrue(missing.chunks.isEmpty());

        FakeGokapi notSetUp = new FakeGokapi();
        notSetUp.info = new JSONObject().put("version", 0).put("nonce", JSONObject.NULL).put("content", JSONObject.NULL);
        assertFails("isn't set up yet", () -> upload(notSetUp, GokapiSioTest.data(10), "a.txt"));
        assertTrue(notSetUp.chunks.isEmpty());
        assertFalse(notSetUp.locked);
    }

    @Test
    public void aListOfFilesThatCantBeReadIsSaidSoAndNeverOverwritten() throws Exception {
        // Sealed with the right key, but not a gob of files.
        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(E2E_KEY, "AES"), new GCMParameterSpec(128, nonce));
        byte[] content = cipher.doFinal("not a gob".getBytes(StandardCharsets.UTF_8));
        String info = new JSONObject().put("version", 1).put("nonce", GokapiClient.Base64.encode(nonce)).put("content", GokapiClient.Base64.encode(content)).toString();
        FakeGokapi gokapi = new FakeGokapi();
        gokapi.fail = path -> path.equals("/api/e2e/get") ? new ExtHttp.Response(200, info) : null;
        assertFails("list of encrypted files on the server can't be read", () -> upload(gokapi, GokapiSioTest.data(10), "a.txt"));
        assertTrue(gokapi.chunks.isEmpty());
        assertFalse(gokapi.requested("/api/e2e/set"));
        assertFalse(gokapi.locked);
    }

    @Test
    public void theServersAnswersAreExplained() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        assertFails("didn't accept the API key", () -> gokapi.client("wrong").upload(new ByteArrayInputStream(new byte[10]), 10, "a", null, E2E_KEY, 5, 3, null));

        FakeGokapi small = new FakeGokapi();
        small.maxSize = 1;
        assertFails("too big for the Gokapi server, which takes up to 1 MB", () -> upload(small, GokapiSioTest.data(2 * MB), "a.txt"));

        FakeGokapi proxy = new FakeGokapi();
        proxy.fail = path -> path.equals("/api/chunk/add") ? new ExtHttp.Response(413, "") : null;
        assertFails("or for a proxy in front of it", () -> upload(proxy, GokapiSioTest.data(10), "a.txt"));

        FakeGokapi failing = new FakeGokapi();
        failing.fail = path -> path.equals("/api/chunk/complete") ? new ExtHttp.Response(400, "{\"Result\":\"error\",\"ErrorMessage\":\"x\"}") : null;
        assertFails("turned the request down (error 400): x", () -> upload(failing, GokapiSioTest.data(10), "a.txt"));

        GokapiClient unreachable = new GokapiClient(SERVER, API_KEY, (method, url, headers, body, sending) -> {
            throw new IOException("Can't reach the Gokapi server: no network");
        });
        assertFails("Can't reach the Gokapi server", () -> unreachable.check(E2E_KEY));

        FakeGokapi elsewhere = new FakeGokapi();
        elsewhere.fail = path -> path.equals("/api/chunk/complete") ? new ExtHttp.Response(200, "{\"FileInfo\":{\"Id\":\"a\",\"UrlDownload\":\"javascript:alert(1)\"}}") : null;
        assertFails("isn't an https link", () -> upload(elsewhere, GokapiSioTest.data(10), "a.txt"));

        FakeGokapi unencrypted = new FakeGokapi();
        unencrypted.fail = path -> path.equals("/api/chunk/complete") ? new ExtHttp.Response(200, "{\"FileInfo\":{\"Id\":\"a\",\"UrlDownload\":\"http://drop.example/d?id=a\"}}") : null;
        assertFails("isn't an https link", () -> upload(unencrypted, GokapiSioTest.data(10), "a.txt"));
    }

    @Test
    public void ifTheFilesNameCantBeAddedToTheListTheLinkStillWorksAndIsGiven() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        gokapi.fail = path -> path.equals("/api/e2e/set") ? new ExtHttp.Response(500, "") : null;
        GokapiClient.Upload result = upload(gokapi, GokapiSioTest.data(10), "a.txt");
        assertEquals("a.txt", linkSecret(result.url).getString("f"));
        assertFalse(gokapi.locked);
    }

    @Test
    public void stoppingAnUploadStopsItAndLetsGoOfTheListOfFiles() throws Exception {
        Steps steps = new Steps();
        FakeGokapi gokapi = new FakeGokapi();
        gokapi.fail = path -> {
            if (path.equals("/api/e2e/get"))
                steps.stopped = true;
            return null;
        };
        try {
            upload(gokapi, GokapiSioTest.data(10), "a.txt", E2E_KEY, 5, 3, steps);
            fail();
        } catch (GokapiClient.GokapiException ex) {
            assertTrue(ex.stopped);
        }
        assertTrue(gokapi.chunks.isEmpty());
        assertFalse(gokapi.locked);
    }

    @Test
    public void stoppingOnceTheFileIsUploadedDeletesItAsNobodyGetsItsLink() throws Exception {
        Steps steps = new Steps();
        FakeGokapi gokapi = new FakeGokapi();
        gokapi.fail = path -> {
            if (path.equals("/api/e2e/mutex/lock") && !gokapi.completed.isEmpty())
                steps.stopped = true;
            return null;
        };
        try {
            upload(gokapi, GokapiSioTest.data(10), "a.txt", E2E_KEY, 5, 3, steps);
            fail();
        } catch (GokapiClient.GokapiException ex) {
            assertTrue(ex.stopped);
        }
        assertEquals(Collections.singletonList("file1"), gokapi.deleted);
        assertFalse(gokapi.locked);
    }

    @Test
    public void stoppingWhileAChunkIsSentIsReportedAsStopped() throws Exception {
        Steps steps = new Steps();
        // As ExtHttp does: what sending throws ends the request, and comes back as a failed connection.
        GokapiClient client = new GokapiClient(SERVER, API_KEY, new GokapiClient.Transport() {
            final FakeGokapi gokapi = new FakeGokapi();

            @Override
            public ExtHttp.Response send(String method, String url, Map<String, String> headers, byte[] body, ExtHttp.Sending sending) throws IOException {
                if (url.endsWith("/chunk/add")) {
                    steps.stopped = true;
                    try {
                        sending.sent(1);
                    } catch (IOException ex) {
                        throw new IOException("Can't reach the Gokapi server: " + ex.getMessage());
                    }
                }
                return gokapi.send(method, url, headers, body, sending);
            }
        });
        try {
            client.upload(new ByteArrayInputStream(new byte[10]), 10, "a.txt", null, E2E_KEY, 5, 3, steps);
            fail();
        } catch (GokapiClient.GokapiException ex) {
            assertTrue(ex.stopped);
        }
    }

    @Test
    public void aFileThatChangedWhileUploadingIsRefused() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        assertFails("changed", () -> gokapi.client().upload(new ByteArrayInputStream(new byte[10]), 20, "a", null, E2E_KEY, 5, 3, null));
        assertFails("changed", () -> gokapi.client().upload(new ByteArrayInputStream(new byte[30]), 20, "a", null, E2E_KEY, 5, 3, null));
        assertTrue(gokapi.completed.isEmpty());
    }

    @Test
    public void checkingTheSettingsUploadsNothing() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        GokapiClient.Config config = gokapi.client().check(E2E_KEY);
        assertEquals(100L * MB, config.maxSize);
        assertEquals(MB, config.chunkSize);
        assertTrue(config.e2e);
        assertFails("enter your end-to-end key", () -> gokapi.client().check(null));
        assertFails("doesn't match", () -> gokapi.client().check(new byte[32]));
        assertTrue(gokapi.chunks.isEmpty());
        assertFalse(gokapi.locked);
    }

    // --- Deleting -------------------------------------------------------------------------------------------------

    @Test
    public void aFileIsDeletedWithItsIdAndOneThatsAlreadyGoneCountsAsDeleted() throws Exception {
        FakeGokapi gokapi = new FakeGokapi();
        gokapi.client().delete("file9");
        assertEquals(Collections.singletonList("file9"), gokapi.deleted);
        assertEquals("DELETE", gokapi.requests.get(0).method);

        FakeGokapi gone = new FakeGokapi();
        gone.fail = path -> new ExtHttp.Response(404, "");
        gone.client().delete("file9");

        assertFails("didn't accept the API key", () -> gokapi.client("wrong").delete("file9"));
    }
}
