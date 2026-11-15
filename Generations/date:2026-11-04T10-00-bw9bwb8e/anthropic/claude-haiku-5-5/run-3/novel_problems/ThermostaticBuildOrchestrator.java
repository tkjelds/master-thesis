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
        private final int effectiveLimit;
        private final int normalLimit;
        private final int runningCount;
        private final long[] pendingIds;
        private final boolean closed;

        Snapshot(Mode automaticMode, Mode overrideMode, Mode effectiveMode, int effectiveLimit,
                 int normalLimit, int runningCount, long[] pendingIds, boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.effectiveLimit = effectiveLimit;
            this.normalLimit = normalLimit;
            this.runningCount = runningCount;
            this.pendingIds = pendingIds;
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

        public int getEffectiveLimit() {
            return effectiveLimit;
        }

        public int getNormalLimit() {
            return normalLimit;
        }

        public int getRunningCount() {
            return runningCount;
        }

        public long[] getPendingIds() {
            return pendingIds.clone();
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private final Object lock = new Object();
    private final int queueCapacity;
    private final int normalLimit;
    private final int lowThreshold;
    private final int highThreshold;

    // Guarded by lock
    private final long[] pendingIds;
    private final Job[] pendingJobs;
    private int pendingSize;
    private long nextId = 1L;
    private int runningCount;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
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
        this.lowThreshold = low;
        this.highThreshold = high;
        this.pendingIds = new long[queueCapacity];
        this.pendingJobs = new Job[queueCapacity];
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
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

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < pendingSize; i++) {
                if (pendingIds[i] == jobId) {
                    int tail = pendingSize - i - 1;
                    if (tail > 0) {
                        System.arraycopy(pendingIds, i + 1, pendingIds, i, tail);
                        System.arraycopy(pendingJobs, i + 1, pendingJobs, i, tail);
                    }
                    pendingSize--;
                    pendingIds[pendingSize] = 0L;
                    pendingJobs[pendingSize] = null;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("load must be in 0..100");
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
                if (pendingSize == 0) {
                    if (closed) {
                        return false;
                    }
                    lock.wait();
                    continue;
                }
                if (runningCount < effectiveLimitLocked()) {
                    break;
                }
                lock.wait();
            }
            job = pendingJobs[0];
            int tail = pendingSize - 1;
            if (tail > 0) {
                System.arraycopy(pendingIds, 1, pendingIds, 0, tail);
                System.arraycopy(pendingJobs, 1, pendingJobs, 0, tail);
            }
            pendingSize--;
            pendingIds[pendingSize] = 0L;
            pendingJobs[pendingSize] = null;
            runningCount++;
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
            Mode effective = effectiveModeLocked();
            long[] ids = new long[pendingSize];
            System.arraycopy(pendingIds, 0, ids, 0, pendingSize);
            return new Snapshot(automaticMode, overrideMode, effective, effectiveLimitLocked(),
                    normalLimit, runningCount, ids, closed);
        }
    }

    private Mode effectiveModeLocked() {
        return overrideMode != null ? overrideMode : automaticMode;
    }

    private int effectiveLimitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
