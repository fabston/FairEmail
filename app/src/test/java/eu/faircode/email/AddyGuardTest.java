package eu.faircode.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The same cases as the Betterbird add-on's tests, adapted to FairEmail's always-HTML drafts. */
public class AddyGuardTest {
    private static final String ALIAS_TO = "shop+hello=store.com@johndoe.addy.io";

    private static AddyAccount account() {
        AddyAccount account = new AddyAccount();
        account.domains = Collections.singletonList("johndoe.addy.io");
        account.recipients = Collections.singletonList(new AddyAccount.Recipient("real@example.com", true, true));
        return account;
    }

    private static AddyGuard.Input input(String... to) {
        AddyGuard.Input input = new AddyGuard.Input();
        input.account = account();
        input.identity = new AddyGuard.Identity(1, "real@example.com", "Real Person");
        input.draft.to = new ArrayList<>(Arrays.asList(to));
        input.draft.html = "<p>hello there</p>";
        return input;
    }

    private static List<String> ids(AddyGuard.Input input) {
        List<String> ids = new ArrayList<>();
        for (AddyGuard.Problem problem : AddyGuard.check(input))
            ids.add(problem.id);
        return ids;
    }

    private static List<String> list(String... ids) {
        return Arrays.asList(ids);
    }

    @Test
    public void ordinaryEmailIsLeftAlone_whateverItContains() {
        AddyGuard.Input input = input("friend@example.com");
        input.draft.cc = list("other@example.com");
        input.draft.html = "<p>I'm Real Person, real@example.com</p>";
        input.draft.replyTo = list("x@y.com");
        assertEquals(list(), ids(input));
        assertEquals(list(), ids(input("bounce+a=b.com@lists.example.com")));
        assertEquals(list(), ids(input()));
    }

    @Test
    public void emailThatSkipsTheAliasIsCaught_whenThatWarningIsOn() {
        AddyGuard.Input input = input("friend@example.com");
        input.warnDirect = true;
        List<AddyGuard.Problem> problems = AddyGuard.check(input);
        assertEquals("real-sender", problems.get(0).id);
        assertTrue(problems.get(0).detail.contains("friend@example.com from real@example.com"));
        input.account = null;
        assertEquals(list("real-sender"), ids(input));
    }

    @Test
    public void thatWarningLeavesAloneWhatItShouldntCatch() {
        AddyGuard.Input alias = input(ALIAS_TO);
        alias.warnDirect = true;
        assertEquals(list(), ids(alias));
        AddyGuard.Input fromAlias = input("friend@example.com");
        fromAlias.warnDirect = true;
        fromAlias.draft.from = "shop@johndoe.addy.io";
        assertEquals(list(), ids(fromAlias));
        AddyGuard.Input trusted = input("Friend@Example.com");
        trusted.warnDirect = true;
        trusted.trusted = list("friend@example.com");
        assertEquals(list(), ids(trusted));
    }

    @Test
    public void aCleanAliasEmailPasses() {
        assertEquals(list(), ids(input(ALIAS_TO)));
        AddyGuard.Input reply = input(ALIAS_TO);
        reply.draft.reply = true;
        reply.related = new AddyGuard.Related("shop@johndoe.addy.io");
        assertEquals(list(), ids(reply));
    }

    @Test
    public void changingAReplysAddressToTheRealOneIsCaught() {
        AddyGuard.Input reply = input("hello@store.com");
        reply.draft.reply = true;
        reply.related = new AddyGuard.Related("shop@johndoe.addy.io");
        List<AddyGuard.Problem> problems = AddyGuard.check(reply);
        assertEquals("reply-real-address", problems.get(0).id);
        assertTrue(problems.get(0).detail.contains("shop@johndoe.addy.io"));
        reply.account = null;
        assertEquals(list("reply-real-address"), ids(reply));
    }

    @Test
    public void yourOwnAliasOnAReplyAllIsntALeak() {
        AddyGuard.Input reply = input(ALIAS_TO);
        reply.draft.cc = list("shop@johndoe.addy.io");
        assertEquals(list(), ids(reply));
    }

    @Test
    public void aRecipientOutsideTheAliasIsCaught_whereverTheyAre() {
        AddyGuard.Input to = input(ALIAS_TO, "friend@example.com");
        assertEquals(list("mixed-recipients"), ids(to));
        AddyGuard.Input cc = input(ALIAS_TO);
        cc.draft.cc = list("friend@example.com");
        assertEquals(list("mixed-recipients"), ids(cc));
        AddyGuard.Input bcc = input(ALIAS_TO);
        bcc.draft.bcc = list("friend@example.com");
        assertEquals(list("mixed-recipients"), ids(bcc));
        to.account = null;
        assertEquals(list("mixed-recipients"), ids(to));
    }

    @Test
    public void ccAndBccThroughAnAliasAreFlagged() {
        AddyGuard.Input input = input(ALIAS_TO);
        input.draft.cc = list("shop+b=c.com@johndoe.addy.io");
        assertEquals(list("cc-bcc"), ids(input));
    }

