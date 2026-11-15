```java
public class ThermostaticBuildOrchestrator {
    private static final int INITIAL_JOB_ID = 1;
    private final Job[] pendingQueue;
    private final Object monitor = new Object();
    private int pendingSize;
    private volatile int runningCount;
    private int limit;
    private Mode currentMode;
    private int lowThreshold;
    private int highThreshold;
    private boolean closed;

    public ThermostaticBuildOrchestrator(int queueCapacity, int concurrencyLimit, int lowThreshold, int highThreshold) {
        if (queueCapacity <= 0 || concurrencyLimit <= 0 || lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.pendingQueue = new Job[queueCapacity];
        this.limit = concurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.currentMode = Mode.NORMAL;
    }

    public long submit(Job job) {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Orchestrator is closed");
            }
            if (pendingSize == pendingQueue.length) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (job == null) {
                throw new IllegalArgumentException("Job cannot be null");
            }
            pendingQueue[pendingSize++] = job;
            monitor.notifyAll();
            return INITIAL_JOB_ID + (pendingSize - 1);
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            int index = (int) (jobId - INITIAL_JOB_ID);
            if (index < 0 || index >= pendingSize) {
                return false;
            }
            pendingQueue[index] = null;
            if (index == pendingSize - 1) {
                --pendingSize;
            } else {
                System.arraycopy(pendingQueue, index + 1, pendingQueue, index, --pendingSize - index);
            }
            monitor.notifyAll();
            return true;
        }
    }

    public void updateLoad(int load) {
        synchronized (monitor) {
            if (load < 0 || load > 100) {
                throw new IllegalArgumentException("Invalid load value");
            }
            currentMode = load >= highThreshold ? Mode.THROTTLED : (load <= lowThreshold ? Mode.NORMAL : currentMode);
            monitor.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        synchronized (monitor) {
            currentMode = mode;
            monitor.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (monitor) {
            currentMode = Mode.NORMAL;
            monitor.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        synchronized (monitor) {
            while (pendingSize == 0 || currentMode == Mode.THROTTLED && runningCount >= limit) {
                if (closed && pendingSize == 0) {
                    return false;
                }
                monitor.wait();
            }
            Job job = pendingQueue[0];
            System.arraycopy(pendingQueue, 1, pendingQueue, 0, --pendingSize);
            try {
                job.run();
            } catch (RuntimeException e) {
                throw e;
            } finally {
                --runningCount;
                monitor.notifyAll();
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

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(currentMode, pendingQueue, runningCount, limit, closed);
        }
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }

    public static class Snapshot {
        private final Mode mode;
        private final Job[] pendingJobs;
        private final int runningCount;
        private final int limit;
        private final boolean closed;

        public Snapshot(Mode mode, Job[] pendingJobs, int runningCount, int limit, boolean closed) {
            this.mode = mode;
            this.pendingJobs = pendingJobs;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        // Getters for snapshot fields
    }

    public interface Job {
        void run();
    }
}
```
