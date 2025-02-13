/****************************************************************************
 * Copyright (C) 2016-2019 Maschell
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ****************************************************************************/
package de.mas.wiiu.jnus.utils;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import lombok.extern.java.Log;

@Log
public final class StreamUtils {
    private StreamUtils() {
        // Utility class
    }

    /**
     * Tries to read a given amount of bytes from a stream and return them as a byte array. Closes the inputs stream on success AND failure.
     * 
     * @param in
     * @param size
     * @return
     * @throws IOException
     */
    public static byte[] getBytesFromStream(InputStream in, int size) throws IOException {
        try {
            synchronized (in) {
                byte[] result = new byte[size];
                byte[] buffer = null;
                if (size < 0x8000) {
                    buffer = new byte[size];
                } else {
                    buffer = new byte[0x8000];
                }
                int toRead = size;
                int curReadChunk = buffer.length;
                do {
                    if (toRead < curReadChunk) {
                        curReadChunk = toRead;
                    }
                    int read = in.read(buffer, 0, curReadChunk);
                    if (read < 0) break;
                    System.arraycopy(buffer, 0, result, size - toRead, read);
                    toRead -= read;
                } while (toRead > 0);
                return result;
            }
        } finally {
            StreamUtils.closeAll(in);
        }
    }

    public static int getChunkFromStream(InputStream inputStream, byte[] output, ByteArrayBuffer overflowbuffer, int BLOCKSIZE) throws IOException {
        synchronized (inputStream) {
            int bytesRead = -1;
            int inBlockBuffer = 0;
            byte[] overflowbuf = overflowbuffer.getBuffer();
            do {
                try {
                    bytesRead = inputStream.read(overflowbuf, overflowbuffer.getLengthOfDataInBuffer(), overflowbuffer.getSpaceLeft());
                } catch (IOException e) {
                    log.info(e.getMessage());
                    if (!e.getMessage().equals("Write end dead")) {
                        throw e;
                    }
                    bytesRead = -1;
                }

                if (bytesRead <= 0) {
                    if (overflowbuffer.getLengthOfDataInBuffer() > 0) {
                        System.arraycopy(overflowbuf, 0, output, 0, overflowbuffer.getLengthOfDataInBuffer());
                        inBlockBuffer = overflowbuffer.getLengthOfDataInBuffer();
                    } else {
                        if (inBlockBuffer == 0) {
                            return bytesRead;
                        }
                    }
                    break;
                }

                overflowbuffer.addLengthOfDataInBuffer(bytesRead);

                if (inBlockBuffer + overflowbuffer.getLengthOfDataInBuffer() > BLOCKSIZE) {
                    int tooMuch = (inBlockBuffer + bytesRead) - BLOCKSIZE;
                    int toRead = BLOCKSIZE - inBlockBuffer;

                    System.arraycopy(overflowbuf, 0, output, inBlockBuffer, toRead);
                    inBlockBuffer += toRead;

                    if (tooMuch > 0) {
                        System.arraycopy(overflowbuf, toRead, overflowbuf, 0, tooMuch);
                        overflowbuffer.setLengthOfDataInBuffer(tooMuch);
                    } else {
                        // Moving into front.
                        int missingLength = overflowbuffer.getLengthOfDataInBuffer() - toRead;
                        if (missingLength > 0) {
                            System.arraycopy(overflowbuf, toRead, overflowbuf, 0, missingLength);
                            overflowbuffer.setLengthOfDataInBuffer(missingLength);
                        }
                    }
                } else {
                    System.arraycopy(overflowbuf, 0, output, inBlockBuffer, overflowbuffer.getLengthOfDataInBuffer());
                    inBlockBuffer += overflowbuffer.getLengthOfDataInBuffer();
                    overflowbuffer.resetLengthOfDataInBuffer();
                }
            } while (inBlockBuffer != BLOCKSIZE);
            return inBlockBuffer;
        }
    }

    public static long saveInputStreamToOutputStream(InputStream inputStream, OutputStream outputStream, long filesize) throws IOException {
        try {
            return saveInputStreamToOutputStreamWithHash(inputStream, outputStream, filesize, null, 0L, true);
        } catch (CheckSumWrongException e) {
            // Should never happen because the hash is not set. Lets print it anyway.
            e.printStackTrace();
        }
        return -1;
    }

    public static long saveInputStreamToOutputStreamWithHash(InputStream inputStream, OutputStream outputStream, long filesize, byte[] hash,
            long expectedSizeForHash, boolean partial) throws IOException, CheckSumWrongException {
        long written = 0;

        synchronized (inputStream) {
            MessageDigest sha1 = null;
            if (hash != null && !partial) {
                try {
                    sha1 = MessageDigest.getInstance("SHA1");
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }
            }

            int BUFFER_SIZE = 0x8000;
            byte[] buffer = new byte[BUFFER_SIZE];
            int read = 0;
            long totalRead = 0;

            try {
                do {
                    read = inputStream.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    totalRead += read;

                    if (totalRead > filesize) {
                        read = (int) (read - (totalRead - filesize));
                    }

                    outputStream.write(buffer, 0, read);
                    written += read;

                    if (sha1 != null) {
                        sha1.update(buffer, 0, read);
                    }
                } while (written < filesize);

                if (sha1 != null && hash != null) {
                    long missingInHash = expectedSizeForHash - written;
                    if (missingInHash > 0) {
                        sha1.update(new byte[(int) missingInHash]);
                    }

                    byte[] calculated_hash = sha1.digest();
                    byte[] expected_hash = hash;
                    if (!Arrays.equals(calculated_hash, expected_hash)) {
                        throw new CheckSumWrongException("Hash doesn't match saves output stream.", calculated_hash, expected_hash);
                    }
                }

            } finally {
                StreamUtils.closeAll(inputStream, outputStream);
            }
        }
        return written > 0 ? written : -1;
    }

    public static void skipExactly(InputStream in, long offset) throws IOException {
        synchronized (in) {
            long n = offset;
            while (n != 0) {
                long skipped = in.skip(n);
                if (skipped == 0) {
                    in.close();
                    throw new EOFException();
                }
                n -= skipped;
            }

        }
    }

    public static void closeAll(Closeable... stream) throws IOException {
        IOException exception = null;
        for (Closeable cur : stream) {
            try {
                cur.close();
            } catch (IOException e) {
                exception = e;
            }
        }
        if (exception != null) {
            throw exception;
        }
    }
}
