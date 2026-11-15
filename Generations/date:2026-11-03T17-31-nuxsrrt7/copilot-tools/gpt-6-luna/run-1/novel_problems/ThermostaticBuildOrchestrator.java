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
            this.pendingJobIds = pendingJobIds.clone();
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public long[] getPendingJobIds() {
            return pendingJobIds.clone();
        }
    }

    private final Object stateMonitor = new Object();
    private final int queueCapacity;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final Job[] jobs;
    private final long[] jobIds;

    private int queueHead;
    private int queueTail;
    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1;
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
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("normalConcurrencyLimit must be positive");
        }
        if (lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
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

        synchronized (stateMonitor) {
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

            jobs[queueTail] = job;
            jobIds[queueTail] = id;
            queueTail = nextIndex(queueTail);
            pendingCount++;
            stateMonitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (stateMonitor) {
            int index = queueHead;
            int offset = 0;
            while (offset < pendingCount && jobIds[index] != jobId) {
                index = nextIndex(index);
                offset++;
            }
            if (offset == pendingCount) {
                return false;
            }

            int destination = index;
            for (int i = offset; i < pendingCount - 1; i++) {
                int source = nextIndex(destination);
                jobs[destination] = jobs[source];
                jobIds[destination] = jobIds[source];
                destination = source;
            }
            jobs[destination] = null;
            jobIds[destination] = 0;
            queueTail = previousIndex(queueTail);
            pendingCount--;
            stateMonitor.notifyAll();
            return true;
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
            stateMonitor.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }

        synchronized (stateMonitor) {
            overrideMode = mode;
            stateMonitor.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (stateMonitor) {
            if (overrideMode != null) {
                overrideMode = null;
                stateMonitor.notifyAll();
            }
        }
    }

    public boolean runNext() throws InterruptedException {
        final Job job;
        synchronized (stateMonitor) {
            while (true) {
                if (pendingCount > 0 && runningCount < currentAdmissionLimit()) {
                    job = jobs[queueHead];
                    jobs[queueHead] = null;
                    jobIds[queueHead] = 0;
                    queueHead = nextIndex(queueHead);
                    pendingCount--;
                    runningCount++;
                    break;
                }
                if (closed && pendingCount == 0) {
                    return false;
                }
                stateMonitor.wait();
            }
        }

        try {
            job.run();
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
            long[] pendingIds = new long[pendingCount];
            int index = queueHead;
            for (int i = 0; i < pendingCount; i++) {
                pendingIds[i] = jobIds[index];
                index = nextIndex(index);
            }

            Mode effectiveMode = overrideMode == null ? automaticMode : overrideMode;
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveMode,
                    pendingIds,
                    runningCount,
                    currentAdmissionLimit(),
                    closed);
        }
    }

    private int currentAdmissionLimit() {
        Mode effectiveMode = overrideMode == null ? automaticMode : overrideMode;
        return effectiveMode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }

    private int nextIndex(int index) {
        return index == queueCapacity - 1 ? 0 : index + 1;
    }

    private int previousIndex(int index) {
        return index == 0 ? queueCapacity - 1 : index - 1;
    }
}
