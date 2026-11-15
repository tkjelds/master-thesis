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

        private Snapshot(byte[] storage, int cursor, long heat,
                         long threshold, Mode mode) {
            this.storage = storage;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }
    }

    private final Object monitor = new Object();

    private byte[] storage = new byte[0];
    private int length;
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
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("data must not be null or empty");
            }

            byte[] copy = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                copy[i] = data[i];
            }

            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) copy.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }
                if (mode == Mode.NOMINAL
                        && (long) copy.length <= threshold - heat) {
                    break;
                }
                monitor.wait();
            }

            long required = (long) length + copy.length;
            if (required > 2147483647L) {
                throw new OutOfMemoryError("storage exceeds array size limit");
            }
            ensureCapacity((int) required);

            for (int i = 0; i < copy.length; i++) {
                storage[length + i] = copy[i];
            }
            length = (int) required;
            heat += copy.length;
            updateMode();
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
                    if (cursor == length) {
                        return null;
                    }
                    break;
                }
                if (cursor < length && mode == Mode.NOMINAL) {
                    break;
                }
                monitor.wait();
            }

            int count = length - cursor;
            if (count > maxBytes) {
                count = maxBytes;
            }
            byte[] result = new byte[count];
            for (int i = 0; i < count; i++) {
                result[i] = storage[cursor + i];
            }
            cursor += count;
            return result;
        }
    }

    public void cool(long amount) {
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
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
            updateMode();
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
            byte[] copy = new byte[length];
            for (int i = 0; i < length; i++) {
                copy[i] = storage[i];
            }
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    // Called only while holding monitor.
    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    // Called only while holding monitor.
    private void ensureCapacity(int required) {
        if (required <= storage.length) {
            return;
        }

        long grown = (long) storage.length + (storage.length >> 1) + 1;
        if (grown < required) {
            grown = required;
        }
        if (grown > 2147483647L) {
            grown = 2147483647L;
        }

        byte[] replacement = new byte[(int) grown];
        for (int i = 0; i < length; i++) {
            replacement[i] = storage[i];
        }
        storage = replacement;
    }
}
