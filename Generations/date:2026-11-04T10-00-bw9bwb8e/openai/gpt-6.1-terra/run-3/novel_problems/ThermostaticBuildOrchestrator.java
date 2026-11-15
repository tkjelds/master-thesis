@ThermostaticBuildOrchestrator.ThreadSafe
public final class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    public interface Job {
        void run();
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }

    public static final class Snapshot {
        public final Mode automaticMode;
        // Null means that no manual override is active.
        public final Mode overrideMode;
        public final Mode effectiveMode;
        public final int runningCount;
        public final int limit;
        public final boolean closed;
        public final int pendingCount;

        private final long[] pendingIds;

        private Snapshot(
                Mode automaticMode,
                Mode overrideMode,
                Mode effectiveMode,
                int runningCount,
                int limit,
                boolean closed,
                long[] pendingIds) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
            this.pendingIds = pendingIds;
            this.pendingCount = pendingIds.length;
        }

        // Returns a detached copy in FIFO order.
        public long[] pendingIds() {
            long[] copy = new long[pendingIds.length];
            for (int i = 0; i < pendingIds.length; i++) {
                copy[i] = pendingIds[i];
            }
            return copy;
        }
    }

    private final Object monitor = new Object();
    private final Job[] jobs;
    private final long[] ids;
    private final int normalLimit;
    private final int low;
    private final int high;

    // All mutable state below is guarded by monitor.
    private int head;
    private int pendingCount;
    private int runningCount;
    private long nextId = 1;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private boolean closed;

    public ThermostaticBuildOrchestrator(
            int queueCapacity, int normalConcurrencyLimit, int low, int high) {
        if (queueCapacity <= 0 || normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("Capacity and limit must be positive");
        }
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException("Thresholds must satisfy 0 <= low < high <= 100");
        }
        this.jobs = new Job[queueCapacity];
        this.ids = new long[queueCapacity];
        this.normalLimit = normalConcurrencyLimit;
        this.low = low;
        this.high = high;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Orchestrator is closed");
            }
            if (pendingCount == jobs.length) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (nextId == 0) {
                throw new IllegalStateException("Job IDs are exhausted");
            }

            long id = nextId;
            if (nextId == 9223372036854775807L) {
                nextId = 0;
            } else {
                nextId++;
            }

            int index = indexAt(pendingCount);
            jobs[index] = job;
            ids[index] = id;
            pendingCount++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            for (int offset = 0; offset < pendingCount; offset++) {
                if (ids[indexAt(offset)] == jobId) {
                    for (int i = offset; i < pendingCount - 1; i++) {
                        int destination = indexAt(i);
                        int source = indexAt(i + 1);
                        jobs[destination] = jobs[source];
                        ids[destination] = ids[source];
                    }
                    int last = indexAt(pendingCount - 1);
                    jobs[last] = null;
                    ids[last] = 0;
                    pendingCount--;
                    monitor.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("Load must be between 0 and 100");
        }
        synchronized (monitor) {
            if (load >= high) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= low) {
                automaticMode = Mode.NORMAL;
            }
            monitor.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        synchronized (monitor) {
            overrideMode = mode;
            monitor.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (monitor) {
            overrideMode = null;
            monitor.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        Job job;
        synchronized (monitor) {
            while (true) {
                if (pendingCount > 0 && runningCount < effectiveLimit()) {
                    job = jobs[head];
                    jobs[head] = null;
                    ids[head] = 0;
                    head = head == jobs.length - 1 ? 0 : head + 1;
                    pendingCount--;
                    runningCount++;
                    monitor.notifyAll();
                    break;
                }
                // Drained means that neither pending nor running jobs remain.
                if (closed && pendingCount == 0 && runningCount == 0) {
                    return false;
                }
                monitor.wait();
            }
        }

        try {
            job.run();
            return true;
        } finally {
            synchronized (monitor) {
                runningCount--;
                monitor.notifyAll();
            }
        }
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] pendingIds = new long[pendingCount];
            for (int i = 0; i < pendingCount; i++) {
                pendingIds[i] = ids[indexAt(i)];
            }
            Mode effective = effectiveMode();
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effective,
                    runningCount,
                    effective == Mode.THROTTLED ? 1 : normalLimit,
                    closed,
                    pendingIds);
        }
    }

    // Called only while holding monitor.
    private Mode effectiveMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    // Called only while holding monitor.
    private int effectiveLimit() {
        return effectiveMode() == Mode.THROTTLED ? 1 : normalLimit;
    }

    // Converts a queue offset to an array index without integer overflow.
    // Called only while holding monitor, with 0 <= offset < jobs.length.
    private int indexAt(int offset) {
        int remaining = jobs.length - head;
        return offset >= remaining ? offset - remaining : head + offset;
    }
}
