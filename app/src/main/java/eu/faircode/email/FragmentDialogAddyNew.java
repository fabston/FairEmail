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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Create an alias, like the Alfred workflow's addnew. With a name it's that name on the chosen domain. Without one,
 * it's random, in the chosen format. The new address is copied.
 */
public class FragmentDialogAddyNew extends FragmentDialogBase {
    private static final List<String> FORMATS = Arrays.asList("random_words", "random_characters", "uuid");
    private static final List<String> FORMAT_NAMES = Arrays.asList("Random words", "Random characters", "UUID");

    private String created;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ext_addy_new, null);
        final EditText etLocal = view.findViewById(R.id.etLocal);
        final EditText etDescription = view.findViewById(R.id.etDescription);
        final Spinner spDomain = view.findViewById(R.id.spDomain);
        final TextView tvFormat = view.findViewById(R.id.tvFormat);
        final Spinner spFormat = view.findViewById(R.id.spFormat);
        final ProgressBar pbWait = view.findViewById(R.id.pbWait);
        final TextView tvCreated = view.findViewById(R.id.tvCreated);
        final TextView tvError = view.findViewById(R.id.tvError);

        AddyCompose.setDropdown(spFormat, FORMAT_NAMES);
        etLocal.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // A format only applies to a random alias.
                int visibility = s.toString().trim().isEmpty() ? View.VISIBLE : View.GONE;
                tvFormat.setVisibility(visibility);
                spFormat.setVisibility(visibility);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.ext_addy_nav_new)
                .setView(view)
                .setPositiveButton(R.string.ext_addy_create_alias, null)
                .setNeutralButton(R.string.ext_addy_send_from_it, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        new SimpleTask<AddyAccount>() {
            @Override
            protected AddyAccount onExecute(Context context, Bundle args) throws Throwable {
                return AddyStore.getAccount(context, false);
            }

            @Override
            protected void onExecuted(Bundle args, AddyAccount account) {
                pbWait.setVisibility(View.GONE);
                if (account == null)
                    return;
                List<String> domains = new ArrayList<>(account.domains);
                if (domains.remove(account.defaultDomain))
                    domains.add(0, account.defaultDomain);
                AddyCompose.setDropdown(spDomain, domains);
                int format = FORMATS.indexOf(account.defaultFormat);
                if (format >= 0)
                    spFormat.setSelection(format);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                pbWait.setVisibility(View.GONE);
                tvError.setText(ex.getMessage());
                tvError.setVisibility(View.VISIBLE);
            }
        }.execute(this, new Bundle(), "ext:addy:new:load");

        dialog.setOnShowListener(d -> {
            Button btnCreate = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button btnSend = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            btnSend.setVisibility(View.GONE);
            btnSend.setOnClickListener(v -> {
                // Sending from the new alias: who it's to comes next.
                Bundle args = new Bundle();
                args.putString("alias", created);
                FragmentDialogAddySend send = new FragmentDialogAddySend();
                send.setArguments(args);
                send.show(getParentFragmentManager(), "ext:addy:send");
                dismiss();
            });
            btnCreate.setOnClickListener(v -> {
                if (created != null) {
                    dismiss();
                    return;
                }
                Object domain = spDomain.getSelectedItem();
                if (domain == null) {
                    tvError.setText("Your addy.io domains haven't loaded yet");
                    tvError.setVisibility(View.VISIBLE);
                    return;
                }
                String local = etLocal.getText().toString().trim();
                Bundle args = new Bundle();
                args.putString("domain", domain.toString());
                args.putString("local", local);
                args.putString("format", FORMATS.get(Math.max(0, spFormat.getSelectedItemPosition())));
                args.putString("description", etDescription.getText().toString().trim());
                btnCreate.setEnabled(false);
                pbWait.setVisibility(View.VISIBLE);
                tvError.setVisibility(View.GONE);
                new SimpleTask<AddyClient.Alias>() {
                    @Override
                    protected AddyClient.Alias onExecute(Context context, Bundle args) throws Throwable {
                        return AddyStore.createAlias(context,
                                args.getString("domain"), args.getString("format"),
                                args.getString("local"), args.getString("description"));
                    }

                    @Override
                    protected void onExecuted(Bundle args, AddyClient.Alias alias) {
                        pbWait.setVisibility(View.GONE);
                        // On shared domains addy.io adds random characters to a name, so this is the real address.
                        created = alias.email;
                        tvCreated.setText(created);
                        tvCreated.setVisibility(View.VISIBLE);
                        AddyCompose.copy(context, "alias", created);
                        btnCreate.setText(android.R.string.ok);
                        btnCreate.setEnabled(true);
                        btnSend.setVisibility(View.VISIBLE);
                        getParentFragmentManager().setFragmentResult(FragmentAddyAliases.RESULT_CHANGED, new Bundle());
                    }

                    @Override
                    protected void onException(Bundle args, Throwable ex) {
                        pbWait.setVisibility(View.GONE);
                        btnCreate.setEnabled(true);
                        tvError.setText(ex.getMessage());
                        tvError.setVisibility(View.VISIBLE);
                    }
                }.execute(FragmentDialogAddyNew.this, args, "ext:addy:new");
            });
        });
        return dialog;
    }
}
