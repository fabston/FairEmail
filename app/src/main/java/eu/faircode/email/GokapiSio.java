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

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gokapi's end-to-end encryption of a file, done the way Gokapi's own code does it with sio-go 0.3.1
 * (github.com/secure-io/sio-go), so Gokapi's download page can decrypt it. Ported from the Betterbird add-on.
 * <p>
 * The file is split into fragments of 16 KiB, the last one shorter, and each is sealed with AES-256-GCM under the
 * file's own key. A fragment's nonce is eight zero bytes and then its number, little-endian, counting from 1. What's
 * authenticated along with each fragment is a flag, 0x80 for the last fragment and 0x00 for the others, followed by
 * the tag of an empty message sealed with number 0. So fragments can't be swapped, dropped, or the file cut short. An
 * empty file is a single empty fragment.
 */
public class GokapiSio {
    static final int FRAGMENT_SIZE = 16384;
    static final int KEY_SIZE = 32;
    static final int TAG_SIZE = 16;

    /** How big a file of this many bytes is once encrypted. */
    static long encryptedSize(long size) {
        return size + Math.max(1, (size + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE) * TAG_SIZE;
    }

    /** A new random key for one file. */
    static byte[] newFileKey() {
        byte[] key = new byte[KEY_SIZE];
        new SecureRandom().nextBytes(key);
        return key;
    }

    static byte[] nonce(long number) {
        byte[] bytes = new byte[12];
        for (int i = 0; i < 4; i++)
            bytes[8 + i] = (byte) (number >>> (8 * i));
        return bytes;
    }

    /**
     * Encrypts a file of a known size in pieces, for uploading in chunks. Each piece is whole fragments, at most
     * maxPieceSize bytes once encrypted, and is only read from the stream when it's asked for, so a large file isn't
     * held in memory.
     */
    static class Encryptor {
        private final InputStream in;
        private final long size;
        private final long count;
        private final int perPiece;
        private final SecretKeySpec key;
        private final byte[] notLast;
        private final byte[] last;
        private long next = 0; // the next fragment's index

        Encryptor(InputStream in, long size, byte[] fileKey, int maxPieceSize) throws GeneralSecurityException {
            this.in = in;
            this.size = size;
            this.count = Math.max(1, (size + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE);
            this.perPiece = maxPieceSize / (FRAGMENT_SIZE + TAG_SIZE);
            if (perPiece < 1)
                throw new IllegalArgumentException("A piece must hold at least one fragment");
            this.key = new SecretKeySpec(fileKey, "AES");
            byte[] tag = seal(nonce(0), null, new byte[0], 0, 0);
            notLast = new byte[1 + TAG_SIZE];
            System.arraycopy(tag, 0, notLast, 1, TAG_SIZE);
            last = notLast.clone();
            last[0] = (byte) 0x80;
        }

        boolean hasNext() {
            return next < count;
        }

        /** The next piece, encrypted. Throws if the file is shorter than its size said. */
        byte[] next() throws IOException, GeneralSecurityException {
            long end = Math.min(next + perPiece, count);
            long plainStart = next * FRAGMENT_SIZE;
            int plainLength = (int) (Math.min(end * FRAGMENT_SIZE, size) - plainStart);
            byte[] plain = new byte[plainLength];
            readFully(in, plain);
            byte[] piece = new byte[plainLength + (int) (end - next) * TAG_SIZE];
            for (long index = next; index < end; index++) {
                int start = (int) ((index - next) * FRAGMENT_SIZE);
                int length = Math.min(FRAGMENT_SIZE, plainLength - start);
                byte[] sealed = seal(nonce(index + 1), index == count - 1 ? last : notLast, plain, start, length);
                System.arraycopy(sealed, 0, piece, (int) ((index - next) * (FRAGMENT_SIZE + TAG_SIZE)), sealed.length);
            }
            next = end;
            return piece;
        }

        private byte[] seal(byte[] nonce, byte[] aad, byte[] plain, int offset, int length) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE * 8, nonce));
            if (aad != null)
                cipher.updateAAD(aad);
            return cipher.doFinal(plain, offset, length);
        }
    }

    /** Fills buffer from the stream, or throws if the stream ends first, as the file changed since its size was taken. */
    static void readFully(InputStream in, byte[] buffer) throws IOException {
        int at = 0;
        while (at < buffer.length) {
            int read = in.read(buffer, at, buffer.length - at);
            if (read < 0)
                throw new IOException("The file changed while it was being uploaded");
            at += read;
        }
    }
}
