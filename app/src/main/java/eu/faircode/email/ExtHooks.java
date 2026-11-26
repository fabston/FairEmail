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
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.core.widget.TextViewCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * Where FairEmail Plus's extensions plug into FairEmail. FairEmail's own files only call these, from a line or two
 * each, marked "Plus", so merging FairEmail's updates stays easy. Everything else lives in new files.
 */
public class ExtHooks {
    private static final String ARG_TITLES = "ext_problem_titles";
    private static final String ARG_DETAILS = "ext_problem_details";
    private static final String ARG_ROUTE_ALIASES = "ext_route_aliases";
    private static final String ARG_ROUTE_DESTINATIONS = "ext_route_destinations";
    static final String RESULT_COMPOSE = "ext:compose";
    private static final int MENU_ADDY = 0x7e570001;
    private static final int MENU_AI = 0x7e570002;
    private static final int MENU_GOKAPI = 0x7e570003;
    private static final int MENU_GOKAPI_ATTACHMENT = 0x7e570004;

    // --- Shared -----------------------------------------------------------------------------------------------

    /**
     * Fills a dropdown: when closed, the value in a plain text size, lined up with the label above it; when open,
     * the list FairEmail's own settings use, where Android puts it, so it opens smoothly wherever the field is.
     */
    static <T> void setDropdown(Spinner spinner, List<T> items) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(spinner.getContext(), R.layout.ext_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // The field's own inset is taken out, so the value starts where the label does. The arrow stays at the end.
        spinner.setPaddingRelative(0, spinner.getPaddingTop(), spinner.getPaddingEnd(), spinner.getPaddingBottom());
    }

    // --- Before sending ------------------------------------------------------------------------------------------

