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
        public final Mode effectiveMode;
        public final long[] pendingJobIds;
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;

        private Snapshot(
                Mode automaticMode,
                Mode effectiveMode,
                long[] pendingJobIds,
                int runningCount,
                int admissionLimit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.effectiveMode = effectiveMode;
            this.pendingJobIds = (long[]) pendingJobIds.clone();
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public long[] pendingJobIds() {
            return (long[]) pendingJobIds.clone();
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

    private final Object lock = new Object();
    private final JobEntry[] pending;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1;
    private boolean idsExhausted;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode manualOverride;

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

        this.pending = new JobEntry[queueCapacity];
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount == pending.length) {
                throw new IllegalStateException("pending queue is full");
            }
            if (idsExhausted) {
                throw new IllegalStateException("job ID space exhausted");
            }

            long id = nextJobId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextJobId++;
            }

            pending[pendingCount++] = new JobEntry(id, job);
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < pendingCount; i++) {
                if (pending[i].id == jobId) {
                    for (int j = i; j < pendingCount - 1; j++) {
                        pending[j] = pending[j + 1];
                    }
                    pending[--pendingCount] = null;
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
            manualOverride = mode;
            lock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            manualOverride = null;
            lock.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        JobEntry entry;

        synchronized (lock) {
            while (true) {
                int limit = effectiveLimitLocked();
                if (pendingCount > 0 && runningCount < limit) {
                    entry = pending[0];
                    for (int i = 0; i < pendingCount - 1; i++) {
                        pending[i] = pending[i + 1];
                    }
                    pending[--pendingCount] = null;
                    runningCount++;
                    break;
                }

                if (closed && pendingCount == 0 && runningCount == 0) {
                    return false;
                }

                lock.wait();
            }
        }

        try {
            entry.job.run();
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
            long[] ids = new long[pendingCount];
            for (int i = 0; i < pendingCount; i++) {
                ids[i] = pending[i].id;
            }
            Mode effectiveMode = manualOverride == null ? automaticMode : manualOverride;
            return new Snapshot(
                    automaticMode,
                    effectiveMode,
                    ids,
                    runningCount,
                    effectiveLimitLocked(),
                    closed);
        }
    }

    private int effectiveLimitLocked() {
        Mode mode = manualOverride == null ? automaticMode : manualOverride;
        return mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
