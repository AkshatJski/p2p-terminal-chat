package com.p2p.chat.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process pipe transport for tests and for demoing the mesh on one machine
 * without any network or Bluetooth hardware. Uses chunked blocking queues
 * instead of {@code PipedStream}s so links survive the writer thread exiting.
 */
public final class LoopbackTransport implements Transport {
    private static final int QUEUE_CAPACITY = 64;

    private final Pipe readPipe;   // data the peer writes into
    private final Pipe writePipe;  // data we write for the peer to read
    private final InputStream in;
    private final OutputStream out;
    private final String id;

    private LoopbackTransport(Pipe readPipe, Pipe writePipe, String id) {
        this.readPipe = readPipe;
        this.writePipe = writePipe;
        this.in = new PipeInputStream(readPipe);
        this.out = new PipeOutputStream(writePipe);
        this.id = id;
    }

    /** Returns a pair of transports whose pipes are cross-connected. */
    public static LoopbackTransport[] pair(String idA, String idB) {
        Pipe aToB = new Pipe();
        Pipe bToA = new Pipe();
        return new LoopbackTransport[]{
                new LoopbackTransport(bToA, aToB, idA),
                new LoopbackTransport(aToB, bToA, idB)
        };
    }

    @Override
    public InputStream input() {
        return in;
    }

    @Override
    public OutputStream output() {
        return out;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void close() {
        writePipe.closeWriter(); // peer's reader drains then gets EOF
        readPipe.closeWriter();  // our reader gets EOF
    }

    /** Directional chunk pipe; one writer, one reader. Close is visible to the reader. */
    private static final class Pipe {
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean closed = new AtomicBoolean();

        void write(byte[] data) throws IOException {
            if (closed.get()) {
                throw new IOException("pipe closed");
            }
            try {
                queue.put(data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        /** Next chunk, or null when the writer closed and the queue drained. */
        byte[] take() throws IOException {
            while (true) {
                try {
                    byte[] chunk = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        return chunk;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                if (closed.get() && queue.isEmpty()) {
                    return null;
                }
            }
        }

        void closeWriter() {
            closed.set(true);
        }
    }

    /** Byte stream over a {@link Pipe}. The pending-chunk state lives here (single reader thread). */
    private static final class PipeInputStream extends InputStream {
        private final Pipe pipe;
        private byte[] current;
        private int pos;

        PipeInputStream(Pipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public int read() throws IOException {
            while (true) {
                if (current != null) {
                    if (pos < current.length) {
                        return current[pos++] & 0xFF;
                    }
                    current = null;
                    pos = 0;
                }
                byte[] chunk = pipe.take();
                if (chunk == null) {
                    return -1;
                }
                current = chunk;
            }
        }

        /**
         * Must return as soon as one byte is available - the default
         * implementation blocks until the whole buffer is full, which would
         * deadlock short handshake frames against buffering readers.
         */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            b[off] = (byte) first;
            return 1;
        }

        @Override
        public void close() {
            pipe.closeWriter();
        }
    }

    private static final class PipeOutputStream extends OutputStream {
        private final Pipe pipe;

        PipeOutputStream(Pipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public void write(int b) throws IOException {
            pipe.write(new byte[]{(byte) b});
        }

        /** Enqueue the whole range as one chunk so a flush is a single queue slot. */
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return;
            }
            pipe.write(java.util.Arrays.copyOfRange(b, off, off + len));
        }
    }
}