    /**
     * Runs every extension's check on a draft about to be sent, on FairEmail's background thread but outside its
     * database transaction, as a check can use the network. What they find is kept in args for the send dialog.
     * If a check can't run, that's reported too, so nothing goes out unchecked.
     */
    static void checkDraft(Context context, Bundle args, EntityMessage draft, String body) {
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> details = new ArrayList<>();
        ArrayList<String> aliases = new ArrayList<>();
        ArrayList<String> destinations = new ArrayList<>();
        try {
            AddyGuard.Input input = addyInput(context, draft, body);
            if (input != null) {
                for (AddyGuard.Problem problem : AddyGuard.check(input)) {
                    titles.add(problem.title);
                    details.add(problem.detail);
                }
                for (AddyAddresses.SendAddress route : AddyGuard.routes(input)) {
                    aliases.add(route.alias);
                    destinations.add(route.destination);
                }
            }
        } catch (Throwable ex) {
            Log.e(ex);
            titles.add("The addy.io check couldn't finish");
            details.add("Send only if you're sure this email doesn't show your real address. " +
                    (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
        args.putStringArrayList(ARG_TITLES, titles);
        args.putStringArrayList(ARG_DETAILS, details);
        args.putStringArrayList(ARG_ROUTE_ALIASES, aliases);
        args.putStringArrayList(ARG_ROUTE_DESTINATIONS, destinations);
    }

    static boolean hasProblems(Bundle args) {
        ArrayList<String> titles = args.getStringArrayList(ARG_TITLES);
        return titles != null && !titles.isEmpty();
    }

    private static List<String> addresses(Address[] list) {
        List<String> result = new ArrayList<>();
        if (list != null)
            for (Address address : list)
                if (address instanceof InternetAddress && ((InternetAddress) address).getAddress() != null)
                    result.add(((InternetAddress) address).getAddress());
                else if (address != null)
                    // Anything that can't be read as an address stays, so it's treated as a real recipient.
                    result.add(address.toString());
        return result;
    }

    /** What the addy.io check needs to know about this send, or null if the check is turned off. */
    private static AddyGuard.Input addyInput(Context context, EntityMessage draft, String body) throws Exception {
        if (!AddyStore.guardEnabled(context))
            return null;
        DB db = DB.getInstance(context);
        EntityIdentity identity = draft.identity == null ? null : db.identity().getIdentity(draft.identity);
        AddyGuard.Input input = new AddyGuard.Input();
        input.draft.to = addresses(draft.to);
        input.draft.cc = addresses(draft.cc);
        input.draft.bcc = addresses(draft.bcc);
        input.draft.replyTo = addresses(draft.reply);
        List<String> from = addresses(draft.from);
        input.draft.from = from.isEmpty() ? "" : from.get(0);
        input.draft.subject = draft.subject == null ? "" : draft.subject;
        input.draft.html = body == null ? "" : body;
        input.draft.signed = draft.ui_encrypt != null && !EntityMessage.ENCRYPT_NONE.equals(draft.ui_encrypt);
        input.draft.reply = !TextUtils.isEmpty(draft.inreplyto);
        if (identity != null)
            input.identity = new AddyGuard.Identity(identity.id, identity.email, identity.name);
        for (TupleIdentityEx item : db.identity().getComposableIdentities(null))
            input.identities.add(new AddyGuard.Identity(item.id, item.email, item.name));

        boolean setUp = AddyStore.isSetUp(context);
        input.warnDirect = setUp && AddyStore.warnDirect(context);
        input.trusted = AddyStore.trusted(context);
        input.account = AddyStore.getAccount(context, false);

        // Which alias the original came through, if this is a reply, from its From or Reply-To. Only addresses on
        // addy.io's domains count, as lists and other services use the same shape. Only ever adds a warning.
        if (input.draft.reply)
            for (EntityMessage original : db.message().getMessagesByMsgId(draft.account, draft.inreplyto)) {
                List<String> senders = addresses(original.from);
                senders.addAll(addresses(original.reply));
                for (String address : senders) {
                    AddyAddresses.SendAddress decoded = AddyAddresses.decodeSendAddress(address);
                    if (decoded != null && AddyAddresses.isAddyDomain(decoded.alias, input.account)) {
                        input.related = new AddyGuard.Related(decoded.alias);
                        break;
                    }
                }
                if (input.related != null)
                    break;
            }

        input.confirmed = input.account == null
                ? new ArrayList<>()
                : AddyStore.confirmAliases(context, AddyGuard.aliasesToConfirm(input));
        return input;
    }

    /**
     * Shows, at the top of FairEmail's send dialog, who an email through an alias really reaches and from which
     * alias, as To only shows addy.io's combined address. Then what the checks found. Its Send button sends anyway.
     * The dialog's own content is wrapped rather than changed, so this hardly depends on its layout.
     */
    static void addToSendDialog(Context context, Bundle args, ViewGroup dview) {
        List<String> titles = list(args, ARG_TITLES);
        List<String> details = list(args, ARG_DETAILS);
        List<String> aliases = list(args, ARG_ROUTE_ALIASES);
        List<String> destinations = list(args, ARG_ROUTE_DESTINATIONS);
        if ((titles.isEmpty() && aliases.isEmpty()) || !(dview instanceof ScrollView) || dview.getChildCount() != 1)
            return;
        View content = dview.getChildAt(0);
        dview.removeView(content);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = Helper.dp2pixels(context, 12);

        // FairEmail's title would end up below what's added here, so it's shown again at the top instead.
        View send = content.findViewById(R.id.tvMessage);
        if (send instanceof TextView) {
            TextView original = (TextView) send;
            TextView title = new TextView(context);
            title.setText(original.getText());
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, original.getTextSize());
            title.setTextColor(original.getTextColors());
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.twotone_send_24, 0, 0, 0);
            title.setCompoundDrawablePadding(original.getCompoundDrawablePadding());
            title.setGravity(android.view.Gravity.CENTER_VERTICAL);
            title.setPadding(0, 0, 0, padding);
            column.addView(title);
            original.setVisibility(View.GONE);
        }

        if (!aliases.isEmpty())
            addRoutes(context, column, aliases, destinations, titles.isEmpty());

        int colorWarning = Helper.resolveColor(context, R.attr.colorWarning);
        for (int i = 0; i < titles.size(); i++) {
            TextView title = new TextView(context);
            title.setText(titles.get(i));
            title.setTextColor(colorWarning);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(null, Typeface.BOLD);
            title.setPadding(0, padding / 2, 0, 0);
            TextView detail = new TextView(context);
            detail.setText(i < details.size() ? details.get(i) : "");
            detail.setPadding(0, 0, 0, padding / 2);
            column.addView(title);
            column.addView(detail);
        }
        column.addView(content);
        dview.addView(column);
    }

    private static List<String> list(Bundle args, String key) {
        ArrayList<String> list = args.getStringArrayList(key);
        return list == null ? new ArrayList<>() : list;
    }

    /** "To fbstn@pm.me / From fabian@xmail.sh" for each alias used, in the dialog's own text size. */
    private static void addRoutes(Context context, LinearLayout column, List<String> aliases, List<String> destinations, boolean clean) {
        Map<String, List<String>> byAlias = new LinkedHashMap<>();
        for (int i = 0; i < aliases.size() && i < destinations.size(); i++) {
            List<String> to = byAlias.get(aliases.get(i));
            if (to == null)
                byAlias.put(aliases.get(i), to = new ArrayList<>());
            to.add(destinations.get(i));
        }
        int padding = Helper.dp2pixels(context, 12);
        int colorAccent = Helper.resolveColor(context, androidx.appcompat.R.attr.colorAccent);
        int colorSecondary = Helper.resolveColor(context, android.R.attr.textColorSecondary);

        TextView heading = new TextView(context);
        heading.setText(byAlias.size() == 1 ? "Through your addy.io alias" : "Through your addy.io aliases");
        heading.setTextColor(colorAccent);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.twotone_alternate_email_24, 0, 0, 0);
        heading.setCompoundDrawablePadding(Helper.dp2pixels(context, 6));
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        column.addView(heading);

        SpannableStringBuilder text = new SpannableStringBuilder();
        for (Map.Entry<String, List<String>> route : byAlias.entrySet()) {
            if (text.length() > 0)
                text.append("\n\n");
            addLine(text, "To", TextUtils.join(", ", route.getValue()), colorSecondary);
            text.append("\n");
            addLine(text, "From", route.getKey(), colorSecondary);
        }
        TextView routes = new TextView(context);
        TextViewCompat.setTextAppearance(routes, androidx.appcompat.R.style.TextAppearance_AppCompat_Medium);
        routes.setTextColor(Helper.resolveColor(context, android.R.attr.textColorPrimary));
        routes.setText(text);
        routes.setPadding(0, padding / 2, 0, 0);
        column.addView(routes);

        if (clean) {
            TextView hint = new TextView(context);
            hint.setText("They'll see the alias, not the address it's sent with.");
            hint.setTextColor(colorSecondary);
            hint.setPadding(0, padding / 2, 0, 0);
            column.addView(hint);
        }

        View divider = new View(context);
        divider.setBackgroundResource(R.drawable.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Helper.dp2pixels(context, 1));
        lp.setMargins(0, padding, 0, padding / 2);
        column.addView(divider, lp);
    }

