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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Email addresses, and the format addy.io uses to send from an alias:
 * shop+hello=store.com@johndoe.addy.io sends to hello@store.com from shop@johndoe.addy.io.
 * Plain Java with no Android parts, so it's covered by unit tests. Ported from the Betterbird add-on.
 */
public class AddyAddresses {
    // Known without asking addy.io, so a send is still checked before your account has loaded, or if addy.io is down.
    static final List<String> ADDY_DOMAINS = Arrays.asList("addy.io", "anonaddy.com", "anonaddy.me");
    private static final Pattern SAFE_ADDRESS = Pattern.compile("^[^\\s,;\"<>()\\\\@]+@[^\\s,;\"<>()\\\\@]+$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)+$");

    /** An alias and who it sends to, from an address in addy.io's send format. */
    public static class SendAddress {
        public final String alias;
        public final String destination;

        SendAddress(String alias, String destination) {
            this.alias = alias;
            this.destination = destination;
        }
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static boolean sameAddress(String first, String second) {
        return first != null && !first.isEmpty() && lower(first).equals(lower(second));
    }

    static boolean isPlainAddress(String address) {
        return address != null && SAFE_ADDRESS.matcher(address).matches();
    }

    static String domainOf(String address) {
        String text = address == null ? "" : address;
        return lower(text.substring(text.lastIndexOf('@') + 1));
    }

    /** Throws rather than build an address from anything that could be read as more than one person. */
    static String encodeSendAddress(String alias, String destination) {
        if (!isPlainAddress(alias) || !isPlainAddress(destination))
            throw new IllegalArgumentException("That isn't a valid email address");
        int aliasAt = alias.lastIndexOf('@');
        int destinationAt = destination.lastIndexOf('@');
        return alias.substring(0, aliasAt) + "+" + destination.substring(0, destinationAt) + "=" +
                destination.substring(destinationAt + 1) + "@" + alias.substring(aliasAt + 1);
    }

    /** Split an encoded address into the alias and who it goes to, or null if it isn't one. */
    static SendAddress decodeSendAddress(String address) {
        if (address == null)
            return null;
        int at = address.lastIndexOf('@');
        if (at < 1)
            return null;
        String local = address.substring(0, at);
        String domain = address.substring(at + 1);
        // The destination can contain + and =, so the alias ends at the first + and the domain starts after the last =.
        int plus = local.indexOf('+');
        int equals = local.lastIndexOf('=');
        if (plus < 1 || equals <= plus + 1 || equals == local.length() - 1)
            return null;
        String destinationDomain = local.substring(equals + 1);
        if (!destinationDomain.contains("."))
            return null;
        return new SendAddress(
                local.substring(0, plus) + "@" + domain,
                local.substring(plus + 1, equals) + "@" + destinationDomain);
    }

    static boolean isOnDomains(String address, Collection<String> domains) {
        String domain = domainOf(address);
        for (String known : domains) {
            String k = lower(known);
            if (domain.equals(k) || domain.endsWith("." + k))
                return true;
        }
        return false;
    }

    private static boolean includesDomain(Collection<String> domains, String domain) {
        for (String known : domains)
            if (lower(known).equals(domain))
                return true;
        return false;
    }

    /** Whether this address is on a domain addy.io handles, whoever's alias it is. */
    static boolean isAddyDomain(String address, AddyAccount account) {
        return isOnDomains(address, ADDY_DOMAINS) ||
                (account != null && includesDomain(account.domains, domainOf(address)));
    }

    /**
     * Whether an alias is yours. Your own domains are matched exactly, as every addy.io user has a subdomain of theirs.
     * Anyone can have an alias on a shared domain, so those have to be in confirmed, which comes from your account.
     * With no account there's nothing to confirm against, so addy.io's own domains are the best that can be done.
     */
    static boolean ownsAlias(String address, AddyAccount account, Collection<String> confirmed) {
        if (address == null || address.isEmpty())
            return false;
        if (account == null)
            return isOnDomains(address, ADDY_DOMAINS);
        String domain = domainOf(address);
        List<String> shared = new ArrayList<>(ADDY_DOMAINS);
        shared.addAll(account.sharedDomains);
        if (includesDomain(account.domains, domain) && !includesDomain(shared, domain))
            return true;
        if (confirmed != null)
            for (String alias : confirmed)
                if (sameAddress(alias, address))
                    return true;
        return false;
    }

    /** Whether a recipient is on your list of who you're happy to email from your real address. */
    static boolean isTrustedRecipient(String address, Collection<String> trusted) {
        String lowered = lower(address);
        for (String entry : trusted)
            if (entry.equals(lowered) || entry.equals(domainOf(lowered)))
                return true;
        return false;
    }

    /** What the trusted list can hold: whole addresses and domains, written the same way. Anything else is dropped. */
    static List<String> cleanTrustedRecipients(Collection<String> entries) {
        Set<String> usable = new LinkedHashSet<>();
        for (String entry : entries) {
            String cleaned = lower(entry).trim();
            if (SAFE_ADDRESS.matcher(cleaned).matches() || DOMAIN.matcher(cleaned).matches())
                usable.add(cleaned);
        }
        return new ArrayList<>(usable);
    }
}
