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

    /** Detached, immutable view of the orchestrator state. */
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
        /** Manual override, or null if none. */
        public Mode overrideMode() { return overrideMode; }
        public Mode effectiveMode() { return effectiveMode; }
        public long[] pendingIds() { return pendingIds.clone(); }
        public int pendingCount() { return pendingIds.length; }
        public int runningCount() { return runningCount; }
        public int limit() { return limit; }
        public boolean closed() { return closed; }
    }

    private final Object lock = new Object();

    private final int capacity;
    private final int normalLimit;
    private final int low;
    private final int high;

    // Guarded by lock
    private final Job[] jobs;
    private final long[] ids;
    private int head = 0;
    private int size = 0;
    private long nextId = 1;
    private int running = 0;
    private boolean closed = false;
    private Mode automaticMode = Mode.NORMAL;
    private Mode override = null;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (normalLimit <= 0) {
            throw new IllegalArgumentException("normalLimit must be positive");
        }
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }
        this.capacity = queueCapacity;
        this.normalLimit = normalLimit;
        this.low = low;
        this.high = high;
        this.jobs = new Job[queueCapacity];
        this.ids = new long[queueCapacity];
    }

    // Must hold lock
    private Mode effectiveModeLocked() {
        return override != null ? override : automaticMode;
    }

    // Must hold lock
    private int limitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED ? 1 : normalLimit;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            if (size >= capacity) {
                throw new IllegalStateException("pending queue full");
            }
            long id = nextId++;
            int idx = (head + size) % capacity;
            jobs[idx] = job;
            ids[idx] = id;
            size++;
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                int idx = (head + i) % capacity;
                if (ids[idx] == jobId) {
                    for (int j = i; j < size - 1; j++) {
                        int a = (head + j) % capacity;
                        int b = (head + j + 1) % capacity;
                        jobs[a] = jobs[b];
                        ids[a] = ids[b];
                    }
                    int last = (head + size - 1) % capacity;
                    jobs[last] = null;
                    ids[last] = 0;
                    size--;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("load must be in 0..100");
        }
        synchronized (lock) {
            if (load >= high) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= low) {
                automaticMode = Mode.NORMAL;
            }
            lock.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
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
        Job job;
        synchronized (lock) {
            while (true) {
                if (size > 0 && running < limitLocked()) {
                    job = jobs[head];
                    jobs[head] = null;
                    ids[head] = 0;
                    head = (head + 1) % capacity;
                    size--;
                    running++;
                    // queue head changed; other waiters may be admissible
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
            job.run();
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
            long[] pending = new long[size];
            for (int i = 0; i < size; i++) {
                pending[i] = ids[(head + i) % capacity];
            }
            return new Snapshot(automaticMode, override, effectiveModeLocked(),
                    pending, running, limitLocked(), closed);
        }
    }
}
