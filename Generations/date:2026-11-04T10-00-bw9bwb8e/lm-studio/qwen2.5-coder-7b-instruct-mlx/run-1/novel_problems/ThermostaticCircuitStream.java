@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    private final long threshold;
    private volatile byte[] buffer = new byte[0];
    private volatile int cursor = 0;
    private volatile long heat = 0;
    private volatile Mode mode = Mode.NOMINAL;

    public enum Mode {
        NOMINAL, OVERHEATED, CLOSED
    }

    public ThermostaticCircuitStream(long threshold) {
        if (threshold <= 0) throw new IllegalArgumentException("Threshold must be positive");
        this.threshold = threshold;
    }

    public synchronized void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0 || mode == Mode.CLOSED) return;
        while (heat + data.length > threshold && mode != Mode.OVERHEATED) {
            wait();
        }
        if (mode == Mode.CLOSED) return;
        heat += data.length;
        byte[] newBuffer = new byte[buffer.length + data.length];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        System.arraycopy(data, 0, newBuffer, buffer.length, data.length);
        buffer = newBuffer;
        if (heat >= threshold) {
            mode = Mode.OVERHEATED;
            notifyAll();
        }
    }

    public synchronized byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (mode == Mode.CLOSED || maxBytes <= 0) return null;
        while (cursor >= buffer.length && mode == Mode.NOMINAL) {
            wait();
        }
        if (mode == Mode.CLOSED) return null;
        byte[] result = new byte[Math.min(maxBytes, buffer.length - cursor)];
        System.arraycopy(buffer, cursor, result, 0, result.length);
        cursor += result.length;
        notifyAll();
        return result;
    }

    public synchronized void cool(long amount) {
        if (mode == Mode.CLOSED || amount < 0) return;
        heat = Math.max(heat - amount, 0);
        if (heat < threshold) {
            mode = Mode.NOMINAL;
            notifyAll();
        }
    }

    public synchronized void setThreshold(long threshold) {
        if (this.threshold != threshold && mode != Mode.CLOSED) {
            this.threshold = threshold;
            heat = Math.min(heat, threshold);
            if (heat >= threshold) {
                mode = Mode.OVERHEATED;
            } else {
                mode = Mode.NOMINAL;
            }
            notifyAll();
        }
    }

    public synchronized void close() {
        mode = Mode.CLOSED;
        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(buffer, cursor, heat, threshold, mode);
    }

    public static class Snapshot {
        private final byte[] buffer;
        private final int cursor;
        private final long heat;
        private final long threshold;
        private final Mode mode;

        public Snapshot(byte[] buffer, int cursor, long heat, long threshold, Mode mode) {
            this.buffer = buffer;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getBuffer() {
            return buffer.clone();
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
