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
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LifecycleOwner;

import org.json.JSONObject;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;

/**
 * Checks who mail to your aliases really comes from, as it arrives. addy.io gives every email it forwards headers
 * with the original sender, how that sender's authentication went, and the alias it was for. An email that doesn't
 * fit its alias gets a warning, shown by FairEmail as its own warnings are, in the list and in the email.
 */
public class AddyIncoming {
    // addy.io doesn't take a longer description.
    private static final int MAX_DESCRIPTION = 200;
    // Stands in for a sender's domain that isn't one.
    private static final String UNREADABLE = "an unreadable address";

    /** The registrable domains an alias's description names. Words that only look like domains are left out. */
    static Set<String> expected(Context context, String description) {
        Set<String> roots = new LinkedHashSet<>();
        for (String domain : AddySenders.domainsIn(description)) {
            String root = UriHelper.getRootDomain(context, domain);
            if (root != null)
                roots.add(root);
        }
        return roots;
    }

    private static String header(MimeMessage imessage, String name) throws Exception {
        String value = imessage.getHeader(name, null);
        return value == null ? null : MimeUtility.unfold(value).trim();
    }

    /** The alias an email came through, going by its addresses, for when addy.io's own header doesn't say. */
    private static String aliasOf(EntityMessage message) {
        List<Address> addresses = new ArrayList<>();
        for (Address[] list : new Address[][]{message.reply, message.from})
            if (list != null)
                for (Address address : list)
                    addresses.add(address);
        for (Address address : addresses)
            if (address instanceof InternetAddress) {
                String email = ((InternetAddress) address).getAddress();
                AddyAddresses.SendAddress decoded = AddyAddresses.decodeSendAddress(email);
                if (decoded != null)
                    return decoded.alias;
            }
        return null;
    }

    /**
     * Adds a warning to a new message if it doesn't fit the alias it came through. Runs as FairEmail stores the
     * message, on its background thread, while all of its headers are at hand.
     */
    static void check(Context context, EntityMessage message, MimeMessage imessage) throws Exception {
        if (!AddyStore.isSetUp(context) || !AddyStore.checkSenders(context))
            return;
        // Only email forwarded by addy.io has these.
        String sender = header(imessage, "X-AnonAddy-Original-Sender");
        String from = header(imessage, "X-AnonAddy-Original-From-Header");
        if (from != null)
            try {
                InternetAddress[] parsed = InternetAddress.parseHeader(from, false);
                if (parsed.length > 0 && parsed[0].getAddress() != null)
                    sender = parsed[0].getAddress();
            } catch (Throwable ex) {
                Log.w(ex);
            }
        if (sender == null || !sender.contains("@"))
            return;
        // As it's really written: a name in another alphabet that only looks like an expected one isn't the same.
        String host = AddyAddresses.domainOf(sender);
        String root;
        try {
            host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            root = host.matches("[a-z0-9.-]+") ? UriHelper.getRootDomain(context, host) : null;
        } catch (IllegalArgumentException ex) {
            root = null;
        }
        // A sender that can't be told is flagged rather than let through.
        if (root == null)
            host = root = UNREADABLE;

        AddyClient.Alias alias = AddyStore.knownAlias(context,
                AddySenders.aliasId(header(imessage, "Feedback-ID")), aliasOf(message));
        if (alias == null)
            return;

        AddySenders.Finding finding = AddySenders.check(expected(context, alias.description), host, root,
                AddySenders.dmarc(header(imessage, "X-AnonAddy-Authentication-Results")), AddyStore.suggestSenders(context));
        if (finding == null)
            return;
        message.warning = message.warning == null ? finding.text : finding.text + ", " + message.warning;
        if (message.msgid != null)
            AddyStore.flag(context, message.msgid, alias.id, root, finding);
    }

    /** What was found wrong with a message, as it was kept when it arrived. Null if nothing was, or it's forgotten. */
    private static JSONObject found(Context context, EntityMessage message) {
        if (message.warning == null || !message.warning.contains(AddySenders.MARK) || message.msgid == null)
            return null;
        return AddyStore.flagged(context).optJSONObject(message.msgid);
    }

    private static boolean is(JSONObject found, AddySenders.Kind kind) {
        return kind.name().equals(found.optString("kind"));
    }

