@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private static final int MAX_THRESHOLD = 100;

    public enum Mode { NORMAL, THROTTLED }

    public interface Job {
        void run();
    }

    private final int queueCapacity;
    private final int normalLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private volatile Mode mode = Mode.NORMAL;
    private volatile boolean closed = false;
    private volatile int runningCount = 0;

    private final Job[] pendingQueue;
    private int queueSize = 0;
    private long nextJobId = 1;

    public ThermostaticBuildOrchestrator(int queueCapacity, int concurrencyLimit,
                                        int lowThreshold, int highThreshold) {
        if (queueCapacity <= 0 || concurrencyLimit <= 0 ||
            lowThreshold < 0 || highThreshold > MAX_THRESHOLD ||
            lowThreshold >= highThreshold) {
            throw new IllegalArgumentException();
        }
        this.queueCapacity = queueCapacity;
        this.normalLimit = concurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.pendingQueue = new Job[queueCapacity];
    }

    public long submit(Job job) {
        if (job == null || closed || queueSize >= queueCapacity) {
            throw new IllegalStateException();
        }

        synchronized (this) {
            pendingQueue[queueSize] = job;
            queueSize++;
            return nextJobId++;
        }
    }

    public boolean cancel(long jobId) {
        if (jobId <= 0 || closed) return false;

        synchronized (this) {
            for (int i = 0; i < queueSize; i++) {
                if ((pendingQueue[i] != null) && (nextJobId - queueSize + i == jobId)) {
                    pendingQueue[i] = null;
                    queueSize--;
                    // Shift remaining jobs to fill the gap
                    for (int j = i; j < queueSize; j++) {
                        pendingQueue[j] = pendingQueue[j + 1];
                    }
                    notifyAll();
                    return true;
                }
            }
        }
        return false;
    }

    public void updateLoad(int load) {
        if (load < 0 || load > MAX_THRESHOLD) return;

        synchronized (this) {
            if (!closed && mode == Mode.NORMAL) {
                if (load >= highThreshold) {
                    mode = Mode.THROTTLED;
                } else if (load <= lowThreshold) {
                    mode = Mode.NORMAL;
                }
            }
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null || closed) return;

        synchronized (this) {
            this.mode = mode;
        }
    }

    public void clearOverride() {
        synchronized (this) {
            if (!closed && mode != Mode.NORMAL && mode != Mode.THROTTLED) {
                // Reset to automatic mode
            }
        }
    }

    public boolean runNext() throws RuntimeException {
        synchronized (this) {
            while (!closed && queueSize == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (closed && queueSize == 0) {
                return false;
            }

            Job job = null;
            int limit = (mode == Mode.THROTTLED) ? 1 : normalLimit;

            while (runningCount >= limit && !closed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (closed) {
                return false;
            }

            job = pendingQueue[0];
            for (int i = 1; {
                if (pendingQueue[i] != null) {
                    pendingQueue[0] = pendingQueue[i];
                    break;
                }
            }

            queueSize--;
            runningCount++;
        }

        try {
            job.run();
        } finally {
            synchronized (this) {
                runningCount--;
                notifyAll();
            }
        }

        return true;
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public Object snapshot() {
        synchronized (this) {
            Mode[] modes = { mode };
            long[] ids = new long[queueSize];
            for (int i = 0; i < queueSize; i++) {
                ids[i] = nextJobId - queueSize + i;
            }
            return new Object[] {
                modes[0], ids, runningCount,
                (mode == Mode.THROTTLED) ? 1 : normalLimit, closed
            };
        }
    }

    private void notifyAllRelevant() {
        synchronized (this) {
            notifyAll();
        }
    }

    private void checkClosed() {
        if (closed) throw new IllegalStateException();
    }
}
