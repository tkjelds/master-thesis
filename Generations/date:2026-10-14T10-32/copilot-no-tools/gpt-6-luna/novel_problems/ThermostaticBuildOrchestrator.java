public class ThermostaticBuildOrchestrator {
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
     public final int limit;
     public final boolean closed;

     private Snapshot(
             Mode automaticMode,
             Mode overrideMode,
             Mode effectiveMode,
             long[] pendingJobIds,
             int runningCount,
             int limit,
             boolean closed) {
         this.automaticMode = automaticMode;
         this.overrideMode = overrideMode;
         this.effectiveMode = effectiveMode;
         this.pendingJobIds = pendingJobIds.clone();
         this.runningCount = runningCount;
         this.limit = limit;
         this.closed = closed;
     }

     public long[] pendingJobIds() {
         return pendingJobIds.clone();
     }
 }

 private final Object monitor = new Object();
 private final Job[] jobs;
 private final long[] jobIds;
 private final int normalConcurrencyLimit;
 private final int lowThreshold;
 private final int highThreshold;

 private int head;
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

     this.jobs = new Job[queueCapacity];
     this.jobIds = new long[queueCapacity];
     this.normalConcurrencyLimit = normalConcurrencyLimit;
     this.lowThreshold = lowThreshold;
     this.highThreshold = highThreshold;
 }

 public long submit(Job job) {
     if (job == null) {
         throw new NullPointerException("job");
     }

     synchronized (monitor) {
         if (closed) {
             throw new IllegalStateException("orchestrator is closed");
         }
         if (pendingCount == jobs.length) {
             throw new IllegalStateException("pending queue is full");
         }
         if (jobIdsExhausted) {
             throw new IllegalStateException("job ID space is exhausted");
         }

         long id = nextJobId;
         int tail = indexAt(pendingCount);
         jobs[tail] = job;
         jobIds[tail] = id;
         pendingCount++;

         if (nextJobId == Long.MAX_VALUE) {
             jobIdsExhausted = true;
         } else {
             nextJobId++;
         }

         monitor.notifyAll();
         return id;
     }
 }

 public boolean cancel(long jobId) {
     synchronized (monitor) {
         for (int i = 0; i < pendingCount; i++) {
             int index = indexAt(i);
             if (jobIds[index] == jobId) {
                 for (int j = i; j < pendingCount - 1; j++) {
                     int destination = indexAt(j);
                     int source = indexAt(j + 1);
                     jobs[destination] = jobs[source];
                     jobIds[destination] = jobIds[source];
                 }

                 int last = indexAt(pendingCount - 1);
                 jobs[last] = null;
                 jobIds[last] = 0;
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
         if (load >= highThreshold) {
             automaticMode = Mode.THROTTLED;
         } else if (load <= lowThreshold) {
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

 public boolean runNext() throws InterruptedException {
     Job job;

     synchronized (monitor) {
         while (true) {
             if (pendingCount > 0 && runningCount < admissionLimit()) {
                 int index = head;
                 job = jobs[index];
                 jobs[index] = null;
                 jobIds[index] = 0;
                 head = (head + 1) % jobs.length;
                 pendingCount--;
                 runningCount++;
                 break;
             }

             if (pendingCount == 0 && closed && runningCount == 0) {
                 return false;
             }

             monitor.wait();
         }
     }

     try {
         job.run();
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
         long[] pendingIds = new long[pendingCount];
         for (int i = 0; i < pendingCount; i++) {
             pendingIds[i] = jobIds[indexAt(i)];
         }

         Mode effectiveMode = overrideMode != null ? overrideMode : automaticMode;
         return new Snapshot(
                 automaticMode,
                 overrideMode,
                 effectiveMode,
                 pendingIds,
                 runningCount,
                 limitFor(effectiveMode),
                 closed);
     }
 }

 private int admissionLimit() {
     Mode effectiveMode = overrideMode != null ? overrideMode : automaticMode;
     return limitFor(effectiveMode);
 }

 private int limitFor(Mode mode) {
     return mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
 }

 private int indexAt(int offset) {
     return (int) (((long) head + offset) % jobs.length);
 }

}
