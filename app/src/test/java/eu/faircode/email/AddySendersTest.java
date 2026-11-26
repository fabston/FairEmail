package eu.faircode.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AddySendersTest {
    private static final List<String> ONE_PASSWORD = Collections.singletonList("1password.com");

    private static AddySenders.Kind kind(List<String> expected, String host, String root, String dmarc) {
        AddySenders.Finding finding = AddySenders.check(expected, host, root, dmarc, true);
        return finding == null ? null : finding.kind;
    }

    @Test
    public void domainsAreFoundAnywhereInADescription() {
        assertEquals(Collections.singletonList("1password.com"), AddySenders.domainsIn("main account 1password.com"));
        assertEquals(Arrays.asList("paypal.com", "paypal.de"), AddySenders.domainsIn("PayPal: PayPal.com, paypal.de. Paypal.com"));
        assertEquals(Collections.singletonList("shop.example.co.uk"), AddySenders.domainsIn("orders (shop.example.co.uk)"));
        // The domain of an address counts. What's before the @ is found too, and left to the caller to drop.
        assertTrue(AddySenders.domainsIn("billing@stripe.com").contains("stripe.com"));
        assertEquals(Collections.emptyList(), AddySenders.domainsIn("my main account"));
        assertEquals(Collections.emptyList(), AddySenders.domainsIn(null));
    }

    @Test
    public void addysHeadersAreRead() {
        assertEquals("pass", AddySenders.dmarc("mail.addy.io; dkim=pass header.d=1password.com; spf=pass; dmarc=pass (policy=reject) header.from=1password.com"));
        assertEquals("fail", AddySenders.dmarc("mail.addy.io; DMARC = Fail header.from=x.com"));
        assertNull(AddySenders.dmarc("mail.addy.io; spf=pass"));
        assertNull(AddySenders.dmarc(null));
        assertEquals("5f4d-9a", AddySenders.aliasId("F:5f4d-9a:anonaddy"));
        assertNull(AddySenders.aliasId("F:1:someoneelse"));
        assertNull(AddySenders.aliasId(null));
    }

    @Test
    public void mailYourProviderDidntSeeComeFromTheAliasIsForged() {
        String alias = "me.anonaddy.com";
        String passed = "mx.provider.com; dkim=pass header.d=anonaddy.com;\r\n\tdmarc=pass (p=reject) header.from=me.anonaddy.com";
        assertNull(AddySenders.arrival(new String[]{passed}, alias));
        assertNull(AddySenders.arrival(new String[]{passed}, "Me.AnonAddy.com"));
        // Written straight to your address, with addy.io's headers copied in.
        assertEquals(AddySenders.Kind.FORGED, AddySenders.arrival(
                new String[]{"mx.provider.com; spf=pass; dmarc=pass header.from=evil.io"}, alias).kind);
        assertEquals(AddySenders.Kind.FORGED, AddySenders.arrival(
                new String[]{"mx.provider.com; dmarc=fail header.from=me.anonaddy.com"}, alias).kind);
        // Your provider's result is the top one. One that came with the email, or sits in a comment, doesn't count.
        assertEquals(AddySenders.Kind.FORGED, AddySenders.arrival(
                new String[]{"mx.provider.com; dmarc=pass header.from=evil.io", passed}, alias).kind);
        assertEquals(AddySenders.Kind.FORGED, AddySenders.arrival(new String[]{
                "mx.provider.com; spf=pass (\"x; dmarc=pass header.from=me.anonaddy.com\" (y; dmarc=pass)); dmarc=fail header.from=evil.io"}, alias).kind);
        // A provider that doesn't say isn't held against the email.
        assertNull(AddySenders.arrival(new String[]{"mx.provider.com; spf=pass"}, alias));
        assertNull(AddySenders.arrival(new String[]{"mx.provider.com; dmarc=none header.from=me.anonaddy.com"}, alias));
        assertNull(AddySenders.arrival(null, alias));
    }

    @Test
    public void mailFromTheExpectedDomainPasses_whateverItsSubdomain() {
        assertNull(kind(ONE_PASSWORD, "1password.com", "1password.com", "pass"));
        assertNull(kind(ONE_PASSWORD, "news.mail.1password.com", "1password.com", "pass"));
        // No word from addy.io about its authentication isn't held against it.
        assertNull(kind(ONE_PASSWORD, "1password.com", "1password.com", null));
        assertNull(kind(Arrays.asList("paypal.com", "paypal.de"), "paypal.de", "paypal.de", "pass"));
    }

    @Test
    public void anExpectedDomainThatFailsItsOwnCheckIsForged() {
        assertEquals(AddySenders.Kind.FORGED, kind(ONE_PASSWORD, "1password.com", "1password.com", "fail"));
    }

    @Test
    public void mailFromElsewhereIsFlagged_andImitationsMoreStrongly() {
        AddySenders.Finding other = AddySenders.check(ONE_PASSWORD, "mailer.shopify.com", "shopify.com", "pass", true);
        assertEquals(AddySenders.Kind.UNEXPECTED, other.kind);
        // Nothing in a warning is a link: FairEmail would make one of any web address, the suspicious one included.
        assertEquals("addy.io: unexpected sender shopify.com. This alias is for 1password.com.", other.text.replace("\u2060", ""));
        assertTrue(!other.text.contains("shopify.com") && !other.text.contains("addy.io"));
        // A letter changed, another ending, or the name tucked into another domain.
        assertEquals(AddySenders.Kind.LOOKALIKE, kind(ONE_PASSWORD, "1passw0rd.com", "1passw0rd.com", "pass"));
        assertEquals(AddySenders.Kind.LOOKALIKE, kind(ONE_PASSWORD, "1password.co", "1password.co", "pass"));
        assertEquals(AddySenders.Kind.LOOKALIKE, kind(ONE_PASSWORD, "1password.com.evil.io", "evil.io", "pass"));
        assertEquals(AddySenders.Kind.LOOKALIKE, kind(ONE_PASSWORD, "1password-security.com", "1password-security.com", null));
        // A short name isn't enough to call something an imitation: it turns up in unrelated domains too.
        assertEquals(AddySenders.Kind.UNEXPECTED, kind(Collections.singletonList("hp.com"), "shop.example", "shop.example", "pass"));
        assertEquals(AddySenders.Kind.UNEXPECTED, kind(Collections.singletonList("uber.com"), "ubereats.com", "ubereats.com", "pass"));
        assertEquals(AddySenders.Kind.LOOKALIKE, kind(Collections.singletonList("uber.com"), "uber.co", "uber.co", "pass"));
    }

    @Test
    public void anAliasThatExpectsNothingIsOfferedItsSender_ifAsked() {
        AddySenders.Finding suggestion = AddySenders.check(Collections.emptyList(), "mail.1password.com", "1password.com", "pass", true);
        assertEquals(AddySenders.Kind.SUGGEST, suggestion.kind);
        assertTrue(suggestion.text.contains("expect only " + AddySenders.shown("1password.com")));
        // A suggestion isn't a warning, so it isn't shown as one.
        assertTrue(!AddySenders.warns(suggestion.text) && !AddySenders.warns(null) && !AddySenders.warns("Message too large"));
        assertTrue(AddySenders.warns(AddySenders.check(ONE_PASSWORD, "evil.io", "evil.io", "pass", true).text));
        assertNull(AddySenders.check(Collections.emptyList(), "1password.com", "1password.com", "pass", false));
        // Not a sender that fails its own check, and not one that can't be told.
        assertNull(AddySenders.check(Collections.emptyList(), "1password.com", "1password.com", "fail", true));
        assertNull(AddySenders.check(ONE_PASSWORD, null, null, "pass", true));
    }

    @Test
    public void aWarningIsTakenBackWithoutLosingFairEmailsOwn() {
        String ours = AddySenders.MARK + "unexpected sender b.com. This alias is for a.com.";
        assertNull(AddySenders.without(ours, ours));
        assertEquals("Message too large", AddySenders.without(ours + ", Message too large", ours));
        assertEquals("Message too large", AddySenders.without("Message too large", ours));
        assertNull(AddySenders.without(null, ours));
    }
}
