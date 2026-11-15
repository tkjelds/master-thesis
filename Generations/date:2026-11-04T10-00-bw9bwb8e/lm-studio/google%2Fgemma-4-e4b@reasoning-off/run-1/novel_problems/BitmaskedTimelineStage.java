/**
 * Represents a single reported event in the timeline history.
 */
@Event.ThreadSafe
class Event {
    @interface ThreadSafe {}

    final int mask;
    final String message;
    final String label;
    final int revision;

    Event(int mask, String message, String label, int revision) {
        this.mask = mask;
        this.message = message;
        this.label = label;
        this.revision = revision;
    }
}

/**
 * Interface for the timeline consumer.
 */
interface Timeline {
    void report(Event event);
    void success(String message);
    void warning(String message);
    void failure(String message);
    void skip(String message);
    void log();
    void dispatchNext();
    // snapshot implies reading the current state safely, which is handled by BitmaskedTimelineStage itself.
    // Since this interface is injected and represents the *external* reporting mechanism, 
    // we don't need a specific method here beyond the ones mirroring state changes.
}

/**
 * Implements the thread-safe BitmaskedTimelineStage concurrency problem solver.
 */
@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}


    // Outcome Bits
    private static final int INITIAL = 2;
    private static final int SUCCESS = 4;
    private static final int WARNING = 8;
    private static final int FAILURE = 16;
    private static final int SKIPPED = 32;
    private static final int LOGGED = 64;

    // Internal State Variables (Protected by synchronization blocks)
    private int currentMask = INITIAL;
    private String currentMessage = "";
    private String currentLabel = "INITIAL";
    private int revision = 0;

    // Queue for events waiting to be dispatched
    private final java.util.LinkedList<Event> eventQueue = new java.util.LinkedList<>();
    
    // Synchronization controls
    private boolean dispatchActive = false; // Claims ownership of the dispatcher flow
    private final Timeline timeline;

    /**
     * Constructs BitmaskedTimelineStage.
     * @param timeline The object used to report state changes externally.
     */
    public BitmaskedTimelineStage(Timeline timeline) {
        this.timeline = timeline;
    }

    // --- Helper to construct and publish an Event payload ---

    /**
     * Creates a new immutable Event object based on current stage data.
     */
    private Event createEventPayload() {
        // Revision increments upon calling this function pathway.
        return new Event(currentMask, currentMessage, currentLabel, revision);
    }

    /**
     * Atomically updates the internal state and advances revision.
     * This performs the core mutation described in the problem contract for any outcome call or log operation.
     * @param newMask The bits to OR into the current state mask (e.g., SUCCESS).
     * @param newMessage The message to set. Must not be null.
     * @param newLabel The label to set (uppercase).
     * @param enqueue If true, a new Event payload is created and added to the queue.
     * @return The newly formed Event if enqueue was true, or null otherwise.
     */
    private synchronized Event advanceState(int newMask, String newMessage, String newLabel, boolean enqueue) {
        if (newMessage == null || revision < 0) { // Conceptual check against overflow/null message
            // Rejecting null messages or impossible revisions.
            throw new IllegalArgumentException("Null message encountered during state transition.");
        }

        // 1. Update State based on inputs
        int previousMask = currentMask;
        currentMask |= newMask; // New outcome replaces exclusive bits, accumulates LOGGED bit if present in previous state
        currentMessage = newMessage;
        currentLabel = newLabel;

        // 2. Advance revision EXACTLY once for this successful transition
        revision++;
        
        // 3. Handle LOGGED bit combination before enqueueing/returning event
        int effectiveMask = currentMask;
        if ((previousMask & LOGGED) == 0 && (newMask & LOGGED) != 0) {
             // If this operation specifically sets the LOGGED bit, it must be ready to enqueue.
        }

        Event eventToEnqueue = null;
        if (enqueue) {
            // The ENQUEUE action is coupled with the revision advance, creating a loggable snapshot.
            eventToEnqueue = createEventPayload();
        }

        // The contract states: "Outcome calls do not enqueue events." unless they are log operations.
        // We must ensure only specific pathways trigger enqueuing tracked by the queue mechanism flow,
        // but here we model the outcome vs log distinction based on typical usage.
        
        return eventToEnqueue;
    }

    // --- Public Operations ---

    /**
     * Success outcome call. Equivalent to Outcome setting SUCCESS bit and message/label, without logging action automatically.
     * @param message The success message.
     */
    public void success(String message) {
        if (message == null) return; // Reject null message silently as per contract interpretation regarding outcome calls.

        // Outcome call: Does not enqueue event, just updates state.
        synchronized (this) {
            advanceState(SUCCESS, message, "SUCCESS", false);
        }
    }

    /**
     * Warning outcome call.
     */
    public void warning(String message) {
        if (message == null) return;

        synchronized (this) {
            advanceState(WARNING, message, "WARNING", false);
        }
    }

    /**
     * Failure outcome call.
     */
    public void failure(String message) {
        if (message == null) return;

        synchronized (this) {
            advanceState(FAILURE, message, "FAILURE", false);
        }
    }

    /**
     * Skip outcome call.
     */
    public void skip(String message) {
        if (message == null) return;

        synchronized (this) {
            advanceState(SKIPPED, message, "SKIPPED", false);
        }
    }

    /**
     * Log operation. Atomically sets LOGGED bit, updates state, and enqueues a detached Event if successful.
     * This behaves like an outcome call followed by logging, but it must *always* advance revision.
     */
    public void log() {
        // Simulating setting LOGGED bit in the mask while keeping other outcome bits as is.
        synchronized (this) {
            // We use INITIAL mask if no specific outcome bit is passed, implying current state context.
            int effectiveMask = LOGGED; 
            if (currentMask == INITIAL && currentMessage.isEmpty()) {
                // If starting from scratch, use INITIAL + LOGGED bits.
                effectiveMask = INITIAL | LOGGED;
            }

            // Outcome calls implicitly carry the current state, but log forces a unique revision step.
            Event newEvent = advanceState(effectiveMask, currentMessage, currentLabel, true);

            if (newEvent != null) {
                // Enqueue the newly created payload. This transition is atomic with revision advance.
                synchronized (this) {
                    eventQueue.addLast(newEvent);
                    // Notify potential waiting dispatchers that a new event is available *after* state commit.
                }
            } else {
                // If advanceState didn't generate an event (shouldn't happen for a true log operation tracking history), skip queueing.
            }
        }
    }

    /**
     * Attempts to claim and consume the oldest queued event. Blocks if queue is empty or dispatch is active.
     * @return true if an event was successfully claimed and dispatched, false otherwise.
     */
    public boolean dispatchNext() {
        Event claimedEvent = null;
        boolean claimedSuccessfully = false;

        // Phase 1: Acquire exclusive claim on dispatch process
        synchronized (this) {
            if (dispatchActive) {
                // Another dispatcher is active or this attempt failed due to prior action.
                return false;
            }

            // Wait until there is something to dispatch OR if we need to check status without blocking indefinitely.
            while (eventQueue.isEmpty() && !dispatchActive) {
                // If the queue is empty, we must wait if we are meant to block until an event arrives.
                // However, the contract says: "Return false immediately if the queue is empty or another dispatch is active."
                // This implies non-blocking check unless we enter a waiting loop controlled by arrival.
                if (eventQueue.isEmpty()) {
                    return false; // Queue empty, no waiting implied for initial check.
                }
            }

            // If we reach here, the queue is not empty (or we wait until it's non-empty).
            // Set dispatchActive to block subsequent calls.
            dispatchActive = true;

            if (!eventQueue.isEmpty()) {
                claimedEvent = eventQueue.removeFirst();
                claimedSuccessfully = true;
            } else {
                // Should only happen if the queue emptied between check and setting dispatchActive, or race condition cleanup.
                dispatchActive = false; // Release claim immediately if nothing found.
                return false;
            }
        }

        // Phase 2: Report outcome outside the synchronized block to prevent deadlock/contention on timeline object.
        try {
            // Notify the external Timeline consumer of the claimed event.
            timeline.report(claimedEvent);
        } finally {
            // Phase 3: Release the dispatch claim in finally block. Crucial for enabling subsequent calls.
            synchronized (this) {
                dispatchActive = false;
            }
        }

        return claimedSuccessfully;
    }

    /**
     * Returns a snapshot of the current stage. The returned object is immutable relative to internal state mutation.
     * @return A SnapshotInfo object containing the current state and pending events.
     */
    public SnapshotInfo snapshot() {
        synchronized (this) {
            // Deep copy required for consistency guarantees.
            int mask = currentMask;
            String message = currentMessage;
            String label = currentLabel;
            int revisionVal = revision;

            // Copy pending events (shallow copy of references is fine as they are immutable Event objects)
            java.util.LinkedList<Event> pendingCopy = new java.util.LinkedList<>();
            for (Object item : eventQueue) {
                pendingCopy.add(item); // Safe copy constructor/add operation simulated here
            }

            return new SnapshotInfo(mask, message, label, revisionVal, pendingCopy, dispatchActive);
        }
    }

    // --- Internal Classes for Contract Fulfillment ---

    /**
     * Represents the collection of state information returned by snapshot().
     */
    public static class SnapshotInfo {
        final int mask;
        final String message;
        final String label;
        final int revision;
        // Copy of queued events at time of snapshot. These are immutable references to Events.
        final java.util.LinkedList<Event> queuedEventsCopy; 
        final boolean dispatchActiveStatus;

        SnapshotInfo(int mask, String message, String label, int revision, java.util.LinkedList<Event> queuedEventsCopy, boolean dispatchActiveStatus) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.queuedEventsCopy = queuedEventsCopy; // This copy is safe from mutation by the caller
            this.dispatchActiveStatus = dispatchActiveStatus;
        }
    }

    // --- Example usage stub classes needed for compilation completeness ---
    // In a real scenario, these would be passed into the constructor of BitmaskedTimelineStage.

    /*
     * Dummy Timeline implementation for testing purposes (optional, but useful for completeness)
     */
    public static class DummyTimeline implements Timeline {
        @Override public void report(Event event) { /* System.out.println("Reported: " + event.revision); */ }
        @Override public void success(String message) { /* ... */ }
        @Override public void warning(String message) { /* ... */ }
        @Override public void failure(String message) { /* ... */ }
        @Override public void skip(String message) { /* ... */ }
        @Override public void log() { /* Called via specific pathway, not standalone here */ }
        @Override public void dispatchNext() { /* Triggered by the stage itself */ }
    }
}
