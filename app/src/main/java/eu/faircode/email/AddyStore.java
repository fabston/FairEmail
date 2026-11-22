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

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The addy.io extension's settings, and a short-lived copy of your account details, kept in FairEmail's settings. */
public class AddyStore {
    static final String PREF_KEY = "ext_addy_apikey";
    static final String PREF_URL = "ext_addy_url";
    static final String PREF_GUARD = "ext_addy_guard";
    static final String PREF_WARN_DIRECT = "ext_addy_warn_direct";
    static final String PREF_TRUSTED = "ext_addy_trusted";
    private static final String PREF_ACCOUNT = "ext_addy_account";
    private static final String PREF_CONFIRMED = "ext_addy_confirmed";
    private static final long ACCOUNT_FRESH_MS = 60 * 60 * 1000L;

    static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    static boolean isSetUp(Context context) {
        return !TextUtils.isEmpty(prefs(context).getString(PREF_KEY, null));
    }

    static boolean guardEnabled(Context context) {
        return prefs(context).getBoolean(PREF_GUARD, true);
    }

    static boolean warnDirect(Context context) {
        return prefs(context).getBoolean(PREF_WARN_DIRECT, true);
    }

    static String baseUrl(Context context) {
        String url = AddyClient.normalizeBaseUrl(prefs(context).getString(PREF_URL, null));
        return url == null ? AddyClient.DEFAULT_BASE_URL : url;
    }

    static List<String> trusted(Context context) {
        String text = prefs(context).getString(PREF_TRUSTED, "");
        return AddyAddresses.cleanTrustedRecipients(Arrays.asList(text.split("\\n")));
    }

    static AddyClient client(Context context) {
        return new AddyClient(context, baseUrl(context), prefs(context).getString(PREF_KEY, null));
    }

    /** Settings changed, so what's known about the account may no longer be right. */
    static void forget(Context context) {
        aliasesChanged();
        prefs(context).edit().remove(PREF_ACCOUNT).remove(PREF_CONFIRMED).apply();
    }

