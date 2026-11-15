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

    public record Snapshot(
            Mode automaticMode,
            Mode overrideMode,
            Mode effectiveMode,
            long[] pendingIds,
            int runningCount,
            int limit,
            boolean closed) {

        public Snapshot {
            pendingIds = pendingIds.clone();
        }

        @Override
        public long[] pendingIds() {
            return pendingIds.clone();
        }
    }

    private static final class Monitor {
    }

    private static final class Node {
        final long id;
        final Job job;
        Node next;

        Node(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Monitor monitor = new Monitor();
    private final int queueCapacity;
    private final int normalConcurrencyLimit;
    private final int low;
    private final int high;

    private Node head;
    private Node tail;
    private int pendingCount;
    private int runningCount;
    private long lastId;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;

    public ThermostaticBuildOrchestrator(
            int queueCapacity,
            int normalConcurrencyLimit,
            int low,
            int high) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException(
                    "Normal concurrency limit must be positive");
        }
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException(
                    "Thresholds must satisfy 0 <= low < high <= 100");
        }
        this.queueCapacity = queueCapacity;
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.low = low;
        this.high = high;
    }

    // Submission never waits for queue space.
    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Orchestrator is closed");
            }
            if (pendingCount == queueCapacity) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (lastId == 9223372036854775807L) {
                throw new IllegalStateException("Job IDs are exhausted");
            }

            long id = lastId + 1;
            Node node = new Node(id, job);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            lastId = id;
            pendingCount++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            Node previous = null;
            Node current = head;

            while (current != null) {
                if (current.id == jobId) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    if (tail == current) {
                        tail = previous;
                    }
                    current.next = null;
                    pendingCount--;
                    monitor.notifyAll();
                    return true;
                }
                previous = current;
                current = current.next;
            }

            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("Load must be between 0 and 100");
        }

        synchronized (monitor) {
            if (load >= high) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= low) {
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

    // False means closed with neither pending nor running jobs.
    public boolean runNext() throws InterruptedException {
        Node claimed;

        synchronized (monitor) {
            while (head == null || runningCount >= admissionLimitLocked()) {
                if (closed && head == null && runningCount == 0) {
                    return false;
                }
                monitor.wait();
            }

            claimed = head;
            head = claimed.next;
            claimed.next = null;
            if (head == null) {
                tail = null;
            }
            pendingCount--;
            runningCount++;
            monitor.notifyAll();
        }

        try {
            claimed.job.run();
            return true;
        } finally {
            synchronized (monitor) {
                runningCount--;
                monitor.notifyAll();
            }
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
            long[] ids = new long[pendingCount];
            Node current = head;
            int index = 0;
            while (current != null) {
                ids[index++] = current.id;
                current = current.next;
            }

            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveModeLocked(),
                    ids,
                    runningCount,
                    admissionLimitLocked(),
                    closed);
        }
    }

    private Mode effectiveModeLocked() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    private int admissionLimitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED
                ? 1
                : normalConcurrencyLimit;
    }
}
