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
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The addy.io API. Requests only ever go to the server in the settings, and never follow a redirect. */
public class AddyClient {
    static final String DEFAULT_BASE_URL = "https://app.addy.io";
    private static final int TIMEOUT = 15; // seconds
    // addy.io turns down a search outside these lengths.
    private static final int MIN_SEARCH = 3;
    private static final int MAX_SEARCH = 50;

    public static class AddyException extends IOException {
        AddyException(String message) {
            super(message);
        }
    }

    public static class Alias {
        public String id;
        public String email;
        public String description;
        public boolean active;
        public boolean pinned;
        public boolean deleted;
        public long forwarded;

        static Alias from(JSONObject json) {
            Alias alias = new Alias();
            alias.id = json.optString("id");
            alias.email = json.optString("email");
            alias.description = json.isNull("description") ? null : json.optString("description");
            alias.active = json.optBoolean("active", true);
            alias.pinned = json.optBoolean("pinned", false);
            alias.deleted = !json.isNull("deleted_at") && !TextUtils.isEmpty(json.optString("deleted_at"));
            alias.forwarded = json.optLong("emails_forwarded", 0);
            return alias;
        }
    }

    private final Context context;
    private final String baseUrl;
    private final String apiKey;

    AddyClient(Context context, String baseUrl, String apiKey) {
        this.context = context;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** Clean up a server address. Returns null unless it's https, or http on this phone. */
    static String normalizeBaseUrl(String value) {
        String text = value == null || value.trim().isEmpty() ? DEFAULT_BASE_URL : value.trim();
        Uri uri = Uri.parse(text);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean local = "http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host));
        if (host.isEmpty() || (!"https".equals(scheme) && !local))
            return null;
        return text.replaceAll("/+$", "");
    }

    private Object request(String method, String path, String query, JSONObject body) throws IOException {
        if (TextUtils.isEmpty(apiKey))
            throw new AddyException("Add your addy.io API key under Settings → Integrations → Extensions");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("X-Requested-With", "XMLHttpRequest");
        ExtHttp.Response response = ExtHttp.send(context, baseUrl, method,
                baseUrl + "/api/v1/" + path + (query == null ? "" : "?" + query),
                headers, body == null ? null : body.toString(), apiKey, TIMEOUT);
        if (!response.ok())
            throw new AddyException(errorMessage(response));
        String text = response.text.trim();
        if (text.isEmpty())
            return null;
        try {
            return text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        } catch (org.json.JSONException ex) {
            throw new AddyException("Unexpected response from " + baseUrl);
        }
    }

    private String errorMessage(ExtHttp.Response response) {
        int status = response.status;
        if (status == 401)
            return "addy.io rejected your API key";
        if (status >= 300 && status < 400)
            return baseUrl + " redirected the request, so it wasn't sent on. Check the server address.";
        try {
            String message = new JSONObject(response.text).optString("message");
            if (!TextUtils.isEmpty(message))
                return message;
        } catch (Throwable ignored) {
            // Not JSON, so fall through to a general message.
        }
        if (status == 429)
            return "Too many requests, or your alias limit is reached";
        return "addy.io returned HTTP " + status;
    }

    private static String query(String... pairs) {
        Uri.Builder builder = new Uri.Builder();
        for (int i = 0; i + 1 < pairs.length; i += 2)
            builder.appendQueryParameter(pairs[i], pairs[i + 1]);
        return builder.build().getEncodedQuery();
    }

    JSONObject accountDetails() throws IOException {
        return ((JSONObject) request("GET", "account-details", null, null)).optJSONObject("data");
    }

    JSONObject domainOptions() throws IOException {
        return (JSONObject) request("GET", "domain-options", null, null);
    }

    JSONArray recipients() throws IOException {
        return ((JSONObject) request("GET", "recipients", null, null)).optJSONArray("data");
    }

    /**
     * Search by email and description, most recently used first. addy.io leaves out deleted aliases and lists
     * pinned ones first. It matches the same way as here, so a search it can't take whole is still narrowed down.
     */
    List<Alias> searchAliases(String search) throws IOException {
        String text = search == null ? "" : search;
        String shortened = text.length() > MAX_SEARCH ? text.substring(0, MAX_SEARCH) : text;
        String q = shortened.length() >= MIN_SEARCH
                ? query("page[size]", "50", "sort", "-last_used", "filter[search]", shortened)
                : query("page[size]", "50", "sort", "-last_used");
        JSONArray data = ((JSONObject) request("GET", "aliases", q, null)).optJSONArray("data");
        List<Alias> found = new ArrayList<>();
        String lowered = text.toLowerCase(Locale.ROOT);
        if (data != null)
            for (int i = 0; i < data.length(); i++) {
                Alias alias = Alias.from(data.optJSONObject(i));
                String haystack = (alias.email + " " + (alias.description == null ? "" : alias.description)).toLowerCase(Locale.ROOT);
                if (haystack.contains(lowered))
                    found.add(alias);
            }
        return found;
    }

    /** Find one alias by its exact address, as searching also matches descriptions and parts of addresses. */
    Alias findAlias(String email) throws IOException {
        for (Alias alias : searchAliases(email))
            if (AddyAddresses.sameAddress(alias.email, email))
                return alias;
        return null;
    }

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    /** Every alias, deleted ones included, newest first. Stops at 2,000, which is far more than a list can show. */
    List<Alias> allAliases() throws IOException {
        List<Alias> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONObject result = (JSONObject) request("GET", "aliases", query(
                    "filter[deleted]", "with", "sort", "-created_at",
                    "page[size]", Integer.toString(PAGE_SIZE), "page[number]", Integer.toString(page)), null);
            JSONArray data = result.optJSONArray("data");
            if (data != null)
                for (int i = 0; i < data.length(); i++) {
                    Alias alias = Alias.from(data.optJSONObject(i));
                    if (alias.email != null && alias.email.contains("@"))
                        all.add(alias);
                }
            JSONObject meta = result.optJSONObject("meta");
            int last = meta == null ? page : meta.optInt("last_page", page);
            if (page >= last)
                break;
        }
        return all;
    }

