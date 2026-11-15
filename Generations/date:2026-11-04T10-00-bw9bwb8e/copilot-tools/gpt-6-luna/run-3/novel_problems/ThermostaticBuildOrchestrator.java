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

    public static final class Snapshot {
        private final Mode automaticMode;
        private final Mode overrideMode;
        private final Mode effectiveMode;
        private final long[] pendingJobIds;
        private final int runningCount;
        private final int admissionLimit;
        private final boolean closed;

        private Snapshot(
                Mode automaticMode,
                Mode overrideMode,
                Mode effectiveMode,
                long[] pendingJobIds,
                int runningCount,
                int admissionLimit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingJobIds = pendingJobIds.clone();
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public Mode getAutomaticMode() {
            return automaticMode;
        }

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

    private final Object stateLock = new Object();
    private final Job[] jobs;
    private final long[] jobIds;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private int queueHead;
    private int pendingCount;
    private int runningCount;
    private int load;
    private long nextJobId = 1L;
    private boolean jobIdsExhausted;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private Mode effectiveMode = Mode.NORMAL;
    private int admissionLimit;

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

        this.jobs = new Job[queueCapacity];
        this.jobIds = new long[queueCapacity];
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.admissionLimit = normalConcurrencyLimit;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount == jobs.length) {
                throw new IllegalStateException("pending queue is full");
            }
            if (jobIdsExhausted) {
                throw new IllegalStateException("job ID space is exhausted");
            }

            long id = nextJobId;
            if (nextJobId == Long.MAX_VALUE) {
                jobIdsExhausted = true;
            } else {
                nextJobId++;
            }

            int tail = (queueHead + pendingCount) % jobs.length;
            jobs[tail] = job;
            jobIds[tail] = id;
            pendingCount++;
            stateLock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (stateLock) {
            int offset;
            for (offset = 0; offset < pendingCount; offset++) {
                int index = (queueHead + offset) % jobs.length;
                if (jobIds[index] == jobId) {
                    break;
                }
            }
            if (offset == pendingCount) {
                return false;
            }

            for (int current = offset; current < pendingCount - 1; current++) {
                int destination = (queueHead + current) % jobs.length;
                int source = (queueHead + current + 1) % jobs.length;
                jobs[destination] = jobs[source];
                jobIds[destination] = jobIds[source];
            }

            int tail = (queueHead + pendingCount - 1) % jobs.length;
            jobs[tail] = null;
            jobIds[tail] = 0L;
            pendingCount--;
            stateLock.notifyAll();
            return true;
        }
    }

    public void updateLoad(int newLoad) {
        if (newLoad < 0 || newLoad > 100) {
            throw new IllegalArgumentException("load must be between 0 and 100");
        }

        synchronized (stateLock) {
            load = newLoad;
            if (newLoad >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (newLoad <= lowThreshold) {
                automaticMode = Mode.NORMAL;
            }
            updateEffectiveModeLocked();
            stateLock.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }

        synchronized (stateLock) {
            overrideMode = mode;
            updateEffectiveModeLocked();
            stateLock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (stateLock) {
            overrideMode = null;
            updateEffectiveModeLocked();
            stateLock.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        final Job job;
        synchronized (stateLock) {
            while (true) {
                if (pendingCount == 0 && closed) {
                    return false;
                }
                if (pendingCount > 0 && runningCount < admissionLimit) {
                    job = jobs[queueHead];
                    jobs[queueHead] = null;
                    jobIds[queueHead] = 0L;
                    queueHead = (queueHead + 1) % jobs.length;
                    pendingCount--;
                    runningCount++;
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
            long[] pendingIds = new long[pendingCount];
            for (int offset = 0; offset < pendingCount; offset++) {
                pendingIds[offset] = jobIds[(queueHead + offset) % jobs.length];
            }
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveMode,
                    pendingIds,
                    runningCount,
                    admissionLimit,
                    closed);
        }
    }

    private void updateEffectiveModeLocked() {
        effectiveMode = overrideMode == null ? automaticMode : overrideMode;
        admissionLimit = effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
