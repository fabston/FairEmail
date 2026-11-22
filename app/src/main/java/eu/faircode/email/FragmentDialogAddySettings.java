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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The addy.io extension's settings. Saving tests the key, which only reads your account. */
public class FragmentDialogAddySettings extends FragmentDialogBase {
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        final SharedPreferences prefs = AddyStore.prefs(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ext_addy_settings, null);
        final EditText etKey = view.findViewById(R.id.etKey);
        final EditText etServer = view.findViewById(R.id.etServer);
        final SwitchCompat swGuard = view.findViewById(R.id.swGuard);
        final SwitchCompat swWarnDirect = view.findViewById(R.id.swWarnDirect);
        final EditText etTrusted = view.findViewById(R.id.etTrusted);
        final TextView tvResult = view.findViewById(R.id.tvResult);

        if (savedInstanceState == null) {
            etKey.setText(prefs.getString(AddyStore.PREF_KEY, ""));
            String server = AddyStore.baseUrl(context);
            etServer.setText(AddyClient.DEFAULT_BASE_URL.equals(server) ? "" : server);
            swGuard.setChecked(AddyStore.guardEnabled(context));
            swWarnDirect.setChecked(AddyStore.warnDirect(context));
            etTrusted.setText(TextUtils.join("\n", AddyStore.trusted(context)));
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("addy.io")
                .setView(view)
                .setPositiveButton(R.string.ext_addy_save_test, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // Stays open after saving, to show whether the key works.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String server = AddyClient.normalizeBaseUrl(etServer.getText().toString());
            if (server == null) {
                tvResult.setText("The server must start with https://");
                return;
            }
            List<String> trusted = AddyAddresses.cleanTrustedRecipients(Arrays.asList(etTrusted.getText().toString().split("\\n")));
            String key = etKey.getText().toString().trim();
            prefs.edit()
                    .putString(AddyStore.PREF_KEY, key)
                    .putString(AddyStore.PREF_URL, server)
                    .putBoolean(AddyStore.PREF_GUARD, swGuard.isChecked())
                    .putBoolean(AddyStore.PREF_WARN_DIRECT, swWarnDirect.isChecked())
                    .putString(AddyStore.PREF_TRUSTED, TextUtils.join("\n", trusted))
                    .apply();
            AddyStore.forget(context);
            etTrusted.setText(TextUtils.join("\n", trusted));
            if (key.isEmpty()) {
                tvResult.setText("Saved. Add an API key to use your aliases.");
                return;
            }
            tvResult.setText("Testing…");
            new SimpleTask<AddyAccount>() {
                @Override
                protected AddyAccount onExecute(Context context, Bundle args) throws Throwable {
                    return AddyStore.getAccount(context, true);
                }

                @Override
                protected void onExecuted(Bundle args, AddyAccount account) {
                    List<String> parts = new ArrayList<>();
                    parts.add("Connected as " + account.username);
                    if (account.subscription != null)
                        parts.add(account.subscription.substring(0, 1).toUpperCase() + account.subscription.substring(1) + " plan");
                    else if (account.freePlan)
                        parts.add("Free plan, which can't send from aliases");
                    List<String> verified = account.verifiedAddresses();
                    parts.add(verified.isEmpty() ? "No verified address that can send" : "Sends from " + TextUtils.join(", ", verified));
                    tvResult.setText(TextUtils.join(" · ", parts));
                }

                @Override
                protected void onException(Bundle args, Throwable ex) {
                    tvResult.setText(ex.getMessage());
                }
            }.execute(this, new Bundle(), "ext:addy:test");
        }));
        return dialog;
    }
}
