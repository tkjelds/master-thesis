@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    private final long initialThreshold;
    private volatile long threshold;
    private volatile long heat;
    private byte[][] storage = new byte[4][];
    private int size = 0;
    private int cursor = 0;
    private volatile Mode mode = Mode.NOMINAL;

    public enum Mode {
        NOMINAL,
        OVERHEATED,
        CLOSED
    }

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) throw new IllegalArgumentException("Threshold must be positive");
        this.initialThreshold = heatThreshold;
        this.threshold = heatThreshold;
    }

    public synchronized void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) return;
        long requiredHeat = data.length;
        if (heat >= threshold || heat + requiredHeat > threshold) {
            while (heat >= threshold || heat + requiredHeat > threshold) {
                wait();
            }
        }
        byte[] newData = new byte[data.length];
        System.arraycopy(data, 0, newData, 0, data.length);
        storage[size++] = newData;
        heat += requiredHeat;
        checkThreshold();
    }

    public synchronized byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (mode == Mode.CLOSED) return null;
        while (size == cursor || mode != Mode.NOMINAL) {
            wait();
        }
        int toRead = Math.min(size - cursor, maxBytes);
        byte[] result = new byte[toRead];
        System.arraycopy(storage[cursor], 0, result, 0, toRead);
        cursor++;
        notifyAll();
        return result;
    }

    public synchronized void cool(long amount) {
        if (mode == Mode.CLOSED || amount < 0) return;
        heat = Math.max(heat - amount, 0);
        checkThreshold();
    }

    public synchronized void setThreshold(long threshold) {
        if (mode == Mode.CLOSED || threshold <= 0) return;
        this.threshold = threshold;
        checkThreshold();
    }

    public synchronized void close() {
        mode = Mode.CLOSED;
        notifyAll();
    }

    public synchronized ThermostaticCircuitStream snapshot() {
        byte[][] newStorage = new byte[size][];
        System.arraycopy(storage, 0, newStorage, 0, size);
        return new ThermostaticCircuitStream(heat) {{
            this.threshold = threshold;
            this.mode = mode;
            for (int i = 0; i < size; i++) {
                this.storage[i] = newStorage[i];
            }
            this.size = size;
            this.cursor = cursor;
        }};
    }

    private synchronized void checkThreshold() {
        if (heat >= threshold) {
            mode = Mode.OVERHEATED;
        } else {
            mode = Mode.NOMINAL;
        }
    }

    private synchronized void expandStorage() {
        byte[][] newStorage = new byte[storage.length * 2][];
        System.arraycopy(storage, 0, newStorage, 0, size);
        storage = newStorage;
    }
}
