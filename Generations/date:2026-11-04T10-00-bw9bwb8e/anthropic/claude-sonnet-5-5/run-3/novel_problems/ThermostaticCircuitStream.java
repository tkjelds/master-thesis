import java.lang.*;

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

        private Snapshot(byte[] storage, int cursor, long heat, long threshold, Mode mode) {
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

    private final Object lock = new Object();
    private byte[] storage = new byte[16];
    private int size = 0;
    private int cursor = 0;
    private long heat = 0L;
    private long threshold;
    private boolean closed = false;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = heatThreshold;
    }

    // Must be called with lock held.
    private Mode currentMode() {
        if (closed) {
            return Mode.CLOSED;
        }
        return heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must be non-null and non-empty");
        }
        final byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        final long len = copy.length;

        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("stream closed");
                }
                if (len > threshold) {
                    throw new IllegalArgumentException("payload larger than threshold");
                }
                if (heat < threshold && len <= threshold - heat) {
                    break;
                }
                lock.wait();
            }
            if ((long) size + len > (long) (Integer.MAX_VALUE - 8)) {
                throw new IllegalStateException("storage capacity exceeded");
            }
            int needed = size + copy.length;
            if (needed > storage.length) {
                long newCap = Math.max((long) storage.length * 2, (long) needed);
                if (newCap > Integer.MAX_VALUE - 8) {
                    newCap = Integer.MAX_VALUE - 8;
                }
                byte[] ns = new byte[(int) newCap];
                System.arraycopy(storage, 0, ns, 0, size);
                storage = ns;
            }
            System.arraycopy(copy, 0, storage, size, copy.length);
            size = needed;
            heat += len; // safe: heat + len <= threshold <= Long.MAX_VALUE
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
                    if (unread > 0) {
                        return consume(Math.min(unread, maxBytes));
                    }
                    return null;
                }
                if (unread > 0 && heat < threshold) {
                    return consume(Math.min(unread, maxBytes));
                }
                lock.wait();
            }
        }
    }

    // Must be called with lock held.
    private byte[] consume(int n) {
        byte[] out = new byte[n];
        System.arraycopy(storage, cursor, out, 0, n);
        cursor += n;
        lock.notifyAll();
        return out;
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("stream closed");
            }
            heat = (amount >= heat) ? 0L : heat - amount;
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
            return new Snapshot(copy, cursor, heat, threshold, currentMode());
        }
    }
}
