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
        private final long[] pendingJobIds;
        private final int runningCount;
        private final int admissionLimit;
        private final boolean closed;

        Snapshot(Mode automaticMode, Mode overrideMode, Mode effectiveMode,
                 long[] pendingJobIds, int runningCount, int admissionLimit, boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingJobIds = pendingJobIds;
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

        public long[] getPendingJobIds() {
            return pendingJobIds.clone();
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
    private final int queueCapacity;
    private final int normalLimit;
    private final int low;
    private final int high;

    // All state below is guarded by 'lock'.
    private long nextId = 1L;
    private long[] pendingIds;
    private Job[] pendingJobs;
    private int pendingSize;
    private int running;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode = null;
    private boolean closed;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (normalLimit <= 0) {
            throw new IllegalArgumentException("normalLimit must be positive");
        }
        if (low < 0 || high > 100 || low >= high) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }
        this.queueCapacity = queueCapacity;
        this.normalLimit = normalLimit;
        this.low = low;
        this.high = high;
        this.pendingIds = new long[queueCapacity];
        this.pendingJobs = new Job[queueCapacity];
    }

    /** Submits a job; returns its unique positive ID. Throws IllegalStateException if closed or full. */
    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job must not be null");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingSize == queueCapacity) {
                throw new IllegalStateException("pending queue is full");
            }
            long id = nextId++;
            pendingIds[pendingSize] = id;
            pendingJobs[pendingSize] = job;
            pendingSize++;
            lock.notifyAll();
            return id;
        }
    }

    /** Removes a pending job. Returns true only if the job was pending and removed. */
    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < pendingSize; i++) {
                if (pendingIds[i] == jobId) {
                    removeAt(i);
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    /** Updates automatic mode from load (0..100). Applies even during an override. */
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

    /** Sets a manual override which wins over automatic mode until cleared. */
    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode must not be null");
        }
        synchronized (lock) {
            overrideMode = mode;
            lock.notifyAll();
        }
    }

    /** Clears the manual override, restoring the current automatic mode. */
    public void clearOverride() {
        synchronized (lock) {
            overrideMode = null;
            lock.notifyAll();
        }
    }

    /**
     * Waits interruptibly until the oldest pending job can be admitted, runs it, and releases capacity.
     * Returns true if a job ran, false if closed and drained.
     */
    public boolean runNext() throws InterruptedException {
        Job job;
        synchronized (lock) {
            while (true) {
                if (pendingSize > 0) {
                    if (running < effectiveLimitLocked()) {
                        break;
                    }
                } else if (closed) {
                    return false;
                }
                lock.wait();
            }
            job = pendingJobs[0];
            removeAt(0);
            running++;
        }

        try {
            job.run();
            return true;
        } finally {
            synchronized (lock) {
                running--;
                lock.notifyAll();
            }
        }
    }

    /** Rejects new submissions; queued and running jobs continue to drain. */
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    /** Returns a coherent, detached view of the current state. */
    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[pendingSize];
            for (int i = 0; i < pendingSize; i++) {
                ids[i] = pendingIds[i];
            }
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveModeLocked(),
                    ids,
                    running,
                    effectiveLimitLocked(),
                    closed);
        }
    }

    // ---- helpers; caller must hold 'lock' ----

    private Mode effectiveModeLocked() {
        return overrideMode != null ? overrideMode : automaticMode;
    }

    private int effectiveLimitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED ? 1 : normalLimit;
    }

    private void removeAt(int index) {
        for (int i = index; i < pendingSize - 1; i++) {
            pendingIds[i] = pendingIds[i + 1];
            pendingJobs[i] = pendingJobs[i + 1];
        }
        pendingSize--;
        pendingIds[pendingSize] = 0L;
        pendingJobs[pendingSize] = null;
    }
}
