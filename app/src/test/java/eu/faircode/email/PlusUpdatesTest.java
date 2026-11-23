package eu.faircode.email;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlusUpdatesTest {
    @Test
    public void onlyAReleaseFromALaterRunIsNewer() {
        assertTrue(PlusUpdates.isNewer("plus-1.2342-45100000000", "45025895876"));
        // A push keeps FairEmail's version, so only the run number tells them apart.
        assertTrue(PlusUpdates.isNewer("plus-1.2341-45025895877", "45025895876"));
        assertFalse(PlusUpdates.isNewer("plus-1.2341-45025895876", "45025895876"));
        assertFalse(PlusUpdates.isNewer("plus-1.2342-45000000000", "45025895876"));
    }

    @Test
    public void anythingElseIsNeverOffered() {
        assertFalse(PlusUpdates.isNewer("1.2342", "1"));
        assertFalse(PlusUpdates.isNewer("plus-1.2342", "1"));
        assertFalse(PlusUpdates.isNewer("plus-1.2342-abc1234", "1"));
        assertFalse(PlusUpdates.isNewer(null, "1"));
        assertFalse(PlusUpdates.isNewer("plus-1.2342-5", ""));
        assertFalse(PlusUpdates.isNewer("plus-1.2342-99999999999999999999", "1"));
    }
}
