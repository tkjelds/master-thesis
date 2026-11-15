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
        private final Mode automaticMode;
        private final Mode overrideMode;
        private final Mode effectiveMode;
        private final long[] pendingIds;
        private final int runningCount;
        private final int admissionLimit;
        private final boolean closed;

        private Snapshot(Mode automaticMode, Mode overrideMode, Mode effectiveMode,
                         long[] pendingIds, int runningCount, int admissionLimit, boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingIds = pendingIds;
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public Mode getAutomaticMode() {
            return automaticMode;
        }

        /** Returns null when no manual override is active. */
        public Mode getOverrideMode() {
            return overrideMode;
        }

        public Mode getEffectiveMode() {
            return effectiveMode;
        }

        /** Returns a detached copy of pending job IDs in FIFO order. */
        public long[] getPendingIds() {
            return pendingIds.clone();
        }

        public int getRunningCount() {
            return runningCount;
        }

        public int getAdmissionLimit() {
            return admissionLimit;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private final int normalLimit;
    private final int low;
    private final int high;

    private final long[] ids;
    private final Job[] jobs;
    private int count;

    private int load;
    private Mode autoMode = Mode.NORMAL;
    private Mode overrideMode;

    private int running;
    private boolean closed;
    private long nextId = 1L;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalConcurrencyLimit, int low, int high) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("normalConcurrencyLimit must be positive");
        }
        if (low < 0 || high > 100 || low >= high) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }
        this.capacity = queueCapacity;
        this.normalLimit = normalConcurrencyLimit;
        this.low = low;
        this.high = high;
        this.ids = new long[queueCapacity];
        this.jobs = new Job[queueCapacity];
    }

    /**
     * Submits a job and returns its unique positive ID.
     * Policy: a full queue or closed orchestrator causes IllegalStateException (no blocking).
     */
    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job must not be null");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (count == capacity) {
                throw new IllegalStateException("pending queue is full");
            }
            long id = nextId++;
            ids[count] = id;
            jobs[count] = job;
            count++;
            lock.notifyAll();
            return id;
        }
    }

    /** Removes a pending job. Running or completed jobs cannot be cancelled. */
    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                if (ids[i] == jobId) {
                    for (int j = i; j < count - 1; j++) {
                        ids[j] = ids[j + 1];
                        jobs[j] = jobs[j + 1];
                    }
                    count--;
                    jobs[count] = null;
                    ids[count] = 0L;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    /** Updates the load (0..100) and automatic mode. Manual override still takes precedence. */
    public void updateLoad(int newLoad) {
        if (newLoad < 0 || newLoad > 100) {
            throw new IllegalArgumentException("load must be in 0..100");
        }
        synchronized (lock) {
            load = newLoad;
            if (newLoad >= high) {
                autoMode = Mode.THROTTLED;
            } else if (newLoad <= low) {
                autoMode = Mode.NORMAL;
            }
            lock.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode must not be null");
        }
        synchronized (lock) {
            overrideMode = mode;
            lock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            overrideMode = null;
            lock.notifyAll();
        }
    }

    /**
     * Waits interruptibly until the oldest pending job can be admitted, runs it outside
     * all monitors, and releases running capacity afterwards.
     * Returns false only when closed and drained.
     */
    public boolean runNext() throws InterruptedException {
        Job job;
        synchronized (lock) {
            while (true) {
                if (count > 0 && running < admissionLimitLocked()) {
                    break;
                }
                if (closed && count == 0) {
                    return false;
                }
                lock.wait();
            }
            job = jobs[0];
            for (int j = 0; j < count - 1; j++) {
                ids[j] = ids[j + 1];
                jobs[j] = jobs[j + 1];
            }
            count--;
            jobs[count] = null;
            ids[count] = 0L;
            running++;
            lock.notifyAll();
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

    /** Rejects new submissions; queued and running jobs continue to drain. */
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Mode effective = effectiveModeLocked();
            long[] pending = new long[count];
            for (int i = 0; i < count; i++) {
                pending[i] = ids[i];
            }
            return new Snapshot(autoMode, overrideMode, effective, pending,
                    running, admissionLimitLocked(), closed);
        }
    }

    private Mode effectiveModeLocked() {
        return overrideMode != null ? overrideMode : autoMode;
    }

    private int admissionLimitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
