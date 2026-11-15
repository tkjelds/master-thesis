@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    private byte[] data;
    private int cursor;
    private long heat;
    private long threshold;
    private volatile Mode mode;

    enum Mode {
        NOMINAL,
        OVERHEATED,
        CLOSED
    }

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) throw new IllegalArgumentException("Threshold must be positive");
        this.threshold = heatThreshold;
        this.data = new byte[0];
        this.cursor = 0;
        this.heat = 0;
        this.mode = Mode.NOMINAL;
    }

    public synchronized void writeWhenCool(byte[] data) throws InterruptedException {
        checkState();
        while (mode == Mode.OVERHEATED || heat + data.length > threshold) {
            wait();
        }
        byte[] newData = new byte[data.length];
        System.arraycopy(data, 0, newData, 0, data.length);
        this.data = newData;
        heat += data.length;
        if (heat >= threshold) {
            mode = Mode.OVERHEATED;
        }
        notifyAll();
    }

    public synchronized byte[] readWhenReady(int maxBytes) throws InterruptedException {
        checkState();
        while (mode != Mode.NOMINAL || data.length == cursor) {
            wait();
        }
        int actualBytes = Math.min(maxBytes, data.length - cursor);
        byte[] result = new byte[actualBytes];
        System.arraycopy(data, cursor, result, 0, actualBytes);
        cursor += actualBytes;
        notifyAll();
        return result;
    }

    public synchronized void cool(long amount) {
        checkState();
        if (amount < 0) return;
        heat = Math.max(0, heat - amount);
        if (heat < threshold) {
            mode = Mode.NOMINAL;
        }
        notifyAll();
    }

    public synchronized void setThreshold(long threshold) {
        checkState();
        if (threshold <= 0) throw new IllegalArgumentException("Threshold must be positive");
        this.threshold = threshold;
        if (heat >= threshold) {
            mode = Mode.OVERHEATED;
        }
        notifyAll();
    }

    public synchronized void close() {
        if (mode == Mode.CLOSED) return;
        mode = Mode.CLOSED;
        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(data, cursor, heat, threshold, mode);
    }

    private void checkState() {
        if (mode == Mode.CLOSED) throw new IllegalStateException("Stream is closed");
    }

    public static class Snapshot {
        private final byte[] data;
        private final int cursor;
        private final long heat;
        private final long threshold;
        private final Mode mode;

        public Snapshot(byte[] data, int cursor, long heat, long threshold, Mode mode) {
            this.data = data;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getData() {
            return data.clone();
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
}
