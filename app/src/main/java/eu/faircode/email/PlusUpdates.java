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

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FairEmail Plus's own update check. FairEmail's only works for FairEmail's own builds, so builds from this fork's
 * GitHub workflow look for newer releases of the repository they were built from instead. It shares FairEmail's own
 * settings for whether to check and when it last did, and checks as often as FairEmail does for its GitHub builds.
 * Builds made anywhere else never check.
 */
public class PlusUpdates {
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    // The workflow names each release plus-<FairEmail's version>-<its run number>.
    private static final Pattern TAG = Pattern.compile("^plus-(.+)-(\\d+)$");
    private static final int PI_DOWNLOAD = 0x7e5701;
    private static final int PI_RELEASE = 0x7e5702;

    private static class Release {
        String name;
        String page;
        String apk;
    }

    /** Whether this build came from the GitHub workflow, and so can check for newer ones. */
    static boolean isAvailable() {
        return REPOSITORY.matcher(BuildConfig.PLUS_REPOSITORY).matches() && BuildConfig.PLUS_BUILD.matches("\\d+");
    }

    /** Whether a release, by its tag, was built after the build with this run number. */
    static boolean isNewer(String tag, String installedBuild) {
        Matcher matcher = TAG.matcher(tag == null ? "" : tag);
        try {
            return matcher.matches() && Long.parseLong(matcher.group(2)) > Long.parseLong(installedBuild);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Checks for a newer release, as FairEmail's own check would: when the app opens, at most every few hours, or
     * straight away when asked. True if this build checks for itself, so FairEmail's own check is skipped.
     * FairEmail's weekly setting isn't used, as FairEmail hides it in builds it didn't sign.
     */
    static boolean check(AppCompatActivity activity, boolean always) {
        if (!isAvailable())
            return false;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean updates = prefs.getBoolean("updates", true);
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_update_check", 0);
        if (!always && (!updates || last + ActivityView.UPDATE_DAILY > now))
            return true;
        prefs.edit().putLong("last_update_check", now).apply();

        Bundle args = new Bundle();
        args.putBoolean("always", always);
        new SimpleTask<Release>() {
            @Override
            protected Release onExecute(Context context, Bundle args) throws Throwable {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Accept", "application/vnd.github+json");
                ExtHttp.Response response = ExtHttp.send(context, "GitHub", "GET",
                        "https://api.github.com/repos/" + BuildConfig.PLUS_REPOSITORY + "/releases/latest",
                        headers, null, null, 20);
                if (response.status == 404)
                    return null; // no release yet
                if (!response.ok())
                    throw new IOException("GitHub returned HTTP " + response.status);

                JSONObject json = new JSONObject(response.text);
                if (!isNewer(json.optString("tag_name"), BuildConfig.PLUS_BUILD))
                    return null;
                Release release = new Release();
                release.name = json.optString("name", json.optString("tag_name"));
                release.page = github(json.optString("html_url"));
                JSONArray assets = json.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && asset.optString("name").endsWith(".apk")) {
                        release.apk = github(asset.optString("browser_download_url"));
                        break;
                    }
                }
                return release.page == null ? null : release;
            }

            @Override
            protected void onExecuted(Bundle args, Release release) {
                if (release == null) {
                    if (args.getBoolean("always"))
                        ToastEx.makeText(activity, R.string.title_no_update, Toast.LENGTH_LONG).show();
                    return;
                }
                showNotification(activity, release);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                if (args.getBoolean("always"))
                    ToastEx.makeText(activity, Log.formatThrowable(ex, false), Toast.LENGTH_LONG).show();
            }
        }.execute(activity, args, "plus:update");
        return true;
    }

    /** A link from GitHub's answer, only if it goes to GitHub itself, so nothing else can be offered as an update. */
    private static String github(String url) {
        return url != null && url.startsWith("https://github.com/") ? url : null;
    }

    /** The same notification as FairEmail's own update check: tap for the release, or download the APK straight away. */
    private static void showNotification(Context context, Release release) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "update")
                .setSmallIcon(R.drawable.baseline_get_app_white_24)
                .setContentTitle("FairEmail Plus update available")
                .setContentText(release.name)
                .setAutoCancel(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(PendingIntentCompat.getActivity(context, PI_RELEASE,
                        new Intent(Intent.ACTION_VIEW, Uri.parse(release.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT));
        if (release.apk != null)
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.twotone_cloud_download_24,
                    context.getString(R.string.title_download),
                    PendingIntentCompat.getActivity(context, PI_DOWNLOAD,
                            new Intent(Intent.ACTION_VIEW, Uri.parse(release.apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_UPDATE_CURRENT)).build());
        try {
            NotificationManager nm = Helper.getSystemService(context, NotificationManager.class);
            if (NotificationHelper.areNotificationsEnabled(nm))
                nm.notify(NotificationHelper.NOTIFICATION_UPDATE, builder.build());
        } catch (Throwable ex) {
            Log.w(ex);
        }
    }
}
