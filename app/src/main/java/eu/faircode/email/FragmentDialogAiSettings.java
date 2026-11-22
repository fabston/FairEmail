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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.Arrays;
import java.util.List;

/**
 * The AI writing extension's settings: which service, and its key and model. Each service keeps its own, so
 * switching back doesn't lose either. Saving tests the key and model, which sends none of your text.
 */
public class FragmentDialogAiSettings extends FragmentDialogBase {
    private static final List<String> PROVIDERS = Arrays.asList(AiClient.OPENAI, AiClient.ANTHROPIC);
    private static final List<String> PROVIDER_NAMES = Arrays.asList("OpenAI (ChatGPT's models)", "Anthropic (Claude)");

    // What's been typed for each service, until it's saved: provider_key and provider_model.
    private Bundle edits;
    private String current;

    private EditText etKey;
    private Spinner spModel;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ext_ai_settings, null);
        Spinner spProvider = view.findViewById(R.id.spProvider);
        etKey = view.findViewById(R.id.etKey);
        spModel = view.findViewById(R.id.spModel);
        TextView tvKeyHelp = view.findViewById(R.id.tvKeyHelp);
        TextView tvModelNote = view.findViewById(R.id.tvModelNote);
        TextView tvPrivacy = view.findViewById(R.id.tvPrivacy);
        TextView tvResult = view.findViewById(R.id.tvResult);

        if (savedInstanceState == null) {
            edits = new Bundle();
            for (String provider : PROVIDERS) {
                edits.putString(AiStore.keyPref(provider), AiStore.key(context, provider));
                edits.putString(AiStore.modelPref(provider), AiStore.model(context, provider));
            }
            current = AiStore.provider(context);
        } else {
            edits = savedInstanceState.getBundle("edits");
            current = savedInstanceState.getString("current");
        }

        ExtHooks.setDropdown(spProvider, PROVIDER_NAMES);
        spProvider.setSelection(PROVIDERS.indexOf(current));
        show(current, tvKeyHelp, tvModelNote, tvPrivacy);
        spProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                String chosen = PROVIDERS.get(position);
                if (chosen.equals(current))
                    return;
                keep();
                current = chosen;
                show(current, tvKeyHelp, tvModelNote, tvPrivacy);
                tvResult.setText(null);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                tvModelNote.setText(AiClient.models(current).get(position).note);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.ext_ai_title)
                .setView(view)
                .setPositiveButton(R.string.ext_addy_save_test, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // Stays open after saving, to show whether the key works.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            keep();
            SharedPreferences.Editor editor = AiStore.prefs(context).edit().putString(AiStore.PREF_PROVIDER, current);
            for (String provider : PROVIDERS) {
                editor.putString(AiStore.keyPref(provider), edits.getString(AiStore.keyPref(provider), ""));
                editor.putString(AiStore.modelPref(provider), edits.getString(AiStore.modelPref(provider)));
            }
            editor.apply();
            if (!AiStore.isSetUp(context)) {
                tvResult.setText("Saved. Add an API key to use AI writing.");
                return;
            }
            tvResult.setText("Testing…");
            new SimpleTask<Void>() {
                @Override
                protected Void onExecute(Context context, Bundle args) throws Throwable {
                    AiStore.client(context).checkModel();
                    return null;
                }

                @Override
                protected void onExecuted(Bundle args, Void data) {
                    tvResult.setText("Works. In the compose screen, choose ⋮ → Rewrite with AI.");
                }

                @Override
                protected void onException(Bundle args, Throwable ex) {
                    tvResult.setText(ex.getMessage());
                }
            }.execute(this, new Bundle(), "ext:ai:test");
        }));
        return dialog;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        keep();
        outState.putBundle("edits", edits);
        outState.putString("current", current);
        super.onSaveInstanceState(outState);
    }

    /** Remembers what's typed for the service shown, before another is shown or it's saved. */
    private void keep() {
        if (etKey == null || spModel == null || current == null)
            return;
        edits.putString(AiStore.keyPref(current), etKey.getText().toString().trim());
        int model = spModel.getSelectedItemPosition();
        if (model >= 0)
            edits.putString(AiStore.modelPref(current), AiClient.models(current).get(model).id);
    }

    /** Shows a service's key, models and notes. */
    private void show(String provider, TextView tvKeyHelp, TextView tvModelNote, TextView tvPrivacy) {
        etKey.setText(edits.getString(AiStore.keyPref(provider), ""));
        List<AiClient.Model> models = AiClient.models(provider);
        ExtHooks.setDropdown(spModel, models);
        String model = edits.getString(AiStore.modelPref(provider));
        int selected = 0;
        for (int i = 0; i < models.size(); i++)
            if (models.get(i).id.equals(model))
                selected = i;
        spModel.setSelection(selected);
        tvModelNote.setText(models.get(selected).note);
        boolean claude = AiClient.ANTHROPIC.equals(provider);
        tvKeyHelp.setText(claude
                ? "Create one in the Claude Console (platform.claude.com) under API keys, and add some credit under Billing. A Claude subscription doesn't include API use."
                : "Create one on platform.openai.com under API keys, and add some credit under Billing. A ChatGPT subscription doesn't include API use.");
        tvPrivacy.setText((claude
                ? "Anthropic doesn't use API data to train its models by default, and deletes it within 30 days, unless it's flagged for breaking its usage policy."
                : "OpenAI doesn't use API data to train its models unless you opt in. It keeps requests for up to 30 days to check for abuse, and is asked not to store them beyond that.") +
                " Only the text you rewrite is sent, and only when you choose an action. The key stays on this phone, apart from FairEmail's settings, so it isn't in exports, logs or debug info.");
    }
}