    @Test
    public void aSenderAddyWontAcceptIsCaught() {
        AddyGuard.Input input = input(ALIAS_TO);
        input.identity = new AddyGuard.Identity(2, "other@example.com", "");
        List<AddyGuard.Problem> problems = AddyGuard.check(input);
        assertEquals(list("unverified-sender"), ids(input));
        assertTrue(problems.get(0).detail.startsWith("You're sending from other@example.com, but addy.io only sends from your alias if the email comes from real@example.com. As it is, the email would come back to you instead of reaching hello@store.com."));
        assertTrue(problems.get(0).detail.endsWith("Add one of those addresses as an identity in FairEmail to send from it."));
        input.identities = Collections.singletonList(new AddyGuard.Identity(1, "REAL@example.com", ""));
        assertTrue(AddyGuard.check(input).get(0).detail.endsWith("Choose that identity in From."));
        AddyGuard.Input fromHeader = input(ALIAS_TO);
        fromHeader.draft.from = "REAL@example.com";
        assertEquals(list(), ids(fromHeader));
    }

    @Test
    public void theFreePlanIsFlagged() {
        AddyGuard.Input input = input(ALIAS_TO);
        input.account.freePlan = true;
        assertEquals(list("free-plan"), ids(input));
    }

    @Test
    public void yourRealAddressInTheEmailIsCaught_howeverItsWritten() {
        for (String html : list("<p>write to real@example.com</p>", "<p>REAL@EXAMPLE.COM</p>",
                "<blockquote><p>On Monday real@example.com wrote:</p></blockquote>",
                "<a href=\"mailto:real%40example.com\">Me</a>", "<a href=\"mailto:real&#64;example.com\">Me</a>",
                "<a href=\"mailto:real&#x40;example.com\">Me</a>")) {
            AddyGuard.Input input = input(ALIAS_TO);
            input.draft.html = html;
            assertEquals(html, list("address-in-body"), ids(input));
        }
        AddyGuard.Input styled = input(ALIAS_TO);
        styled.draft.html = "<style>a { content: 'real@example.com' }</style><p>Hi</p>";
        assertEquals(list(), ids(styled));
    }

    @Test
    public void yourNameIsCaughtInWhatYouWrote_butNotInQuotedMail() {
        AddyGuard.Input signed = input(ALIAS_TO);
        signed.draft.html = "<p>Thanks,<br>Real   Person</p>";
        assertEquals(list("name-in-body"), ids(signed));
        AddyGuard.Input quoted = input(ALIAS_TO);
        quoted.draft.html = "<p>Thanks</p><blockquote><p>Dear Real Person</p><blockquote>nested</blockquote></blockquote><p>bye</p>";
        assertEquals(list(), ids(quoted));
        AddyGuard.Input oneWord = input(ALIAS_TO);
        oneWord.identity = new AddyGuard.Identity(1, "real@example.com", "Madonna");
        oneWord.draft.html = "<p>I like Madonna</p>";
        assertEquals(list(), ids(oneWord));
    }

    @Test
    public void quotesAreSkipped_evenWhenLowercasingChangesTheLength() {
        // "İ" lowercases to two characters, so positions in the lowercased copy don't fit the original.
        AddyGuard.Input input = input(ALIAS_TO);
        input.draft.html = "<p>İİİİ</p><blockquote><p>Dear Real Person</p></blockquote><p>Real Person</p>";
        assertEquals(list("name-in-body"), ids(input));
    }

    @Test
    public void deeplyNestedQuotesAreSkippedWhole() {
        StringBuilder html = new StringBuilder("<p>hi</p>");
        for (int i = 0; i < 20000; i++)
            html.append("<blockquote>");
        html.append("Real Person");
        for (int i = 0; i < 20000; i++)
            html.append("</blockquote>");
        assertEquals("<p>hi</p> ", AddyGuard.withoutQuotes(html.toString()));
    }

    @Test
    public void yourRealDetailsInTheSubjectAreCaught() {
        AddyGuard.Input address = input(ALIAS_TO);
        address.draft.subject = "From REAL@example.com";
        assertEquals(list("in-subject"), ids(address));
        AddyGuard.Input name = input(ALIAS_TO);
        name.draft.subject = "Invoice for real person";
        assertEquals(list("in-subject"), ids(name));
        AddyGuard.Input direct = input("friend@example.com");
        direct.draft.subject = "From real@example.com";
        assertEquals(list(), ids(direct));
    }

    @Test
    public void signingAndReplyToAreCaught() {
        AddyGuard.Input signed = input(ALIAS_TO);
        signed.draft.signed = true;
        assertEquals(list("signed"), ids(signed));
        AddyGuard.Input replyTo = input(ALIAS_TO);
        replyTo.draft.replyTo = list("real2@example.com");
        assertEquals(list("reply-to"), ids(replyTo));
        replyTo.draft.replyTo = list(ALIAS_TO);
        assertEquals(list(), ids(replyTo));
    }

    @Test
    public void everyProblemIsReportedTogether() {
        AddyGuard.Input input = input(ALIAS_TO, "friend@example.com");
        input.identity = new AddyGuard.Identity(2, "other@example.com", "Real Person");
        input.draft.html = "<p>Real Person real@example.com</p>";
        input.draft.replyTo = list("x@y.com");
        assertEquals(list("mixed-recipients", "unverified-sender", "address-in-body", "name-in-body", "reply-to"), ids(input));
    }

    @Test
    public void sharedDomainAliasesAreSentForConfirmation() {
        AddyGuard.Input input = input("x+a=b.com@anonaddy.me");
        input.account.sharedDomains = Collections.singletonList("anonaddy.me");
        assertEquals(list("x@anonaddy.me"), AddyGuard.aliasesToConfirm(input));
        assertEquals(list(), AddyGuard.aliasesToConfirm(input(ALIAS_TO)));
    }
}
