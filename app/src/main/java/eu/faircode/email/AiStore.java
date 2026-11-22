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

import java.util.Locale;

/**
 * The AI writing extension's settings. Kept in a file of their own, as FairEmail writes its own settings, with their
 * values, to its log, its debug info and its exports, and these hold your API keys. Each service keeps its own key
 * and model, so you can switch between them.
 */
public class AiStore {
    private static final String FILE = "ext_ai";
    static final String PREF_PROVIDER = "provider";
    private static final String PREF_TRANSLATE_TO = "translate_to";

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String keyPref(String provider) {
        return provider + "_key";
    }

    static String modelPref(String provider) {
        return provider + "_model";
    }

    static String provider(Context context) {
        String provider = prefs(context).getString(PREF_PROVIDER, AiClient.OPENAI);
        return AiClient.ANTHROPIC.equals(provider) ? AiClient.ANTHROPIC : AiClient.OPENAI;
    }

    static String key(Context context, String provider) {
        return prefs(context).getString(keyPref(provider), "");
    }

    /** The model in use for a service: the one chosen, if it's still offered, or else its first. */
    static String model(Context context, String provider) {
        String model = prefs(context).getString(modelPref(provider), null);
        for (AiClient.Model offered : AiClient.models(provider))
            if (offered.id.equals(model))
                return model;
        return AiClient.models(provider).get(0).id;
    }

    static boolean isSetUp(Context context) {
        return !TextUtils.isEmpty(key(context, provider(context)));
    }

    static AiClient client(Context context) {
        String provider = provider(context);
        return new AiClient(context, provider, key(context, provider), model(context, provider));
    }

    /** The language last translated into, or else the phone's own. */
    static String translateTo(Context context) {
        String saved = AiPrompts.cleanLanguage(prefs(context).getString(PREF_TRANSLATE_TO, null));
        if (!saved.isEmpty())
            return saved;
        String own = AiPrompts.cleanLanguage(Locale.getDefault().getDisplayLanguage(Locale.ENGLISH));
        return own.isEmpty() ? "English" : own;
    }

    static void setTranslateTo(Context context, String language) {
        prefs(context).edit().putString(PREF_TRANSLATE_TO, AiPrompts.cleanLanguage(language)).apply();
    }
}
