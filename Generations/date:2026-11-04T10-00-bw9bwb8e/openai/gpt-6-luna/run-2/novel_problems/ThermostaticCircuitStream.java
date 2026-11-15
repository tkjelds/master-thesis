@ThermostaticCircuitStream.ThreadSafe
public final class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    public enum Mode {
        NOMINAL,
        OVERHEATED,
        CLOSED
    }

    public static final class Snapshot {
        public final byte[] storage;
        public final long cursor;
        public final long heat;
        public final long threshold;
        public final Mode mode;

        private Snapshot(byte[] storage, long cursor, long heat,
                         long threshold, Mode mode) {
            this.storage = storage;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getStorage() {
            return copyOf(storage, storage.length);
        }

        public long getCursor() {
            return cursor;
        }

        public long getHeat() {
            return heat;
        }

        public long getThreshold() {
            return threshold;
        }

        public Mode getMode() {
            return mode;
        }
    }

    private final Object monitor = new Object();

    // All fields below are accessed only while holding monitor.
    private byte[] storage = new byte[0];
    private int storageLength;
    private int readCursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = threshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }

        // Copy before waiting so later caller changes cannot affect the write.
        byte[] payload = copyOf(data, data.length);

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) payload.length > threshold) {
                    throw new IllegalArgumentException(
                            "payload exceeds the current threshold");
                }

                long requiredLength = (long) storageLength + payload.length;
                if (requiredLength > Integer.MAX_VALUE) {
                    throw new IllegalStateException("stream storage is too large");
                }

                if (mode == Mode.NOMINAL
                        && (long) payload.length <= threshold - heat) {
                    ensureCapacity((int) requiredLength);
                    copyInto(payload, storage, storageLength);
                    storageLength = (int) requiredLength;
                    heat += payload.length;
                    recomputeMode();
                    monitor.notifyAll();
                    return;
                }

                monitor.wait();
            }
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (monitor) {
            while (true) {
                int unread = storageLength - readCursor;

                if (mode == Mode.CLOSED) {
                    if (unread == 0) {
                        return null;
                    }
                    return consume(maxBytes, unread);
                }

                if (unread > 0 && mode == Mode.NOMINAL) {
                    return consume(maxBytes, unread);
                }

                monitor.wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("cooling amount must not be negative");
            }

            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (threshold <= 0) {
                throw new IllegalArgumentException("threshold must be positive");
            }

            this.threshold = threshold;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void close() {
        synchronized (monitor) {
            mode = Mode.CLOSED;
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            byte[] copiedStorage = new byte[storageLength];
            copyInto(storage, copiedStorage, 0, storageLength);
            return new Snapshot(copiedStorage, readCursor, heat, threshold, mode);
        }
    }

    private byte[] consume(int maxBytes, int unread) {
        int count = maxBytes < unread ? maxBytes : unread;
        byte[] result = new byte[count];
        copyInto(storage, result, readCursor, count);
        readCursor += count;
        monitor.notifyAll();
        return result;
    }

    private void recomputeMode() {
        if (mode != Mode.CLOSED) {
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
        }
    }

    private void ensureCapacity(int required) {
        if (storage.length >= required) {
            return;
        }

        int newCapacity = storage.length == 0 ? 8 : storage.length;
        while (newCapacity < required) {
            if (newCapacity <= Integer.MAX_VALUE / 2) {
                newCapacity *= 2;
            } else {
                newCapacity = Integer.MAX_VALUE;
            }
        }

        byte[] expanded = new byte[newCapacity];
        copyInto(storage, expanded, 0, storageLength);
        storage = expanded;
    }

    private static byte[] copyOf(byte[] source, int length) {
        byte[] copy = new byte[length];
        copyInto(source, copy, 0, length);
        return copy;
    }

    private static void copyInto(byte[] source, byte[] destination, int destinationOffset) {
        copyInto(source, destination, 0, destinationOffset, source.length);
    }

    private static void copyInto(byte[] source, byte[] destination,
                                 int sourceOffset, int length) {
        copyInto(source, destination, sourceOffset, 0, length);
    }

    private static void copyInto(byte[] source, byte[] destination,
                                 int sourceOffset, int destinationOffset, int length) {
        for (int i = 0; i < length; i++) {
            destination[destinationOffset + i] = source[sourceOffset + i];
        }
    }
}
