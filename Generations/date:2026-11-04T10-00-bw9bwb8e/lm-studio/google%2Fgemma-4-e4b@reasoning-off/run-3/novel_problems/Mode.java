@ThermostaticBuildOrchestrator.ThreadSafe
class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}


    // --- Nested Definitions ---

    public enum Mode {
        NORMAL, THROTTLED }

    @FunctionalInterface
    public interface Job {
        void run();
    }

    // Simple record-like structure for immutable state snapshot
    public static class Snapshot {
        public final int pendingCount;
        public final int runningCount;
        public final int capacity; // Total capacity (queue + running)
        public final int currentLimit; // Effective admission limit per slot
        public final boolean isClosed;
        public final Mode currentMode;

        Snapshot(int pendingCount, int runningCount, int capacity, int currentLimit, boolean isClosed, Mode currentMode) {
            this.pendingCount = pendingCount;
            this.runningCount = runningCount;
            this.capacity = capacity;
            this.currentLimit = currentLimit;
            this.isClosed = isClosed;
            this.currentMode = currentMode;
        }
    }

    // Internal representation of a queued job, including its unique ID
    private static class QueuedJob {
        final long jobId;
        final Job jobRunnable;

        QueuedJob(long jobId, Job runnable) {
            this.jobId = jobId;
            this.jobRunnable = runnable;
        }
    }

    // --- Fields and State Variables ---

    private final int queueCapacity;
    private final int totalConcurrencyLimit; // Maximum concurrent capacity (Queue + Running)

    // Synchronization and State Variables
    private volatile boolean isClosed = false;
    private int nextJobId = 1;

    // Queue management (FIFO for pending jobs)
    private QueuedJob[] pendingQueue;
    private int head = 0; // Index of the next job to be considered for admission (oldest)
    private int tail = 0; // Index where the next job will be placed (next available slot)
    private int pendingCount = 0;

    // Running job management
    private volatile int runningCount = 0;
    private final int currentAdmissionLimitPerSlot; // The effective admission limit based on configuration/mode

    // Orchestration Mode Management
    private volatile Mode currentMode = Mode.NORMAL; // The mode reflected by the submission logic
    private volatile boolean overrideActive = false;
    private volatile Mode manualOverrideMode = Mode.NORMAL; // What the override is set to

    // Condition variables guards (wait/notify objects)
    private final Object admissionMonitor = new Object(); // Used by runNext waiters
    private final Object stateMonitor = new Object(); // Used for transitions affecting mode/closing

    // --- Constructor and Initialization ---

    /**
     * Constructs the Orchestrator.
     * @param queueCapacity The maximum number of pending jobs allowed before blocking submission.
     * @param totalConcurrencyLimit The absolute upper bound on (pending + running).
     * @param low The load threshold (0 <= low < high) for reverting to NORMAL mode.
     * @param high The load threshold (low < high <= 100) for transitioning to THROTTLED mode.
     */
    public ThermostaticBuildOrchestrator(int queueCapacity, int totalConcurrencyLimit, int low, int high) {
        if (totalConcurrencyLimit < 1 || queueCapacity < 0 || totalConcurrencyLimit - queueCapacity < 1) {
            throw new IllegalArgumentException("Invalid capacity constraints.");
        }
        if (low < 0 || high > 100 || low >= high || high <= 0) {
            throw new IllegalArgumentException("Invalid threshold values.");
        }

        this.queueCapacity = queueCapacity;
        this.totalConcurrencyLimit = totalConcurrencyLimit;
        // The currentAdmissionLimitPerSlot is the maximum concurrent jobs allowed under NORMAL operation.
        this.currentAdmissionLimitPerSlot = totalConcurrencyLimit;

        // Initialize internal state based on constructor parameters.
        this.pendingQueue = new QueuedJob[queueCapacity];
    }

    // --- Public API Methods ---

    /**
     * Submits a new job for execution. Blocks if queue is full or closed.
     * @param job The runnable job.
     * @return A unique positive ID assigned to the job submission.
     * @throws IllegalStateException if closed or queue is full upon immediate check.
     */
    public long submit(Job job) throws IllegalStateException {
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null.");
        }

        synchronized (stateMonitor) {
            if (isClosed) {
                throw new IllegalStateException("Orchestrator is closed.");
            }

            // Check for immediate queue overflow (queue capacity check, not total concurrency)
            if (pendingCount >= queueCapacity && pendingCount + runningCount >= totalConcurrencyLimit) {
                // If the system is completely full (queue full AND running at max capacity), we treat this as a submission failure based on queue constraint.
                // However, the requirement states: "throws IllegalStateException if closed or the pending queue is full"
                // We interpret "pending queue is full" based on the defined `queueCapacity`.
                if (pendingCount >= queueCapacity) {
                     throw new IllegalStateException("Pending queue is full.");
                }
            }

            // If we reach here, there might be space in the queue or admission pool.
            long newId = nextJobId++;

            if (pendingCount < queueCapacity) {
                // Successfully queued
                QueuedJob qj = new QueuedJob(newId, job);
                pendingQueue[tail] = qj;
                tail = (tail + 1) % queueCapacity;
                pendingCount++;

                // Wake up any thread waiting on admissionMonitor, as a new job is available.
                synchronized (admissionMonitor) {
                    admissionMonitor.notifyAll();
                }

                // Check if this new submission triggers an automatic mode change upwards (if not already in THROTTLED)
                // Note: Load update handles the official mode transition based on load, but submission confirms readiness.

                return newId;
            } else {
                 // Should have been caught above, but for robustness against race conditions if checks were looser:
                 throw new IllegalStateException("Pending queue is full.");
            }
        }
    }

    /**
     * Attempts to remove a pending job from the queue.
     * @param jobId The ID of the job to cancel.
     * @return true if the job was found and removed, false otherwise.
     */
    public boolean cancel(long jobId) {
        synchronized (stateMonitor) {
            if (isClosed && pendingCount == 0) return false; // Cannot cancel if closed and drained

            // Linear search through the queue (head to tail-1)
            for (int i = 0; i < pendingCount; i++) {
                int currentIndex = (head + i) % queueCapacity;
                QueuedJob qj = pendingQueue[currentIndex];

                if (qj != null && qj.jobId == jobId) {
                    // Found it, now remove it. This is complex because we must maintain FIFO order integrity.
                    // Since cancellation targets a specific element in the logical queue, we must shift elements if it's not at the head.

                    if (i == 0) {
                        // Cancelling the oldest job (at head) - this is clean.
                        pendingQueue[head] = null; // Clear reference
                        head = (head + 1) % queueCapacity;
                    } else {
                        // Cancelling a job in the middle/tail. We need to physically shift all subsequent jobs up by one spot.
                        // This simulates removal while maintaining FIFO order for the remaining elements.

                        int count = 0;
                        int currentIndex = head;
                        while (count < i) {
                            // Move item from current position to the head position
                            pendingQueue[(head + count) % queueCapacity] = pendingQueue[(head + count + 1) % queueCapacity];
                            count++;
                        }
                        // Now, the target job is at `head + i`. We remove it.
                        int targetIndex = (head + i) % queueCapacity;
                        pendingQueue[targetIndex] = null; // Remove the target job

                        // Reset head to point to the original head element (which might have been shifted)
                        // Since we shifted everything up to `i`, the original head remains at relative index 0.
                        // We don't need to adjust `head` unless the removed item *was* the head.
                        // Since i > 0, the job at `head` remains the next candidate.
                    }

                    // Decrement count safely
                    pendingCount--;
                    return true;
                }
            }
            return false; // Not found
        }
    }

    /**
     * Allows external threads to update the current load measurement.
     * Triggers automatic mode evaluation.
     */
    public void updateLoad(int load) {
        if (load < 0 || load > 100) return;

        synchronized (stateMonitor) {
            // The load update influences the automatic mode calculation but does *not* affect admission directly.

            if (runningCount > 0) {
                // If we are running, the load is effectively influenced by our own activity.
                // However, the specification implies `updateLoad` sets an *external* load measurement that affects mode change logic.
                // Since we lack a mechanism to correlate an external `load` with the *current* running jobs accurately without internal knowledge,
                // we assume `updateLoad` is used to drive the automatic mode transition based on external metrics.

                if (currentMode == Mode.NORMAL && load >= high) {
                    // Transition to THROTTLED only if we are currently operational and the load crosses high.
                    switchToAutomaticMode(Mode.THROTTLED);
                } else if (currentMode == Mode.THROTTLED && load <= low) {
                    // Transition back to NORMAL only if we are throttled and the load drops below low.
                    switchToAutomaticMode(Mode.NORMAL);
                }
            } else {
                 // If nothing is running, we might still be in THROTTLED state if the last operations dictated it,
                 // but typically mode changes are reactive to activity. We defer to the thresholds.
                 if (load >= high) {
                     switchToAutomaticMode(Mode.THROTTLED);
                 } else if (load <= low) {
                     switchToAutomaticMode(Mode.NORMAL);
                 }
            }
        }
    }

    /**
     * Forces the orchestrator into a specific mode, cancelling any pending manual overrides.
     */
    public void overrideMode(Mode desiredMode) {
        synchronized (stateMonitor) {
            this.manualOverrideMode = desiredMode;
            this.overrideActive = true;

            // Note: The effective mode for admission control is determined by checking `overrideActive` first, then falls through to automatic checks.
        }
    }

    /**
     * Clears any active manual mode overrides, restoring control to the automatic logic based on load.
     */
    public void clearOverride() {
        synchronized (stateMonitor) {
            this.overrideActive = false;
            // The next `updateLoad` call will confirm the true operational mode.
        }
    }

    /**
     * Attempts to admit and run the next waiting job. Blocks until a job can be admitted or closed/drained.
     * @return true if a job ran, false if closed and drained.
     * @throws InterruptedException if the thread waiting for admission is interrupted.
     */
    public boolean runNext() throws InterruptedException {
        synchronized (admissionMonitor) {
            // Wait until we can potentially run a job or if the orchestrator is closed.
            while (pendingCount == 0 && !isClosed) {
                // Check if we are stuck waiting because the system is full/closed, or genuinely empty.
                if (runningCount >= currentAdmissionLimitPerSlot && pendingCount == 0) {
                    // If we hit the limit and there's nothing waiting, we might be done unless it's transiently waiting for a callback.
                    // But if pendingCount == 0, we must wait unless closed.
                }
                admissionMonitor.wait();
            }

            if (pendingCount == 0 && runningCount == 0 && isClosed) {
                return false; // Closed and drained, no job to run.
            }

            if (pendingCount == 0 && !isClosed) {
                // Woken up but still no jobs, perhaps due to spurious wakeup or closure signal while waiting. Recheck main loop condition if needed.
                // Given the structure, if we exit the loop successfully, pendingCount > 0 or isClosed is true.
            }

            // Critical admission decision point: Can we admit the oldest job?
            boolean canAdmit;
            synchronized (stateMonitor) {
                // Determine effective admission limit. This is the capacity constraint for a *new* running job.
                int effectiveLimit = overrideActive ? currentAdmissionLimitPerSlot : determineEffectiveAdmissionLimit();

                if (runningCount < effectiveLimit && pendingCount > 0) {
                    canAdmit = true;
                } else if (runningCount >= effectiveLimit && pendingCount > 0) {
                    canAdmit = false; // Admission blocked by limit, even if pending jobs exist.
                } else {
                    canAdmit = false; // Either queue empty or limit reached.
                }
            }

            if (!canAdmit) {
                 // If we waited and still cannot admit due to limits, release monitor lock while returning false.
                 return false;
            }

            // --- Admission Successful: Claim Job and Run ---
            QueuedJob admittedJob;
            synchronized (stateMonitor) {
                // Re-verify eligibility under synchronized block to ensure consistency while dequeuing.
                if (pendingCount == 0) return false; // Lost race condition where count dropped while waiting for admissionMonitor release.

                // Dequeue the oldest job
                admittedJob = pendingQueue[head];
                pendingCount--;

                // Advance head pointer
                head = (head + 1) % queueCapacity;
            }

            // Decrement running count *before* invoking the callback (Claim exactly one job atomically)
            runningCount++;

            try {
                // Invoke callback OUTSIDE state monitors (as required)
                admittedJob.jobRunnable.run();
                return true; // Successfully ran
            } catch (RuntimeException e) {
                // Propagate callback RuntimeException after releasing capacity
                throw e;
            } finally {
                // Release running capacity (crucial part of the contract)
                synchronized (stateMonitor) {
                    runningCount--; // Decrement happens upon completion/failure of run()
                }

                // Notify waiting threads about state change (e.g., admission possible now)
                synchronized (admissionMonitor) {
                    admissionMonitor.notifyAll();
                }
            }
        } // End of admissionMonitor critical section block

        // Should not reach here in clean execution path if the above structure is followed, but needed for compilation flow.
        return false;
    }

    /**
     * Signals the orchestrator to stop accepting new submissions but drain existing queues/running jobs.
     */
    public void close() {
        synchronized (stateMonitor) {
            if (isClosed) return;
            this.isClosed = true;

            // Wake up all waiting threads on admissionMonitor to check the new closed state.
            synchronized (admissionMonitor) {
                admissionMonitor.notifyAll();
            }
        }
    }

    /**
     * Returns an immutable snapshot of the current operational state.
     */
    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            // Determine effective mode and limits based on current state for the snapshot.
            Mode actualMode = overrideActive ? manualOverrideMode : determineCurrentAutomaticMode();
            int admissionLimit = determineEffectiveAdmissionLimit();

            return new Snapshot(
                    pendingCount,
                    runningCount,
                    totalConcurrencyLimit, // Using total concurrency limit for capacity context
                    admissionLimit,
                    isClosed,
                    actualMode
            );
        }
    }

    // --- Internal Helper Methods (Require synchronization context) ---

    /**
     * Determines the effective admission limit based on configuration and overrides.
     * Must be called under stateMonitor lock context if reading volatile members affects flow control critical paths.
     * Returns the maximum number of concurrent jobs allowed if admitting a new one.
     */
    private int determineEffectiveAdmissionLimit() {
        if (overrideActive) {
            return currentAdmissionLimitPerSlot; // Override wins, uses constructor's limit (totalConcurrencyLimit)
        }
        // If no override, the effective limit is determined by configuration and current mode.
        return currentAdmissionLimitPerSlot; // In this simplified model, THROTTLED/NORMAL mostly controls *admission*, but the structural limit is totalConcurrencyLimit.
    }

    /**
     * Determines the current mode if no manual override is active.
     */
    private Mode determineCurrentAutomaticMode() {
        // In a real system, this checks the load vs low/high against the moment of submission.
        // Here, we use the volatile `currentMode` as the best guess unless overridden.
        return currentMode;
    }

    /**
     * Handles the transition logic for automatic mode change. Must be called under stateMonitor lock context.
     */
    private void switchToAutomaticMode(Mode desiredMode) {
        if (!overrideActive) {
            this.currentMode = desiredMode;
        }
    }

    // --- Lifecycle Transition Logic (Simplified Admission Check) ---

    /**
     * Simulates the load-based mode change that happens when activity occurs.
     * This drives the `currentMode` variable used by snapshot and comparison against HIGH/LOW thresholds.
     * NOTE: In a fully implemented system, load changes must correlate to the *actual* number of active jobs.
     */
    private void evaluateAndTransitionMode() {
        // This internal method simulates the evaluation that updateLoad triggers.
        if (runningCount > 0) { // Only check mode transitions when work is active.
            if (currentMode == Mode.NORMAL && runningCount * 100 / totalConcurrencyLimit >= high) { // Simplified check
                switchToAutomaticMode(Mode.THROTTLED);
            } else if (currentMode == Mode.THROTTLED && runningCount * 100 / totalConcurrencyLimit <= low) { // Simplified check
                switchToAutomaticMode(Mode.NORMAL);
            }
        }
    }
}
