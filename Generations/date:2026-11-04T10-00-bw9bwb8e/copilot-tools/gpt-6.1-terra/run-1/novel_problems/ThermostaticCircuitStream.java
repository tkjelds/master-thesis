@ThermostaticCircuitStream.ThreadSafe
public final class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    public enum Mode {
        NOMINAL, OVERHEATED, CLOSED
    }

    public static final class Snapshot {
        private final byte[] storage;
        private final int cursor;
        private final long heat;
        private final long threshold;
        private final Mode mode;

        private Snapshot(byte[] storage, int cursor, long heat, long threshold, Mode mode) {
            this.storage = storage;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getStorage() {
            byte[] copy = new byte[storage.length];
            for (int i = 0; i < storage.length; i++) {
                copy[i] = storage[i];
            }
            return copy;
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

    private final Object monitor = new Object();
    private byte[] storage = new byte[0];
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
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }

        byte[] copy = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            copy[i] = data[i];
        }

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if (copy.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }
                if (mode == Mode.NOMINAL && heat <= threshold - copy.length) {
                    break;
                }
                monitor.wait();
            }

            if (copy.length > 2147483647 - size) {
                throw new IllegalStateException("storage capacity exceeded");
            }

            int required = size + copy.length;
            if (required > storage.length) {
                int capacity = storage.length == 0 ? 1 : storage.length;
                while (capacity < required) {
                    if (capacity > 2147483647 / 2) {
                        capacity = required;
                    } else {
                        capacity *= 2;
                    }
                }

                byte[] expanded = new byte[capacity];
                for (int i = 0; i < size; i++) {
                    expanded[i] = storage[i];
                }
                storage = expanded;
            }

            for (int i = 0; i < copy.length; i++) {
                storage[size + i] = copy[i];
            }
            size = required;
            heat += copy.length;
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
            monitor.notifyAll();
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    if (cursor == size) {
                        return null;
                    }
                    break;
                }
                if (cursor < size && mode == Mode.NOMINAL) {
                    break;
                }
                monitor.wait();
            }

            int remaining = size - cursor;
            int count = maxBytes < remaining ? maxBytes : remaining;
            byte[] result = new byte[count];
            for (int i = 0; i < count; i++) {
                result[i] = storage[cursor + i];
            }
            cursor += count;
            return result;
        }
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }

        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            heat = amount >= heat ? 0 : heat - amount;
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
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
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
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
            byte[] copy = new byte[size];
            for (int i = 0; i < size; i++) {
                copy[i] = storage[i];
            }
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }
}
