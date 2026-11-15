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

        public byte[] getStorage() {
            return storage.clone();
        }
    }

    private final Object lock = new Object();
    private byte[] storage = new byte[0];
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

        byte[] copy = data.clone();
        long length = copy.length;

        synchronized (lock) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if (length > threshold) {
                    throw new IllegalArgumentException(
                            "payload is larger than the current threshold");
                }
                if (heat < threshold && length <= threshold - heat) {
                    append(copy);
                    heat += length;
                    updateMode();
                    lock.notifyAll();
                    return;
                }
                lock.wait();
            }
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (lock) {
            while (true) {
                int unread = storage.length - cursor;
                if (unread > 0 && (mode == Mode.NOMINAL || mode == Mode.CLOSED)) {
                    int count = unread < maxBytes ? unread : maxBytes;
                    byte[] result = new byte[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = storage[cursor + i];
                    }
                    cursor += count;
                    return result;
                }
                if (mode == Mode.CLOSED) {
                    return null;
                }
                lock.wait();
            }
        }
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("cooling amount must not be negative");
        }

        synchronized (lock) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            lock.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }

        synchronized (lock) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            this.threshold = threshold;
            updateMode();
            lock.notifyAll();
        }
    }

    public void close() {
        synchronized (lock) {
            if (mode != Mode.CLOSED) {
                mode = Mode.CLOSED;
                lock.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[storage.length];
            for (int i = 0; i < storage.length; i++) {
                copy[i] = storage[i];
            }
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private void append(byte[] data) {
        long requiredLong = (long) storage.length + data.length;
        if (requiredLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("stream storage exceeds maximum array size");
        }
        int required = (int) requiredLong;

        if (required > storage.length) {
            long doubled = storage.length == 0 ? 16L : (long) storage.length * 2L;
            int newCapacity = (int) (doubled > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : doubled);
            if (newCapacity < required) {
                newCapacity = required;
            }

            byte[] expanded = new byte[newCapacity];
            for (int i = 0; i < storage.length; i++) {
                expanded[i] = storage[i];
            }
            storage = expanded;
        }

        int offset = required - data.length;
        for (int i = 0; i < data.length; i++) {
            storage[offset + i] = data[i];
        }

        if (storage.length != required) {
            byte[] exact = new byte[required];
            for (int i = 0; i < required; i++) {
                exact[i] = storage[i];
            }
            storage = exact;
        }
    }

    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }
}
