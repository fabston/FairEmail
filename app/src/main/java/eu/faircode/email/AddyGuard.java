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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a send would expose your real address. Takes plain data and has no side effects, so it's covered
 * by unit tests. Ported from the Betterbird add-on.
 * <p>
 * Everything here can come from a hostile email, so it stays linear in the size of its input, and only credits an
 * address as yours once that's been confirmed. Headers and an address's shape prove nothing.
 */
public class AddyGuard {
    /** The email being sent. Addresses are plain addresses, without names. */
    public static class Draft {
        public List<String> to = new ArrayList<>();
        public List<String> cc = new ArrayList<>();
        public List<String> bcc = new ArrayList<>();
        public List<String> replyTo = new ArrayList<>();
        public String from = "";
        public String subject = "";
        public String html = "";
        public boolean reply;
        public boolean signed;
    }

    public static class Identity {
        public final long id;
        public final String email;
        public final String name;

        public Identity(long id, String email, String name) {
            this.id = id;
            this.email = email;
            this.name = name;
        }
    }

    /** For a reply: which alias the original came through, going by its headers, which anyone can forge. */
    public static class Related {
        public final String alias;

        public Related(String alias) {
            this.alias = alias;
        }
    }

    public static class Problem {
        public final String id;
        public final String title;
        public final String detail;

        Problem(String id, String title, String detail) {
            this.id = id;
            this.title = title;
            this.detail = detail;
        }
    }

    public static class Input {
        public Draft draft = new Draft();
        public Identity identity;
        public List<Identity> identities = new ArrayList<>();
        public AddyAccount account;
        public Related related; // can only add a warning, never make an address count as yours
        public List<String> confirmed = new ArrayList<>(); // aliases addy.io has confirmed are yours
        public boolean warnDirect; // also stop email that doesn't go through an alias at all
        public List<String> trusted = new ArrayList<>(); // who warnDirect needn't ask about
    }

