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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.util.List;

import javax.mail.internet.InternetAddress;

/** Starting an email from an alias, and copying an address, from the addy.io screens. */
public class AddyCompose {
    /** Who person is, from text like "Jane <jane@example.com>" or a plain address. Null if it isn't one address. */
    static InternetAddress person(String text) {
        try {
            InternetAddress[] parsed = InternetAddress.parse(text == null ? "" : text.trim(), false);
            if (parsed.length != 1 || !AddyAddresses.isPlainAddress(parsed[0].getAddress()))
                return null;
            return parsed[0];
        } catch (Throwable ex) {
            return null;
        }
    }

    static void copy(Context context, String label, String text) {
        ClipboardManager clipboard = Helper.getSystemService(context, ClipboardManager.class);
        if (clipboard == null)
            return;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        ToastEx.makeText(context, "Copied " + text, Toast.LENGTH_SHORT).show();
    }

    /**
     * Opens a new email to person, sent from alias: To gets the address that makes addy.io send from it, and From
     * an identity addy.io accepts, if you have one. The check before sending still runs when you tap Send.
     */
    static void startEmail(FragmentActivity activity, String alias, InternetAddress person) {
        Bundle args = new Bundle();
        args.putString("alias", alias);
        args.putString("email", person.getAddress());
        args.putString("name", person.getPersonal());
        new SimpleTask<Intent>() {
            @Override
            protected Intent onExecute(Context context, Bundle args) throws Throwable {
                String encoded = AddyAddresses.encodeSendAddress(args.getString("alias"), args.getString("email"));
                String to = new InternetAddress(encoded, args.getString("name"), "UTF-8").toUnicodeString();
                Intent intent = new Intent(context, ActivityCompose.class)
                        .putExtra("action", "new")
                        .putExtra("to", to);
                AddyAccount account = AddyStore.getAccount(context, false);
                List<String> verified = account == null ? null : account.verifiedAddresses();
                if (verified != null)
                    for (TupleIdentityEx identity : DB.getInstance(context).identity().getComposableIdentities(null))
                        for (String address : verified)
                            if (AddyAddresses.sameAddress(address, identity.email))
                                return intent.putExtra("account", identity.account).putExtra("identity", identity.id);
                return intent;
            }

            @Override
            protected void onExecuted(Bundle args, Intent intent) {
                activity.startActivity(intent);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                ToastEx.makeText(activity, ex.getMessage(), Toast.LENGTH_LONG).show();
            }
            // Runs with the screen behind the dialog, as the dialog closes straight away.
        }.execute(activity, activity, args, "ext:addy:compose");
    }
}
