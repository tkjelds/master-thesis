@PersistedScheduleContext.ThreadSafe
class PersistedScheduleContext {
    @interface ThreadSafe {}


    // --- Constants and Internal Structures ---

    /** Represents a time interval [start, end) */
    private static class Interval {
        final long start;
        final long end;

        Interval(long start, long end) {
            this.start = start;
            this.end = end;
        }

        // Simple equality check based on bounds (assuming unique ID context implies structure)
        boolean equals(Interval other) {
            return this.start == other.start && this.end == other.end;
        }
    }

    /** Represents a unique reservation */
    private static class Reservation {
        final long id;
        final Interval interval;

        Reservation(long id, Interval interval) {
            this.id = id;
            this.interval = interval;
        }
    }

    /** Snapshot taken at a specific moment */
    private static class ScheduleSnapshot {
        final long committedRevision;
        final long durableRevision;
        final boolean isFlushing;
        final Reservation[] currentReservations; // Copy of the active reservations
        final Throwable failureCause; // If any terminal error occurred

        ScheduleSnapshot(long cr, long dr, boolean flushing, Reservation[] res, Throwable cause) {
            this.committedRevision = cr;
            this.durableRevision = dr;
            this.isFlushing = flushing;
            // Deep copy of reservations array is crucial for immutability isolation
            this.currentReservations = res == null ? null : new Reservation[res.length];
            if (res != null) {
                System.arraycopy(res, 0, this.currentReservations, 0, res.length);
            }
            this.failureCause = cause;
        }
    }

