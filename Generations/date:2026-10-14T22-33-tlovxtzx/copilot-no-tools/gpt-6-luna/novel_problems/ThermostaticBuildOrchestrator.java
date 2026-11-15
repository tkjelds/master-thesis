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
        public final Mode effectiveMode;
        public final Mode overrideMode;
        public final long[] pendingIds;
        public final int runningCount;
        public final int limit;
        public final boolean closed;

        private Snapshot(
                Mode automaticMode,
                Mode effectiveMode,
                Mode overrideMode,
                long[] pendingIds,
                int runningCount,
                int limit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.effectiveMode = effectiveMode;
            this.overrideMode = overrideMode;
            this.pendingIds = pendingIds;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public Mode getAutomaticMode() {
            return automaticMode;
        }

        public Mode getEffectiveMode() {
            return effectiveMode;
        }

        public Mode getOverrideMode() {
            return overrideMode;
        }

        public long[] getPendingIds() {
            long[] copy = new long[pendingIds.length];
            System.arraycopy(pendingIds, 0, copy, 0, pendingIds.length);
            return copy;
        }

        public int getRunningCount() {
            return runningCount;
        }

        public int getLimit() {
            return limit;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private final Object stateLock = new Object();
    private final Job[] pendingJobs;
    private final long[] pendingJobIds;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private int queueHead;
    private int queueSize;
    private int runningCount;
    private long nextJobId = 1L;
    private boolean jobIdsExhausted;
    private boolean closed;
    private int load;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;

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

        this.pendingJobs = new Job[queueCapacity];
        this.pendingJobIds = new long[queueCapacity];
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
            if (queueSize == pendingJobs.length) {
                throw new IllegalStateException("pending queue is full");
            }
            if (jobIdsExhausted) {
                throw new IllegalStateException("no positive job IDs remain");
            }

            long jobId = nextJobId;
            if (jobId == Long.MAX_VALUE) {
                jobIdsExhausted = true;
            } else {
                nextJobId = jobId + 1L;
            }

            int tail = (queueHead + queueSize) % pendingJobs.length;
            pendingJobs[tail] = job;
            pendingJobIds[tail] = jobId;
            queueSize++;
            stateLock.notifyAll();
            return jobId;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (stateLock) {
            for (int offset = 0; offset < queueSize; offset++) {
                int index = (queueHead + offset) % pendingJobs.length;
                if (pendingJobIds[index] == jobId) {
                    for (int shift = offset; shift < queueSize - 1; shift++) {
                        int destination = (queueHead + shift) % pendingJobs.length;
                        int source = (queueHead + shift + 1) % pendingJobs.length;
                        pendingJobs[destination] = pendingJobs[source];
                        pendingJobIds[destination] = pendingJobIds[source];
                    }

                    int last = (queueHead + queueSize - 1) % pendingJobs.length;
                    pendingJobs[last] = null;
                    pendingJobIds[last] = 0L;
                    queueSize--;
                    if (queueSize == 0) {
                        queueHead = 0;
                    }
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
            if (load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= lowThreshold) {
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
        final Job job;

        synchronized (stateLock) {
            while (true) {
                if (queueSize == 0) {
                    if (closed) {
                        return false;
                    }
                    stateLock.wait();
                    continue;
                }

                int limit = currentLimitLocked();
                if (runningCount < limit) {
                    job = pendingJobs[queueHead];
                    pendingJobs[queueHead] = null;
                    pendingJobIds[queueHead] = 0L;
                    queueHead = (queueHead + 1) % pendingJobs.length;
                    queueSize--;
                    if (queueSize == 0) {
                        queueHead = 0;
                    }
                    runningCount++;
                    stateLock.notifyAll();
                    break;
                }

                stateLock.wait();
            }
        }

        try {
            job.run();
        } finally {
            synchronized (stateLock) {
                runningCount--;
                stateLock.notifyAll();
            }
        }
        return true;
    }

    public void close() {
        synchronized (stateLock) {
            closed = true;
            stateLock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            long[] ids = new long[queueSize];
            for (int offset = 0; offset < queueSize; offset++) {
                ids[offset] = pendingJobIds[(queueHead + offset) % pendingJobs.length];
            }

            Mode effectiveMode = overrideMode == null ? automaticMode : overrideMode;
            int limit = effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
            return new Snapshot(
                    automaticMode,
                    effectiveMode,
                    overrideMode,
                    ids,
                    runningCount,
                    limit,
                    closed);
        }
    }

    private int currentLimitLocked() {
        Mode effectiveMode = overrideMode == null ? automaticMode : overrideMode;
        return effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
