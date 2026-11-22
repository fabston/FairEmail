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
import java.util.List;
import java.util.Locale;

/** What the add-on needs to know about your addy.io account. Kept on your phone for an hour at a time. */
public class AddyAccount {
    public static class Recipient {
        public final String email;
        public final boolean canReplySend;
        public final boolean verified;

        public Recipient(String email, boolean canReplySend, boolean verified) {
            this.email = email;
            this.canReplySend = canReplySend;
            this.verified = verified;
        }
    }

    public long fetchedAt;
    public String username = "";
    public String subscription; // null on the Free plan
    public boolean freePlan;
    public List<String> domains = new ArrayList<>();
    public List<String> sharedDomains = new ArrayList<>();
    public String defaultDomain = "";
    public String defaultFormat = "random_words";
    public List<Recipient> recipients = new ArrayList<>();

    /** Every recipient's address. */
    List<String> recipientEmails() {
        List<String> emails = new ArrayList<>();
        for (Recipient recipient : recipients)
            if (recipient.email != null)
                emails.add(recipient.email);
        return emails;
    }

    /** The addresses addy.io accepts sending from: verified recipients that may reply and send. */
    List<String> verifiedAddresses() {
        List<String> emails = new ArrayList<>();
        for (Recipient recipient : recipients)
            if (recipient.verified && recipient.canReplySend && recipient.email != null)
                emails.add(recipient.email);
        return emails;
    }

    /** A size the way addy.io shows it, counting 1024 bytes to the KB: "5.2 MB", "10 GB". */
    static String size(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        String number = value < 10 && unit > 0 && value != Math.floor(value)
                ? String.format(Locale.ROOT, "%.1f", value)
                : String.format(Locale.ROOT, "%.0f", value);
        return number + " " + units[unit];
    }
}