    /** What's wrong with a flagged email, short enough for its notification. Null if nothing is, or it's only a suggestion. */
    static String notificationLabel(Context context, EntityMessage message) {
        JSONObject found = found(context, message);
        if (found == null)
            return null;
        if (is(found, AddySenders.Kind.UNEXPECTED))
            return "Unexpected sender";
        if (is(found, AddySenders.Kind.LOOKALIKE))
            return "Looks like a fake sender";
        if (is(found, AddySenders.Kind.FORGED))
            return "Sender may be forged";
        return null;
    }

    /**
     * What to do about a flagged email, when its warning icon is tapped: expect its sender on the alias from now on,
     * or turn the alias off. False if the message's warning isn't one of these.
     */
    static boolean showActions(Context context, LifecycleOwner owner, EntityMessage message) {
        JSONObject found = found(context, message);
        if (found == null)
            return false;
        String sender = found.optString("sender");
        // The warning, without where it's from, which the title says.
        String text = found.optString("text").replace(AddySenders.MARK, "");
        String said = is(found, AddySenders.Kind.SUGGEST) || text.isEmpty()
                ? "This alias doesn't expect a sender yet. This email is from " + sender + "."
                : text.substring(0, 1).toUpperCase() + text.substring(1);

        Bundle args = new Bundle();
        args.putString("alias", found.optString("alias"));
        args.putString("sender", sender);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("addy.io")
                .setMessage(said + "\n\nWhat an alias expects is the domains in its description on addy.io.")
                .setNeutralButton("Turn off alias", (dialog, which) -> run(context, owner, args, false))
                .setNegativeButton(android.R.string.cancel, null);
        // A sender that failed its own check is already expected, and one that can't be read can't be: nothing to add.
        if (!is(found, AddySenders.Kind.FORGED) && !UNREADABLE.equals(sender))
            builder.setPositiveButton("Expect " + sender, (dialog, which) -> run(context, owner, args, true));
        builder.show();
        return true;
    }

    private static void run(Context context, LifecycleOwner owner, Bundle args, boolean expect) {
        args.putBoolean("expect", expect);
        new SimpleTask<String>() {
            @Override
            protected String onExecute(Context context, Bundle args) throws Throwable {
                String sender = args.getString("sender");
                AddyClient.Alias alias = AddyStore.knownAlias(context, args.getString("alias"), null);
                if (alias == null)
                    throw new IllegalArgumentException("That alias isn't on addy.io any more");
                if (!args.getBoolean("expect")) {
                    AddyStore.client(context).deactivate(alias.id);
                    AddyStore.aliasesChanged();
                    return alias.email + " is turned off";
                }

                String description = alias.description == null ? "" : alias.description.trim();
                if (!expected(context, description).contains(sender)) {
                    description = description.isEmpty() ? sender : description + " " + sender;
                    if (description.length() > MAX_DESCRIPTION)
                        throw new IllegalArgumentException("This alias's description is full. Shorten it on addy.io first.");
                    AddyStore.client(context).setDescription(alias.id, description);
                    AddyStore.aliases(context, true);
                }

                // Take the warning off every email it no longer applies to.
                DB db = DB.getInstance(context);
                JSONObject flagged = AddyStore.flagged(context);
                List<String> cleared = new ArrayList<>();
                for (Iterator<String> msgids = flagged.keys(); msgids.hasNext(); ) {
                    String msgid = msgids.next();
                    JSONObject entry = flagged.optJSONObject(msgid);
                    if (entry == null || !alias.id.equals(entry.optString("alias")) || !sender.equals(entry.optString("sender")) ||
                            AddySenders.Kind.FORGED.name().equals(entry.optString("kind")))
                        continue;
                    for (EntityMessage message : db.message().getMessagesByMsgId(null, msgid))
                        db.message().setMessageWarning(message.id, AddySenders.without(message.warning, entry.optString("text")));
                    cleared.add(msgid);
                }
                AddyStore.unflag(context, cleared);
                return alias.email + " now expects " + sender;
            }

            @Override
            protected void onExecuted(Bundle args, String done) {
                ToastEx.makeText(context, done, Toast.LENGTH_LONG).show();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                ToastEx.makeText(context, Log.formatThrowable(ex, false), Toast.LENGTH_LONG).show();
            }
        }.execute(context, owner, args, "ext:addy:sender");
    }
}
