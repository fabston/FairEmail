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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Go's gob encoding, as much of it as Gokapi's list of end-to-end encrypted files needs. Gokapi keeps that list on the
 * server encrypted, as a gob of []E2EFile, and its web page decodes it with Go's own code, so what's written here has
 * to be exactly what Go writes. Ported from the Betterbird add-on, whose tests check it against the worked example in
 * Go's encoding/gob documentation, as these do.
 * <p>
 * A gob stream is a series of messages, each its length and then a type id. A negative id defines that type; a
 * positive one is a value of it. Numbers are variable-length: below 128 a single byte, otherwise the negated count of
 * bytes that follow, big-endian. Signed numbers are shifted left one bit, with the low bit set and the rest
 * complemented when negative. A struct is (field number delta, value) pairs, ending with 0, with zero values left out.
 */
public class GokapiGob {
    static class GobException extends Exception {
        GobException(String message) {
            super(message);
        }
    }

    /** One of Gokapi's E2EFile entries. Empty strings and an empty cipher stand for Go's zero values. */
    static class File {
        final String uuid;
        final String id;
        final String filename;
        final byte[] cipher;

        File(String uuid, String id, String filename, byte[] cipher) {
            this.uuid = uuid == null ? "" : uuid;
            this.id = id == null ? "" : id;
            this.filename = filename == null ? "" : filename;
            this.cipher = cipher == null ? new byte[0] : cipher;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof File))
                return false;
            File file = (File) other;
            return uuid.equals(file.uuid) && id.equals(file.id) && filename.equals(file.filename) && Arrays.equals(cipher, file.cipher);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "File{" + uuid + ", " + id + ", " + filename + ", " + cipher.length + " bytes}";
        }
    }

    // Go's predefined type ids.
    static final int BOOL = 1;
    static final int INT = 2;
    static final int UINT = 3;
    static final int FLOAT = 4;
    static final int BYTES = 5;
    static final int STRING = 6;
    static final int COMPLEX = 7;
    static final int INTERFACE = 8;
    // The first id a gob stream gives a type of its own.
    private static final int FIRST_USER_ID = 65;

    // --- Writing --------------------------------------------------------------------------------------------------

    private static class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer uint(long value) {
            if (value < 0)
                throw new IllegalArgumentException("Can't encode " + value);
            if (value < 128) {
                out.write((int) value);
                return this;
            }
            int count = 0;
            for (long rest = value; rest > 0; rest >>>= 8)
                count++;
            out.write(256 - count);
            for (int i = count - 1; i >= 0; i--)
                out.write((int) (value >>> (8 * i)) & 0xff);
            return this;
        }

        Writer sint(long value) {
            return uint(value < 0 ? ((-value - 1) << 1) | 1 : value << 1);
        }

        Writer bytes(byte[] value) {
            uint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Writer string(String value) {
            return bytes(value.getBytes(StandardCharsets.UTF_8));
        }

        /** What's been written, as one message: its length, then what's in it. */
        byte[] message() {
            byte[] body = out.toByteArray();
            Writer whole = new Writer().uint(body.length);
            whole.out.write(body, 0, body.length);
            return whole.out.toByteArray();
        }
    }

    private interface Field {
        void write(Writer writer);
    }

    /** A struct's fields, in order of their number, each left out when it's null, as Go leaves out zero values. */
    private static void writeStruct(Writer writer, Field... fields) {
        int last = -1;
        for (int number = 0; number < fields.length; number++) {
            if (fields[number] == null)
                continue;
            writer.uint(number - last);
            fields[number].write(writer);
            last = number;
        }
        writer.uint(0);
    }

    // A wireType describes a type: its field 1 is a slice type, field 2 a struct type. Both start with a CommonType
    // of the type's name and id.
    private static Field commonType(String name, int id) {
        return w -> writeStruct(w, name.isEmpty() ? null : v -> v.string(name), v -> v.sint(id));
    }

    /** The message that defines a struct type of the given name and id, with fields as {name, type id}. */
    static byte[] structTypeMessage(int id, String name, Object[][] fields) {
        Writer writer = new Writer().sint(-id);
        Field structType = w -> writeStruct(w,
                commonType(name, id),
                v -> {
                    v.uint(fields.length);
                    for (Object[] field : fields)
                        writeStruct(v, x -> x.string((String) field[0]), x -> x.sint((Integer) field[1]));
                });
        writeStruct(writer, null, null, structType);
        return writer.message();
    }

    /** The message that defines an unnamed slice type, as Go names none for []T, of elements of type elem. */
    static byte[] sliceTypeMessage(int id, int elem) {
        Writer writer = new Writer().sint(-id);
        Field sliceType = w -> writeStruct(w, commonType("", id), v -> v.sint(elem));
        writeStruct(writer, null, sliceType);
        return writer.message();
    }

    // Gokapi's E2EFile, with its fields in Go's order.
    private static final Object[][] E2E_FILE_FIELDS = {{"Uuid", STRING}, {"Id", STRING}, {"Filename", STRING}, {"Cipher", BYTES}};

    /**
     * Encodes Gokapi's list of files as Go encodes a []E2EFile on a new encoder: the slice type's definition, then its
     * element's, then the list. Go numbers the element 65 and the slice 66, as it builds the element's type first.
     */
    static byte[] encodeFiles(List<File> files) {
        int element = FIRST_USER_ID;
        int slice = FIRST_USER_ID + 1;
        Writer value = new Writer().sint(slice).uint(0).uint(files.size());
        for (File file : files)
            writeStruct(value,
                    file.uuid.isEmpty() ? null : w -> w.string(file.uuid),
                    file.id.isEmpty() ? null : w -> w.string(file.id),
                    file.filename.isEmpty() ? null : w -> w.string(file.filename),
                    file.cipher.length == 0 ? null : w -> w.bytes(file.cipher));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : new byte[][]{
                sliceTypeMessage(slice, element),
                structTypeMessage(element, "E2EFile", E2E_FILE_FIELDS),
                value.message()})
            out.write(part, 0, part.length);
        return out.toByteArray();
    }

    // --- Reading --------------------------------------------------------------------------------------------------

    private static class Reader {
        final byte[] data;
        int at;
        final int end;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.at = start;
            this.end = end;
        }

        int left() {
            return end - at;
        }

        int next() throws GobException {
            if (at >= end)
                throw new GobException("The data ends too soon");
            return data[at++] & 0xff;
        }

        long uint() throws GobException {
            int first = next();
            if (first < 128)
                return first;
            int count = 256 - first;
            if (count > 8)
                throw new GobException("A number is malformed");
            long value = 0;
            for (int i = 0; i < count; i++) {
                if (value > (Long.MAX_VALUE >>> 8))
                    throw new GobException("A number is too large");
                value = (value << 8) | next();
            }
            return value;
        }

        long sint() throws GobException {
            long value = uint();
            return (value & 1) != 0 ? ~(value >>> 1) : value >>> 1;
        }

        int length() throws GobException {
            long value = uint();
            if (value > left())
                throw new GobException("A length is longer than the data");
            return (int) value;
        }

        byte[] bytes() throws GobException {
            int count = length();
            at += count;
            return Arrays.copyOfRange(data, at - count, at);
        }

        String string() throws GobException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        /** The next message, as its own reader, skipped over in this one. */
        Reader message() throws GobException {
            int count = length();
            at += count;
            return new Reader(data, at - count, at);
        }
    }

    private interface Read {
        Object read(Reader reader) throws GobException;
    }

    /** A struct's fields, by number, each read by readers[number]. A field there's no reader for is an error. */
    private static Map<Integer, Object> readStruct(Reader reader, Read... readers) throws GobException {
        Map<Integer, Object> fields = new HashMap<>();
        for (int number = -1; ; ) {
            long delta = reader.uint();
            if (delta == 0)
                return fields;
            if (delta > readers.length)
                throw new GobException("Unexpected field " + (number + delta));
            number += (int) delta;
            if (number >= readers.length || readers[number] == null)
                throw new GobException("Unexpected field " + number);
            fields.put(number, readers[number].read(reader));
        }
    }

    private static class WireType {
        final String kind;
        int elem;
        int key;
        List<Object[]> fields = new ArrayList<>(); // {name, type id}

        WireType(String kind) {
            this.kind = kind;
        }
    }

    private static final Read COMMON = r -> readStruct(r, Reader::string, Reader::sint);

    private static int intField(Map<Integer, Object> fields, int number) {
        Object value = fields.get(number);
        return value == null ? 0 : (int) (long) (Long) value;
    }

    @SuppressWarnings("unchecked")
    private static WireType readWireType(Reader reader) throws GobException {
        Map<Integer, Object> kinds = readStruct(reader,
                r -> {
                    Map<Integer, Object> f = readStruct(r, COMMON, Reader::sint, Reader::sint);
                    WireType type = new WireType("array");
                    type.elem = intField(f, 1);
                    return type;
                },
                r -> {
                    Map<Integer, Object> f = readStruct(r, COMMON, Reader::sint);
                    WireType type = new WireType("slice");
                    type.elem = intField(f, 1);
                    return type;
                },
                r -> {
                    Map<Integer, Object> f = readStruct(r, COMMON, s -> {
                        int count = s.length();
                        List<Object[]> list = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            Map<Integer, Object> field = readStruct(s, Reader::string, Reader::sint);
                            Object name = field.get(0);
                            list.add(new Object[]{name == null ? "" : name, intField(field, 1)});
                        }
                        return list;
                    });
                    WireType type = new WireType("struct");
                    if (f.get(1) != null)
                        type.fields = (List<Object[]>) f.get(1);
                    return type;
                },
                r -> {
                    Map<Integer, Object> f = readStruct(r, COMMON, Reader::sint, Reader::sint);
                    WireType type = new WireType("map");
                    type.key = intField(f, 1);
                    type.elem = intField(f, 2);
                    return type;
                },
                r -> encoderType(r),
                r -> encoderType(r),
                r -> encoderType(r));
        // Only one kind of type per definition: anything else isn't from Go.
        if (kinds.size() != 1)
            throw new GobException("A type definition is malformed");
        return (WireType) kinds.values().iterator().next();
    }

    private static WireType encoderType(Reader reader) throws GobException {
        readStruct(reader, COMMON);
        return new WireType("encoder");
    }

    private static Object readValue(Reader reader, int id, Map<Integer, WireType> types, int depth) throws GobException {
        if (depth > 32)
            throw new GobException("The data is nested too deeply");
        switch (id) {
            case BOOL:
                return reader.uint() != 0;
            case INT:
                return reader.sint();
            case UINT:
                return reader.uint();
            case FLOAT:
                reader.uint();
                return null;
            case BYTES:
                return reader.bytes();
            case STRING:
                return reader.string();
            case COMPLEX:
                reader.uint();
                reader.uint();
                return null;
            case INTERFACE:
                throw new GobException("Interface values aren't supported");
        }
        WireType type = types.get(id);
        if (type == null)
            throw new GobException("Type " + id + " isn't defined");
        switch (type.kind) {
            case "slice":
            case "array": {
                int count = reader.length();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < count; i++)
                    list.add(readValue(reader, type.elem, types, depth + 1));
                return list;
            }
            case "map": {
                int count = reader.length();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < count; i++)
                    list.add(new Object[]{readValue(reader, type.key, types, depth + 1), readValue(reader, type.elem, types, depth + 1)});
                return list;
            }
            case "struct": {
                Map<String, Object> value = new LinkedHashMap<>();
                for (int number = -1; ; ) {
                    long delta = reader.uint();
                    if (delta == 0)
                        return value;
                    if (delta > type.fields.size())
                        throw new GobException("Unexpected field " + (number + delta));
                    number += (int) delta;
                    if (number >= type.fields.size())
                        throw new GobException("Unexpected field " + number);
                    Object[] field = type.fields.get(number);
                    value.put((String) field[0], readValue(reader, (Integer) field[1], types, depth + 1));
                }
            }
            default:
                throw new GobException("Values of type " + id + " aren't supported");
        }
    }

    /**
     * Decodes the first value in a gob stream. Structs become maps by field name, with missing fields left out, slices
     * lists, integers longs, and []byte byte arrays.
     */
    static Object decode(byte[] bytes) throws GobException {
        Reader stream = new Reader(bytes, 0, bytes.length);
        Map<Integer, WireType> types = new HashMap<>();
        while (stream.left() > 0) {
            Reader message = stream.message();
            long id = message.sint();
            if (id < 0) {
                if (id < Integer.MIN_VALUE)
                    throw new GobException("A type id is malformed");
                types.put((int) -id, readWireType(message));
                continue;
            }
            if (id > Integer.MAX_VALUE)
                throw new GobException("A type id is malformed");
            // A value that isn't a struct is sent as if it were a struct's only field, with the delta 0.
            WireType type = types.get((int) id);
            if ((type == null || !"struct".equals(type.kind)) && message.uint() != 0)
                throw new GobException("A value is malformed");
            return readValue(message, (int) id, types, 0);
        }
        throw new GobException("There's no value in the data");
    }

    /** Decodes Gokapi's list of files, from a gob of []E2EFile. */
    static List<File> decodeFiles(byte[] bytes) throws GobException {
        Object list = decode(bytes);
        if (!(list instanceof List))
            throw new GobException("The data isn't a list");
        List<File> files = new ArrayList<>();
        for (Object entry : (List<?>) list) {
            if (!(entry instanceof Map))
                throw new GobException("The list isn't of files");
            Map<?, ?> file = (Map<?, ?>) entry;
            files.add(new File(
                    file.get("Uuid") instanceof String ? (String) file.get("Uuid") : "",
                    file.get("Id") instanceof String ? (String) file.get("Id") : "",
                    file.get("Filename") instanceof String ? (String) file.get("Filename") : "",
                    file.get("Cipher") instanceof byte[] ? (byte[]) file.get("Cipher") : new byte[0]));
        }
        return files;
    }
}
