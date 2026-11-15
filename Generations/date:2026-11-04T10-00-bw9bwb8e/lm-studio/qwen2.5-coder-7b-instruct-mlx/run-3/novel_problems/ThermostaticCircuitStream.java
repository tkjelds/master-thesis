@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    private byte[] storage;
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) throw new IllegalArgumentException("Heat threshold must be positive");
        this.threshold = heatThreshold;
        this.storage = new byte[0];
        this.cursor = 0;
        this.heat = 0;
        this.mode = Mode.NOMINAL;
    }

    public synchronized void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Invalid data");
        if (heat + data.length > threshold) waitUntilCool(data.length);
        appendData(data);
    }

    private void appendData(byte[] data) {
        byte[] newStorage = new byte[storage.length + data.length];
        System.arraycopy(storage, 0, newStorage, 0, storage.length);
        System.arraycopy(data, 0, newStorage, storage.length, data.length);
        storage = newStorage;
        heat += data.length;
        recalculateMode();
    }

    private void waitUntilCool(long neededHeat) throws InterruptedException {
        while (heat + neededHeat > threshold) {
            wait();
        }
    }

    public synchronized byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Invalid max bytes");
        while (mode != Mode.NOMINAL || unreadBytes() == 0) {
            wait();
        }
        return consumeData(maxBytes);
    }

    private byte[] consumeData(int maxBytes) {
        int available = unreadBytes();
        if (maxBytes > available) maxBytes = available;
        byte[] data = new byte[maxBytes];
        System.arraycopy(storage, cursor, data, 0, maxBytes);
        cursor += maxBytes;
        return data;
    }

    public synchronized void cool(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Invalid cooling amount");
        heat = Math.max(heat - amount, 0);
        recalculateMode();
        notifyAll();
    }

    public synchronized void setThreshold(long threshold) {
        if (threshold <= 0) throw new IllegalArgumentException("Heat threshold must be positive");
        this.threshold = threshold;
        recalculateMode();
        notifyAll();
    }

    public synchronized void close() {
        mode = Mode.CLOSED;
        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(storage, cursor, heat, threshold, mode);
    }

    private void recalculateMode() {
        if (heat >= threshold) {
            mode = Mode.OVERHEATED;
        } else {
            mode = Mode.NOMINAL;
        }
    }

    private int unreadBytes() {
        return storage.length - cursor;
    }

    private enum Mode {
        NOMINAL, OVERHEATED, CLOSED
    }

    public static class Snapshot {
        private final byte[] storage;
        private final int cursor;
        private final long heat;
        private final long threshold;
        private final Mode mode;

        public Snapshot(byte[] storage, int cursor, long heat, long threshold, Mode mode) {
            this.storage = storage.clone();
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        // Getters for snapshot fields
    }
}