    private static void addLine(SpannableStringBuilder text, String label, String value, int colorLabel) {
        int start = text.length();
        text.append(label).append("  ");
        text.setSpan(new ForegroundColorSpan(colorLabel), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = text.length();
        text.append(value);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // --- While writing ------------------------------------------------------------------------------------------

    static void onCreateComposeMenu(Context context, Menu menu) {
        if (menu.findItem(MENU_ADDY) == null) {
            // First in the menu, as it's what you'd use most when everything goes through addy.io.
            MenuItem addy = menu.add(Menu.NONE, MENU_ADDY, 0, "addy.io alias")
                    .setIcon(R.drawable.twotone_alternate_email_24);
            // FairEmail shows the icons in ⋮ by putting them in the titles, which it's already done for its own items.
            PopupMenuLifecycle.insertIcon(context, addy, false);
        }
        if (menu.findItem(MENU_GOKAPI) == null) {
            MenuItem drop = menu.add(Menu.NONE, MENU_GOKAPI, 0, R.string.ext_gokapi_share_file)
                    .setIcon(R.drawable.twotone_file_upload_24);
            PopupMenuLifecycle.insertIcon(context, drop, false);
        }
        if (menu.findItem(MENU_AI) == null) {
            // Always in the top bar, like FairEmail's own AI and encryption.
            MenuItem ai = menu.add(Menu.NONE, MENU_AI, 1, R.string.ext_ai_rewrite)
                    .setIcon(R.drawable.twotone_auto_fix_high_24);
            ai.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
    }

    /** True if an extension handled the menu item. etTo and etBody are the email's To and its text. */
    static boolean onComposeMenu(Fragment compose, MenuItem item, TextView etTo, EditText etBody) {
        if (item.getItemId() == MENU_AI) {
            AiCompose.show(compose, etBody);
            return true;
        }
        if (item.getItemId() == MENU_GOKAPI) {
            GokapiCompose.show(compose, null);
            return true;
        }
        if (item.getItemId() != MENU_ADDY)
            return false;
        String to = etTo.getText().toString();
        Context context = compose.getContext();
        if (!AddyStore.isSetUp(context)) {
            new AlertDialog.Builder(context)
                    .setTitle("addy.io alias")
                    .setMessage("Add your addy.io API key first, under Settings → Integrations → Extensions.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        Bundle args = new Bundle();
        args.putString("to", to);
        FragmentDialogAddyAlias dialog = new FragmentDialogAddyAlias();
        dialog.setArguments(args);
        dialog.show(compose.getParentFragmentManager(), "ext:addy:alias");
        return true;
    }

    /**
     * In the compose screen, long-pressing an attachment also offers to share it as a link instead, once Gokapi
     * is set up. Not for images shown in the text, which would leave a gap there.
     */
    static void onAttachmentMenu(Fragment parent, Menu menu, EntityAttachment attachment) {
        if (parent instanceof FragmentCompose && GokapiStore.isSetUp(parent.requireContext()) && GokapiCompose.canShare(attachment))
            menu.add(Menu.NONE, MENU_GOKAPI_ATTACHMENT, 6, R.string.ext_gokapi_share_attachment);
    }

    /** True if an extension handled the attachment's menu item. */
    static boolean onAttachmentMenuItem(Fragment parent, MenuItem item, EntityAttachment attachment) {
        if (item.getItemId() != MENU_GOKAPI_ATTACHMENT)
            return false;
        GokapiCompose.show(parent, attachment);
        return true;
    }

    /** Adds an Upload with Gokapi button to FairEmail's Insert link dialog, next to its own Send upload. */
    static void addToInsertLinkDialog(Fragment dialog, View view, EditText etLink, EditText etTitle) {
        GokapiCompose.addToLinkDialog(dialog, view, etLink, etTitle);
    }

    /**
     * Applies what an extension's dialog decided to the email being written: a new To, the identity to send from, or
     * links to put in the text. Registered once the compose screen exists, so it also works after it's recreated.
     */
    static void listenCompose(@NonNull Fragment compose, TextView etTo, Spinner spIdentity, EditText etBody) {
        compose.getParentFragmentManager().setFragmentResultListener(RESULT_COMPOSE, compose.getViewLifecycleOwner(), (key, result) -> {
            if (result.containsKey(GokapiCompose.RESULT_LINKS)) {
                GokapiCompose.insert(compose, etBody, result);
                return;
            }
            String to = result.getString("to");
            if (to != null)
                etTo.setText(to);
            // Switch to an identity addy.io accepts sending from, unless the current one already is.
            List<String> verified = result.getStringArrayList("verified");
            SpinnerAdapter adapter = spIdentity.getAdapter();
            if (verified == null || verified.isEmpty() || adapter == null)
                return;
            Object current = spIdentity.getSelectedItem();
            if (current instanceof EntityIdentity && containsAddress(verified, ((EntityIdentity) current).email))
                return;
            for (int pos = 0; pos < adapter.getCount(); pos++) {
                Object entry = adapter.getItem(pos);
                if (entry instanceof EntityIdentity && containsAddress(verified, ((EntityIdentity) entry).email)) {
                    spIdentity.setSelection(pos);
                    break;
                }
            }
        });
    }

    private static boolean containsAddress(List<String> addresses, String address) {
        for (String entry : addresses)
            if (AddyAddresses.sameAddress(entry, address))
                return true;
        return false;
    }

    // --- Reading mail -------------------------------------------------------------------------------------------

    /**
     * In the message list: an address in addy.io's send format, through one of your aliases, is shown as who it
     * really reaches, after addy.io's icon, instead of the combined address. Only what's shown changes: replying and
     * everything else still use the real address, so mail keeps going through the alias.
     */
    static void showListAddresses(TextView tv, Address[]... lists) {
        Context context = tv.getContext();
        SpannableStringBuilder text = aliasAddresses(context, tv.getText(), lists, (out, route) -> {
            Drawable icon = context.getDrawable(R.drawable.twotone_alternate_email_24);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(Helper.resolveColor(context, androidx.appcompat.R.attr.colorAccent));
                out.append("@");
                out.setSpan(new IconSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append(route.destination);
        });
        if (text != null)
            tv.setText(text);
    }

    /** The same in an opened message's header, where there's room to say which alias: "hello@store.com via shop@…". */
    static Spanned headerAddresses(Context context, Spanned text, Address[] addresses) {
        SpannableStringBuilder changed = aliasAddresses(context, text, new Address[][]{addresses}, (out, route) -> {
            out.append(route.destination);
            int start = out.length();
            out.append(" via ").append(route.alias);
            out.setSpan(new ForegroundColorSpan(Helper.resolveColor(context, android.R.attr.textColorSecondary)),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(0.85f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        });
        return changed == null ? text : changed;
    }

    /**
     * An icon in a line of text, as tall as the text and centred on it, with a gap after it. Sized as it's drawn,
     * as FairEmail sets the row's text size afterwards, and the gap is part of it, so it isn't underlined.
     */
    private static class IconSpan extends ReplacementSpan {
        private final Drawable icon;

        IconSpan(Drawable icon) {
            this.icon = icon;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(paint.getTextSize() * 1.25f);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            int size = Math.round(paint.getTextSize());
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int left = Math.round(x);
            int iconTop = y + (fm.ascent + fm.descent) / 2 - size / 2;
            icon.setBounds(left, iconTop, left + size, iconTop + size);
            icon.draw(canvas);
        }
    }

    private interface RouteWriter {
        void write(SpannableStringBuilder out, AddyAddresses.SendAddress route);
    }

    /** text with each address through one of your aliases written as writer says, or null if there was none. */
    private static SpannableStringBuilder aliasAddresses(Context context, CharSequence text, Address[][] lists, RouteWriter writer) {
        if (TextUtils.isEmpty(text) || !AddyStore.isSetUp(context))
            return null;
        SpannableStringBuilder result = null;
        for (Address[] list : lists)
            if (list != null)
                for (Address address : list) {
                    if (!(address instanceof InternetAddress))
                        continue;
                    String email = ((InternetAddress) address).getAddress();
                    AddyAddresses.SendAddress route = AddyAddresses.decodeSendAddress(email);
                    if (route == null || !AddyStore.ownsSavedAlias(context, route.alias))
                        continue;
                    if (result == null)
                        result = new SpannableStringBuilder(text);
                    int at = TextUtils.indexOf(result, email);
                    while (at >= 0) {
                        SpannableStringBuilder replacement = new SpannableStringBuilder();
                        writer.write(replacement, route);
                        result.replace(at, at + email.length(), replacement);
                        at = TextUtils.indexOf(result, email, at + replacement.length());
                    }
                }
        return result;
    }

    // --- Receiving mail ------------------------------------------------------------------------------------------

    /**
     * Lets the extensions check a new message as FairEmail stores it, on its background thread, with all of its
     * headers at hand. They can add a warning, which FairEmail shows as its own. A check that fails changes nothing.
     */
    static void checkIncoming(Context context, EntityMessage message, MimeMessage imessage) {
        try {
            AddyIncoming.check(context, message, imessage);
        } catch (Throwable ex) {
            Log.w(ex);
        }
    }

    /**
     * The button beside a message's warning is FairEmail's warning sign for an extension's warning, and FairEmail's
     * own question mark otherwise, as rows are reused.
     */
    static void showWarningIcon(ImageButton ibError, EntityMessage message) {
        // Rows without a warning don't show the button, so there's nothing to set.
        if (ibError == null || ibError.getVisibility() != View.VISIBLE)
            return;
        boolean warns = AddySenders.warns(message.warning);
        Context context = ibError.getContext();
        ibError.setImageResource(warns ? R.drawable.twotone_warning_24 : R.drawable.twotone_help_24);
        ImageViewCompat.setImageTintList(ibError, ColorStateList.valueOf(Helper.resolveColor(context,
                warns ? R.attr.colorWarning : androidx.appcompat.R.attr.colorAccent)));
    }

    /**
     * Marks a new message's notification if an extension warned about it: a sign before who it's from, and what's
     * wrong in a word or two beside the app's name. The subject and preview stay as they are.
     */
    static void addToNotification(Context context, NotificationCompat.Builder builder, EntityMessage message, String from) {
        String label = AddyIncoming.notificationLabel(context, message);
        if (label != null)
            builder.setSmallIcon(R.drawable.twotone_warning_24).setContentTitle("\u26A0 " + from).setSubText(label);
    }

    /** True if a message's warning is an extension's, which then shows what can be done about it. */
    static boolean onWarningHelp(Context context, LifecycleOwner owner, EntityMessage message) {
        return AddyIncoming.showActions(context, owner, message);
    }

    // --- Side menu ----------------------------------------------------------------------------------------------

    /**
     * Adds the addy.io section to the top of the side menu's fixed items, right below your folders: your aliases,
     * a new alias, and sending from one. Only shown once an API key is set. closeDrawer closes the side menu.
     */
    static void addNavMenu(AppCompatActivity activity, List<NavMenuItem> menus, Runnable closeDrawer) {
        // FairEmail rebuilds its main screen when one of these settings changes, so the section comes and goes with the key.
        if (!Arrays.asList(FragmentOptions.OPTIONS_RESTART).contains(AddyStore.PREF_SET_UP)) {
            List<String> restart = new ArrayList<>(Arrays.asList(FragmentOptions.OPTIONS_RESTART));
            restart.add(AddyStore.PREF_SET_UP);
            FragmentOptions.OPTIONS_RESTART = restart.toArray(new String[0]);
        }
        if (!AddyStore.isSetUp(activity))
            return;
        List<NavMenuItem> addy = new ArrayList<>();
        addy.add(new NavMenuItem(R.drawable.twotone_alternate_email_24, R.string.ext_addy_nav_aliases, () -> {
            closeDrawer.run();
            FragmentManager fm = activity.getSupportFragmentManager();
            if (activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                fm.popBackStack("ext:addy", FragmentManager.POP_BACK_STACK_INCLUSIVE);
            fm.beginTransaction()
                    .replace(R.id.content_frame, new FragmentAddyAliases())
                    .addToBackStack("ext:addy")
                    .commit();
        }));
        addy.add(new NavMenuItem(R.drawable.twotone_add_24, R.string.ext_addy_nav_new, () -> {
            closeDrawer.run();
            new FragmentDialogAddyNew().show(activity.getSupportFragmentManager(), "ext:addy:new");
        }));
        addy.add(new NavMenuItem(R.drawable.twotone_send_24, R.string.ext_addy_nav_send, () -> {
            closeDrawer.run();
            new FragmentDialogAddySend().show(activity.getSupportFragmentManager(), "ext:addy:send");
        }).setSeparated());
        menus.addAll(0, addy);
        addDividers(activity);
    }

    /**
     * A line under side menu items marked as separated, as FairEmail already draws in the lower part of the menu.
     * Added to the menu's list from here, so FairEmail's own code needn't change.
     */
    private static void addDividers(AppCompatActivity activity) {
        RecyclerView rvMenu = activity.findViewById(R.id.rvMenu);
        if (rvMenu == null || rvMenu.getTag(R.id.rvMenu) != null)
            return;
        rvMenu.setTag(R.id.rvMenu, true);
        Drawable divider = activity.getDrawable(R.drawable.divider);
        if (divider == null)
            return;
        DividerItemDecoration decoration = new DividerItemDecoration(activity, DividerItemDecoration.VERTICAL) {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                RecyclerView.Adapter<?> adapter = parent.getAdapter();
                NavMenuItem item = (adapter instanceof AdapterNavMenu && pos >= 0 ? ((AdapterNavMenu) adapter).get(pos) : null);
                outRect.set(0, 0, 0, item != null && item.isSeparated() ? divider.getIntrinsicHeight() : 0);
            }
        };
        decoration.setDrawable(divider);
        rvMenu.addItemDecoration(decoration);
    }

    // --- Settings ------------------------------------------------------------------------------------------------

    /** Adds an Extensions card at the top of the Integrations settings, by wrapping the page rather than changing it. */
    static void addSettings(Fragment fragment, View view) {
        ScrollView scroll = view.findViewById(R.id.scroll);
        if (scroll == null || scroll.getChildCount() != 1)
            return;
        Context context = view.getContext();
        View content = scroll.getChildAt(0);
        scroll.removeView(content);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(View.inflate(context, R.layout.ext_settings_card, null));
        column.addView(content);
        scroll.addView(column);
        column.findViewById(R.id.btnExtAddy).setOnClickListener(v ->
                new FragmentDialogAddySettings().show(fragment.getParentFragmentManager(), "ext:addy:settings"));
        column.findViewById(R.id.btnExtAi).setOnClickListener(v ->
                new FragmentDialogAiSettings().show(fragment.getParentFragmentManager(), "ext:ai:settings"));
        column.findViewById(R.id.btnExtGokapi).setOnClickListener(v ->
                new FragmentDialogGokapiSettings().show(fragment.getParentFragmentManager(), "ext:gokapi:settings"));
        // FairEmail hides its own switch for this in builds it didn't sign, so it's here instead, for the same setting.
        if (PlusUpdates.isAvailable()) {
            SwitchCompat swUpdates = column.findViewById(R.id.swExtUpdates);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            swUpdates.setChecked(prefs.getBoolean("updates", true));
            swUpdates.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("updates", checked).apply());
            swUpdates.setVisibility(View.VISIBLE);
            column.findViewById(R.id.tvExtUpdatesHint).setVisibility(View.VISIBLE);
        }
    }

    // --- Updates ------------------------------------------------------------------------------------------------

    /** True if this is a build from FairEmail Plus's GitHub workflow, which then checks its own releases instead. */
    static boolean checkUpdate(AppCompatActivity activity, boolean always) {
        return PlusUpdates.check(activity, always);
    }
}
