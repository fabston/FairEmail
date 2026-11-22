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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.mail.internet.InternetAddress;

/**
 * Start an email from an alias, like the Alfred workflow's addsend. Choose who it's to and which alias, in either
 * order. An alias you type that doesn't exist yet can work too, on a catch-all domain.
 */
public class FragmentDialogAddySend extends FragmentDialogBase {
    private AutoCompleteTextView etTo;
    private TextView tvFrom;
    private EditText etSearch;
    private ListView lvAliases;
    private TextView tvError;

    private String chosen;
    private AddyAccount account;
    private List<AddyClient.Alias> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>(); // the aliases listed, in order
    private final List<String> notes = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ext_addy_send, null);
        etTo = view.findViewById(R.id.etTo);
        tvFrom = view.findViewById(R.id.tvFrom);
        etSearch = view.findViewById(R.id.etSearch);
        final ProgressBar pbWait = view.findViewById(R.id.pbWait);
        lvAliases = view.findViewById(R.id.lvAliases);
        tvError = view.findViewById(R.id.tvError);

        chosen = getArguments() == null ? null : getArguments().getString("alias");
        if (savedInstanceState != null)
            chosen = savedInstanceState.getString("chosen", chosen);

        adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                ((TextView) row.findViewById(android.R.id.text2)).setText(notes.get(position));
                return row;
            }
        };
        lvAliases.setAdapter(adapter);
        lvAliases.setOnItemClickListener((parent, v, position, id) -> choose(shown.get(position)));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter();
            }
        });

        // People you've emailed or heard from, as FairEmail remembers them. Found by name or address.
        etTo.setAdapter(new ContactSuggestions(context));

        showFrom();

        new SimpleTask<List<AddyClient.Alias>>() {
            @Override
            protected List<AddyClient.Alias> onExecute(Context context, Bundle args) throws Throwable {
                account = AddyStore.getAccount(context, false);
                return AddyStore.aliases(context, false);
            }

            @Override
            protected void onExecuted(Bundle args, List<AddyClient.Alias> aliases) {
                pbWait.setVisibility(View.GONE);
                all = aliases;
                filter();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                pbWait.setVisibility(View.GONE);
                fail(ex.getMessage());
            }
        }.execute(this, new Bundle(), "ext:addy:send:load");

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.ext_addy_nav_send)
                .setView(view)
                .setPositiveButton(R.string.ext_addy_start_email, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> go()));
        return dialog;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("chosen", chosen);
        super.onSaveInstanceState(outState);
    }

    private void fail(String message) {
        tvError.setText(message);
        tvError.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void showFrom() {
        tvFrom.setText(chosen == null ? "From: choose an alias" : "From: " + chosen);
        int visibility = chosen == null ? View.VISIBLE : View.GONE;
        etSearch.setVisibility(visibility);
        lvAliases.setVisibility(visibility);
        tvFrom.setOnClickListener(chosen == null ? null : v -> {
            // Tapping the alias lets you choose another.
            chosen = null;
            showFrom();
        });
    }

    /** Active aliases matching the search, pinned ones first, and the search itself if it's an alias not on the list. */
    private void filter() {
        String search = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        notes.clear();
        List<AddyClient.Alias> matching = new ArrayList<>();
        for (AddyClient.Alias alias : all)
            if (alias.active && !alias.deleted &&
                    (alias.email.toLowerCase(Locale.ROOT).contains(search) ||
                            (alias.description != null && alias.description.toLowerCase(Locale.ROOT).contains(search))))
                matching.add(alias);
        matching.sort((a, b) -> Boolean.compare(b.pinned, a.pinned));
        List<String> labels = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        for (AddyClient.Alias alias : matching) {
            shown.add(alias.email);
            labels.add((alias.pinned ? "📌 " : "") + alias.email);
            notes.add(alias.description == null ? "" : alias.description);
            listed.add(alias.email.toLowerCase(Locale.ROOT));
        }
        // An alias that doesn't exist yet can still work, as a catch-all domain creates it on the first email.
        if (AddyAddresses.isPlainAddress(search) && !listed.contains(search) && account != null &&
                account.domains.contains(AddyAddresses.domainOf(search)) && !account.sharedDomains.contains(AddyAddresses.domainOf(search))) {
            shown.add(search);
            labels.add(search);
            notes.add("Not one of your aliases yet, so this needs a catch-all domain");
        }
        adapter.clear();
        adapter.addAll(labels);
    }

    private void choose(String alias) {
        chosen = alias;
        fail(null);
        showFrom();
        if (AddyCompose.person(etTo.getText().toString()) != null)
            go();
        else
            etTo.requestFocus();
    }

    private void go() {
        InternetAddress person = AddyCompose.person(etTo.getText().toString());
        if (person == null) {
            fail("Who's it to? Choose a contact or type an address.");
            etTo.requestFocus();
            return;
        }
        if (chosen == null) {
            fail("Choose the alias to send from");
            return;
        }
        AddyCompose.startEmail(requireActivity(), chosen, person);
        dismiss();
    }

    /** Suggestions for who it's to, from FairEmail's own contacts. */
    private static class ContactSuggestions extends ArrayAdapter<String> {
        private final Context context;

        ContactSuggestions(Context context) {
            super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
            this.context = context.getApplicationContext();
        }

        @NonNull
        @Override
        public android.widget.Filter getFilter() {
            return new android.widget.Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    List<String> found = new ArrayList<>();
                    if (!TextUtils.isEmpty(constraint)) {
                        Set<String> unique = new LinkedHashSet<>();
                        for (EntityContact contact : DB.getInstance(context).contact().searchContacts(null, null, "%" + constraint + "%")) {
                            // Not anyone you've blocked.
                            if (contact.email == null || contact.type == EntityContact.TYPE_JUNK)
                                continue;
                            unique.add(TextUtils.isEmpty(contact.name) ? contact.email : contact.name + " <" + contact.email + ">");
                            if (unique.size() >= 20)
                                break;
                        }
                        found.addAll(unique);
                    }
                    results.values = found;
                    results.count = found.size();
                    return results;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    clear();
                    if (results.values != null)
                        addAll((List<String>) results.values);
                    notifyDataSetChanged();
                }
            };
        }
    }
}