    /** The interface contract for the persistence sink */
    interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot); // Note: The problem implies this might be called internally by the system, but here we implement the required contract.

        // The required operational methods exposed to PersistedScheduleContext
        void reserve(long start, long end); // For simplicity in this implementation, we map the required contract usage to these methods.
        void cancel(long reservationId); // This should ideally use the ID returned from reserve
        void flush();
        ScheduleSnapshot snapshot(); // Returns a coherent view of the current state.
    }

    // --- PersistedScheduleContext Members ---

    private final ScheduleSink sink; // The injected dependency implementation
    private long nextReservationId = 1;

    // State management variables - must be protected by synchronization
    private long committedRevision = 0;
    private long durableRevision = 0;

    // In-memory state representing the current, pending commits
    private Reservation[] inMemoryReservations;
    private int reservationCount = 0;

    // Flusher mechanism state
    private volatile boolean isFlushingInProgress = false;
    private Thread flusherWaiterThread = null; // Used to manage the single flusher role lifecycle

    // Synchronization primitives
    private final Object stateLock = new Object(); // Protects all critical shared state changes

    // Failure tracking
    private Throwable terminalFailureCause = null; // Retained cause if persistence fails fatally

    /**
     * Constructor initializes the context with the dependency.
     */
    PersistedScheduleContext(ScheduleSink sink) {
        this.sink = sink;
        // Initial state: committed/durable revision 0, empty. Done implicitly by initialization.
    }

    // --- Public API Implementation ---

    /**
     * Reserves a half-open interval [start, end). Returns the unique positive ID on success, -1 on conflict.
     * This operation commits in memory but does not immediately call the sink.
     */
    public long reserve(long start, long end) {
        if (start >= end) return -1;

        // Check for overflow/invalid inputs before acquiring lock, if possible.
        // Since we don't have explicit bounds checking for long overflows leading to bad ranges,
        // we assume standard long range is sufficient unless the problem implies limits.

        synchronized (stateLock) {
            // 1. Check for terminal failure state (read path validation)
            if (terminalFailureCause != null) {
                throw new IllegalStateException("Cannot mutate state after terminal failure occurred.");
            }

            // 2. Conflict check (in-memory validation)
            if (checkConflict(start, end)) {
                return -1; // Conflict detected
            }

            // 3. Successful commit prep: Assign ID and add to in-memory structure
            long newId = nextReservationId++;
            Interval proposedInterval = new Interval(start, end);
            Reservation newRes = new Reservation(newId, proposedInterval);

            // Create a new array to hold the current state + the addition
            Reservation[] nextReservations = new Reservation[reservationCount + 1];
            System.arraycopy(inMemoryReservations, 0, nextReservations, 0, reservationCount);
            nextReservations[reservationCount] = newRes;

            // Advance in-memory state (successful commit)
            inMemoryReservations = nextReservations;
            reservationCount++;

            // Return the ID and signal that a successful in-memory commit has occurred.
            return newId;
        }
    }

    /**
     * Cancels a reservation identified by its unique ID. Returns true if it existed and was cancelled, false otherwise.
     */
    public boolean cancel(long reservationId) {
        synchronized (stateLock) {
            if (terminalFailureCause != null) {
                // Cannot perform mutation/check against a failed state cleanly, though the requirement implies throw on subsequent attempt.
                throw new IllegalStateException("Cannot mutate state after terminal failure occurred.");
            }

            // Check if ID exists in the current set
            for (int i = 0; i < reservationCount; i++) {
                if (inMemoryReservations[i].id == reservationId) {
                    // Found it. Now, remove it from the array representation (shifting elements)
                    Reservation[] nextReservations = new Reservation[reservationCount - 1];
                    int j = 0;
                    for (int i = 0; i < reservationCount; i++) {
                        if (i != indexOfReservationById(reservationId)) {
                            nextReservations[j++] = inMemoryReservations[i];
                        }
                    }
                    inMemoryReservations = nextReservations;
                    reservationCount--;
                    return true;
                }
            }
            return false; // ID not found
        }
    }

    /**
     * Signals that all pending in-memory commits should be persisted to the sink.
     * If flush is already running, this operation waits for it or becomes the flusher if needed.
     * The caller may claim the single flusher role.
     */
    public void flush() {
        // This implementation simulates the single flusher role management using wait/notify.

        synchronized (stateLock) {
            if (terminalFailureCause != null) {
                // If terminal failure occurred, flush must propagate that failure immediately if target was reached.
                handleTerminalFailureFlush();
                return;
            }

            // If flush is already in progress, the current caller must wait until it finishes.
            while (isFlushingInProgress) {
                // Wait for the flusher to finish its cycle or signal completion/failure.
                try {
                    wait();
                } catch (InterruptedException e) {
                    // If interrupted while waiting for flusher to clear, we must continue trying the sequence.
                    Thread.currentThread().interrupt();
                }
            }

            // CLAIM THE FLUSHER ROLE: We are now responsible for driving persistence.
            if (!isFlushingInProgress) {
                try {
                    // 1. Capture Target Revision (Committed revision at entry)
                    long targetCommittedRevision = committedRevision;

                    // 2. Signal start of flush operation
                    isFlushingInProgress = true;
                    notifyAll(); // Wake up any threads waiting on stateLock (including potential previous flusher attempts)

                    // 3. Perform persistence operation outside the tight lock, but maintain ownership of the flusher claim
                    performPersistenceCycle(targetCommittedRevision);

                } finally {
                    // 4. Release flusher role and notify waiters upon completion/failure of persistence cycle
                    synchronized (stateLock) {
                        isFlushingInProgress = false;
                        notifyAll(); // Wake up any threads waiting on the stateLock after persistence finishes/fails.
                    }
                }
            } else {
                // This branch should ideally not be hit if the while loop check passed, but handles race conditions defensively.
                // If it passes the initial `while (!isFlushingInProgress)` check but fails here, something is inconsistent.
            }
        }
    }

    /**
     * Retrieves a coherent, non-mutating view of the current schedule state.
     */
    public ScheduleSnapshot snapshot() {
        synchronized (stateLock) {
            if (terminalFailureCause != null) {
                // If there is a failure, all subsequent snapshots report it.
                return new ScheduleSnapshot(
                        committedRevision, 0, false, null, terminalFailureCause);
            }

            // Copy current in-memory state safely
            Reservation[] reservationsCopy = new Reservation[reservationCount];
            if (inMemoryReservations != null) {
                System.arraycopy(inMemoryReservations, 0, reservationsCopy, 0, reservationCount);
            }

            return new ScheduleSnapshot(
                    committedRevision, durableRevision, isFlushingInProgress, reservationsCopy, null);
        }
    }

    // --- Internal Helpers (Simulation of Sink Interactions) ---

    /** Simulates the successful delivery and state transition upon sink confirmation. */
    private void completePersistence(long targetCommittedRevision) {
        synchronized (stateLock) {
            // Sink confirmed durability >= target. Advance durable revision.
            if (durableRevision < targetCommittedRevision) {
                durableRevision = targetCommittedRevision;
            }

            // Wake up any threads waiting for durability confirmation on their flush/snapshot() calls
            notifyAll(); 
        }
    }

    /** Handles the terminal state caused by a Sink RuntimeException. */
    private void handleTerminalFailure(Throwable cause) {
        synchronized (stateLock) {
            if (terminalFailureCause == null) {
                terminalFailureCause = cause; // Retain the first failure cause
            }
            // Wake all threads waiting on stateLock, forcing them to see the failure cause upon entry.
            notifyAll(); 
        }
    }

    // --- Detailed Internal Synchronization Logic (Simplified structure for length constraint) ---

    /**
     * This method simulates the lifecycle of the flusher role trying to persist.
     * In a real system, this would likely run in a dedicated background thread managed by the Flusher Claim.
     */
    private void performPersistenceCycle(long targetCommittedRevision) {
        // In a true implementation, this would involve running the sink logic.
        try {
            // 1. Create snapshot representing current in-memory state (matching targetCommittedRevision)
            Reservation[] currentRes = new Reservation[reservationCount];
            if (inMemoryReservations != null) {
                System.arraycopy(inMemoryReservations, 0, currentRes, 0, reservationCount);
            }
            // Must assume Sink implementation uses this snapshot correctly.
            ScheduleSnapshot attempt = new ScheduleSnapshot(
                    targetCommittedRevision, durableRevision, true, currentRes, null);

            // 2. Call sink.persist(attempt). This call blocks until the sink processes it.
            // For simulation: we assume Sink implementation handles serialization queueing based on revision ordering.
            sink.persist(attempt); // This simulates the blocking call to the Sink layer.

            // 3. If sink completes successfully (i.e., doesn't throw):
            completePersistence(targetCommittedRevision);

        } catch (Throwable t) {
            // Sink reported an error. This is terminal if it's a severe runtime issue for the sink itself.
            // The problem states Sink RuntimeException is terminal for persistence, clearing flusher claim.
            if (t instanceof RuntimeException) {
                handleTerminalFailure(t); // Propagate failure state across the entire system.
            } else {
                // Other errors might just mean the flush timed out or was unexpected.
            }
        }
    }

    // --- Utility Methods (Crucial for correctness but simplified) ---

    private boolean checkConflict(long start, long end) {
        // Checks if [start, end) overlaps with any existing reservation.
        for (int i = 0; i < reservationCount; i++) {
            Reservation existing = inMemoryReservations[i];
            // Check for overlap: (StartA < EndB) && (EndA > StartB)
            if (start < existing.interval.end && end > existing.interval.start) {
                return true; // Conflict found
            }
        }
        return false;
    }

    private int indexOfReservationById(long id) {
        for (int i = 0; i < reservationCount; i++) {
            if (inMemoryReservations[i].id == id) return i;
        }
        return -1;
    }

    private void handleTerminalFailureFlush() {
        // If failure occurred, flush must throw or immediately report the blockage.
        if (durableRevision < committedRevision) { // Simplification: assuming failure happens before revision advances
            throw new IllegalStateException("Persistence system failed and cannot flush.");
        }
    }

    // Note: The provided interface implementation `ScheduleSink` is missing its internal mapping
    // for reserve/cancel/flush/snapshot calls back into this class structure. For the contract to be fully satisfied,
    // `PersistedScheduleContext` *is* the implementation detail backing these methods. The structure above assumes
    // that when an external entity calls `context.reserve()`, this internal logic runs, and when they call
    `context.flush()`, it drives the persistence cycle described.

}