    private static List<String> strings(JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array != null)
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, null);
                // Only domains that look like domains, as the send check relies on them.
                if (value != null && value.matches("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+$"))
                    list.add(value);
            }
        return list;
    }

    private static AddyAccount fromJson(JSONObject json) {
        AddyAccount account = new AddyAccount();
        account.fetchedAt = json.optLong("fetchedAt");
        account.username = json.optString("username");
        account.subscription = json.isNull("subscription") ? null : json.optString("subscription", null);
        account.freePlan = json.optBoolean("freePlan");
        account.domains = strings(json.optJSONArray("domains"));
        account.sharedDomains = strings(json.optJSONArray("sharedDomains"));
        account.defaultDomain = json.optString("defaultDomain");
        account.defaultFormat = json.optString("defaultFormat", "random_words");
        JSONArray recipients = json.optJSONArray("recipients");
        if (recipients != null)
            for (int i = 0; i < recipients.length(); i++) {
                JSONObject recipient = recipients.optJSONObject(i);
                String email = recipient == null ? null : recipient.optString("email", null);
                if (email != null && email.contains("@"))
                    account.recipients.add(new AddyAccount.Recipient(email,
                            recipient.optBoolean("can_reply_send", true),
                            !recipient.isNull("email_verified_at")));
            }
        return account;
    }

    private static JSONObject toJson(AddyAccount account) throws org.json.JSONException {
        JSONObject json = new JSONObject();
        json.put("fetchedAt", account.fetchedAt);
        json.put("username", account.username);
        json.put("subscription", account.subscription == null ? JSONObject.NULL : account.subscription);
        json.put("freePlan", account.freePlan);
        json.put("domains", new JSONArray(account.domains));
        json.put("sharedDomains", new JSONArray(account.sharedDomains));
        json.put("defaultDomain", account.defaultDomain);
        json.put("defaultFormat", account.defaultFormat);
        JSONArray recipients = new JSONArray();
        for (AddyAccount.Recipient recipient : account.recipients) {
            JSONObject r = new JSONObject();
            r.put("email", recipient.email);
            r.put("can_reply_send", recipient.canReplySend);
            r.put("email_verified_at", recipient.verified ? "yes" : JSONObject.NULL);
            recipients.put(r);
        }
        json.put("recipients", recipients);
        return json;
    }

    private static AddyAccount saved(Context context) {
        String text = prefs(context).getString(PREF_ACCOUNT, null);
        if (text == null)
            return null;
        try {
            return fromJson(new JSONObject(text));
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Your verified addresses, domains and plan. Uses the saved copy while it's fresh, and an old copy if addy.io
     * can't be reached, as the send check shouldn't depend on the network. Null if there's no key. Runs on a
     * background thread.
     */
    static AddyAccount getAccount(Context context, boolean refresh) throws IOException {
        if (!isSetUp(context))
            return null;
        AddyAccount saved = saved(context);
        if (saved != null && !refresh && System.currentTimeMillis() - saved.fetchedAt < ACCOUNT_FRESH_MS)
            return saved;
        AddyClient client = client(context);
        try {
            JSONObject details = client.accountDetails();
            JSONObject options = client.domainOptions();
            JSONArray recipients = client.recipients();
            JSONObject json = new JSONObject();
            json.put("fetchedAt", System.currentTimeMillis());
            json.put("username", details == null ? "" : details.optString("username"));
            Object subscription = details == null ? null : details.opt("subscription");
            json.put("subscription", subscription == null || subscription == JSONObject.NULL ? JSONObject.NULL : subscription.toString());
            // Shared domains are listed separately, and the main list leaves out any you've hidden.
            Set<String> domains = new LinkedHashSet<>(strings(options == null ? null : options.optJSONArray("data")));
            domains.addAll(strings(options == null ? null : options.optJSONArray("sharedDomains")));
            json.put("domains", new JSONArray(domains));
            json.put("sharedDomains", options == null ? new JSONArray() : options.optJSONArray("sharedDomains"));
            json.put("defaultDomain", options == null ? "" : options.optString("defaultAliasDomain"));
            json.put("defaultFormat", options == null ? "random_words" : options.optString("defaultAliasFormat", "random_words"));
            json.put("recipients", recipients == null ? new JSONArray() : recipients);
            AddyAccount fresh = fromJson(json);
            fresh.freePlan = fresh.subscription == null && AddyClient.DEFAULT_BASE_URL.equals(baseUrl(context));
            prefs(context).edit().putString(PREF_ACCOUNT, toJson(fresh).toString()).apply();
            return fresh;
        } catch (org.json.JSONException ex) {
            if (saved != null)
                return saved;
            throw new AddyClient.AddyException("Unexpected response from addy.io");
        } catch (IOException ex) {
            if (saved != null)
                return saved;
            throw ex;
        }
    }

    private static List<AddyClient.Alias> aliasCache;
    private static long aliasesFetchedAt;
    private static final long ALIASES_FRESH_MS = 5 * 60 * 1000L;

    /** All your aliases, deleted ones included. Kept in memory for 5 minutes, and never written to storage. */
    static synchronized List<AddyClient.Alias> aliases(Context context, boolean refresh) throws IOException {
        if (aliasCache != null && !refresh && System.currentTimeMillis() - aliasesFetchedAt < ALIASES_FRESH_MS)
            return new ArrayList<>(aliasCache);
        List<AddyClient.Alias> fresh = client(context).allAliases();
        List<String> emails = new ArrayList<>();
        for (AddyClient.Alias alias : fresh)
            emails.add(alias.email);
        // Listed by addy.io for your key, so these are yours.
        rememberOwnAliases(context, emails);
        aliasCache = fresh;
        aliasesFetchedAt = System.currentTimeMillis();
        return new ArrayList<>(fresh);
    }

    /**
     * Creates an alias named localPart, or a random one in format if there's no name. It's then known to be yours,
     * and the list of aliases is fetched again.
     */
    static AddyClient.Alias createAlias(Context context, String domain, String format, String localPart, String description) throws IOException {
        AddyClient.Alias alias = client(context).createAlias(
                domain, TextUtils.isEmpty(localPart) ? format : "custom", localPart, description);
        rememberOwnAliases(context, Collections.singletonList(alias.email));
        aliasesChanged();
        return alias;
    }

    /** An alias was created or changed, so the list has to be fetched again. */
    static synchronized void aliasesChanged() {
        aliasCache = null;
    }

    /** Aliases addy.io has confirmed are yours, kept until your settings change. */
    static List<String> confirmed(Context context) {
        List<String> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(PREF_CONFIRMED, "[]"));
            for (int i = 0; i < array.length(); i++)
                list.add(array.getString(i));
        } catch (Throwable ignored) {
        }
        return list;
    }

    static void rememberOwnAliases(Context context, List<String> aliases) {
        if (aliases.isEmpty())
            return;
        Set<String> all = new LinkedHashSet<>(confirmed(context));
        for (String alias : aliases)
            all.add(AddyAddresses.lower(alias));
        prefs(context).edit().putString(PREF_CONFIRMED, new JSONArray(all).toString()).apply();
    }

    /** Ask addy.io about any address that could be anyone's alias, so it only counts as yours if it is. */
    static List<String> confirmAliases(Context context, List<String> candidates) throws IOException {
        List<String> known = confirmed(context);
        List<String> found = new ArrayList<>();
        AddyClient client = client(context);
        for (String candidate : candidates) {
            if (known.contains(AddyAddresses.lower(candidate)))
                continue;
            AddyClient.Alias alias = client.findAlias(candidate);
            if (alias != null)
                found.add(alias.email);
        }
        rememberOwnAliases(context, found);
        return confirmed(context);
    }
}
