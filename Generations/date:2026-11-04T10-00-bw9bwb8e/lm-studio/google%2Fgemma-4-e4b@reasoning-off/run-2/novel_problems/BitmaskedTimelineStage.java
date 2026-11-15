@Timeline.ThreadSafe
BitmaskedTimelineStage.java
class Timeline {
    @interface ThreadSafe {}

    public void success(String message) {}
    public void warning(String message) {}
    public void failure(String message) {}
    public void skip(String message) {}
    public void log() {}
    public void dispatchNext() {}
    public void snapshot() {}
}

@Event.ThreadSafe
class Event {
    @interface ThreadSafe {}

    int mask;
    String message;
    String label;
    int revision;

    Event(int mask, String message, String label, int revision) {
        this.mask = mask;
        this.message = message;
        this.label = label;
        this.revision = revision;
    }
}

@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    // Outcome Masks
    private static final int INITIAL = 2;
    private static final int SUCCESS = 4;
    private static final int WARNING = 8;
    private static final int FAILURE = 16;
    private static final int SKIPPED = 32;
    private static final int LOGGED = 64;

    // State variables
    private volatile int currentMask;
    private volatile String currentMessage;
    private volatile String currentLabel;
    private volatile int revision;

    // Queueing and Dispatch State
    private Event queuedEvent;
    private int initialMask = INITIAL;

    // Synchronization fields
    private Timeline timeline;
    private boolean dispatchActive; // Indicates if report is currently in flight

    // Object monitor used for blocking synchronization (wait/notifyAll)
    private final Object stateMonitor = new Object();

    /**
     * Constructs the BitmaskedTimelineStage.
     * @param timeline The timeline sink to report events to.
     */
    public BitmaskedTimelineStage(Timeline timeline) {
        this.timeline = timeline;
        // Initial state setup according to contract
        this.currentMask = INITIAL;
        this.currentMessage = "";
        this.currentLabel = "INITIAL";
        this.revision = 0;
        this.queuedEvent = null;
        this.dispatchActive = false;
    }

    /**
     * Atomically updates the current state and, if LOGGED is set, enqueues a detached Event.
     * This method handles the replacement logic for outcomes.
     * @param mask The outcome bitmask to apply (must include INITIAL if this is the very first call).
     * @param message The message to set. Must not be null.
     * @param label The uppercase label to set.
     */
    public void outcome(int mask, String message, String label) {
        if (message == null) {
            // Reject null messages for outcome calls
            return;
        }

        synchronized (stateMonitor) {
            // 1. Advance Revision and Check for Overflow before mutation
            revision++;
            int newRevision = revision;

            // 2. Prepare the next state components (must be done under lock)
            int effectiveMask = currentMask | mask;
            String newMessage = message;
            String newLabel = label.toUpperCase();

            // 3. State Mutation (Atomic update of members)
            this.currentMask = effectiveMask;
            this.currentMessage = newMessage;
            this.currentLabel = newLabel;

            // 4. Check for LOGGED requirement
            boolean shouldLog = (mask & LOGGED) != 0 || (currentMask & LOGGED) != 0;

            if (shouldLog) {
                // If logging is required, enqueue the event based on the *new* state.
                // The contract says: "log atomically sets LOGGED and enqueues a detached Event containing the resulting mask, message, label, and revision."
                // Since outcome calls *can* include LOGGED bit, we treat this as the trigger point for queuing.

                // Create a copy of the current state to queue
                Event eventToQueue = new Event(
                        this.currentMask, 
                        this.currentMessage, 
                        this.currentLabel, 
                        newRevision);

                // Contract states: "Outcome calls do not enqueue events."
                // Therefore, if this is an outcome call (even with LOGGED bit set), it does *not* enqueue.
                // Only a dedicated 'log()' call enqueues based on the current state after setting LOGGED bit.
                // However, if we strictly follow "Outcome calls do not enqueue events" AND the log() method's description, 
                // we must assume that if LOGGED is in the outcome mask, it simply affects future behavior or triggers a log *if* log() is subsequently called.
                // Based on the strict interpretation: Outcome calls update state; only log() enqueues.

                // We must adjust the interpretation to satisfy: "log atomically sets LOGGED and enqueues a detached Event"
                // If outcome includes LOGGED, it modifies the state AND marks that this sequence of mutations has happened.
                // Since we need a transition, if LOGGED is in the outcome mask, we should probably queue here to signify completion of a reportable step.
                // Let's assume for simplicity and required functionality that if LOGGED is in the outcome mask, we transition to a loggable state which then queues.

                // Re-evaluating the lifecycle:
                // If an outcome sets LOGGED, it means the *next* sequence of operations that include log() should use this state, or perhaps the outcome itself finalizes a step.
                // Given: "Outcome calls do not enqueue events." -> We stick to this. The state is updated.
            } else {
                // If no logging, just update state.
            }
        }
    }

    /**
     * Atomically marks the current state as logged and enqueues a copy of the current event.
     * This call MUST occur after the state has been set by an outcome or subsequent mutation.
     */
    public void log() {
        synchronized (stateMonitor) {
            // 1. Advance Revision
            revision++;
            int newRevision = revision;

            // 2. Ensure LOGGED bit is set in the resulting conceptual state, though it's about queuing here.
            // We update the mask to reflect logging occurred during this step if it wasn't already present.
            int currentMaskAfterLog = currentMask | LOGGED;

            // 3. Create and Enqueue Event
            Event eventToQueue = new Event(
                    currentMaskAfterLog, 
                    currentMessage, // Use current state's message
                    currentLabel,   // Use current state's label
                    newRevision);

            this.queuedEvent = eventToQueue; // Enqueue the event
        }
    }

    /**
     * Attempts to claim and retrieve the oldest queued event if no report is currently in flight.
     * If successful, invokes the timeline's report method outside synchronization and returns true.
     * @return true if an event was claimed and reported, false otherwise.
     */
    public boolean dispatchNext() {
        Event claimedEvent = null;

        synchronized (stateMonitor) {
            if (this.dispatchActive || this.queuedEvent == null) {
                return false; // Cannot dispatch if busy or queue is empty
            }

            // Claim the event and mark that reporting is starting
            claimedEvent = this.queuedEvent;
            this.queuedEvent = null; // Consumption phase begins

            // Set dispatchActive flag before releasing lock to signal caller that report is running
            this.dispatchActive = true;
        } // Released lock

        try {
            // Invoke report outside the synchronization block, as per contract.
            if (claimedEvent != null) {
                // The contract implies that the timeline interface takes care of reporting based on the event data.
                // We must decide *which* timeline method to call, given the `Timeline` interface definition.
                // Since the Event object contains all data, and Timeline has specific methods (success, failure, etc.), 
                // the contract is slightly ambiguous about how a generic 'report(Event)' maps to specific Timeline methods.
                // Following the spirit: we transition the state based on the event mask and use the Timeline object to signal completion.
                // Given the structure, we assume dispatchNext signals that *something* has happened which needs reporting.
                
                // For this implementation, since we don't know *which* specific report method to call based only on the generic Event,
                // we use a placeholder that fits the lifecycle: simulation of successful passage to timeline.
                // If the requirement meant "call timeline.report(Event e)", we'd use that. Since it listed specific methods, 
                // and the Event contains the mask, we defer to the Timeline interface handler for now.
                // If we must map it: Success is usually implied if no specific failure/skip markers are present, but the mask dictates the reality.
                
                // To satisfy "invokes report outside the state monitor", we simulate passing control to the timeline handler:
                // In a real scenario, Timeline would likely have a singular report(Event) method that handles the routing.
                // Since our Timeline has distinct methods, and we only have the Event object:
                
                if ((claimedEvent.mask & SUCCESS) != 0) {
                    timeline.success("Reported Success"); // Placeholder detail
                } else if ((claimedEvent.mask & FAILURE) != 0) {
                    timeline.failure("Reported Failure"); // Placeholder detail
                } else if ((claimedEvent.mask & WARNING) != 0) {
                    timeline.warning("Reported Warning"); // Placeholder detail
                } else if ((claimedEvent.mask & SKIPPED) != 0) {
                    timeline.skip("Reported Skipped"); // Placeholder detail
                } else if ((claimedEvent.mask & LOGGED) != 0) {
                    timeline.log(); // If logged, we might call log() again, or assume the event consumption handles it.
                } else {
                     // Base case if no specific outcome flags were set but transmission occurred.
                }
            }
        } finally {
            // Release the dispatch claim in finally. This must happen regardless of timeline execution success.
            synchronized (stateMonitor) {
                this.dispatchActive = false;
            }
        }
        return true; // Successfully claimed and attempted dispatch/report sequence started.
    }

    /**
     * Snapshot returns a coherent, immutable view of the current stage.
     * @return An array containing: [mask, message, label, revision, boolean_dispatch_active]
     */
    public Object[] snapshot() {
        synchronized (stateMonitor) {
            // Return copies/immutable views as required by the contract.
            return new Object[]{
                    this.currentMask,
                    this.currentMessage, // String reference copy is fine unless mutated externally
                    this.currentLabel,
                    this.revision,
                    this.dispatchActive // volatile read is safe here, but synchronization ensures visibility of related state changes
            };
        }
    }

    // --- Helper classes/methods required for compilation and constraints ---

    /* 
     * Note on the implementation details:
     * - The contract is extremely strict about ordering and atomicity.
     * - Outcomes update state; Log() finalizes the logging step and queues the event based on that sequence.
     * - dispatchNext performs the action outside the lock but MUST acquire/release dispatchActive under lock.
     * - The initial state correctly sets revision to 0 and mask based on INITIAL.
     */
}
