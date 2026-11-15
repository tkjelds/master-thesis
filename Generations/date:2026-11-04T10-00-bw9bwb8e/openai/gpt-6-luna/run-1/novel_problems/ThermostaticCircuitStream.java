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
            this.storage = copyBytes(storage, storage.length);
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getStorage() {
            return copyBytes(storage, storage.length);
        }

        public int getCursor() {
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

    private byte[] buffer = new byte[0];
    private int size;
    private int cursor;
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

        byte[] payload = copyBytes(data, data.length);

        synchronized (this) {
            for (;;) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) payload.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }

                if (mode == Mode.NOMINAL
                        && (long) payload.length <= threshold - heat) {
                    if (payload.length > Integer.MAX_VALUE - size) {
                        throw new IllegalStateException("stream storage capacity exceeded");
                    }

                    int required = size + payload.length;
                    int capacity = buffer.length;
                    if (capacity < required) {
                        int newCapacity = capacity == 0 ? 16 : capacity;
                        while (newCapacity < required) {
                            if (newCapacity > Integer.MAX_VALUE / 2) {
                                newCapacity = required;
                                break;
                            }
                            newCapacity *= 2;
                        }

                        byte[] expanded = new byte[newCapacity];
                        copyInto(buffer, 0, expanded, 0, size);
                        copyInto(payload, 0, expanded, size, payload.length);
                        buffer = expanded;
                    } else {
                        copyInto(payload, 0, buffer, size, payload.length);
                    }

                    size = required;
                    heat += payload.length;
                    recomputeMode();
                    notifyAll();
                    return;
                }

                wait();
            }
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (this) {
            for (;;) {
                int unread = size - cursor;
                if (unread > 0 && (mode == Mode.NOMINAL || mode == Mode.CLOSED)) {
                    int count = unread < maxBytes ? unread : maxBytes;
                    byte[] result = new byte[count];
                    copyInto(buffer, cursor, result, 0, count);
                    cursor += count;
                    notifyAll();
                    return result;
                }

                if (mode == Mode.CLOSED) {
                    return null;
                }

                wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (this) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("cooling amount must not be negative");
            }

            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        synchronized (this) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (threshold <= 0) {
                throw new IllegalArgumentException("threshold must be positive");
            }

            this.threshold = threshold;
            recomputeMode();
            notifyAll();
        }
    }

    public void close() {
        synchronized (this) {
            mode = Mode.CLOSED;
            notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            byte[] storage = new byte[size];
            copyInto(buffer, 0, storage, 0, size);
            return new Snapshot(storage, cursor, heat, threshold, mode);
        }
    }

    private void recomputeMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    private static byte[] copyBytes(byte[] source, int length) {
        byte[] copy = new byte[length];
        copyInto(source, 0, copy, 0, length);
        return copy;
    }

    private static void copyInto(byte[] source, int sourceOffset,
                                 byte[] destination, int destinationOffset,
                                 int length) {
        for (int i = 0; i < length; i++) {
            destination[destinationOffset + i] = source[sourceOffset + i];
        }
    }
}
