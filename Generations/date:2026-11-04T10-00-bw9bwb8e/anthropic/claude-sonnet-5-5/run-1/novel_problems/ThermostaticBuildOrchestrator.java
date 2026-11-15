@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}


    public interface Job {
        void run();
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }

    /** Immutable detached snapshot of orchestrator state. */
    public static final class Snapshot {
        private final Mode automaticMode;
        private final Mode overrideMode;
        private final Mode effectiveMode;
        private final long[] pendingIds;
        private final int runningCount;
        private final int limit;
        private final boolean closed;

        Snapshot(Mode automaticMode, Mode overrideMode, Mode effectiveMode,
                 long[] pendingIds, int runningCount, int limit, boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingIds = pendingIds;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public Mode automaticMode() { return automaticMode; }
        /** May be null if no override is active. */
        public Mode overrideMode() { return overrideMode; }
        public Mode effectiveMode() { return effectiveMode; }
        public long[] pendingIds() { return pendingIds.clone(); }
        public int runningCount() { return runningCount; }
        public int limit() { return limit; }
        public boolean closed() { return closed; }
    }

    private static final class Entry {
        final long id;
        final Job job;
        Entry(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private final int normalLimit;
    private final int low;
    private final int high;

    // All below guarded by lock
    private final Entry[] ring;
    private int head = 0;
    private int size = 0;
    private int running = 0;
    private boolean closed = false;
    private long nextId = 1;
    private Mode autoMode = Mode.NORMAL;
    private Mode override = null;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (normalLimit <= 0) throw new IllegalArgumentException("limit must be positive");
        if (low < 0 || low >= high || high > 100)
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        this.capacity = queueCapacity;
        this.normalLimit = normalLimit;
        this.low = low;
        this.high = high;
        this.ring = new Entry[queueCapacity];
    }

    // requires lock held
    private Mode effectiveModeLocked() {
        return override != null ? override : autoMode;
    }

    // requires lock held
    private int limitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED ? 1 : normalLimit;
    }

    public long submit(Job job) {
        if (job == null) throw new NullPointerException("job");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("closed");
            if (size >= capacity) throw new IllegalStateException("queue full");
            long id = nextId++;
            ring[(head + size) % capacity] = new Entry(id, job);
            size++;
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                int idx = (head + i) % capacity;
                if (ring[idx].id == jobId) {
                    for (int j = i; j < size - 1; j++) {
                        ring[(head + j) % capacity] = ring[(head + j + 1) % capacity];
                    }
                    ring[(head + size - 1) % capacity] = null;
                    size--;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) throw new IllegalArgumentException("load out of range");
        synchronized (lock) {
            if (load >= high) {
                autoMode = Mode.THROTTLED;
            } else if (load <= low) {
                autoMode = Mode.NORMAL;
            }
            lock.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) throw new NullPointerException("mode");
        synchronized (lock) {
            override = mode;
            lock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            override = null;
            lock.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        Entry entry;
        synchronized (lock) {
            while (true) {
                if (size > 0 && running < limitLocked()) {
                    entry = ring[head];
                    ring[head] = null;
                    head = (head + 1) % capacity;
                    size--;
                    running++;
                    lock.notifyAll();
                    break;
                }
                if (closed && size == 0) {
                    return false;
                }
                lock.wait();
            }
        }
        try {
            entry.job.run();
        } finally {
            synchronized (lock) {
                running--;
                lock.notifyAll();
            }
        }
        return true;
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[size];
            for (int i = 0; i < size; i++) {
                ids[i] = ring[(head + i) % capacity].id;
            }
            return new Snapshot(autoMode, override, effectiveModeLocked(), ids,
                    running, limitLocked(), closed);
        }
    }
}
