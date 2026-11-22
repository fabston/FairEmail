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
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Your addy.io aliases, like the Alfred workflow's addy: search, filter, and copy, send from, pin, turn off, edit,
 * delete or restore one. Pinned aliases come first and deleted ones last.
 */
public class FragmentAddyAliases extends FragmentBase {
    static final String RESULT_CHANGED = "ext:addy:changed";
    private static final List<String> FILTERS = Arrays.asList("All", "Active", "Inactive", "Pinned", "Deleted");

    private TextView tvAccount;
    private View cardAccount;
    private TextView tvBandwidth;
    private LinearLayout llStats;
    private EditText etSearch;
    private Spinner spFilter;
    private ProgressBar pbWait;
    private TextView tvEmpty;
    private ListView lvAliases;

    private List<AddyClient.Alias> all = new ArrayList<>();
    private boolean loaded;
    private final List<AddyClient.Alias> shown = new ArrayList<>();
    private ArrayAdapter<AddyClient.Alias> adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        setSubtitle(R.string.ext_addy_nav_aliases);
        View view = inflater.inflate(R.layout.fragment_ext_addy_aliases, container, false);
        tvAccount = view.findViewById(R.id.tvAccount);
        cardAccount = view.findViewById(R.id.cardAccount);
        tvBandwidth = view.findViewById(R.id.tvBandwidth);
        llStats = view.findViewById(R.id.llStats);
        etSearch = view.findViewById(R.id.etSearch);
        spFilter = view.findViewById(R.id.spFilter);
        pbWait = view.findViewById(R.id.pbWait);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        lvAliases = view.findViewById(R.id.lvAliases);
        Context context = view.getContext();

        AddyCompose.setDropdown(spFilter, FILTERS);
        spFilter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                filter();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
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

