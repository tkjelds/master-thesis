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
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;
        private final long[] pendingJobIds;

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

        public long[] getPendingJobIds() {
            return pendingJobIds.clone();
        }
    }

    private final Object stateLock = new Object();
    private final Job[] jobs;
    private final long[] jobIds;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private int head;
    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1L;
    private boolean closed;
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

        this.jobs = new Job[queueCapacity];
        this.jobIds = new long[queueCapacity];
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
            if (pendingCount == jobs.length) {
                throw new IllegalStateException("pending queue is full");
            }
            if (nextJobId == 0L) {
                throw new IllegalStateException("job ID space exhausted");
            }

            long id = nextJobId;
            nextJobId = id == Long.MAX_VALUE ? 0L : id + 1L;

            int tail = (head + pendingCount) % jobs.length;
            jobs[tail] = job;
            jobIds[tail] = id;
            pendingCount++;
            stateLock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (stateLock) {
            for (int i = 0; i < pendingCount; i++) {
                int index = (head + i) % jobs.length;
                if (jobIds[index] == jobId) {
                    for (int j = i; j < pendingCount - 1; j++) {
                        int current = (head + j) % jobs.length;
                        int next = (head + j + 1) % jobs.length;
                        jobs[current] = jobs[next];
                        jobIds[current] = jobIds[next];
                    }

                    int tail = (head + pendingCount - 1) % jobs.length;
                    jobs[tail] = null;
                    jobIds[tail] = 0L;
                    pendingCount--;
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
        Job job;

        synchronized (stateLock) {
            while (true) {
                if (pendingCount > 0 && runningCount < currentAdmissionLimit()) {
                    job = jobs[head];
                    jobs[head] = null;
                    jobIds[head] = 0L;
                    head = (head + 1) % jobs.length;
                    pendingCount--;
                    runningCount++;
                    break;
                }

                if (closed && pendingCount == 0) {
                    return false;
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
            for (int i = 0; i < pendingCount; i++) {
                pendingIds[i] = jobIds[(head + i) % jobs.length];
            }

            Mode effectiveMode = currentMode();
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveMode,
                    pendingIds,
                    runningCount,
                    limitFor(effectiveMode),
                    closed);
        }
    }

    private Mode currentMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    private int currentAdmissionLimit() {
        return limitFor(currentMode());
    }

    private int limitFor(Mode mode) {
        return mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
