public interface Job {
    void run();
}

public enum Mode {
    NORMAL, THROTTLED
}

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int queueCapacity;
    private final int normalLimit;
    private final AtomicInteger runningCount;
    private final AtomicInteger currentLimit;
    private volatile Mode mode;
    private final LinkedList<Job> pendingQueue;
    private volatile boolean closed;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0 || normalLimit <= 0 || low < 0 || high > 100 || low >= high) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.queueCapacity = queueCapacity;
        this.normalLimit = normalLimit;
        this.runningCount = new AtomicInteger(0);
        this.currentLimit = new AtomicInteger(normalLimit);
        this.mode = Mode.NORMAL;
        this.pendingQueue = new LinkedList<>();
        this.closed = false;
    }

    public long submit(Job job) throws IllegalStateException {
        if (closed || pendingQueue.size() >= queueCapacity) {
            throw new IllegalStateException("Cannot submit job");
        }
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null");
        }
        long jobId = System.currentTimeMillis(); // Simulate unique positive ID
        pendingQueue.add(job);
        synchronized (this) {
            notifyAll();
        }
        return jobId;
    }

    public boolean cancel(long jobId) {
        synchronized (pendingQueue) {
            return pendingQueue.removeIf(job -> System.currentTimeMillis() == jobId);
        }
    }

    public void updateLoad(int load) {
        synchronized (this) {
            if (load >= 100) {
                mode = Mode.THROTTLED;
                currentLimit.set(1);
            } else if (load <= 0) {
                mode = Mode.NORMAL;
                currentLimit.set(normalLimit);
            } else if (mode != Mode.NORMAL) {
                mode = Mode.NORMAL;
                currentLimit.set(normalLimit);
            }
        }
    }

    public void overrideMode(Mode mode) {
        synchronized (this) {
            this.mode = mode;
            if (mode == Mode.THROTTLED) {
                currentLimit.set(1);
            } else if (mode == Mode.NORMAL) {
                currentLimit.set(normalLimit);
            }
        }
    }

    public void clearOverride() {
        synchronized (this) {
            if (mode != Mode.NORMAL) {
                mode = Mode.NORMAL;
                currentLimit.set(normalLimit);
            }
        }
    }

    public boolean runNext() throws InterruptedException {
        Job job;
        synchronized (pendingQueue) {
            while (true) {
                if (closed && pendingQueue.isEmpty()) {
                    return false;
                }
                while (runningCount.get() >= currentLimit.get()) {
                    pendingQueue.wait();
                }
                job = pendingQueue.pollFirst();
                if (job != null) {
                    runningCount.incrementAndGet();
                    break;
                }
            }
        }

        try {
            job.run();
        } catch (RuntimeException e) {
            synchronized (this) {
                runningCount.decrementAndGet();
                notifyAll();
                throw e;
            }
        }

        synchronized (this) {
            runningCount.decrementAndGet();
            notifyAll();
            return true;
        }
    }

    public void close() {
        synchronized (this) {
            this.closed = true;
            pendingQueue.clear();
            runningCount.set(0);
            notifyAll();
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (this) {
            return Collections.unmodifiableMap(new HashMap<String, Object>() {{
                put("mode", mode);
                put("pendingIds", pendingQueue.stream().map(job -> System.currentTimeMillis()).collect(Collectors.toList()));
                put("runningCount", runningCount.get());
                put("limit", currentLimit.get());
                put("closed", closed);
            }});
        }
    }
}
