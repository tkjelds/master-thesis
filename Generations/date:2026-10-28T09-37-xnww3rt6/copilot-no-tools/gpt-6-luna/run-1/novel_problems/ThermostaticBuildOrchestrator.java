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

    private final Object lock = new Object();
    private final int queueCapacity;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final Job[] pendingJobs;
    private final long[] pendingIds;

    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1L;
    private boolean jobIdsExhausted;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;

    public ThermostaticBuildOrchestrator(
            int queueCapacity,
            int normalConcurrencyLimit,
            int lowThreshold,
            int highThreshold) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("normal concurrency limit must be positive");
        }
        if (lowThreshold < 0 || lowThreshold >= highThreshold || highThreshold > 100) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }

        this.queueCapacity = queueCapacity;
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.pendingJobs = new Job[queueCapacity];
        this.pendingIds = new long[queueCapacity];
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount == queueCapacity) {
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

            pendingJobs[pendingCount] = job;
            pendingIds[pendingCount] = id;
            pendingCount++;
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < pendingCount; i++) {
                if (pendingIds[i] == jobId) {
                    int remaining = pendingCount - i - 1;
                    if (remaining > 0) {
                        System.arraycopy(pendingJobs, i + 1, pendingJobs, i, remaining);
                        System.arraycopy(pendingIds, i + 1, pendingIds, i, remaining);
                    }
                    pendingCount--;
                    pendingJobs[pendingCount] = null;
                    pendingIds[pendingCount] = 0L;
                    lock.notifyAll();
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
                int limit = currentAdmissionLimit();
                if (pendingCount > 0 && runningCount < limit) {
                    job = pendingJobs[0];
                    int remaining = pendingCount - 1;
                    if (remaining > 0) {
                        System.arraycopy(pendingJobs, 1, pendingJobs, 0, remaining);
                        System.arraycopy(pendingIds, 1, pendingIds, 0, remaining);
                    }
                    pendingCount--;
                    pendingJobs[pendingCount] = null;
                    pendingIds[pendingCount] = 0L;
                    runningCount++;
                    lock.notifyAll();
                    break;
                }

                if (closed && pendingCount == 0) {
                    return false;
                }

                lock.wait();
            }
        }

        try {
            job.run();
            return true;
        } finally {
            synchronized (lock) {
                runningCount--;
                lock.notifyAll();
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[pendingCount];
            System.arraycopy(pendingIds, 0, ids, 0, pendingCount);
            Mode effectiveMode = currentEffectiveMode();
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveMode,
                    ids,
                    runningCount,
                    limitFor(effectiveMode),
                    closed);
        }
    }

    private Mode currentEffectiveMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    private int currentAdmissionLimit() {
        return limitFor(currentEffectiveMode());
    }

    private int limitFor(Mode mode) {
        return mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
