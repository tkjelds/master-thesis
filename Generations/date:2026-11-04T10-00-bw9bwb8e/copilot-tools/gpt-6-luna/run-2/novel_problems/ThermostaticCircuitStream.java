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
        public final int cursor;
        public final long heat;
        public final long threshold;
        public final Mode mode;

        private Snapshot(byte[] storage, int cursor, long heat, long threshold, Mode mode) {
            this.storage = storage;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }
    }

    private final Object state = new Object();
    private byte[] storage = new byte[0];
    private int storageSize;
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        threshold = heatThreshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }

        byte[] copy = new byte[data.length];
        copyBytes(data, 0, copy, 0, data.length);

        synchronized (state) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) copy.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }
                if (mode == Mode.NOMINAL && (long) copy.length <= threshold - heat) {
                    long requiredSize = (long) storageSize + copy.length;
                    if (requiredSize > Integer.MAX_VALUE) {
                        throw new OutOfMemoryError("stream storage exceeds maximum array size");
                    }

                    int newSize = (int) requiredSize;
                    ensureCapacity(newSize);
                    copyBytes(copy, 0, storage, storageSize, copy.length);
                    storageSize = newSize;
                    heat += copy.length;
                    recomputeMode();
                    state.notifyAll();
                    return;
                }
                state.wait();
            }
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (state) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    if (cursor == storageSize) {
                        return null;
                    }
                    return consume(maxBytes);
                }
                if (mode == Mode.NOMINAL && cursor < storageSize) {
                    return consume(maxBytes);
                }
                state.wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (state) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("cooling amount must not be negative");
            }

            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            state.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        synchronized (state) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (newThreshold <= 0) {
                throw new IllegalArgumentException("threshold must be positive");
            }

            threshold = newThreshold;
            recomputeMode();
            state.notifyAll();
        }
    }

    public void close() {
        synchronized (state) {
            mode = Mode.CLOSED;
            state.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (state) {
            byte[] copy = new byte[storageSize];
            copyBytes(storage, 0, copy, 0, storageSize);
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private byte[] consume(int maxBytes) {
        int unread = storageSize - cursor;
        int count = Math.min(maxBytes, unread);
        byte[] result = new byte[count];
        copyBytes(storage, cursor, result, 0, count);
        cursor += count;
        return result;
    }

    private void ensureCapacity(int required) {
        if (required <= storage.length) {
            return;
        }

        long newCapacity = storage.length == 0 ? 16L : storage.length;
        while (newCapacity < required) {
            newCapacity *= 2;
            if (newCapacity > Integer.MAX_VALUE) {
                newCapacity = Integer.MAX_VALUE;
                break;
            }
        }

        byte[] expanded = new byte[(int) newCapacity];
        copyBytes(storage, 0, expanded, 0, storageSize);
        storage = expanded;
    }

    private void recomputeMode() {
        if (mode != Mode.CLOSED) {
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
        }
    }

    private static void copyBytes(byte[] source, int sourceOffset,
                                  byte[] destination, int destinationOffset, int length) {
        for (int i = 0; i < length; i++) {
            destination[destinationOffset + i] = source[sourceOffset + i];
        }
    }
}
