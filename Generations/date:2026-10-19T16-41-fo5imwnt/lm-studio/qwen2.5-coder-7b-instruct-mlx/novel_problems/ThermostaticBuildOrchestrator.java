@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int capacity;
    private final int normalLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private volatile Mode mode = Mode.NORMAL;
    private int runningCount;
    private Job[] jobs;
    private int head;
    private int tail;
    private boolean closed;

    public ThermostaticBuildOrchestrator(int capacity, int normalLimit, int lowThreshold, int highThreshold) {
        if (capacity <= 0 || normalLimit <= 0 || lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.capacity = capacity;
        this.normalLimit = normalLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.jobs = new Job[capacity];
        this.head = 0;
        this.tail = 0;
    }

    public long submit(Job job) throws IllegalStateException {
        if (job == null || closed) {
            throw new IllegalStateException();
        }
        synchronized (this) {
            if (tail - head >= capacity) {
                throw new IllegalStateException();
            }
            jobs[tail++] = job;
        }
        notifyAll();
        return tail - 1; // unique positive ID
    }

    public boolean cancel(long jobId) {
        synchronized (this) {
            return cancel0(jobId);
        }
    }

    private boolean cancel0(long jobId) {
        for (int i = head; i < tail; i++) {
            if (jobs[i] instanceof CancellableJob && ((CancellableJob) jobs[i]).getJobId() == jobId) {
                jobs[i] = null;
                return true;
            }
        }
        return false;
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("Invalid load");
        }
        synchronized (this) {
            mode = load >= highThreshold ? Mode.THROTTLED : load <= lowThreshold ? Mode.NORMAL : mode;
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Invalid mode");
        }
        synchronized (this) {
            this.mode = mode;
        }
    }

    public void clearOverride() {
        synchronized (this) {
            this.mode = lowThreshold < highThreshold ? mode : Mode.NORMAL;
        }
    }

    public boolean runNext() throws InterruptedException {
        synchronized (this) {
            while (head == tail && !closed) {
                wait();
            }
            if (closed && head == tail) {
                return false;
            }
            int jobId = jobs[head++].run() ? (int) head : head;
            if (--runningCount < normalLimit) {
                notifyAll();
            }
            return true;
        }
    }

    public void close() throws InterruptedException {
        synchronized (this) {
            closed = true;
            notifyAll();
            while (head != tail) {
                wait();
            }
        }
    }

    public String snapshot() {
        synchronized (this) {
            StringBuilder sb = new StringBuilder();
            sb.append("Mode: ").append(mode).append("\n");
            sb.append("Pending IDs: ");
            for (int i = head; i < tail; i++) {
                if (jobs[i] != null) {
                    sb.append(jobs[i].getJobId()).append(" ");
                }
            }
            sb.append("\nRunning Count: ").append(runningCount).append("\n");
            sb.append("Limit: ").append(normalLimit).append("\n");
            sb.append("Closed: ").append(closed).append("\n");
            return sb.toString();
        }
    }

    public interface Job {
        boolean run();
        long getJobId();
    }

    @FunctionalInterface
    public interface CancellableJob extends Job {
        long getJobId();
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }
}
