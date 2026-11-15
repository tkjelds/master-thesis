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
    private int size;
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive");
        }
        this.threshold = threshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data must not be null or empty");
        }

        byte[] copy = data.clone();

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("Stream is closed");
                }
                if ((long) copy.length > threshold) {
                    throw new IllegalArgumentException(
                            "Payload exceeds the current threshold");
                }
                if (mode == Mode.NOMINAL
                        && (long) copy.length <= threshold - heat) {
                    break;
                }
                monitor.wait();
            }

            long required = (long) size + copy.length;
            if (required > Integer.MAX_VALUE) {
                throw new OutOfMemoryError("Storage exceeds array size limit");
            }

            ensureCapacity((int) required);
            for (int i = 0; i < copy.length; i++) {
                storage[size + i] = copy[i];
            }
            size = (int) required;
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
            while (mode != Mode.CLOSED
                    && (cursor == size || mode != Mode.NOMINAL)) {
                monitor.wait();
            }

            if (cursor == size) {
                return null;
            }

            int count = size - cursor;
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
        if (amount < 0) {
            throw new IllegalArgumentException("Cooling amount must not be negative");
        }

        synchronized (monitor) {
            requireOpen();
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive");
        }

        synchronized (monitor) {
            requireOpen();
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
            byte[] copy = new byte[size];
            for (int i = 0; i < size; i++) {
                copy[i] = storage[i];
            }
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private void requireOpen() {
        if (mode == Mode.CLOSED) {
            throw new IllegalStateException("Stream is closed");
        }
    }

    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    private void ensureCapacity(int required) {
        if (required <= storage.length) {
            return;
        }

        long capacity = (long) storage.length * 2;
        if (capacity < required) {
            capacity = required;
        }
        if (capacity > Integer.MAX_VALUE) {
            capacity = Integer.MAX_VALUE;
        }

        byte[] expanded = new byte[(int) capacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = storage[i];
        }
        storage = expanded;
    }
}
