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
        public final long[] pendingIds;
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;

        private Snapshot(Mode automaticMode, Mode overrideMode, Mode effectiveMode,
                         long[] pendingIds, int runningCount, int admissionLimit,
                         boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingIds = pendingIds;
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }
    }

    private static final class QueueEntry {
        private final long id;
        private final Job job;

        private QueueEntry(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object stateMonitor = new Object();
    private final QueueEntry[] queue;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private int queueHead;
    private int queueSize;
    private int runningCount;
    private long nextJobId = 1L;
    private boolean jobIdsExhausted;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private Mode effectiveMode = Mode.NORMAL;

    public ThermostaticBuildOrchestrator(int queueCapacity,
                                         int normalConcurrencyLimit,
                                         int lowThreshold,
                                         int highThreshold) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("normal concurrency limit must be positive");
        }
        if (lowThreshold < 0 || highThreshold > 100
                || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }

        this.queue = new QueueEntry[queueCapacity];
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }
        synchronized (stateMonitor) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (queueSize == queue.length) {
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

            int tail = (queueHead + queueSize) % queue.length;
            queue[tail] = new QueueEntry(id, job);
            queueSize++;
            stateMonitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (stateMonitor) {
            for (int offset = 0; offset < queueSize; offset++) {
                int index = (queueHead + offset) % queue.length;
                if (queue[index].id == jobId) {
                    for (int shift = offset; shift < queueSize - 1; shift++) {
                        int current = (queueHead + shift) % queue.length;
                        int next = (queueHead + shift + 1) % queue.length;
                        queue[current] = queue[next];
                    }
                    int last = (queueHead + queueSize - 1) % queue.length;
                    queue[last] = null;
                    queueSize--;
                    stateMonitor.notifyAll();
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
        synchronized (stateMonitor) {
            if (load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= lowThreshold) {
                automaticMode = Mode.NORMAL;
            }
            refreshEffectiveMode();
            stateMonitor.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        synchronized (stateMonitor) {
            overrideMode = mode;
            refreshEffectiveMode();
            stateMonitor.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (stateMonitor) {
            overrideMode = null;
            refreshEffectiveMode();
            stateMonitor.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        final QueueEntry entry;
        synchronized (stateMonitor) {
            while (true) {
                int limit = admissionLimit();
                if (queueSize > 0 && runningCount < limit) {
                    entry = queue[queueHead];
                    queue[queueHead] = null;
                    queueHead = (queueHead + 1) % queue.length;
                    queueSize--;
                    runningCount++;
                    stateMonitor.notifyAll();
                    break;
                }
                if (closed && queueSize == 0 && runningCount == 0) {
                    return false;
                }
                stateMonitor.wait();
            }
        }

        try {
            entry.job.run();
        } finally {
            synchronized (stateMonitor) {
                runningCount--;
                stateMonitor.notifyAll();
            }
        }
        return true;
    }

    public void close() {
        synchronized (stateMonitor) {
            closed = true;
            stateMonitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            long[] pendingIds = new long[queueSize];
            for (int offset = 0; offset < queueSize; offset++) {
                pendingIds[offset] = queue[(queueHead + offset) % queue.length].id;
            }
            return new Snapshot(automaticMode, overrideMode, effectiveMode,
                    pendingIds, runningCount, admissionLimit(), closed);
        }
    }

    private void refreshEffectiveMode() {
        effectiveMode = overrideMode == null ? automaticMode : overrideMode;
    }

    private int admissionLimit() {
        return effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }
}
