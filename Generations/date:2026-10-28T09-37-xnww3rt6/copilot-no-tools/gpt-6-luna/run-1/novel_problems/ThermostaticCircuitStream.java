@at.ThreadSafe
I’ll add the standalone class at the repository root and compile it without touching the unrelated existing worktree changes.
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
            byte[] copy = new byte[storage.length];
            System.arraycopy(storage, 0, copy, 0, storage.length);
            return copy;
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
    private byte[] storage = new byte[0];
    private int storageSize;
    private long cursor;
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

        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) copy.length > threshold) {
                    throw new IllegalArgumentException(
                            "payload exceeds the current threshold");
                }

                if (mode == Mode.NOMINAL
                        && (long) copy.length <= threshold - heat) {
                    if (copy.length > Integer.MAX_VALUE - storageSize) {
                        throw new IllegalStateException(
                                "stream storage exceeds maximum array size");
                    }

                    int newSize = storageSize + copy.length;
                    ensureCapacity(newSize);
                    System.arraycopy(copy, 0, storage, storageSize,
                            copy.length);
                    storageSize = newSize;
                    heat += copy.length;
                    updateMode();
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
                int unread = storageSize - (int) cursor;
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
        if (amount < 0) {
            throw new IllegalArgumentException(
                    "cooling amount must not be negative");
        }

        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }

        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            this.threshold = threshold;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void close() {
        synchronized (monitor) {
            if (mode != Mode.CLOSED) {
                mode = Mode.CLOSED;
                monitor.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            byte[] copy = new byte[storageSize];
            System.arraycopy(storage, 0, copy, 0, storageSize);
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private byte[] consume(int maxBytes, int unread) {
        int count = Math.min(maxBytes, unread);
        byte[] result = new byte[count];
        System.arraycopy(storage, (int) cursor, result, 0, count);
        cursor += count;
        return result;
    }

    private void ensureCapacity(int required) {
        if (required <= storage.length) {
            return;
        }

        int newCapacity = storage.length == 0 ? 16 : storage.length;
        while (newCapacity < required) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = Integer.MAX_VALUE;
            } else {
                newCapacity *= 2;
            }
        }

        byte[] expanded = new byte[newCapacity];
        System.arraycopy(storage, 0, expanded, 0, storageSize);
        storage = expanded;
    }

    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }
}
