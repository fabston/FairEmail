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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether mail to an alias comes from who that alias is for. An alias's description names the domains it
 * expects, like "main account 1password.com", and mail from anywhere else is flagged: a fake, or a sign the alias
 * was leaked. Takes plain data and has no side effects, so it's covered by unit tests.
 * <p>
 * Domains are compared by the part you'd register, so news.1password.com counts as 1password.com, and
 * 1password.com.evil.io doesn't.
 */
public class AddySenders {
    /** Starts every warning, so it's clear where it comes from, and so it can be found again. */
    static final String MARK = "addy.\u2060io: ";

    private static final String SUGGESTION = "tap the icon to expect only ";

    /** Whether a message's warning is one of these, and a real one: not just the offer of a sender for an alias. */
    static boolean warns(String warning) {
        return warning != null && warning.contains(MARK) && !warning.contains(SUGGESTION);
    }

    enum Kind {
        UNEXPECTED, // from a domain the alias doesn't expect
        LOOKALIKE, // and one that imitates an expected one
        FORGED, // from an expected domain, but its authentication failed
        SUGGEST // the alias expects nothing yet, and this sender could be it
    }

    static class Finding {
        final Kind kind;
        final String text;

        Finding(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    // A domain, alone or as the end of an email address.
    private static final Pattern DOMAIN = Pattern.compile(
            "(?<![\\w.-])([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+)(?![\\w-])");
    private static final Pattern DMARC = Pattern.compile("\\bdmarc\\s*=\\s*([a-z]+)");
    private static final Pattern PROVIDER_DMARC = Pattern.compile("^\\s*dmarc\\s*=\\s*([a-z]+)");
    private static final Pattern HEADER_FROM = Pattern.compile("\\bheader\\.from\\s*=\\s*([^\\s;]+)");
    private static final Pattern FEEDBACK_ID = Pattern.compile("^F:([^:]+):anonaddy$");

    /** Everything in a description that could be a domain, lowercased. Whether each is a real one is up to the caller. */
    static List<String> domainsIn(String description) {
        List<String> found = new ArrayList<>();
        Matcher matcher = DOMAIN.matcher(description == null ? "" : description.toLowerCase(Locale.ROOT));
        while (matcher.find())
            if (!found.contains(matcher.group(1)))
                found.add(matcher.group(1));
        return found;
    }

    /** How addy.io's check of the original email's DMARC went, like "pass" or "fail". Null if it doesn't say. */
    static String dmarc(String authenticationResults) {
        Matcher matcher = DMARC.matcher(authenticationResults == null ? "" : authenticationResults.toLowerCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Flags an email that your mail provider didn't see come from the alias's domain, so it wasn't forwarded by
     * addy.io, and what its addy.io headers say is anyone's word. authenticationResults is the email's
     * Authentication-Results headers from the top: your provider adds its own above any that came with the email.
     * Null if it did come from there, or your provider doesn't say.
     */
    static Finding arrival(String[] authenticationResults, String aliasDomain) {
        if (authenticationResults == null || aliasDomain == null)
            return null;
        for (String header : authenticationResults) {
            // Comments and quoted strings go first, as they can repeat what the sender wrote, a fake result included.
            String plain = header.toLowerCase(Locale.ROOT).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", " ");
            for (String stripped = null; !plain.equals(stripped); ) {
                stripped = plain;
                plain = plain.replaceAll("\\([^()]*\\)", " ");
            }
            for (String clause : plain.split(";")) {
                Matcher dmarc = PROVIDER_DMARC.matcher(clause);
                if (!dmarc.find())
                    continue;
                Matcher from = HEADER_FROM.matcher(clause);
                boolean elsewhere = from.find() && !from.group(1).equals(aliasDomain.toLowerCase(Locale.ROOT));
                return "fail".equals(dmarc.group(1)) || elsewhere
                        ? new Finding(Kind.FORGED, MARK + "this didn't come through your alias. Its sender may be forged.")
                        : null;
            }
        }
        return null;
    }

    /** The alias's id, from the Feedback-ID header addy.io gives every email it forwards. Null if it isn't one. */
    static String aliasId(String feedbackId) {
        Matcher matcher = FEEDBACK_ID.matcher(feedbackId == null ? "" : feedbackId.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * A domain as it's written in a warning. FairEmail turns web addresses in warnings into links, and a warning
     * mustn't offer a link to what it warns about, so an invisible character after each dot keeps it plain text.
     */
    static String shown(String domain) {
        return domain.replace(".", ".\u2060");
    }

    private static String name(String domain) {
        int dot = domain.indexOf('.');
        return dot < 0 ? domain : domain.substring(0, dot);
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
            previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
                current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1,
                        previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return previous[b.length()];
    }

    /** The expected domain a sender imitates: its name in another domain, or within a letter or two of it. Or null. */
    private static String imitated(Collection<String> expected, String senderHost, String senderRoot) {
        String sender = name(senderRoot);
        for (String domain : expected) {
            String wanted = name(domain);
            // Short names turn up in unrelated domains too often to call those imitations.
            if (sender.equals(wanted) ||
                    (wanted.length() >= 6 && (senderHost.contains(wanted) || distance(sender, wanted) <= 2)))
                return domain;
        }
        return null;
    }

    /**
     * What's wrong with an email to an alias, or null if nothing is. expected is the registrable domains the alias's
     * description names, senderHost the domain the email is really from and senderRoot its registrable part, and
     * dmarc how addy.io's check of it went. With suggest, an alias that expects nothing yet is offered this sender.
     */
    static Finding check(Collection<String> expected, String senderHost, String senderRoot, String dmarc, boolean suggest) {
        if (senderRoot == null || senderRoot.isEmpty())
            return null;
        boolean failed = "fail".equals(dmarc);
        if (expected.isEmpty())
            // A sender that fails its own check isn't one to suggest.
            return suggest && !failed
                    ? new Finding(Kind.SUGGEST, MARK + SUGGESTION + shown(senderRoot) + " on this alias.")
                    : null;
        if (expected.contains(senderRoot))
            return failed
                    ? new Finding(Kind.FORGED, MARK + "couldn't confirm this is from " + shown(senderRoot) + ". It may be forged.")
                    : null;
        String imitated = imitated(expected, senderHost == null ? senderRoot : senderHost, senderRoot);
        if (imitated != null)
            return new Finding(Kind.LOOKALIKE, MARK + shown(senderRoot) + " imitates " + shown(imitated) + ". It may be fake.");
        return new Finding(Kind.UNEXPECTED, MARK + "unexpected sender " + shown(senderRoot) +
                ". This alias is for " + shown(String.join(", ", expected)) + ".");
    }

    /** A message's warning without this one, which FairEmail may have joined to its own. Null if nothing is left. */
    static String without(String warning, String text) {
        if (warning == null)
            return null;
        String rest = warning.replace(text, "").trim();
        while (rest.startsWith(","))
            rest = rest.substring(1).trim();
        while (rest.endsWith(","))
            rest = rest.substring(0, rest.length() - 1).trim();
        return rest.isEmpty() ? null : rest;
    }
}
