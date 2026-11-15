@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}


    public enum Mode {
        NOMINAL,
        OVERHEATED,
        CLOSED
    }

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

    private static final int MAX_STORAGE = Integer.MAX_VALUE - 8;

    private final Object lock = new Object();
    private byte[] storage = new byte[16];
    private int size = 0;
    private int cursor = 0;
    private long heat = 0;
    private long threshold;
    private boolean closed = false;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = heatThreshold;
    }

    // Must be called with lock held.
    private Mode computeMode() {
        if (closed) {
            return Mode.CLOSED;
        }
        return heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
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
                if (closed) {
                    throw new IllegalStateException("stream closed");
                }
                if (len > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }
                if (heat < threshold && len <= threshold - heat) {
                    break;
                }
                lock.wait();
            }
            if (len > MAX_STORAGE - size) {
                throw new IllegalStateException("storage capacity exceeded");
            }
            if (size + len > storage.length) {
                long newCap = (long) storage.length * 2;
                if (newCap < (long) size + len) {
                    newCap = (long) size + len;
                }
                if (newCap > MAX_STORAGE) {
                    newCap = MAX_STORAGE;
                }
                byte[] ns = new byte[(int) newCap];
                System.arraycopy(storage, 0, ns, 0, size);
                storage = ns;
            }
            System.arraycopy(copy, 0, storage, size, len);
            size += len;
            heat += len; // safe: len <= threshold - heat
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
                if (closed) {
                    if (unread == 0) {
                        return null;
                    }
                    break;
                }
                if (unread > 0 && heat < threshold) {
                    break;
                }
                lock.wait();
            }
            int n = Math.min(maxBytes, size - cursor);
            byte[] out = new byte[n];
            System.arraycopy(storage, cursor, out, 0, n);
            cursor += n;
            lock.notifyAll();
            return out;
        }
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("stream closed");
            }
            heat = amount >= heat ? 0 : heat - amount;
            lock.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        if (newThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("stream closed");
            }
            threshold = newThreshold;
            lock.notifyAll();
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[size];
            System.arraycopy(storage, 0, copy, 0, size);
            return new Snapshot(copy, cursor, heat, threshold, computeMode());
        }
    }
}
