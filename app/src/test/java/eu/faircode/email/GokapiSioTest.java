package eu.faircode.email;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GokapiSioTest {
    private static final int FRAGMENT = GokapiSio.FRAGMENT_SIZE;
    private static final int WHOLE = FRAGMENT + 16;
    private static final long[] SIZES = {0, 1, FRAGMENT - 1, FRAGMENT, FRAGMENT + 1, 2 * FRAGMENT, 100000};

    static byte[] data(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++)
            bytes[i] = (byte) ((i * 31 + 7) % 251);
        return bytes;
    }

    static List<byte[]> encrypt(byte[] plain, byte[] key, int pieceSize) throws Exception {
        GokapiSio.Encryptor encryptor = new GokapiSio.Encryptor(new ByteArrayInputStream(plain), plain.length, key, pieceSize);
        List<byte[]> pieces = new ArrayList<>();
        while (encryptor.hasNext())
            pieces.add(encryptor.next());
        return pieces;
    }

    private static Cipher gcm(int mode, byte[] key, long number) throws GeneralSecurityException {
        // Eight zero bytes, then the fragment's number, little-endian. Written out again here, not taken from GokapiSio.
        byte[] nonce = new byte[12];
        nonce[8] = (byte) number;
        nonce[9] = (byte) (number >> 8);
        nonce[10] = (byte) (number >> 16);
        nonce[11] = (byte) (number >> 24);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher;
    }

    /** Decrypts what sio-go's EncryptReader writes, checking every fragment is the size and shape it requires. */
    static byte[] sioDecrypt(byte[] data, byte[] key) throws GeneralSecurityException {
        byte[] tag = gcm(Cipher.ENCRYPT_MODE, key, 0).doFinal();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        for (int number = 1; offset < data.length || number == 1; number++) {
            int size = Math.min(WHOLE, data.length - offset);
            assertTrue("every fragment has its tag", size >= 16);
            boolean last = offset + size >= data.length;
            if (!last)
                assertEquals("every fragment but the last is whole", WHOLE, size);
            Cipher cipher = gcm(Cipher.DECRYPT_MODE, key, number);
            byte[] aad = new byte[17];
            aad[0] = (byte) (last ? 0x80 : 0x00);
            System.arraycopy(tag, 0, aad, 1, 16);
            cipher.updateAAD(aad);
            byte[] plain = cipher.doFinal(data, offset, size);
            out.write(plain, 0, plain.length);
            offset += size;
        }
        return out.toByteArray();
    }

    @Test
    public void theEncryptedSizeIsWhatSioGosOverheadGives() {
        long[] expected = {16, 17, 16399, 16400, 16417, 32800, 100112};
        for (int i = 0; i < SIZES.length; i++)
            assertEquals(expected[i], GokapiSio.encryptedSize(SIZES[i]));
    }

    @Test
    public void filesDecryptToExactlyWhatWasEncryptedAtEveryFragmentBoundary() throws Exception {
        byte[] key = GokapiSio.newFileKey();
        for (long size : SIZES) {
            byte[] plain = data((int) size);
            byte[] encrypted = GokapiGobTest.concat(encrypt(plain, key, 2 * WHOLE));
            assertEquals("size " + size, GokapiSio.encryptedSize(size), encrypted.length);
            assertArrayEquals("size " + size, plain, sioDecrypt(encrypted, key));
        }
    }

    @Test
    public void piecesAreWholeFragmentsNeverOverTheSizeAskedFor() throws Exception {
        List<byte[]> pieces = encrypt(data(5 * FRAGMENT + 10), GokapiSio.newFileKey(), 2 * WHOLE + 100);
        assertEquals(3, pieces.size());
        assertEquals(2 * WHOLE, pieces.get(0).length);
        assertEquals(2 * WHOLE, pieces.get(1).length);
        assertEquals(WHOLE + 10 + 16, pieces.get(2).length);
        try {
            encrypt(data(10), GokapiSio.newFileKey(), WHOLE - 1);
            fail();
        } catch (IllegalArgumentException expected) {
            // A piece must hold a fragment.
        }
    }

    @Test
    public void theLastFragmentIsMarkedSoAFileCantBeCutShortOrReordered() throws Exception {
        byte[] key = GokapiSio.newFileKey();
        byte[] data = GokapiGobTest.concat(encrypt(data(3 * FRAGMENT), key, 10 * WHOLE));
        assertRefused(Arrays.copyOfRange(data, 0, 2 * WHOLE), key);
        byte[] swapped = GokapiGobTest.concat(Arrays.asList(
                Arrays.copyOfRange(data, WHOLE, 2 * WHOLE), Arrays.copyOfRange(data, 0, WHOLE), Arrays.copyOfRange(data, 2 * WHOLE, data.length)));
        assertRefused(swapped, key);
        assertRefused(data, new byte[32]);
    }

    private static void assertRefused(byte[] data, byte[] key) throws GeneralSecurityException {
        try {
            sioDecrypt(data, key);
            fail();
        } catch (AEADBadTagException expected) {
            // Doesn't authenticate.
        }
    }

    @Test
    public void encryptsWhatTheBetterbirdAddOnEncryptsByteForByte() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++)
            key[i] = (byte) (i * 7 + 1);
        // From the add-on's lib/sio.js encryptFile, with the same key and data, in pieces of three fragments.
        String[][] expected = {
                {"0", "e46512b425f1891c519f74ac444171a8e7422bc532093e118120e2629e3e9a7f"},
                {"5", "8d99258c10f54fb969bdac219e6dd9d854ad8dd7ba099524b583082c38f4da14"},
                {"16384", "632ebefc3e5d8187f07acf52f5ed9add7665e5e6695615b5b332fec4363f668f"},
                {"40000", "c1287f20f452795991a8e3d426eae92865fadf17edfbcb0d3aeb905b63a68901"},
        };
        for (String[] vector : expected) {
            byte[] encrypted = GokapiGobTest.concat(encrypt(data(Integer.parseInt(vector[0])), key, 3 * WHOLE));
            assertEquals("size " + vector[0], vector[1], GokapiGobTest.sha256(encrypted));
        }
    }

    @Test
    public void aFileShorterThanItsSizeIsRefused() throws Exception {
        GokapiSio.Encryptor encryptor = new GokapiSio.Encryptor(new ByteArrayInputStream(data(10)), 20, GokapiSio.newFileKey(), WHOLE);
        try {
            encryptor.next();
            fail();
        } catch (IOException ex) {
            assertTrue(ex.getMessage().contains("changed"));
        }
    }
}
