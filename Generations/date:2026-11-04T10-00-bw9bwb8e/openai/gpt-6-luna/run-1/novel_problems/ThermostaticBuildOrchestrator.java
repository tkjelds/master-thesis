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
        public final Mode overrideMode;
        public final Mode effectiveMode;
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;

        private final long[] pendingIds;

        private Snapshot(
                Mode automaticMode,
                Mode overrideMode,
                Mode effectiveMode,
                long[] pendingIds,
                int runningCount,
                int admissionLimit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingIds = pendingIds.clone();
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public long[] pendingJobIds() {
            return pendingIds.clone();
        }

        public int pendingCount() {
            return pendingIds.length;
        }
    }

    private static final class JobEntry {
        final long id;
        final Job job;

        JobEntry(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object stateLock = new Object();
    private final JobEntry[] pending;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private int pendingCount;
    private int runningCount;
    private int load;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private boolean closed;
    private long nextId = 1;
    private boolean idsExhausted;

    public ThermostaticBuildOrchestrator(
            int queueCapacity,
            int normalConcurrencyLimit,
            int lowThreshold,
            int highThreshold) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("normalConcurrencyLimit must be positive");
        }
        if (lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }

        this.pending = new JobEntry[queueCapacity];
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount == pending.length) {
                throw new IllegalStateException("pending queue is full");
            }
            if (idsExhausted) {
                throw new IllegalStateException("no positive job IDs remain");
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1;
            }

            pending[pendingCount++] = new JobEntry(id, job);
            stateLock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (stateLock) {
            for (int i = 0; i < pendingCount; i++) {
                if (pending[i].id == jobId) {
                    int remaining = pendingCount - i - 1;
                    if (remaining > 0) {
                        System.arraycopy(pending, i + 1, pending, i, remaining);
                    }
                    pending[--pendingCount] = null;
                    stateLock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("load must be between 0 and 100");
        }

        synchronized (stateLock) {
            this.load = load;
            if (automaticMode == Mode.NORMAL && load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (automaticMode == Mode.THROTTLED && load <= lowThreshold) {
                automaticMode = Mode.NORMAL;
            }
            stateLock.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }

        synchronized (stateLock) {
            overrideMode = mode;
            stateLock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (stateLock) {
            overrideMode = null;
            stateLock.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        JobEntry entry;

        synchronized (stateLock) {
            while (true) {
                int limit = currentAdmissionLimit();

                if (pendingCount > 0 && runningCount < limit) {
                    entry = pending[0];
                    if (pendingCount > 1) {
                        System.arraycopy(pending, 1, pending, 0, pendingCount - 1);
                    }
                    pending[--pendingCount] = null;
                    runningCount++;
                    stateLock.notifyAll();
                    break;
                }

                if (closed && pendingCount == 0) {
                    return false;
                }

                stateLock.wait();
            }
        }

        try {
            entry.job.run();
            return true;
        } finally {
            synchronized (stateLock) {
                runningCount--;
                stateLock.notifyAll();
            }
        }
    }

    public void close() {
        synchronized (stateLock) {
            closed = true;
            stateLock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            long[] ids = new long[pendingCount];
            for (int i = 0; i < pendingCount; i++) {
                ids[i] = pending[i].id;
            }

            Mode effectiveMode = overrideMode != null ? overrideMode : automaticMode;
            int limit = effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;

            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveMode,
                    ids,
                    runningCount,
                    limit,
                    closed);
        }
    }

    private int currentAdmissionLimit() {
        Mode effectiveMode = overrideMode != null ? overrideMode : automaticMode;
        return effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
