@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    private final long heatThreshold;
    private byte[] storage = new byte[0];
    private int cursor = 0;
    private long heat = 0;
    private boolean closed = false;
    private Mode mode = Mode.NOMINAL;

    public enum Mode {
        NOMINAL, OVERHEATED
    }

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("Heat threshold must be positive");
        }
        this.heatThreshold = heatThreshold;
    }

    public synchronized void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data must not be null or empty");
        }
        if (closed) {
            throw new IllegalStateException("Stream is closed");
        }

        while (heat + data.length >= heatThreshold) {
            if (mode == Mode.OVERHEATED && data.length + cursor < storage.length) {
                break;
            }
            wait();
        }

        byte[] newData = new byte[storage.length + data.length];
        System.arraycopy(storage, 0, newData, 0, storage.length);
        System.arraycopy(data, 0, newData, storage.length, data.length);
        storage = newData;

        heat += data.length;
        checkMode();
    }

    public synchronized byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Max bytes must be positive");
        }
        if (closed) {
            readRemainingBytes();
            return null;
        }

        while (cursor == storage.length || mode != Mode.NOMINAL) {
            wait();
        }

        int bytesToRead = Math.min(maxBytes, storage.length - cursor);
        byte[] result = new byte[bytesToRead];
        System.arraycopy(storage, cursor, result, 0, bytesToRead);
        cursor += bytesToRead;

        return result;
    }

    public synchronized void cool(long amount) {
        if (amount < 0 || closed) {
            return;
        }

        heat = Math.max(heat - amount, 0);
        checkMode();
    }

    public synchronized void setThreshold(long threshold) {
        if (threshold <= 0 || closed) {
            return;
        }

        heatThreshold = threshold;

        while (heat >= heatThreshold) {
            wait();
        }

        checkMode();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized ThermostaticCircuitStream snapshot() {
        return new ThermostaticCircuitStream(heatThreshold) {{
            storage = storage.clone();
            cursor = this.cursor;
            heat = this.heat;
            mode = this.mode;
        }};
    }

    private void checkMode() {
        if (heat >= heatThreshold) {
            mode = Mode.OVERHEATED;
        } else {
            mode = Mode.NOMINAL;
        }
        notifyAll();
    }

    private synchronized void readRemainingBytes() {
        byte[] result = new byte[storage.length - cursor];
        System.arraycopy(storage, cursor, result, 0, storage.length - cursor);
        cursor = storage.length;
    }
}
