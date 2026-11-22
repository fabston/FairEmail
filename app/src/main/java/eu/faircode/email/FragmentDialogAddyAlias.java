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
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.mail.internet.InternetAddress;

/**
 * Choose or create the alias to send from. To is rewritten to the address that makes addy.io send from it, and the
 * identity is switched to one addy.io accepts, as in the Betterbird add-on.
 */
public class FragmentDialogAddyAlias extends FragmentDialogBase {
    private static class Loaded {
        AddyAccount account;
        List<AddyClient.Alias> aliases = new ArrayList<>();
    }

    private TextView tvRoute;
    private EditText etDestination;
    private EditText etSearch;
    private ProgressBar pbWait;
    private ListView lvAliases;
    private EditText etLocal;
    private Spinner spDomain;
    private Button btnCreate;
    private TextView tvError;

    private static final long SEARCH_DELAY = 400; // milliseconds

    private AddyAccount account;
    private final List<AddyClient.Alias> shown = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private final Runnable search = () -> load(etSearch.getText().toString());

    /** Each person in To, as written, with who they really are: name and address, kept in order. */
    private Map<String, String> people() {
        Map<String, String> people = new LinkedHashMap<>();
        String to = getArguments() == null ? "" : getArguments().getString("to", "");
        try {
            List<String> confirmed = AddyStore.confirmed(requireContext());
            for (InternetAddress address : InternetAddress.parse(to == null ? "" : to, false)) {
                String email = address.getAddress();
                AddyAddresses.SendAddress decoded = AddyAddresses.decodeSendAddress(email);
                // Only unwrapped if it's your own alias: someone else's has the same shape, and mail to it goes to them.
                if (decoded != null && AddyAddresses.ownsAlias(decoded.alias, account, confirmed))
                    email = decoded.destination;
                people.put(email, address.getPersonal());
            }
        } catch (Throwable ex) {
            Log.w(ex);
        }
        String typed = etDestination.getText().toString().trim();
        if (!typed.isEmpty())
            people.put(typed, null);
        return people;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ext_addy_alias, null);
        tvRoute = view.findViewById(R.id.tvRoute);
        etDestination = view.findViewById(R.id.etDestination);
        etSearch = view.findViewById(R.id.etSearch);
        pbWait = view.findViewById(R.id.pbWait);
        lvAliases = view.findViewById(R.id.lvAliases);
        etLocal = view.findViewById(R.id.etLocal);
        spDomain = view.findViewById(R.id.spDomain);
        btnCreate = view.findViewById(R.id.btnCreate);
        tvError = view.findViewById(R.id.tvError);

        adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull android.view.ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                AddyClient.Alias alias = shown.get(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText((alias.pinned ? "📌 " : "") + alias.email);
                ((TextView) row.findViewById(android.R.id.text2)).setText(alias.description == null ? "" : alias.description);
                return row;
            }
        };
        lvAliases.setAdapter(adapter);
        lvAliases.setOnItemClickListener((parent, v, position, id) -> use(shown.get(position)));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Each search asks addy.io, so it waits until typing pauses.
                etSearch.removeCallbacks(search);
                etSearch.postDelayed(search, SEARCH_DELAY);
            }
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH || shown.isEmpty())
                return false;
            use(shown.get(0));
            return true;
        });
        btnCreate.setOnClickListener(v -> create());

        load("");
        return new AlertDialog.Builder(context)
                .setTitle(R.string.ext_addy_alias_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    @Override
    public void onDestroyView() {
        etSearch.removeCallbacks(search);
        super.onDestroyView();
    }

    private void fail(String message) {
        tvError.setText(message);
        tvError.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void showRoute() {
        Map<String, String> people = people();
        etDestination.setVisibility(people.isEmpty() || etDestination.getText().length() > 0 ? View.VISIBLE : View.GONE);
        tvRoute.setText(people.isEmpty() ? "" : "To " + TextUtils.join(", ", people.keySet()));
    }

    /** Your aliases for a search, or else for the recipient's domain, or else the ones used most recently. */
    private void load(String search) {
        Bundle args = new Bundle();
        args.putString("search", search);
        String firstDomain = null;
        for (String email : people().keySet()) {
            firstDomain = AddyAddresses.domainOf(email);
            break;
        }
        args.putString("domain", firstDomain);
        pbWait.setVisibility(View.VISIBLE);
        new SimpleTask<Loaded>() {
            @Override
            protected Loaded onExecute(Context context, Bundle args) throws Throwable {
                Loaded loaded = new Loaded();
                loaded.account = AddyStore.getAccount(context, false);
                AddyClient client = AddyStore.client(context);
                String search = args.getString("search", "");
                String domain = args.getString("domain");
                if (search.isEmpty() && domain != null) {
                    loaded.aliases = client.searchAliases(domain);
                    if (loaded.aliases.isEmpty())
                        loaded.aliases = client.searchAliases("");
                } else
                    loaded.aliases = client.searchAliases(search);
                List<String> emails = new ArrayList<>();
                for (AddyClient.Alias alias : loaded.aliases)
                    emails.add(alias.email);
                // Listed by addy.io for your key, so these are yours.
                AddyStore.rememberOwnAliases(context, emails);
                return loaded;
            }

            @Override
            protected void onExecuted(Bundle args, Loaded loaded) {
                pbWait.setVisibility(View.GONE);
                // A newer search may have finished first.
                if (!args.getString("search", "").equals(etSearch.getText().toString()))
                    return;
                fail(null);
                boolean first = account == null;
                account = loaded.account;
                shown.clear();
                for (AddyClient.Alias alias : loaded.aliases)
                    if (alias.active)
                        shown.add(alias);
                // Pinned aliases first, otherwise in the order addy.io gave.
                shown.sort((a, b) -> Boolean.compare(b.pinned, a.pinned));
                List<String> labels = new ArrayList<>();
                for (AddyClient.Alias alias : shown)
                    labels.add(alias.email);
                adapter.clear();
                adapter.addAll(labels);
                if (first && account != null) {
                    List<String> domains = new ArrayList<>(account.domains);
                    if (domains.remove(account.defaultDomain))
                        domains.add(0, account.defaultDomain);
                    AddyCompose.setDropdown(spDomain, domains);
                }
                showRoute();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                pbWait.setVisibility(View.GONE);
                fail(ex.getMessage());
            }
        }.execute(this, args, "ext:addy:load");
    }

    private void create() {
        Map<String, String> people = people();
        if (people.isEmpty()) {
            etDestination.setVisibility(View.VISIBLE);
            fail("Who's it to? Add them in To, or here.");
            return;
        }
        Object domain = spDomain.getSelectedItem();
        if (domain == null) {
            fail("Your addy.io domains haven't loaded yet");
            return;
        }
        Bundle args = new Bundle();
        args.putString("domain", domain.toString());
        args.putString("local", etLocal.getText().toString().trim());
        args.putString("description", AddyAddresses.domainOf(people.keySet().iterator().next()));
        args.putString("format", account == null ? "random_words" : account.defaultFormat);
        btnCreate.setEnabled(false);
        new SimpleTask<AddyClient.Alias>() {
            @Override
            protected AddyClient.Alias onExecute(Context context, Bundle args) throws Throwable {
                return AddyStore.createAlias(context,
                        args.getString("domain"), args.getString("format"),
                        args.getString("local"), args.getString("description"));
            }

            @Override
            protected void onExecuted(Bundle args, AddyClient.Alias alias) {
                use(alias);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                btnCreate.setEnabled(true);
                fail(ex.getMessage());
            }
        }.execute(this, args, "ext:addy:create");
    }

    /** Rewrite To so each person gets the email from this alias, and send from an identity addy.io accepts. */
    private void use(AddyClient.Alias alias) {
        Map<String, String> people = people();
        if (people.isEmpty()) {
            etDestination.setVisibility(View.VISIBLE);
            fail("Who's it to? Add them in To, or here.");
            return;
        }
        List<String> to = new ArrayList<>();
        try {
            for (Map.Entry<String, String> person : people.entrySet()) {
                String encoded = AddyAddresses.encodeSendAddress(alias.email, person.getKey());
                to.add(new InternetAddress(encoded, person.getValue(), "UTF-8").toUnicodeString());
            }
        } catch (Throwable ex) {
            fail(ex.getMessage());
            return;
        }
        Bundle result = new Bundle();
        result.putString("to", TextUtils.join(", ", to));
        result.putStringArrayList("verified", account == null ? new ArrayList<>() : new ArrayList<>(account.verifiedAddresses()));
        getParentFragmentManager().setFragmentResult(ExtHooks.RESULT_COMPOSE, result);
        dismiss();
    }
}
