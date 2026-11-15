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
        public final Mode mode;
        public final long[] pendingIds;
        public final int runningCount;
        public final int limit;
        public final boolean closed;

        public Snapshot(Mode automaticMode, Mode overrideMode, long[] pendingIds, int runningCount, int limit, boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.mode = overrideMode != null ? overrideMode : automaticMode;
            this.pendingIds = pendingIds == null ? new long[0] : pendingIds.clone();
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public Mode getAutomaticMode() {
            return automaticMode;
        }

        public Mode getOverrideMode() {
            return overrideMode;
        }

        public Mode getMode() {
            return mode;
        }

        public long[] getPendingIds() {
            return pendingIds.clone();
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

        public boolean isOverrideActive() {
            return overrideMode != null;
        }
    }

    private final Object monitor = new Object();
    private final int queueCapacity;
    private final int normalLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final long[] pendingIds;
    private final Job[] pendingJobs;

    private int pendingHead;
    private int pendingTail;
    private int pendingCount;
    private long nextJobId;
    private int runningCount;
    private int load;
    private Mode automaticMode;
    private Mode overrideMode;
    private boolean closed;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalLimit, int lowThreshold, int highThreshold) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive");
        }
        if (normalLimit <= 0) {
            throw new IllegalArgumentException("normal limit must be positive");
        }
        if (lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("invalid thresholds");
        }
        this.queueCapacity = queueCapacity;
        this.normalLimit = normalLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.pendingIds = new long[queueCapacity];
        this.pendingJobs = new Job[queueCapacity];
        this.automaticMode = Mode.NORMAL;
        this.nextJobId = 0L;
        this.load = 0;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount >= queueCapacity) {
                throw new IllegalStateException("pending queue is full");
            }
            long id = ++nextJobId;
            int index = pendingTail;
            pendingIds[index] = id;
            pendingJobs[index] = job;
            pendingTail = (pendingTail + 1) % queueCapacity;
            pendingCount++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            if (jobId <= 0) {
                return false;
            }
            if (pendingCount == 0) {
                return false;
            }

            int index = -1;
            for (int offset = 0; offset < pendingCount; offset++) {
                int slot = (pendingHead + offset) % queueCapacity;
                if (pendingIds[slot] == jobId) {
                    index = offset;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }

            for (int offset = index; offset < pendingCount - 1; offset++) {
                int src = (pendingHead + offset + 1) % queueCapacity;
                int dst = (pendingHead + offset) % queueCapacity;
                pendingIds[dst] = pendingIds[src];
                pendingJobs[dst] = pendingJobs[src];
            }

            int last = (pendingHead + pendingCount - 1) % queueCapacity;
            pendingIds[last] = 0L;
            pendingJobs[last] = null;
            pendingCount--;
            if (pendingCount == 0) {
                pendingHead = 0;
                pendingTail = 0;
            } else {
                pendingTail = (pendingHead + pendingCount) % queueCapacity;
            }
            monitor.notifyAll();
            return true;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("load must be between 0 and 100");
        }
        synchronized (monitor) {
            this.load = load;
            if (this.load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (this.load <= lowThreshold) {
                automaticMode = Mode.NORMAL;
            }
            monitor.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        synchronized (monitor) {
            overrideMode = mode;
            monitor.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (monitor) {
            overrideMode = null;
            monitor.notifyAll();
        }
    }

    public boolean runNext() {
        try {
            return runNextInterruptible();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean runNextInterruptible() throws InterruptedException {
        while (true) {
            Job nextJob = null;
            synchronized (monitor) {
                while (true) {
                    if (pendingCount > 0 && runningCount < effectiveLimit()) {
                        nextJob = pendingJobs[pendingHead];
                        pendingJobs[pendingHead] = null;
                        pendingIds[pendingHead] = 0L;
                        pendingHead = (pendingHead + 1) % queueCapacity;
                        pendingCount--;
                        if (pendingCount == 0) {
                            pendingHead = 0;
                            pendingTail = 0;
                        }
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

            boolean released = false;
            try {
                nextJob.run();
            } catch (RuntimeException e) {
                released = true;
                synchronized (monitor) {
                    runningCount--;
                    monitor.notifyAll();
                }
                throw e;
            } catch (Error e) {
                released = true;
                synchronized (monitor) {
                    runningCount--;
                    monitor.notifyAll();
                }
                throw e;
            } finally {
                if (!released) {
                    synchronized (monitor) {
                        runningCount--;
                        monitor.notifyAll();
                    }
                }
            }
            return true;
        }
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
    }

    public boolean isClosed() {
        synchronized (monitor) {
            return closed;
        }
    }

    public int getRunningCount() {
        synchronized (monitor) {
            return runningCount;
        }
    }

    public int getLimit() {
        synchronized (monitor) {
            return effectiveLimit();
        }
    }

    public Mode getAutomaticMode() {
        synchronized (monitor) {
            return automaticMode;
        }
    }

    public Mode getOverrideMode() {
        synchronized (monitor) {
            return overrideMode;
        }
    }

    public Mode getMode() {
        synchronized (monitor) {
            return overrideMode != null ? overrideMode : automaticMode;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] ids = new long[pendingCount];
            for (int offset = 0; offset < pendingCount; offset++) {
                int slot = (pendingHead + offset) % queueCapacity;
                ids[offset] = pendingIds[slot];
            }
            return new Snapshot(automaticMode, overrideMode, ids, runningCount, effectiveLimit(), closed);
        }
    }

    private int effectiveLimit() {
        return overrideMode != null ? (overrideMode == Mode.THROTTLED ? 1 : normalLimit)
                : (automaticMode == Mode.THROTTLED ? 1 : normalLimit);
    }
}
