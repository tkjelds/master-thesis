@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int queueCapacity;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final Job[] queue;
    private volatile Mode mode;
    private volatile int runningCount;
    private volatile int loadLimit;
    private volatile boolean closed;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalConcurrencyLimit, int lowThreshold, int highThreshold) {
        if (queueCapacity <= 0 || normalConcurrencyLimit <= 0 || lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        this.queueCapacity = queueCapacity;
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.queue = new Job[queueCapacity];
        this.mode = Mode.NORMAL;
        this.runningCount = 0;
        this.loadLimit = normalConcurrencyLimit;
        this.closed = false;
    }

    public long submit(Job job) throws IllegalStateException {
        synchronized (this) {
            if (closed || queue.length == 0) {
                throw new IllegalStateException("Cannot submit job");
            }
            long jobId = System.currentTimeMillis(); // Simplified unique ID generation
            while (queue.length == queueCapacity) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Submission interrupted");
                }
            }
            int index = 0;
            while (queue[index] != null) {
                index++;
            }
            queue[index] = job;
            notifyAll();
            return jobId;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (this) {
            for (int i = 0; i < queue.length; i++) {
                if (queue[i] != null && System.currentTimeMillis() == jobId) {
                    queue[i] = null;
                    notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        synchronized (this) {
            mode = determineMode(load);
            loadLimit = (mode == Mode.NORMAL) ? normalConcurrencyLimit : 1;
        }
    }

    private Mode determineMode(int load) {
        if (load >= highThreshold) {
            return Mode.THROTTLED;
        } else if (load <= lowThreshold) {
            return Mode.NORMAL;
        }
        return mode;
    }

    public void overrideMode(Mode mode) {
        synchronized (this) {
            this.mode = mode;
            loadLimit = (mode == Mode.NORMAL) ? normalConcurrencyLimit : 1;
        }
    }

    public void clearOverride() {
        synchronized (this) {
            this.mode = determineMode(load);
            loadLimit = (mode == Mode.NORMAL) ? normalConcurrencyLimit : 1;
        }
    }

    public boolean runNext() {
        while (true) {
            synchronized (this) {
                if (closed && runningCount == 0) {
                    return false;
                }
                while (runningCount >= loadLimit || queue.length == 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                int index = 0;
                while (queue[index] == null) {
                    index++;
                }
                Job job = queue[index];
                queue[index] = null;
                runningCount++;
                notifyAll();
                try {
                    job.run();
                } catch (RuntimeException e) {
                    // Handle exception
                } finally {
                    runningCount--;
                    notifyAll();
                }
                return true;
            }
        }
    }

    public void close() {
        synchronized (this) {
            this.closed = true;
            notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(mode, System.currentTimeMillis(), runningCount, loadLimit, closed);
        }
    }

    public static class Snapshot {
        private final Mode mode;
        private final long snapshotTime;
        private final int runningCount;
        private final int loadLimit;
        private final boolean closed;

        public Snapshot(Mode mode, long snapshotTime, int runningCount, int loadLimit, boolean closed) {
            this.mode = mode;
            this.snapshotTime = snapshotTime;
            this.runningCount = runningCount;
            this.loadLimit = loadLimit;
            this.closed = closed;
        }

        // Getters for snapshot fields
    }

    public interface Job {
        void run();
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }
}
