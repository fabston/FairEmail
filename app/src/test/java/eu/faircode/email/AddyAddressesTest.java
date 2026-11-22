package eu.faircode.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AddyAddressesTest {
    private static AddyAccount account() {
        AddyAccount account = new AddyAccount();
        account.domains = Arrays.asList("johndoe.addy.io", "mydomain.com", "anonaddy.me");
        account.sharedDomains = Collections.singletonList("anonaddy.me");
        return account;
    }

    @Test
    public void sendAddressesEncodeAndDecode() {
        assertEquals("shop+hello=store.com@johndoe.addy.io", AddyAddresses.encodeSendAddress("shop@johndoe.addy.io", "hello@store.com"));
        AddyAddresses.SendAddress decoded = AddyAddresses.decodeSendAddress("shop+hello=store.com@johndoe.addy.io");
        assertEquals("shop@johndoe.addy.io", decoded.alias);
        assertEquals("hello@store.com", decoded.destination);
    }

    @Test
    public void destinationsWithPlusAndEqualsSurvive() {
        String encoded = AddyAddresses.encodeSendAddress("shop@johndoe.addy.io", "a+b=c@x.com");
        AddyAddresses.SendAddress decoded = AddyAddresses.decodeSendAddress(encoded);
        assertEquals("shop@johndoe.addy.io", decoded.alias);
        assertEquals("a+b=c@x.com", decoded.destination);
    }

    @Test
    public void thingsThatArentSendAddressesAreRejected() {
        assertNull(AddyAddresses.decodeSendAddress("plain@example.com"));
        assertNull(AddyAddresses.decodeSendAddress("a+b@example.com"));
        assertNull(AddyAddresses.decodeSendAddress("a+b=nodot@example.com"));
        assertNull(AddyAddresses.decodeSendAddress("a+=x.com@example.com"));
        assertNull(AddyAddresses.decodeSendAddress(null));
        assertThrows(IllegalArgumentException.class, () -> AddyAddresses.encodeSendAddress("shop@johndoe.addy.io", "a@b.com, c@d.com"));
        assertThrows(IllegalArgumentException.class, () -> AddyAddresses.encodeSendAddress("shop@johndoe.addy.io", "\"x\"@b.com"));
    }

    @Test
    public void yourOwnDomainsAreYours_sharedOnesNeedConfirming() {
        AddyAccount account = account();
        assertTrue(AddyAddresses.ownsAlias("shop@johndoe.addy.io", account, null));
        assertTrue(AddyAddresses.ownsAlias("Shop@MyDomain.com", account, null));
        assertFalse(AddyAddresses.ownsAlias("someone@anonaddy.me", account, null));
        assertTrue(AddyAddresses.ownsAlias("mine@anonaddy.me", account, Collections.singletonList("MINE@anonaddy.me")));
        assertFalse(AddyAddresses.ownsAlias("x@other.addy.io", account, null));
        assertFalse(AddyAddresses.ownsAlias("x@evil-johndoe.addy.io.example", account, null));
    }

    @Test
    public void withoutAnAccount_addysOwnDomainsAreTheBestGuess() {
        assertTrue(AddyAddresses.ownsAlias("x@johndoe.addy.io", null, null));
        assertTrue(AddyAddresses.ownsAlias("x@anonaddy.com", null, null));
        assertFalse(AddyAddresses.ownsAlias("x@example.com", null, null));
    }

    @Test
    public void trustedRecipientsMatchByAddressOrExactDomain() {
        assertTrue(AddyAddresses.isTrustedRecipient("Friend@Example.com", Collections.singletonList("friend@example.com")));
        assertTrue(AddyAddresses.isTrustedRecipient("a@sub.example.org", Collections.singletonList("sub.example.org")));
        assertFalse(AddyAddresses.isTrustedRecipient("a@sub.example.org", Collections.singletonList("example.org")));
        assertEquals(Arrays.asList("friend@example.com", "example.org"),
                AddyAddresses.cleanTrustedRecipients(Arrays.asList(" Friend@Example.com", "", "not valid", "example.org", "EXAMPLE.org")));
    }

    @Test
    public void sizesAreShownAsAddyShowsThem() {
        assertEquals("0 B", AddyAccount.size(0));
        assertEquals("5.2 MB", AddyAccount.size(5_452_595));
        assertEquals("10 GB", AddyAccount.size(10_737_418_240L));
        assertEquals("512 MB", AddyAccount.size(536_870_912));
    }
}
