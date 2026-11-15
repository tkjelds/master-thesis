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
        public final Mode automaticMode;
        public final Mode overrideMode;
        public final Mode effectiveMode;
        public final long[] pendingJobIds;
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;

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
            this.pendingJobIds = pendingJobIds;
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public long[] getPendingJobIds() {
            return pendingJobIds.clone();
        }
    }

    private final Object lock = new Object();
    private final int queueCapacity;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final Job[] jobs;
    private final long[] jobIds;

    private int queueHead;
    private int queueSize;
    private int runningCount;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private long nextJobId = 1L;
    private boolean jobIdsExhausted;

    public ThermostaticBuildOrchestrator(
            int queueCapacity,
            int normalConcurrencyLimit,
            int lowThreshold,
            int highThreshold) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("Normal concurrency limit must be positive");
        }
        if (lowThreshold < 0
                || lowThreshold >= highThreshold
                || highThreshold > 100) {
            throw new IllegalArgumentException("Thresholds must satisfy 0 <= low < high <= 100");
        }

        this.queueCapacity = queueCapacity;
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.jobs = new Job[queueCapacity];
        this.jobIds = new long[queueCapacity];
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Orchestrator is closed");
            }
            if (queueSize == queueCapacity) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (jobIdsExhausted) {
                throw new IllegalStateException("No positive job IDs remain");
            }

            long id = nextJobId;
            if (nextJobId == Long.MAX_VALUE) {
                jobIdsExhausted = true;
            } else {
                nextJobId++;
            }

            int tail = (queueHead + queueSize) % queueCapacity;
            jobs[tail] = job;
            jobIds[tail] = id;
            queueSize++;
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int offset = 0; offset < queueSize; offset++) {
                int index = (queueHead + offset) % queueCapacity;
                if (jobIds[index] == jobId) {
                    for (int shift = offset; shift < queueSize - 1; shift++) {
                        int destination = (queueHead + shift) % queueCapacity;
                        int source = (queueHead + shift + 1) % queueCapacity;
                        jobs[destination] = jobs[source];
                        jobIds[destination] = jobIds[source];
                    }

                    int last = (queueHead + queueSize - 1) % queueCapacity;
                    jobs[last] = null;
                    jobIds[last] = 0L;
                    queueSize--;
                    lock.notifyAll();
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

        synchronized (lock) {
            if (load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= lowThreshold) {
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

    public boolean runNext() throws InterruptedException {
        Job job;

        synchronized (lock) {
            while (true) {
                if (queueSize > 0 && runningCount < admissionLimitLocked()) {
                    job = jobs[queueHead];
                    jobs[queueHead] = null;
                    jobIds[queueHead] = 0L;
                    queueHead = (queueHead + 1) % queueCapacity;
                    queueSize--;
                    runningCount++;
                    break;
                }

                if (closed && queueSize == 0) {
                    return false;
                }

                lock.wait();
            }
        }

        try {
            job.run();
        } finally {
            synchronized (lock) {
                runningCount--;
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
            long[] pendingIds = new long[queueSize];
            for (int offset = 0; offset < queueSize; offset++) {
                pendingIds[offset] = jobIds[(queueHead + offset) % queueCapacity];
            }

            Mode effectiveMode = overrideMode == null ? automaticMode : overrideMode;
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveMode,
                    pendingIds,
                    runningCount,
                    effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit,
                    closed);
        }
    }

    private int admissionLimitLocked() {
        Mode effectiveMode = overrideMode == null ? automaticMode : overrideMode;
        return effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
