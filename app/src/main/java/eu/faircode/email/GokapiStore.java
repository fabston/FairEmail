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
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gokapi's settings: the Gokapi server, its API key, your end-to-end key, and how many downloads and days a link
 * gets. Kept in a file of their own, as FairEmail writes its own settings, with their values, to its log, its debug
 * info and its exports, and these hold your keys.
 */
public class GokapiStore {
    private static final String FILE = "ext_gokapi";
    static final String PREF_SERVER = "server";
    static final String PREF_API_KEY = "api_key";
    static final String PREF_E2E_KEY = "e2e_key";
    static final String PREF_DOWNLOADS = "downloads";
    static final String PREF_DAYS = "days";

    // 0 is no limit, as Gokapi takes it.
    static final List<Integer> DOWNLOADS = Arrays.asList(1, 2, 3, 5, 10, 20, 50, 100, 0);
    // Gokapi keeps files for whole days.
    static final List<Integer> DAYS = Arrays.asList(1, 2, 3, 7, 14, 30);
    static final int DEFAULT_DOWNLOADS = 10;
    static final int DEFAULT_DAYS = 3;
    // Each upload's request may take this long to be answered, once it's been sent.
    private static final int TIMEOUT = 60; // seconds

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Your server's address, or null if none is entered. */
    static String server(Context context) {
        return GokapiClient.normalizeServer(prefs(context).getString(PREF_SERVER, ""));
    }

    static String apiKey(Context context) {
        return prefs(context).getString(PREF_API_KEY, "").trim();
    }

    /** The end-to-end key, as text, or empty. */
    static String e2eKeyText(Context context) {
        String text = prefs(context).getString(PREF_E2E_KEY, "").trim();
        return GokapiClient.parseE2eKey(text) == null ? "" : text;
    }

    static byte[] e2eKey(Context context) {
        return GokapiClient.parseE2eKey(e2eKeyText(context));
    }

    static int downloads(Context context) {
        int downloads = prefs(context).getInt(PREF_DOWNLOADS, DEFAULT_DOWNLOADS);
        return DOWNLOADS.contains(downloads) ? downloads : DEFAULT_DOWNLOADS;
    }

    static int days(Context context) {
        int days = prefs(context).getInt(PREF_DAYS, DEFAULT_DAYS);
        return DAYS.contains(days) ? days : DEFAULT_DAYS;
    }

    /**
     * Ready to upload: it has a server and an API key. Whether the server needs an end-to-end key is only known by
     * asking it.
     */
    static boolean isSetUp(Context context) {
        return server(context) != null && !TextUtils.isEmpty(apiKey(context));
    }

    /** A client for the server and API key saved, whose requests never go anywhere else. Only once it's set up. */
    static GokapiClient client(Context context) {
        String server = server(context);
        String apiKey = apiKey(context);
        Context app = context.getApplicationContext();
        return new GokapiClient(server, apiKey, (method, url, headers, body, sending) ->
                ExtHttp.send(app, "the Gokapi server", method, url, headers, body, sending, apiKey, TIMEOUT));
    }

    static List<String> downloadLabels() {
        List<String> labels = new ArrayList<>();
        for (int count : DOWNLOADS)
            labels.add(downloadsText(count));
        return labels;
    }

    static List<String> dayLabels() {
        List<String> labels = new ArrayList<>();
        for (int days : DAYS)
            labels.add(days == 1 ? "1 day" : days + " days");
        return labels;
    }

    static String downloadsText(int count) {
        return count == 0 ? "Unlimited downloads" : count == 1 ? "1 download" : count + " downloads";
    }
}