        adapter = new ArrayAdapter<AddyClient.Alias>(context, android.R.layout.simple_list_item_2, android.R.id.text1, shown) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                AddyClient.Alias alias = shown.get(position);
                String mark = alias.deleted ? "🗑 " : (alias.pinned ? "📌 " : "") + (alias.active ? "" : "⏸ ");
                ((TextView) row.findViewById(android.R.id.text1)).setText(mark + alias.email);
                List<String> details = new ArrayList<>();
                if (alias.deleted)
                    details.add("Deleted");
                else if (!alias.active)
                    details.add("Inactive");
                if (alias.description != null && !alias.description.isEmpty())
                    details.add(alias.description);
                details.add(alias.forwarded + " forwarded");
                ((TextView) row.findViewById(android.R.id.text2)).setText(String.join(" · ", details));
                return row;
            }
        };
        lvAliases.setAdapter(adapter);
        lvAliases.setOnItemClickListener((parent, v, position, id) -> showActions(shown.get(position)));
        lvAliases.setOnItemLongClickListener((parent, v, position, id) -> {
            AddyCompose.copy(v.getContext(), "alias", shown.get(position).email);
            return true;
        });

        view.findViewById(R.id.btnNew).setOnClickListener(v ->
                new FragmentDialogAddyNew().show(getParentFragmentManager(), "ext:addy:new"));
        view.findViewById(R.id.btnSend).setOnClickListener(v ->
                new FragmentDialogAddySend().show(getParentFragmentManager(), "ext:addy:send"));

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // FairEmail's tasks only run for a fragment whose view exists, so loading starts here.
        // A new alias made from here shows up straight away.
        getParentFragmentManager().setFragmentResultListener(RESULT_CHANGED, getViewLifecycleOwner(), (key, result) -> load(false));
        load(false);
        loadAccount();
    }

    private void load(boolean refresh) {
        pbWait.setVisibility(View.VISIBLE);
        Bundle args = new Bundle();
        args.putBoolean("refresh", refresh);
        new SimpleTask<List<AddyClient.Alias>>() {
            @Override
            protected List<AddyClient.Alias> onExecute(Context context, Bundle args) throws Throwable {
                return AddyStore.aliases(context, args.getBoolean("refresh"));
            }

            @Override
            protected void onExecuted(Bundle args, List<AddyClient.Alias> aliases) {
                pbWait.setVisibility(View.GONE);
                all = aliases;
                loaded = true;
                filter();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                pbWait.setVisibility(View.GONE);
                tvEmpty.setText(ex.getMessage());
                tvEmpty.setVisibility(View.VISIBLE);
            }
        }.execute(this, args, "ext:addy:aliases");
    }

    /** Your alias counts, emails forwarded, bandwidth and plan, like addy :account. */
    private void loadAccount() {
        new SimpleTask<JSONObject>() {
            @Override
            protected JSONObject onExecute(Context context, Bundle args) throws Throwable {
                return AddyStore.client(context).accountDetails();
            }

            @Override
            protected void onExecuted(Bundle args, JSONObject d) {
                if (d != null)
                    showAccount(d);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                tvAccount.setText(ex.getMessage());
                tvAccount.setVisibility(View.VISIBLE);
            }
        }.execute(this, new Bundle(), "ext:addy:account");
    }

    /** Your alias and mail counts in one row. Tapping a count of aliases shows just those. */
    private void showAccount(JSONObject d) {
        Context context = requireContext();
        llStats.removeAllViews();
        addStat(context, d.optLong("total_active_aliases"), "Active", 1);
        addStat(context, d.optLong("total_inactive_aliases"), "Inactive", 2);
        addStat(context, d.optLong("total_deleted_aliases"), "Deleted", 4);
        addStat(context, d.optLong("total_emails_forwarded"), "Forwarded", -1);
        addStat(context, d.optLong("total_emails_blocked"), "Blocked", -1);

        // Bandwidth is only worth a mention when it's about to run out. No limit comes back as 0.
        long used = d.optLong("bandwidth", 0);
        long limit = d.optLong("bandwidth_limit", 0);
        boolean nearLimit = limit > 0 && used * 10 >= limit * 8;
        tvBandwidth.setText(nearLimit
                ? "⚠ " + AddyAccount.size(used) + " of your " + AddyAccount.size(limit) + " monthly bandwidth used"
                : "");
        tvBandwidth.setVisibility(nearLimit ? View.VISIBLE : View.GONE);

        tvAccount.setVisibility(View.GONE);
        cardAccount.setVisibility(View.VISIBLE);
    }

    /** A number with its label under it. filter is the list filter it selects when tapped, or -1 for none. */
    private void addStat(Context context, long value, String label, int filter) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int padding = Helper.dp2pixels(context, 4);
        tile.setPadding(0, padding, 0, padding);
        TextView number = new TextView(context);
        number.setText(String.format(Locale.getDefault(), "%,d", value));
        number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        number.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView name = new TextView(context);
        name.setText(label);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        name.setMaxLines(1);
        number.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        name.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tile.addView(number);
        tile.addView(name);
        if (filter >= 0) {
            android.util.TypedValue outValue = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            tile.setBackgroundResource(outValue.resourceId);
            tile.setContentDescription(value + " " + label + " aliases, show them");
            tile.setOnClickListener(v -> spFilter.setSelection(spFilter.getSelectedItemPosition() == filter ? 0 : filter));
        }
        llStats.addView(tile, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void filter() {
        if (adapter == null)
            return;
        String search = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        String which = FILTERS.get(Math.max(0, spFilter.getSelectedItemPosition()));
        List<AddyClient.Alias> matching = new ArrayList<>();
        for (AddyClient.Alias alias : all) {
            boolean keep;
            switch (which) {
                case "Active":
                    keep = alias.active && !alias.deleted;
                    break;
                case "Inactive":
                    keep = !alias.active && !alias.deleted;
                    break;
                case "Pinned":
                    keep = alias.pinned && !alias.deleted;
                    break;
                case "Deleted":
                    keep = alias.deleted;
                    break;
                default:
                    keep = true;
            }
            String text = (alias.email + " " + (alias.description == null ? "" : alias.description)).toLowerCase(Locale.ROOT);
            if (keep && text.contains(search))
                matching.add(alias);
        }
        // Pinned first, deleted last, otherwise newest first, as addy.io gave them.
        matching.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        shown.clear();
        shown.addAll(matching);
        adapter.notifyDataSetChanged();
        tvEmpty.setText(all.isEmpty() ? "No aliases yet. Tap New alias to create one." : "No aliases match.");
        tvEmpty.setVisibility(loaded && matching.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static int rank(AddyClient.Alias alias) {
        return alias.deleted ? 2 : alias.pinned ? 0 : 1;
    }

    private void showActions(AddyClient.Alias alias) {
        Context context = requireContext();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Copy address");
        actions.add(() -> AddyCompose.copy(context, "alias", alias.email));
        if (alias.deleted) {
            labels.add("Restore");
            actions.add(() -> change(alias, "restore", null));
        } else {
            if (alias.active) {
                labels.add("Send from this alias");
                actions.add(() -> {
                    Bundle args = new Bundle();
                    args.putString("alias", alias.email);
                    FragmentDialogAddySend send = new FragmentDialogAddySend();
                    send.setArguments(args);
                    send.show(getParentFragmentManager(), "ext:addy:send");
                });
            }
            labels.add(alias.pinned ? "Unpin" : "Pin to the top");
            actions.add(() -> change(alias, alias.pinned ? "unpin" : "pin", null));
            labels.add(alias.active ? "Turn off (stop forwarding)" : "Turn on (forward again)");
            actions.add(() -> change(alias, alias.active ? "deactivate" : "activate", null));
            labels.add("Edit description");
            actions.add(() -> editDescription(alias));
            labels.add("Delete (can be restored)");
            actions.add(() -> new AlertDialog.Builder(context)
                    .setTitle("Delete " + alias.email + "?")
                    .setMessage("It stops working until you restore it. Mail sent to it is discarded meanwhile.")
                    .setPositiveButton("Delete", (d, w) -> change(alias, "delete", null))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }
        labels.add("Open on addy.io");
        actions.add(() -> Helper.view(context, Uri.parse(AddyStore.baseUrl(context) + "/aliases?search=" + Uri.encode(alias.email)), true));
        new AlertDialog.Builder(context)
                .setTitle(alias.email)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void editDescription(AddyClient.Alias alias) {
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setText(alias.description == null ? "" : alias.description);
        input.setSingleLine(true);
        input.setSelection(input.length());
        int padding = Helper.dp2pixels(context, 24);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(input);
        new AlertDialog.Builder(context)
                .setTitle("Description")
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (d, w) -> change(alias, "describe", input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void change(AddyClient.Alias alias, String what, String description) {
        Bundle args = new Bundle();
        args.putString("id", alias.id);
        args.putString("what", what);
        args.putString("description", description);
        pbWait.setVisibility(View.VISIBLE);
        new SimpleTask<Void>() {
            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                AddyClient client = AddyStore.client(context);
                String id = args.getString("id");
                switch (args.getString("what")) {
                    case "pin":
                        client.pin(id);
                        break;
                    case "unpin":
                        client.unpin(id);
                        break;
                    case "activate":
                        client.activate(id);
                        break;
                    case "deactivate":
                        client.deactivate(id);
                        break;
                    case "delete":
                        client.delete(id);
                        break;
                    case "restore":
                        client.restore(id);
                        break;
                    case "describe":
                        client.setDescription(id, args.getString("description"));
                        break;
                }
                AddyStore.aliasesChanged();
                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                load(true);
                loadAccount();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                pbWait.setVisibility(View.GONE);
                new AlertDialog.Builder(requireContext())
                        .setMessage(ex.getMessage())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }.execute(this, args, "ext:addy:change");
    }
}
