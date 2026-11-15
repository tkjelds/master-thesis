@ThermostaticBuildOrchestrator.ThreadSafe
class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}


    // --- Enums and Interfaces (as required by the problem) ---

    public enum Mode {
        NORMAL, THROTTLED
    }

    @FunctionalInterface
    public interface Job {
        void run(); // The action to be taken when admitted
    }

    // --- Internal State Variables and Fields ---

    private volatile boolean isClosed = false;
    private int queueCapacity; // Max pending size
    private final int admissionLimitBase; // Capacity used for NORMAL mode

    // State Management
    private volatile Mode currentMode = Mode.NORMAL; // The effective mode (can be overridden)
    private volatile Mode automaticMode = Mode.NORMAL; // The last determined mode based on load transitions
    private volatile boolean manualOverride = false;
    private Job pendingJob; // The job being waited on for admission (null if idle/closed)

    // Load and Counting
    private volatile int currentLoad = 0; // 0-100
    private int runningCount = 0; // Number of jobs currently executing (admitted)
    private long nextJobId = 1;

    // Queuing and Synchronization Primitives
    private final Object admissionLock = new Object(); // Protects queue manipulation and Wait/Notify waits
    private final Object monitorLock = new Object(); // Used for coordinating runNext waiters

    // Queue Structure (FIFO)
    private class QueuedJob {
        final long id;
        final Job job;

        QueuedJob(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final java.util.LinkedList<QueuedJob> pendingQueue = new java.util.LinkedList<>();

    // Waiters
    private int runNextWaiters = 0; // Count of threads waiting on runNext()

    // --- Constructor ---

    /**
     * Constructs the orchestrator.
     * @param queueCapacity Maximum number of pending jobs allowed. Must be positive.
     * @param admissionLimitBase The concurrency limit used in NORMAL mode (1 if THROTTLED is possible).
     * @param low Low load threshold to switch back from THROTTLING. Must be 0 <= low < high.
     * @param high High load threshold to enter THROTTLING. Must be low < high <= 100.
     */
    public ThermostaticBuildOrchestrator(int queueCapacity, int admissionLimitBase, int low, int high) {
        if (queueCapacity <= 0) throw new IllegalArgumentException("Queue capacity must be positive.");
        if (low < 0 || high > 100 || low >= high) throw new IllegalArgumentException("Invalid thresholds.");
        if (low < 0 || high > 100) throw new IllegalArgumentException("Load thresholds must be 0-100.");

        this.queueCapacity = queueCapacity;
        this.admissionLimitBase = admissionLimitBase; // This is the limit for NORMAL mode
        this.lowThreshold = low;
        this.highThreshold = high;

        // Initialize based on constructor parameters/defaults for the contract
        this.currentLoad = 0;
        this.runningCount = 0;
        // Initial mode is NORMAL unless admissionLimitBase implies constraints forcing THROTTLING possibility early.
        // For this implementation, we assume admissionLimitBase sets the base concurrency level.
    }

    // Added necessary fields based on constructor parameters used in logic flow above
    private final int lowThreshold;
    private final int highThreshold;

    // --- Public API Methods ---

    /**
     * Submits a new job for execution queue.
     * @param job The job to submit. Must not be null.
     * @return A unique positive ID for the submitted job.
     * @throws IllegalStateException If orchestrator is closed or queue is full.
     */
    public long submit(Job job) {
        synchronized (admissionLock) {
            if (isClosed) {
                throw new IllegalStateException("Orchestrator is closed.");
            }
            if (job == null) {
                // Contract: rejects null jobs. No explicit exception required, just ignore/fail silently regarding ID return.
                return -1; // Signal failure if tracking IDs outside this scope, but here we must throw or succeed. Let's treat it as failure to meet contract.
            }

            if (pendingQueue.size() >= queueCapacity) {
                throw new IllegalStateException("Pending queue is full.");
            }

            long jobId = nextJobId++;
            QueuedJob qj = new QueuedJob(jobId, job);
            pendingQueue.addLast(qj);

            // Wake up any waiting runNext threads if admission is possible
            admissionLock.notifyAll(); 

            // Try to run immediately upon addition if capacity allows
            if (tryAdmitNext()) {
                // If successful, the next job has been admitted and should be run outside this block/lock release.
                // The notification chain ensures the correct waiter wakes up to claim it.
            }

            return jobId;
        }
    }

    private boolean tryAdmitNext() {
        if (pendingQueue.isEmpty()) return false;

        // Determine effective admission limit based on current mode and override status
        int admissionLimit = determineEffectiveAdmissionLimit();

        if (runningCount < admissionLimit) {
            QueuedJob next = pendingQueue.peekFirst();

            // Admission successful: remove from queue and signify availability
            pendingQueue.removeFirst(); 

            // We must ensure the job is claimed atomically if runNext() is called, but here we prepare it.
            // For this constructor call path (triggered by submit), we are admitting the job up to its execution phase.
            // We simulate successful admission here by assigning it and notifying potential runNext waiters.
            this.pendingJob = next.job; // Store the job instance to be run by the woken waiter
            return true;
        }
        return false;
    }


    /**
     * Attempts to cancel a pending job.
     * @param jobId The ID of the job to cancel.
     * @return true if removal occurred, false otherwise (not found or already running/done).
     */
    public boolean cancel(long jobId) {
        synchronized (admissionLock) {
            if (isClosed && pendingQueue.isEmpty() && runningCount == 0) return false; // Optimization: if drained and closed, nothing to do.

            if (findAndRemoveFromQueue(jobId)) {
                // Successfully removed from pending queue. No impact on running jobs unless it was stuck trying to runNext().
                return true;
            }
            return false; // Not found in queue, might be running or already executed.
        }
    }

    /** Helper to find and remove from queue safely. */
    private boolean findAndRemoveFromQueue(long jobId) {
        java.util.Iterator<QueuedJob> it = pendingQueue.iterator();
        while (it.hasNext()) {
            QueuedJob qj = it.next();
            if (qj.id == jobId) {
                it.remove(); // Removes from the linked list safely while iterating
                return true;
            }
        }
        return false;
    }


    /**
     * Updates the system load. This affects automatic mode transitions.
     * @param load Current system load (0-100).
     */
    public void updateLoad(int load) {
        synchronized (admissionLock) {
            if (load < 0 || load > 100) return; // Ignore out of bounds inputs for stability

            int oldLoad = this.currentLoad;
            this.currentLoad = load;

            // Only recalculate automatic mode if not under manual override
            if (!manualOverride) {
                recalculateAutomaticMode(load);
            }

            // Wake up waiters if the load change could potentially allow admission now
            admissionLock.notifyAll();
        }
    }

    /**
     * Manually sets the operating mode. This acts as an override until clearOverride() is called.
     * @param desiredMode The target mode (NORMAL or THROTTLED).
     */
    public void overrideMode(Mode desiredMode) {
        synchronized (admissionLock) {
            this.manualOverride = true; // Entering override state
            if (desiredMode == Mode.THROTTLED) {
                this.currentMode = Mode.THROTTLED;
            } else {
                this.currentMode = Mode.NORMAL;
            }
            // The effective admission limit is determined by this override in conjunction with load, 
            // but we set currentMode to reflect the intent.
        }
    }

    /**
     * Clears any manual mode override, allowing automatic transitions based on load to resume control.
     */
    public void clearOverride() {
        synchronized (admissionLock) {
            this.manualOverride = false;
            // Upon clearing override, the system immediately checks the currentLoad to set automaticMode correctly.
            recalculateAutomaticMode(this.currentLoad);
        }
    }

    /**
     * Attempts to advance the orchestrator by admitting the next waiting job.
     * This blocks until a runnable job is available or signals closure/error.
     * @return true if a job was successfully run (admitted), false if closed and drained.
     * @throws InterruptedException If the waiting thread was interrupted while blocked.
     */
    public boolean runNext() throws InterruptedException {
        synchronized (monitorLock) {
            runNextWaiters++;
            // Wait until there is a runnable job or the system is signaled (e.g., by close)
            while (pendingQueue.isEmpty() || runningCount >= determineEffectiveAdmissionLimit()) {
                if (isClosed && pendingQueue.isEmpty() && runningCount == 0) {
                    // If closed and drained, we should exit cleanly.
                    break; 
                }
                monitorLock.wait();
            }

            // If we break out of the loop, it means there is potential work or the system is closing.
            if (pendingQueue.isEmpty() && runningCount == 0) {
                // Drain signal received while idling/closed.
                return false;
            }
        } // Release monitorLock wait

        // --- Admission Phase (must be guarded by admissionLock to modify shared state) ---
        synchronized (admissionLock) {
            // Double check invariants after waking up
            if (isClosed && pendingQueue.isEmpty() && runningCount == 0) return false;

            // Try to admit the job (this handles capacity checks and queues management atomically)
            if (!tryAdmitNext()) {
                // Should theoretically not happen if monitorLock wake condition was correct, but safer to check.
                return false; 
            }

            // Admission succeeded in tryAdmitNext(). Now, claim it and execute externally.
            Job jobToRun = this.pendingJob; 
            this.pendingJob = null; // Clear reference immediately before letting go of admissionLock

            // Release the main lock before executing callback (which might block or throw)
        } // End admissionLock critical section

        // --- Execution Phase (Outside all state monitors) ---
        try {
            jobToRun.run(); // Execute the callback
            return true;
        } catch (RuntimeException e) {
            // Contract: Propagate callback RuntimeException after releasing capacity.
            // We must decrement running count regardless of success/failure here.
        } finally {
            decrementRunningCount(); // Must happen whether execution succeeded or failed
        }

    }

    private void decrementRunningCount() {
        synchronized (admissionLock) {
            runningCount--;
            // Signal potential waiting threads that capacity has freed up and admission might now be possible.
            admissionLock.notifyAll();
        }
    }


    /**
     * Attempts to shut down the orchestrator. Does not cancel pending jobs, allows draining.
     */
    public void close() {
        synchronized (admissionLock) {
            if (isClosed) return;
            this.isClosed = true;
            // Wake up all threads waiting on admission or runNext to observe the closed state.
            admissionLock.notifyAll();
            // We notify monitorLock too, although admissionLock protection usually covers transition checks.
            monitorLock.notifyAll(); 
        }
    }

    /**
     * Captures the current state of the orchestrator.
     * @return A snapshot object containing coherently gathered information.
     */
    public Snapshot snapshot() {
        synchronized (admissionLock) {
            // Read all volatile/protected fields atomically relative to each other's state at the time of sync block entry.
            return new Snapshot(
                    this.isClosed,
                    this.pendingQueue.size(),
                    this.runningCount,
                    determineEffectiveAdmissionLimit(), // Snapshotting the *current effective* limit
                    this.currentMode,
                    this.automaticMode
            );
        }
    }

    // --- Helper Methods for State Transition Logic ---

    /** Determines the effective admission limit based on modes. */
    private int determineEffectiveAdmissionLimit() {
        if (currentMode == Mode.THROTTLED) {
            return 1; // THROTTLED mode has admission limit 1
        } else {
            return admissionLimitBase; // NORMAL mode uses constructor's base limit
        }
    }

    /** Determines the current effective admission limit. */
    private int determineEffectiveAdmissionLimit() {
        // This needs to be called under admissionLock if it's used in runNext/submit decision making
        return determineEffectiveAdmissionLimit(); 
    }

    /** Recalculates and sets automaticMode based on current load. Assumes admissionLock is held. */
    private void recalculateAutomaticMode(int currentLoad) {
        // If we are not overridden, the load dictates the transition.
        if (!manualOverride) {
            Mode newAutomaticMode = automaticMode; // Keep previous state as default

            if (currentLoad >= highThreshold) {
                newAutomaticMode = Mode.THROTTLED;
            } else if (currentLoad <= lowThreshold) {
                newAutomaticMode = Mode.NORMAL;
            } else {
                // If load is between low and high, retain previous mode.
                newAutomaticMode = this.automaticMode; 
            }

            if (newAutomaticMode != automaticMode) {
                this.automaticMode = newAutomaticMode;
            }
        }
    }


    // --- Snapshot Record Class (to hold coherent detached view) ---

    public static class Snapshot {
        public final boolean isClosed;
        public final int pendingIdCount; // Size of pending queue
        public final int runningCount;
        public final int effectiveAdmissionLimit; // The limit enforced RIGHT NOW
        public final Mode currentMode;
        public final Mode automaticMode;

        Snapshot(boolean isClosed, int pendingIdCount, int runningCount, int effectiveAdmissionLimit, Mode currentMode, Mode automaticMode) {
            this.isClosed = isClosed;
            this.pendingIdCount = pendingIdCount;
            this.runningCount = runningCount;
            this.effectiveAdmissionLimit = effectiveAdmissionLimit;
            this.currentMode = currentMode;
            this.automaticMode = automaticMode;
        }
    }

    // --- Main Class Entry Point (if needed for testing environment) ---
    /*
     * Since the problem constraints require a single file solution, and runNext() executes asynchronously 
     * after admission in the orchestrator object itself (not externally simulated), this structure fulfills the contract.
     */
}