    private static final Pattern DECIMAL = Pattern.compile("&#(\\d{1,7});");
    private static final Pattern HEX = Pattern.compile("&#x([0-9a-fA-F]{1,6});");
    private static final Pattern NAMED = Pattern.compile("&(commat|period|plus|amp|lt|gt|nbsp);", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile("%([0-9a-fA-F]{2})");

    private static String lower(String value) {
        return AddyAddresses.lower(value);
    }

    private static String join(List<String> values) {
        return String.join(", ", values);
    }

    /** Lowercased HTML without its style blocks. A block that's never closed is left in, which can only find more. */
    static String withoutStyles(String html) {
        String lowered = lower(html);
        StringBuilder kept = new StringBuilder();
        int position = 0;
        while (position < lowered.length()) {
            int open = lowered.indexOf("<style", position);
            int close = open == -1 ? -1 : lowered.indexOf("</style>", open);
            if (close == -1)
                break;
            kept.append(lowered, position, open).append(' ');
            position = close + "</style>".length();
        }
        kept.append(lowered.substring(position));
        return kept.toString();
    }

    static String htmlToText(String html) {
        String source = withoutStyles(html);
        StringBuilder kept = new StringBuilder();
        int position = 0;
        while (position < source.length()) {
            int open = source.indexOf('<', position);
            int close = open == -1 ? -1 : source.indexOf('>', open);
            if (close == -1)
                break;
            kept.append(source, position, open).append(' ');
            position = close + 1;
        }
        kept.append(source.substring(position));
        return decodeEncoded(kept.toString());
    }

    private static String codePoint(int value) {
        return value > 0 && value <= 0x10ffff ? new String(Character.toChars(value)) : " ";
    }

    private interface Replacer {
        String replace(Matcher matcher);
    }

    private static String replaceAll(Pattern pattern, String text, Replacer replacer) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            result.append(text, last, matcher.start()).append(replacer.replace(matcher));
            last = matcher.end();
        }
        return result.append(text.substring(last)).toString();
    }

    /** Write out what's encoded in links and HTML, as "jane%2Bnews%40example.com" is still your address. */
    static String decodeEncoded(String text) {
        String result = replaceAll(DECIMAL, text, (m) -> {
            try {
                return codePoint(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ex) {
                return " ";
            }
        });
        result = replaceAll(HEX, result, (m) -> codePoint(Integer.parseInt(m.group(1), 16)));
        result = replaceAll(NAMED, result, (m) -> {
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "commat":
                    return "@";
                case "period":
                    return ".";
                case "plus":
                    return "+";
                case "amp":
                    return "&";
                case "lt":
                    return "<";
                case "gt":
                    return ">";
                default:
                    return " ";
            }
        });
        return replaceAll(PERCENT, result, (m) -> String.valueOf((char) Integer.parseInt(m.group(1), 16)));
    }

    /** Text to look for a name in: wrapped lines, odd spaces and *bold* or /italic/ markers don't hide it. */
    static String comparable(String text) {
        return lower(text).replaceAll("[*/_]", "").replaceAll("\\s+", " ");
    }

    /**
     * Lowercased HTML without its quoted mail, which FairEmail puts in blockquotes. Nested quotes are skipped whole.
     * Works on the lowercased copy only, as lowercasing can change the length, so its positions don't fit the original.
     */
    static String withoutQuotes(String html) {
        String lowered = lower(html);
        StringBuilder kept = new StringBuilder();
        int position = 0;
        int depth = 0;
        // Each tag is only looked for again once it's been passed, so deep nesting stays linear.
        int open = lowered.indexOf("<blockquote");
        int close = lowered.indexOf("</blockquote");
        while (open != -1 || close != -1) {
            boolean opening = open != -1 && (close == -1 || open < close);
            int at = opening ? open : close;
            if (depth == 0)
                kept.append(lowered, position, at).append(' ');
            depth = opening ? depth + 1 : Math.max(0, depth - 1);
            int end = lowered.indexOf('>', at);
            position = end == -1 ? lowered.length() : end + 1;
            if (open != -1 && open < position)
                open = lowered.indexOf("<blockquote", position);
            if (close != -1 && close < position)
                close = lowered.indexOf("</blockquote", position);
        }
        if (depth == 0 && position < lowered.length())
            kept.append(lowered.substring(position));
        return kept.toString();
    }

    private static String unquotedLines(String text) {
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\r?\\n"))
            if (!line.trim().startsWith(">"))
                kept.append(line).append('\n');
        return kept.toString();
    }

    /** The aliases this send would need confirming as yours, because they could belong to any addy.io user. */
    static List<String> aliasesToConfirm(Input input) {
        if (input.account == null)
            return Collections.emptyList();
        Draft draft = input.draft;
        List<String> candidates = new ArrayList<>();
        for (String address : everyone(draft)) {
            AddyAddresses.SendAddress decoded = AddyAddresses.decodeSendAddress(address);
            candidates.add(decoded == null ? address : decoded.alias);
        }
        if (input.related != null)
            candidates.add(input.related.alias);
        String sender = senderOf(input);
        candidates.add(sender);
        Set<String> unique = new LinkedHashSet<>();
        for (String candidate : candidates)
            if (candidate != null && !candidate.isEmpty())
                unique.add(lower(candidate));
        List<String> result = new ArrayList<>();
        for (String address : unique)
            if (AddyAddresses.isAddyDomain(address, input.account) && !AddyAddresses.ownsAlias(address, input.account, null))
                result.add(address);
        return result;
    }

    private static String senderOf(Input input) {
        if (input.draft.from != null && !input.draft.from.isEmpty())
            return input.draft.from;
        return input.identity == null || input.identity.email == null ? "" : input.identity.email;
    }

    /** What's wrong with this send. Empty means it can go ahead. */
    static List<Problem> check(Input input) {
        List<Problem> problems = new ArrayList<>();
        Draft draft = input.draft;
        AddyAccount account = input.account;
        List<String> copies = new ArrayList<>(draft.cc);
        copies.addAll(draft.bcc);
        List<String> everyone = everyone(draft);

        List<String> throughAlias = new ArrayList<>();
        // Your own alias can come back on a reply all. Mail to it reaches only you, so it isn't a leak.
        List<String> direct = new ArrayList<>();
        for (String address : everyone)
            if (usesAlias(address, input))
                throughAlias.add(address);
            else if (!isMine(address, input))
                direct.add(address);

        String sender = senderOf(input);
        List<String> recipients = account == null ? Collections.emptyList() : account.recipientEmails();

        if (draft.reply && input.related != null && throughAlias.isEmpty()) {
            if (!direct.isEmpty())
                problems.add(new Problem("reply-real-address",
                        "This reply will show your real address",
                        "It answers mail sent to " + input.related.alias + ", but this reply goes straight to " + join(direct) +
                                ". Reply again from the original message to keep the alias's address in To."));
            return problems;
        }

        if (throughAlias.isEmpty()) {
            List<String> unasked = new ArrayList<>();
            for (String address : direct)
                if (!AddyAddresses.isTrustedRecipient(address, input.trusted))
                    unasked.add(address);
            boolean sendingFromAlias = isMine(sender, input) && !contains(recipients, sender);
            if (input.warnDirect && !unasked.isEmpty() && !sendingFromAlias)
                problems.add(new Problem("real-sender",
                        "This email will show your real address",
                        "It goes straight to " + join(unasked) + " from " + (sender.isEmpty() ? "this identity" : sender) +
                                ". To hide your address, go back, tap the menu and choose addy.io alias."));
            return problems;
        }

        if (!direct.isEmpty())
            problems.add(new Problem("mixed-recipients",
                    "Some recipients will see your real address",
                    join(direct) + (direct.size() == 1 ? " isn't" : " aren't") +
                            " going through your alias, so they'd get this from your real address."));

        for (String address : copies)
            if (usesAlias(address, input)) {
                problems.add(new Problem("cc-bcc",
                        "Cc and Bcc through an alias are untested",
                        "addy.io's documentation doesn't say how these are handled. Sending separate emails is safer."));
                break;
            }

        List<String> verified = account == null ? Collections.emptyList() : account.verifiedAddresses();
        if (!verified.isEmpty() && !contains(verified, sender)) {
            List<String> destinations = new ArrayList<>();
            for (String address : throughAlias)
                destinations.add(AddyAddresses.decodeSendAddress(address).destination);
            boolean hasReplacement = false;
            for (Identity identity : input.identities)
                if (identity != null && contains(verified, identity.email))
                    hasReplacement = true;
            problems.add(new Problem("unverified-sender",
                    "This email is being sent from the wrong address",
                    "You're sending from " + (sender.isEmpty() ? "an identity addy.io doesn't accept" : sender) +
                            ", but addy.io only sends from your alias if the email comes from " + join(verified) +
                            ". As it is, the email would come back to you instead of reaching " + join(destinations) + "." +
                            (hasReplacement
                                    ? " Choose that identity in From."
                                    : " Add one of those addresses as an identity in FairEmail to send from it.")));
        }

        if (account != null && account.freePlan)
            problems.add(new Problem("free-plan",
                    "Sending from an alias needs a paid plan",
                    "addy.io's Free plan doesn't include this. Lite and Pro do."));

        String text = htmlToText(draft.html);
        String lowered = lower(decodeEncoded(text + "\n" + withoutStyles(draft.html)));
        Set<String> real = new LinkedHashSet<>();
        List<Identity> yours = new ArrayList<>();
        yours.add(input.identity);
        yours.addAll(input.identities);
        for (Identity identity : yours)
            if (identity != null && identity.email != null &&
                    (contains(recipients, identity.email) || !isMine(identity.email, input)))
                real.add(lower(identity.email));
        for (String recipient : recipients)
            real.add(lower(recipient));

        List<String> inBody = new ArrayList<>();
        for (String address : real)
            if (lowered.contains(address))
                inBody.add(address);
        if (!inBody.isEmpty())
            problems.add(new Problem("address-in-body",
                    "Your real address is in the email",
                    join(inBody) + " appears in the text, the signature, a link or the quoted email."));

        String name = input.identity == null || input.identity.name == null ? "" : input.identity.name.trim();
        String fullName = name.split("\\s+").length >= 2 ? name : "";
        String written = unquotedLines(htmlToText(withoutQuotes(draft.html)));
        if (!fullName.isEmpty() && comparable(written).contains(comparable(fullName)))
            problems.add(new Problem("name-in-body",
                    "Your name is in the email",
                    "\"" + fullName + "\" appears in what you wrote or your signature."));

        String subject = lower(draft.subject);
        List<String> inSubject = new ArrayList<>();
        for (String address : real)
            if (subject.contains(address))
                inSubject.add(address);
        if (!fullName.isEmpty() && comparable(subject).contains(comparable(fullName)))
            inSubject.add(fullName);
        if (!inSubject.isEmpty())
            problems.add(new Problem("in-subject",
                    "Your real details are in the subject",
                    join(inSubject) + " appears in the subject, which addy.io sends as it is."));

        if (draft.signed)
            problems.add(new Problem("signed",
                    "This email will be signed or encrypted",
                    "addy.io can pass your signature or key on, and it can reveal your real address. Turn off signing and encryption for this email."));

        List<String> replyTo = new ArrayList<>();
        for (String address : draft.replyTo)
            if (!usesAlias(address, input) && !isMine(address, input))
                replyTo.add(address);
        if (!replyTo.isEmpty())
            problems.add(new Problem("reply-to",
                    "This email has a Reply-To address",
                    join(replyTo) + " is set as the Reply-To, which could reveal it. Check this identity's settings."));

        return problems;
    }

    /** Each recipient that goes through one of your aliases: the alias it's sent from, and who it really reaches. */
    static List<AddyAddresses.SendAddress> routes(Input input) {
        List<AddyAddresses.SendAddress> routes = new ArrayList<>();
        for (String address : everyone(input.draft))
            if (usesAlias(address, input))
                routes.add(AddyAddresses.decodeSendAddress(address));
        return routes;
    }

    /** Everyone the email goes to: To, Cc and Bcc. */
    private static List<String> everyone(Draft draft) {
        List<String> everyone = new ArrayList<>(draft.to);
        everyone.addAll(draft.cc);
        everyone.addAll(draft.bcc);
        return everyone;
    }

    private static boolean contains(List<String> addresses, String address) {
        for (String entry : addresses)
            if (AddyAddresses.sameAddress(entry, address))
                return true;
        return false;
    }

    private static boolean isMine(String address, Input input) {
        return AddyAddresses.ownsAlias(address, input.account, input.confirmed);
    }

    private static boolean usesAlias(String address, Input input) {
        AddyAddresses.SendAddress decoded = AddyAddresses.decodeSendAddress(address);
        return decoded != null && isMine(decoded.alias, input);
    }
}
