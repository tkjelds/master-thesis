@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
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

    private final Object lock = new Object();
    private byte[] storage = new byte[0];
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive");
        }
        threshold = heatThreshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data must not be null or empty");
        }

        byte[] copy = data.clone();

        synchronized (lock) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            if (copy.length > threshold) {
                throw new IllegalArgumentException("Payload exceeds the heat threshold");
            }

            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("Stream is closed");
                }
                if (copy.length > threshold) {
                    throw new IllegalArgumentException("Payload exceeds the heat threshold");
                }

                if (mode == Mode.NOMINAL && (long) copy.length <= threshold - heat) {
                    append(copy);
                    heat += copy.length;
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
            while (cursor == storage.length || (mode != Mode.CLOSED && mode != Mode.NOMINAL)) {
                if (mode == Mode.CLOSED && cursor == storage.length) {
                    return null;
                }
                lock.wait();
            }

            int count = Math.min(maxBytes, storage.length - cursor);
            byte[] result = new byte[count];
            System.arraycopy(storage, cursor, result, 0, count);
            cursor += count;
            return result;
        }
    }

    public void cool(long amount) {
        synchronized (lock) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("Cooling amount must not be negative");
            }

            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            lock.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        synchronized (lock) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            if (newThreshold <= 0) {
                throw new IllegalArgumentException("Threshold must be positive");
            }

            threshold = newThreshold;
            updateMode();
            lock.notifyAll();
        }
    }

    public void close() {
        synchronized (lock) {
            mode = Mode.CLOSED;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[storage.length];
            System.arraycopy(storage, 0, copy, 0, storage.length);
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private void append(byte[] data) {
        if (data.length > Integer.MAX_VALUE - storage.length) {
            throw new OutOfMemoryError("Stream storage capacity exceeded");
        }

        int required = storage.length + data.length;
        if (required > storage.length) {
            int capacity = storage.length == 0 ? 1 : storage.length;
            while (capacity < required) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = required;
                } else {
                    capacity *= 2;
                }
            }

            byte[] expanded = new byte[capacity];
            System.arraycopy(storage, 0, expanded, 0, storage.length);
            System.arraycopy(data, 0, expanded, storage.length, data.length);
            storage = expanded;
        } else {
            System.arraycopy(data, 0, storage, storage.length, data.length);
        }
    }

    private void updateMode() {
        if (mode != Mode.CLOSED) {
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
        }
    }
}
