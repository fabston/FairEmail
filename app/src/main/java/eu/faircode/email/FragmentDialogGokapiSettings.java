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

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/**
 * Gokapi's settings: the Gokapi server, its API key and your end-to-end key, and how many downloads and days a
 * link gets unless you choose otherwise. Saving checks both keys with the server, without uploading anything.
 */
public class FragmentDialogGokapiSettings extends FragmentDialogBase {
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ext_gokapi_settings, null);
        EditText etServer = view.findViewById(R.id.etServer);
        EditText etApiKey = view.findViewById(R.id.etApiKey);
        EditText etE2eKey = view.findViewById(R.id.etE2eKey);
        Spinner spDownloads = view.findViewById(R.id.spDownloads);
        Spinner spDays = view.findViewById(R.id.spDays);
        TextView tvResult = view.findViewById(R.id.tvResult);

        ExtHooks.setDropdown(spDownloads, GokapiStore.downloadLabels());
        ExtHooks.setDropdown(spDays, GokapiStore.dayLabels());
        if (savedInstanceState == null) {
            etServer.setText(GokapiStore.server(context));
            etApiKey.setText(GokapiStore.apiKey(context));
            etE2eKey.setText(GokapiStore.e2eKeyText(context));
            spDownloads.setSelection(GokapiStore.DOWNLOADS.indexOf(GokapiStore.downloads(context)));
            spDays.setSelection(GokapiStore.DAYS.indexOf(GokapiStore.days(context)));
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.ext_gokapi_title)
                .setView(view)
                .setPositiveButton(R.string.ext_addy_save_test, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // Stays open after saving, to show whether the keys work.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String server = GokapiClient.normalizeServer(etServer.getText().toString());
            if (server == null) {
                tvResult.setText("Enter your Gokapi server's address, starting with https://");
                etServer.requestFocus();
                return;
            }
            String apiKey = etApiKey.getText().toString().trim();
            String e2eText = etE2eKey.getText().toString().trim();
            byte[] e2eKey = GokapiClient.parseE2eKey(e2eText);
            if (!e2eText.isEmpty() && e2eKey == null) {
                tvResult.setText("That isn't an end-to-end key: Gokapi's are 44 characters, ending in =");
                etE2eKey.requestFocus();
                return;
            }
            GokapiStore.prefs(context).edit()
                    .putString(GokapiStore.PREF_SERVER, server)
                    .putString(GokapiStore.PREF_API_KEY, apiKey)
                    .putString(GokapiStore.PREF_E2E_KEY, e2eText)
                    .putInt(GokapiStore.PREF_DOWNLOADS, GokapiStore.DOWNLOADS.get(Math.max(0, spDownloads.getSelectedItemPosition())))
                    .putInt(GokapiStore.PREF_DAYS, GokapiStore.DAYS.get(Math.max(0, spDays.getSelectedItemPosition())))
                    .apply();
            etServer.setText(server);
            if (TextUtils.isEmpty(apiKey)) {
                tvResult.setText("Saved. Enter an API key to upload.");
                return;
            }
            tvResult.setText("Saved. Checking with the server…");
            // Read back from what was just saved, as a task's arguments can end up in FairEmail's log.
            new SimpleTask<GokapiClient.Config>() {
                @Override
                protected GokapiClient.Config onExecute(Context context, Bundle args) throws Throwable {
                    return GokapiStore.client(context).check(GokapiStore.e2eKey(context));
                }

                @Override
                protected void onExecuted(Bundle args, GokapiClient.Config config) {
                    tvResult.setText(config.e2e
                            ? "Saved, and the server accepted both keys. In the compose screen, long-press an attachment, or choose ⋮ → Share a file as a link."
                            : "Saved, and the server accepted the key. It doesn't use end-to-end encryption, so it can read the files.");
                }

                @Override
                protected void onException(Bundle args, Throwable ex) {
                    tvResult.setText("Saved, but: " + ex.getMessage());
                }
            }.execute(this, new Bundle(), "ext:gokapi:test");
        }));
        return dialog;
    }
}
