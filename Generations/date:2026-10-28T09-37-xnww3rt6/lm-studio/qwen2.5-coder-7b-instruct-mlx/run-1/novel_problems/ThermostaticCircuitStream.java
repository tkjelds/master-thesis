@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    private byte[] storage;
    private int cursor;
    private long heat;
    private long threshold;
    public enum Mode { NOMINAL, OVERHEATED, CLOSED }
    private Mode mode;

    public ThermostaticCircuitStream(long threshold) {
        if (threshold <= 0) throw new IllegalArgumentException("Threshold must be positive");
        this.threshold = threshold;
        storage = new byte[0];
        cursor = 0;
        heat = 0;
        mode = Mode.NOMINAL;
    }

    public synchronized void writeWhenCool(byte[] data) throws InterruptedException {
        if (mode == Mode.CLOSED || data == null || data.length == 0) return;
        if (heat + data.length > threshold) {
            while (mode != Mode.NOMINAL && heat < threshold) wait();
        }
        if (heat < threshold) {
            byte[] newData = new byte[storage.length + data.length];
            System.arraycopy(storage, 0, newData, 0, storage.length);
            System.arraycopy(data, 0, newData, storage.length, data.length);
            storage = newData;
            heat += data.length;
            if (heat >= threshold) mode = Mode.OVERHEATED;
        }
    }

    public synchronized byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (mode == Mode.CLOSED) return null;
        while (cursor == storage.length && mode != Mode.NOMINAL) wait();
        if (mode != Mode.NOMINAL) return null;
        int toRead = Math.min(maxBytes, storage.length - cursor);
        byte[] result = new byte[toRead];
        System.arraycopy(storage, cursor, result, 0, toRead);
        cursor += toRead;
        return result;
    }

    public synchronized void cool(long amount) throws InterruptedException {
        if (mode == Mode.CLOSED || amount < 0) return;
        heat = Math.max(0, heat - amount);
        if (heat < threshold) mode = Mode.NOMINAL;
        notifyAll();
    }

    public synchronized void setThreshold(long threshold) throws InterruptedException {
        if (mode == Mode.CLOSED || threshold <= 0) return;
        this.threshold = threshold;
        if (heat >= threshold) mode = Mode.OVERHEATED;
        else mode = Mode.NOMINAL;
        notifyAll();
    }

    public synchronized void close() {
        if (mode == Mode.CLOSED) return;
        mode = Mode.CLOSED;
        storage = null;
        notifyAll();
    }

    public synchronized Object snapshot() {
        return new Object[] { storage, cursor, heat, threshold, mode };
    }

    private static class Snapshot {
        byte[] storage;
        int cursor;
        long heat;
        long threshold;
        Mode mode;

        Snapshot(Object[] array) {
            storage = (byte[])array[0];
            cursor = (int)array[1];
            heat = (long)array[2];
            threshold = (long)array[3];
            mode = (Mode)array[4];
        }

        @Override
        public String toString() {
            return "Snapshot{" +
                    "storage=" + Arrays.toString(storage) +
                    ", cursor=" + cursor +
                    ", heat=" + heat +
                    ", threshold=" + threshold +
                    ", mode=" + mode +
                    '}';
        }
    }

    public static void main(String[] args) {
        ThermostaticCircuitStream stream = new ThermostaticCircuitStream(10);
        try {
            stream.writeWhenCool(new byte[]{1, 2});
            System.out.println(stream.readWhenReady(10));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
