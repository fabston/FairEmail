package eu.faircode.email;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GokapiGobTest {
    static byte[] hex(String text) {
        String[] parts = text.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++)
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        return bytes;
    }

    static String ascii(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.US_ASCII))
            out.append(String.format(" %02x", b));
        return out.toString();
    }

    static String sha256(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
            out.append(String.format("%02x", b));
        return out.toString();
    }

    static byte[] filled(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    // The example in Go's encoding/gob documentation (doc.go): Point{22, 33}, of type Point struct{ X, Y int }.
    private static final byte[] GO_EXAMPLE = hex(
            "1f ff 81 03 01 01 05 50 6f 69 6e 74 01 ff 82 00 " +
                    "01 02 01 01 58 01 04 00 01 01 59 01 04 00 00 00 " +
                    "07 ff 82 01 2c 01 42 00");

    @Test
    public void aStructTypeIsDefinedExactlyAsInGosOwnExample() {
        byte[] message = GokapiGob.structTypeMessage(65, "Point", new Object[][]{{"X", GokapiGob.INT}, {"Y", GokapiGob.INT}});
        assertArrayEquals(Arrays.copyOfRange(GO_EXAMPLE, 0, 32), message);
    }

    @Test
    public void gosOwnExamplesDecode() throws Exception {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("X", 22L);
        point.put("Y", 33L);
        assertEquals(point, GokapiGob.decode(GO_EXAMPLE));
        // "A signed integer with value 3 presented as the argument to Encode will emit: 03 04 00 06".
        assertEquals(3L, GokapiGob.decode(hex("03 04 00 06")));
    }

    @Test
    public void gokapisListOfFilesIsWrittenAsGoWritesAnE2EFileSlice() {
        byte[] bytes = GokapiGob.encodeFiles(Collections.singletonList(new GokapiGob.File("u", "i", "f", new byte[]{1, 2})));
        // Worked out by hand from Go's documentation, field by field, as in the Betterbird add-on's tests.
        byte[] expected = hex(
                "0d ff 83 02 01 02 ff 84 00 01 ff 82 00 00 " +
                        "3d ff 81 03 01 01 07" + ascii("E2EFile") + " 01 ff 82 00 01 04 " +
                        "01 04" + ascii("Uuid") + " 01 0c 00 " +
                        "01 02" + ascii("Id") + " 01 0c 00 " +
                        "01 08" + ascii("Filename") + " 01 0c 00 " +
                        "01 06" + ascii("Cipher") + " 01 0a 00 " +
                        "00 00 " +
                        "12 ff 84 00 01 01 01 75 01 01 69 01 01 66 01 02 01 02 00");
        assertArrayEquals(expected, bytes);
    }

    @Test
    public void writesWhatTheBetterbirdAddOnWritesByteForByte() throws Exception {
        List<GokapiGob.File> files = new ArrayList<>();
        files.add(new GokapiGob.File(repeat("a", 30), "Xy12", "Grüße & ✓.pdf", filled(32, 200)));
        files.add(new GokapiGob.File(repeat("b", 30), "", "", new byte[0]));
        for (int i = 0; i < 200; i++)
            files.add(new GokapiGob.File("uuid" + i, "id" + i, "file " + i + ".txt", filled(32, i % 256)));
        // From the add-on's lib/gob.js encodeFiles, for the same list.
        assertEquals("8eb60dab32dd4a596e4c55f1bc9bfa77b73d8def84880caeb77e6d4e8e3dd437", sha256(GokapiGob.encodeFiles(files)));
    }

    @Test
    public void filesRoundTripWithEmptyFieldsLeftOut() throws Exception {
        List<GokapiGob.File> files = Arrays.asList(
                new GokapiGob.File(repeat("a", 30), "Xy12", "Grüße & ✓.pdf", filled(32, 200)),
                new GokapiGob.File(repeat("b", 30), "", "", new byte[0]));
        assertEquals(files, GokapiGob.decodeFiles(GokapiGob.encodeFiles(files)));
        assertEquals(Collections.emptyList(), GokapiGob.decodeFiles(GokapiGob.encodeFiles(Collections.emptyList())));
    }

    @Test
    public void aLongListUsesGosLongerNumbers() throws Exception {
        List<GokapiGob.File> files = new ArrayList<>();
        for (int i = 0; i < 300; i++)
            files.add(new GokapiGob.File("uuid" + i, "id" + i, "file " + i + ".txt", filled(32, i % 256)));
        assertEquals(files, GokapiGob.decodeFiles(GokapiGob.encodeFiles(files)));
    }

    @Test
    public void typeIdsAreReadFromTheDataNotAssumed() throws Exception {
        GokapiGob.File file = new GokapiGob.File("u", "i", "f", new byte[]{9});
        byte[] bytes = GokapiGob.encodeFiles(Collections.singletonList(file));
        // The same list, with the types numbered 70 and 71 instead, as another Go program might.
        Map<Integer, Integer> swap = new LinkedHashMap<>();
        swap.put(0x81, 0x8b);
        swap.put(0x82, 0x8c);
        swap.put(0x83, 0x8d);
        swap.put(0x84, 0x8e);
        for (int i = 0; i < bytes.length - 1; i++)
            if ((bytes[i] & 0xff) == 0xff && swap.containsKey(bytes[i + 1] & 0xff))
                bytes[i + 1] = (byte) (int) swap.get(bytes[i + 1] & 0xff);
        assertEquals(Collections.singletonList(file), GokapiGob.decodeFiles(bytes));
    }

    @Test
    public void brokenOrUnexpectedDataIsRefusedNeverMisread() {
        byte[] bytes = GokapiGob.encodeFiles(Collections.singletonList(new GokapiGob.File("u", "i", "f", new byte[]{1})));
        for (int end = 1; end < bytes.length; end++)
            assertRefused(Arrays.copyOfRange(bytes, 0, end), null);
        assertRefused(new byte[0], null);
        assertRefused(hex("03 04 00 06"), "isn't a list");
        try {
            GokapiGob.decode(hex("04 ff 82 00 2c"));
            fail();
        } catch (GokapiGob.GobException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("isn't defined"));
        }
    }

    private static void assertRefused(byte[] bytes, String message) {
        try {
            GokapiGob.decodeFiles(bytes);
            fail("Read " + bytes.length + " bytes");
        } catch (GokapiGob.GobException ex) {
            if (message != null)
                assertTrue(ex.getMessage(), ex.getMessage().contains(message));
        }
    }

    static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++)
            out.append(text);
        return out.toString();
    }

    static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts)
            out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
