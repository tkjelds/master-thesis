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

        private Snapshot(Mode automaticMode, Mode overrideMode, Mode effectiveMode,
                         long[] pendingJobIds, int runningCount,
                         int admissionLimit, boolean closed) {
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

    private final Object monitor = new Object();
    private final int queueCapacity;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final Job[] pendingJobs;
    private final long[] pendingIds;

    private int queueHead;
    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1L;
    private boolean idSpaceExhausted;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private boolean closed;

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
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount == queueCapacity) {
                throw new IllegalStateException("pending queue is full");
            }
            if (idSpaceExhausted) {
                throw new IllegalStateException("job ID space is exhausted");
            }

            long jobId = nextJobId;
            if (nextJobId == Long.MAX_VALUE) {
                idSpaceExhausted = true;
            } else {
                nextJobId++;
            }

            int tail = indexForOffset(pendingCount);
            pendingJobs[tail] = job;
            pendingIds[tail] = jobId;
            pendingCount++;
            monitor.notifyAll();
            return jobId;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            for (int offset = 0; offset < pendingCount; offset++) {
                int index = indexForOffset(offset);
                if (pendingIds[index] == jobId) {
                    for (int shift = offset; shift < pendingCount - 1; shift++) {
                        int destination = indexForOffset(shift);
                        int source = indexForOffset(shift + 1);
                        pendingJobs[destination] = pendingJobs[source];
                        pendingIds[destination] = pendingIds[source];
                    }
                    int oldTail = indexForOffset(pendingCount - 1);
                    pendingJobs[oldTail] = null;
                    pendingIds[oldTail] = 0L;
                    pendingCount--;
                    monitor.notifyAll();
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
        synchronized (monitor) {
            Mode previousMode = automaticMode;
            if (load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= lowThreshold) {
                automaticMode = Mode.NORMAL;
            }
            if (automaticMode != previousMode) {
                monitor.notifyAll();
            }
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        synchronized (monitor) {
            if (overrideMode != mode) {
                overrideMode = mode;
                monitor.notifyAll();
            }
        }
    }

    public void clearOverride() {
        synchronized (monitor) {
            if (overrideMode != null) {
                overrideMode = null;
                monitor.notifyAll();
            }
        }
    }

    public boolean runNext() throws InterruptedException {
        Job job;
        synchronized (monitor) {
            for (;;) {
                if (pendingCount > 0 && runningCount < currentAdmissionLimit()) {
                    job = pendingJobs[queueHead];
                    pendingJobs[queueHead] = null;
                    pendingIds[queueHead] = 0L;
                    queueHead++;
                    if (queueHead == queueCapacity) {
                        queueHead = 0;
                    }
                    pendingCount--;
                    runningCount++;
                    monitor.notifyAll();
                    break;
                }
                if (closed && pendingCount == 0 && runningCount == 0) {
                    return false;
                }
                monitor.wait();
            }
        }

        try {
            job.run();
        } finally {
            synchronized (monitor) {
                runningCount--;
                monitor.notifyAll();
            }
        }
        return true;
    }

    public void close() {
        synchronized (monitor) {
            if (!closed) {
                closed = true;
                monitor.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] ids = new long[pendingCount];
            for (int offset = 0; offset < pendingCount; offset++) {
                ids[offset] = pendingIds[indexForOffset(offset)];
            }
            Mode effectiveMode = currentMode();
            int limit = effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
            return new Snapshot(automaticMode, overrideMode, effectiveMode, ids,
                    runningCount, limit, closed);
        }
    }

    private Mode currentMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    private int currentAdmissionLimit() {
        return currentMode() == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }

    private int indexForOffset(int offset) {
        int slotsToEnd = queueCapacity - queueHead;
        return offset < slotsToEnd ? queueHead + offset : offset - slotsToEnd;
    }
}