    private static String path(String collection, String id) {
        return collection + "/" + Uri.encode(id);
    }

    private static JSONObject idBody(String id) throws IOException {
        try {
            return new JSONObject().put("id", id);
        } catch (org.json.JSONException ex) {
            throw new AddyException("Couldn't prepare the request");
        }
    }

    void activate(String id) throws IOException {
        request("POST", "active-aliases", null, idBody(id));
    }

    void deactivate(String id) throws IOException {
        request("DELETE", path("active-aliases", id), null, null);
    }

    void pin(String id) throws IOException {
        request("POST", "pinned-aliases", null, idBody(id));
    }

    void unpin(String id) throws IOException {
        request("DELETE", path("pinned-aliases", id), null, null);
    }

    /** Deleted aliases can be restored, until they're removed for good on addy.io. */
    void delete(String id) throws IOException {
        request("DELETE", path("aliases", id), null, null);
    }

    void restore(String id) throws IOException {
        request("PATCH", path("aliases", id) + "/restore", null, null);
    }

    void setDescription(String id, String description) throws IOException {
        try {
            request("PATCH", path("aliases", id), null, new JSONObject().put("description", TextUtils.isEmpty(description) ? JSONObject.NULL : description));
        } catch (org.json.JSONException ex) {
            throw new AddyException("Couldn't prepare the request");
        }
    }

    Alias createAlias(String domain, String format, String localPart, String description) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("domain", domain);
            body.put("format", format);
            if (!TextUtils.isEmpty(localPart))
                body.put("local_part", localPart);
            if (!TextUtils.isEmpty(description))
                body.put("description", description);
            return Alias.from(((JSONObject) request("POST", "aliases", null, body)).optJSONObject("data"));
        } catch (org.json.JSONException ex) {
            throw new AddyException("Couldn't create the alias");
        }
    }
}
