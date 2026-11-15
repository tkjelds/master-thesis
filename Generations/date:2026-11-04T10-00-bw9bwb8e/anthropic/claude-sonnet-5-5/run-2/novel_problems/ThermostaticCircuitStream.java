@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}


    public enum Mode { NOMINAL, OVERHEATED, CLOSED }

    public static final class Snapshot {
        private final byte[] storage;
        private final int cursor;
        private final long heat;
        private final long threshold;
        private final Mode mode;

        Snapshot(byte[] storage, int cursor, long heat, long threshold, Mode mode) {
            this.storage = storage;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getStorage() {
            byte[] c = new byte[storage.length];
            System.arraycopy(storage, 0, c, 0, storage.length);
            return c;
        }

        public int getCursor() { return cursor; }
        public long getHeat() { return heat; }
        public long getThreshold() { return threshold; }
        public Mode getMode() { return mode; }
    }

    private final Object lock = new Object();
    private byte[] buffer = new byte[64];
    private int size = 0;
    private int cursor = 0;
    private long heat = 0;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = heatThreshold;
    }

    // Must be called with lock held.
    private void recomputeMode() {
        if (mode == Mode.CLOSED) {
            return;
        }
        mode = (heat >= threshold) ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must be non-empty");
        }
        final byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        final int len = copy.length;
        synchronized (lock) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream closed");
                }
                if (len > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }
                if (mode == Mode.NOMINAL && len <= threshold - heat) {
                    break;
                }
                lock.wait();
            }
            // Ensure capacity
            long needed = (long) size + len;
            if (needed > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("storage capacity exceeded");
            }
            if (needed > buffer.length) {
                long newCap = Math.max((long) buffer.length * 2, needed);
                if (newCap > Integer.MAX_VALUE - 8) {
                    newCap = Integer.MAX_VALUE - 8;
                }
                byte[] nb = new byte[(int) newCap];
                System.arraycopy(buffer, 0, nb, 0, size);
                buffer = nb;
            }
            System.arraycopy(copy, 0, buffer, size, len);
            size += len;
            heat += len; // len <= threshold - heat, so no overflow
            recomputeMode();
            lock.notifyAll();
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        synchronized (lock) {
            while (true) {
                int unread = size - cursor;
                if (unread > 0 && (mode == Mode.NOMINAL || mode == Mode.CLOSED)) {
                    int n = Math.min(unread, maxBytes);
                    byte[] out = new byte[n];
                    System.arraycopy(buffer, cursor, out, 0, n);
                    cursor += n;
                    lock.notifyAll();
                    return out;
                }
                if (mode == Mode.CLOSED && unread == 0) {
                    return null;
                }
                lock.wait();
            }
        }
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        synchronized (lock) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream closed");
            }
            heat = (amount >= heat) ? 0 : heat - amount;
            recomputeMode();
            lock.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        if (newThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        synchronized (lock) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream closed");
            }
            threshold = newThreshold;
            recomputeMode();
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
            byte[] copy = new byte[size];
            System.arraycopy(buffer, 0, copy, 0, size);
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }
}
