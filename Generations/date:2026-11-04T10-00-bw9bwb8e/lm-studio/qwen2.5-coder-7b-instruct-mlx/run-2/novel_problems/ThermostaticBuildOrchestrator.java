import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

interface Job {
    void run();
    enum Mode { NORMAL, THROTTLED }
}

@ThermostaticBuildOrchestrator.ThreadSafe
class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int capacity;
    private final int limit;
    private final AtomicInteger runningCount = new AtomicInteger(0);
    private final AtomicInteger overrideLimit = new AtomicInteger(0);
    private volatile Job.Mode mode = Job.Mode.NORMAL;
    private final Object lock = new Object();
    private final AtomicLong nextJobId = new AtomicLong(1);
    private final AtomicReference<Thread> drainingThread = new AtomicReference<>(null);

    public ThermostaticBuildOrchestrator(int capacity, int limit, int low, int high) {
        if (capacity <= 0 || limit <= 0 || low < 0 || high > 100 || low >= high) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.capacity = capacity;
        this.limit = limit;
        overrideLimit.set(limit);
    }

    public long submit(Job job) throws IllegalStateException {
        if (job == null) {
            throw new NullPointerException("Job cannot be null");
        }
        synchronized (lock) {
            if (isClosed()) {
                throw new IllegalStateException("Orchestrator is closed");
            }
            if (pendingJobs.size() >= capacity) {
                throw new IllegalStateException("Pending queue is full");
            }
            pendingJobs.add(job);
            notifyNextJob();
            return nextJobId.getAndIncrement();
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (Job job : pendingJobs) {
                if (jobId == 0 || jobId != getJobId(job)) {
                    continue;
                }
                pendingJobs.remove(job);
                notifyNextJob();
                return true;
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        synchronized (lock) {
            if (load < 0 || load > 100) {
                throw new IllegalArgumentException("Load must be between 0 and 100");
            }
            if (load >= getHighThreshold()) {
                mode = Job.Mode.THROTTLED;
                overrideLimit.set(1);
            } else if (load <= getLowThreshold()) {
                mode = Job.Mode.NORMAL;
                overrideLimit.set(limit);
            } else {
                // Keep the current mode
            }
        }
    }

    public void overrideMode(Job.Mode mode) {
        synchronized (lock) {
            this.mode = mode;
            if (mode == Job.Mode.THROTTLED) {
                overrideLimit.set(1);
            } else {
                overrideLimit.set(limit);
            }
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            overrideLimit.set(limit);
        }
    }

    public boolean runNext() {
        Job job;
        synchronized (lock) {
            while (!isClosed() && pendingJobs.isEmpty()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (isClosed()) {
                while (!pendingJobs.isEmpty() && runningCount.get() > 0) {
                    cancel(pendingJobs.remove(0).getJobId());
                }
                return false;
            }
            job = pendingJobs.remove(0);
        }

        Runnable runner = new Runnable() {
            @Override
            public void run() {
                try {
                    job.run();
                } catch (RuntimeException e) {
                    e.printStackTrace();
                } finally {
                    synchronized (lock) {
                        runningCount.decrementAndGet();
                        notifyNextJob();
                    }
                }
            }
        };

        Thread thread = new Thread(runner);
        try {
            thread.start();
        } catch (OutOfMemoryError e) {
            synchronized (lock) {
                runningCount.decrementAndGet();
                notifyNextJob();
            }
            return false;
        }

        synchronized (lock) {
            runningCount.incrementAndGet();
        }

        return true;
    }

    public void close() {
        synchronized (lock) {
            if (isClosed()) {
                return;
            }
            isClosed.set(true);
        }

        while (!pendingJobs.isEmpty() && runningCount.get() > 0) {
            cancel(pendingJobs.remove(0).getJobId());
        }

        notifyAll();
    }

    public String snapshot() {
        synchronized (lock) {
            return "Mode: " + mode + ", Pending IDs: " + pendingJobs.stream().mapToLong(Job::getJobId).toArray() +
                   ", Running Count: " + runningCount.get() + ", Limit: " + overrideLimit.get() +
                   ", Closed: " + isClosed();
        }
    }

    private boolean isClosed() {
        return drainingThread.get() != null;
    }

    private void notifyNextJob() {
        synchronized (lock) {
            if (!pendingJobs.isEmpty()) {
                lock.notify();
            }
        }
    }

    private long getJobId(Job job) {
        // Assuming Job class has a method to get the ID
        return job.getJobId();
    }

    private int getLowThreshold() {
        // Assuming low threshold is a constant
        return 20;
    }

    private int getHighThreshold() {
        // Assuming high threshold is a constant
        return 80;
    }

    private static final class PendingJob {
        private final Job job;

        public PendingJob(Job job) {
            this.job = job;
        }

        public Job getJob() {
            return job;
        }
    }
}
