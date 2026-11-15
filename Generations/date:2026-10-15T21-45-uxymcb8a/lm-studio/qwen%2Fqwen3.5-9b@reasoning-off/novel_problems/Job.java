import java.util.IdentityHashMap;

/**
 * Interface representing a unit of work in the thermostatic orchestrator.
 */
public interface Job {
    void run();
}

/**
 * Concurrency limits mode: NORMAL uses the configured limit, THROTTLED uses limit 1.
 */
enum Mode {
    NORMAL,
    THROTTLED
}

/**
 * ThermostaticBuildOrchestrator manages a queue of jobs and dynamically adjusts
 * the concurrency limit (admission rate) based on the current system load (thermostatic control).
 * It supports manual overrides of the mode and provides a snapshot mechanism for observation.
 */
@ThermostaticBuildOrchestrator.ThreadSafe
class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int capacity;
    private final int normalLimit;
    private final int low;
    private final int high;
    private final IdentityHashMap<Integer, Job> pendingJobs = new IdentityHashMap<>();
    private final ListDeque<Integer> pendingQueue = new ListDeque<>(); // Stores IDs in FIFO order
    private int runningCount = 0;
    private int load = 50; // Initial load
    private Mode mode = Mode.NORMAL;
    private volatile Mode overrideMode = null; // Manual override, null means automatic
    private volatile boolean closed = false;
    private int nextId = 1;

    // Waiters waiting for specific events
    private final IdentityHashMap<Integer, java.util.HashSet<Thread>> waitingOn = new IdentityHashMap<>();
    private final java.util.List<Thread> waitingForCapacity = new java.util.ArrayList<>();
    private final java.util.List<Thread> waitingForClose = new java.util.ArrayList<>();
    private final java.util.List<Thread> waitingForDrain = new java.util.ArrayList<>();

    /**
     * Constructor for the orchestrator.
     * @param capacity Positive queue capacity.
     * @param normalLimit Normal concurrency limit (positive).
     * @param low Threshold (0 <= low < high <= 100).
     * @param high Threshold (low < high <= 100).
     */
    ThermostaticBuildOrchestrator(int capacity, int normalLimit, int low, int high) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        if (normalLimit <= 0) throw new IllegalArgumentException("Normal limit must be positive");
        if (low < 0 || high > 100) throw new IllegalArgumentException("Thumbnails must be between 0 and 100");
        if (low >= high) throw new IllegalArgumentException("Low must be less than high");
        
        this.capacity = capacity;
        this.normalLimit = normalLimit;
        this.low = low;
        this.high = high;
    }

    /**
     * Submits a job for execution.
     * @param job The job to submit.
     * @return A unique positive ID.
     * @throws IllegalStateException if the orchestrator is closed or the pending queue is full.
     */
    int submit(Job job) {
        if (job == null) throw new IllegalArgumentException("Job cannot be null");
        if (closed) throw new IllegalStateException("Orchestrator is closed");
        
        // Enforce capacity constraint
        if (pendingQueue.size() >= capacity) {
            throw new IllegalStateException("Pending queue is full");
        }

        int id = nextId++;
        pendingJobs.put(id, job);
        pendingQueue.add(id);
        return id;
    }

    /**
     * Cancels a pending job.
     * @param jobId The ID of the job to cancel.
     * @return true if the job was found and removed from the pending queue, false otherwise.
     */
    boolean cancel(long jobId) {
        if (closed) return false;
        Integer objId = (Integer) jobId;
        
        // Check pending queue (FIFO list)
        try {
            int index = pendingQueue.indexOf(objId);
            if (index != -1) {
                pendingQueue.remove(index);
                pendingJobs.remove(objId);
                wakeWaiters();
                return true;
            }
        } catch (Exception e) {
            return false;
        }

        // Check running jobs? No, cancel removes pending jobs only per contract.
        return false;
    }

    /**
     * Updates the current load.
     * @param load The new load value (0-100).
     */
    void updateLoad(int load) {
        if (load < 0 || load > 100) return;
        
        int newLoad = load;
        int currentMode = 0; // 0 for NORMAL, 1 for THROTTLED
        
        // If there is an override, the new load still affects the automatic mode logic
        // but the effective mode remains the override until clearOverride.
        // However, the contract says: "updateLoad accepts 0..100 and updates automatic mode even during an override."
        // This implies we calculate what the mode *would* be, or we switch it back?
        // Re-reading: "changes to THROTTLED at load >= high, changes back at load <= low".
        // "A manual override wins until clearOverride restores the current automatic mode."
        // This suggests updateLoad triggers a recalculation of the target mode based on current load.
        // If override is active, we compute the target mode. If the target mode differs from the active mode,
        // does it override? The text says "updates automatic mode even during an override".
        // Interpretation: The system tracks a desired state based on load.
        // If load is high, desired is THROTTLED. If load is low, desired is NORMAL.
        // If an override is set, the effective mode is the override.
        // BUT, "updates automatic mode" implies we might update the internal tracking or simply apply the logic.
        // Let's assume 'mode' field represents the current effective mode if override is null, 
        // and if override exists, this method updates the 'overrideMode' logic?
        // Actually, "A manual override wins until clearOverride restores the current automatic mode."
        // This implies: 
        // 1. Calculate targetMode based on load.
        // 2. If no override, set mode = targetMode.
        // 3. If override exists, do we change it? 
        //    Text: "updates automatic mode even during an override". This is slightly ambiguous.
        //    It likely means the 'mode' variable (which represents the calculated state) is updated,
        //    but the 'effectiveMode' used for admission is still the override.
        //    And when clearOverride is called, the effectiveMode becomes the updated mode.
        
        boolean isThrottled = (newLoad >= high);
        boolean targetIsThrottled = isThrottled;
        
        // Logic: If load >= high, we want THROTTLED. If load <= low, we want NORMAL.
        // If low < load < high, we keep the previous automatic mode.
        
        if (overrideMode == null) {
            if (load >= high) {
                currentMode = 1; // THROTTLED
            } else if (load <= low) {
                currentMode = 0; // NORMAL
            }
            mode = (currentMode == 1) ? Mode.THROTTLED : Mode.NORMAL;
        } else {
            // Update the 'mode' field (the automatic mode) to reflect the new load,
            // but keep 'overrideMode' as the effective one.
            if (load >= high) {
                overrideMode = Mode.THROTTLED;
            } else if (load <= low) {
                overrideMode = Mode.NORMAL;
            }
            // If load is in between, overrideMode stays as is.
        }
    }

    /**
     * Overrides the mode manually.
     * @param mode The mode to override with.
     */
    void overrideMode(Mode mode) {
        if (closed) return;
        this.overrideMode = mode;
        wakeWaiters();
    }

    /**
     * Clears the manual override, restoring automatic mode logic.
     */
    void clearOverride() {
        if (closed) return;
        this.overrideMode = null;
        wakeWaiters();
    }

    /**
     * Runs the next admissible job.
     * @return true if a job ran, false if closed and drained.
     */
    boolean runNext() {
        synchronized (this) {
            if (closed) {
                if (pendingQueue.isEmpty() && runningCount == 0) {
                    return false; // Drained
                }
                // If not drained yet, wait
                if (waitingForClose.contains(Thread.currentThread())) {
                    throw new IllegalMonitorStateException("Already waiting for close");
                }
                waitingForClose.add(Thread.currentThread());
                Thread temp = Thread.currentThread();
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false; // Or propagate? Usually return false if we broke wait.
                }
                if (waitingForClose.contains(Thread.currentThread())) {
                    waitingForClose.remove(Thread.currentThread());
                    return false; // Still closed, check drain below
                }
                // Re-check state after wakeup
            }

            if (closed) {
                // Wait for close if we are waiting for something else, or if we are just checking state
                // Actually, if closed, we can only run if queue is empty and count is 0.
                // If queue not empty or count not 0, we are effectively blocked waiting for drain.
                if (pendingQueue.isEmpty() && runningCount == 0) {
                    return false;
                }
                if (!waitingForClose.contains(Thread.currentThread())) {
                    waitingForClose.add(Thread.currentThread());
                    Thread.sleep(0); // Just to simulate wait if no one else woke us, but we rely on notifyAll
                }
                // Loop back to check closed again? No, synchronized block handles this.
                // We need a loop condition outside? 
                // Let's restructure the logic flow carefully.
            }
        }
        // The above structure is flawed for a clean "wait until" loop. 
        // Let's rewrite the method body to be a loop.
        return runNextImpl();
    }
    
    private boolean runNextImpl() {
        synchronized (this) {
            // Main loop
            while (true) {
                if (closed) {
                    // Wait for close notification
                    if (waitingForClose.contains(Thread.currentThread())) {
                         // Do nothing, just continue to wait
                    } else {
                         waitingForClose.add(Thread.currentThread());
                    }
                    Thread.currentThread().yield(); // Simulate busy wait loop without sleep? 
                    // Requirement: Do not use busy-waiting.
                    // We must wait until notified.
                    // If we are waiting for close, we wait.
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    // Clear the waiting set to avoid infinite holding of the lock if we didn't wake
                    // But we must release lock to wait. Wait, we are inside synchronized.
                    // How to wait and release? We do 'wait()' which releases lock.
                    // But we can't add to set and wait.
                    // Strategy: If closed, and we are the first one to realize it?
                    // No, the closed flag is volatile. But the 'waitingForClose' set is for coordination.
                    // Standard pattern:
                    // if (closed && condition) { wait(); } else { compute logic; }
                    // Since we are inside synchronized, we own the lock.
                    // If condition (close) is met:
                    //    add self to set.
                    //    return false? No, we need to wait until everyone finishes or close happens.
                    //    But wait() releases the lock. Other threads will re-acquire.
                    //    This is tricky.
                    
                    // Better approach:
                    // Check conditions. If blocked, add to appropriate set, then return a sentinel or 
                    // just rely on the fact that we are synchronized.
                    // But we can't return from synchronized method while waiting? We can, if we break.
                    // But we need to know if we should return true or false.
                    
                    // Let's restructure completely.
                    // 1. Determine if we can run now.
                    //    Eligible: !closed && canAdmit()
                    // 2. If eligible: run job, return true.
                    // 3. If not eligible:
                    //    If closed: add to waitingForClose.
                    //    If pending queue not empty: add to waitingForCapacity.
                    //    If runningCount == 0: (Nothing to wait for specifically other than close? No, just loop)
                    //    Actually, if runningCount is 0 and not closed, we can run immediately (if queue not empty).
                    //    So if not eligible:
                    //       If closed -> wait for close.
                    //       If queue empty and count > 0 -> wait for drain (callback completion).
                    //       If queue not empty and count == 0 -> wait for capacity.
                    //       Else (queue empty and count == 0 and not closed) -> Just wait for closed or anything? 
                    //          No, if not closed, we try again next loop.
                    
                    break; // Break the synchronized block? No, we need to hold lock to manage sets.
                }

                // Check admission limits
                int limit = (overrideMode == Mode.THROTTLED) ? 1 : normalLimit;
                if (runningCount < limit) {
                    // Can admit
                    if (!pendingQueue.isEmpty()) {
                        int id = pendingQueue.remove(0); // FIFO
                        pendingJobs.remove(id);
                        runningCount++;
                        
                        Job job = pendingJobs.get(id);
                        synchronized (job) { // Wait, we can't lock external jobs.
                            // We must lock the orchestrator for everything, but callback outside.
                            // So:
                            // 1. Remove from queue.
                            // 2. Run job (lock job, then unlock job immediately? No, lock must be consistent).
                            //    If Job is not this class, we can't lock it safely without deadlock if Job locks itself.
                            //    We must assume Job.run() doesn't hold a lock on this instance? 
                            //    The prompt says: "invoke its callback outside all state monitors".
                            //    This implies we run it without holding 'this' lock.
                            //    But we need to ensure thread safety of the Job itself? 
                            //    Usually, if the user provides the job, they manage its own locking.
                            //    So we just call it.
                            try {
                                job.run();
                                return true;
                            } catch (RuntimeException re) {
                                // Propagate
                                // But we need to release capacity.
                                throw re;
                            }
                        }
                    } else {
                        // Queue empty, try again later
                        // But maybe we should wake waiters? 
                        // Yes, runNext might be called by multiple threads.
                    }
                } else {
                    // Cannot admit
                    if (!pendingQueue.isEmpty()) {
                        // Wait for capacity
                        if (!waitingForCapacity.contains(Thread.currentThread())) {
                            waitingForCapacity.add(Thread.currentThread());
                        }
                        break;
                    }
                }
                
                // If we are here, either:
                // - Cannot admit (limit reached) and queue empty -> Wait for running count to drop? 
                //   If queue empty and count == limit (which is > 0), we wait for drain.
                
                if (!waitingForDrain.contains(Thread.currentThread())) {
                    waitingForDrain.add(Thread.currentThread());
                }
                break;
            }
            
            // If we exited the loop due to waiting condition, we need to wait.
            // If we exited due to running a job, we return true.
            // But the structure above is a bit messy.
            // Let's flatten the logic.
            
            // Correct Logic Loop:
            // 1. Check Closed.
            // 2. If Closed:
            //    a. If Queue empty AND Count == 0: Return false (Done).
            //    b. Else: Add to WaitingForClose, Wait, Loop.
            // 3. If Not Closed:
            //    a. Check if Queue Empty AND Count == 0: 
            //       If Closed: (Handled above). If Not Closed: We are waiting for something?
            //       Actually if Not Closed and Queue Empty and Count 0, we can do nothing, just loop waiting for load change or close?
            //       The method should block if no job can run.
            //       So if Queue Empty and Count 0: 
            //          We are effectively "blocked" because no job is available to run.
            //          Do we wait? The contract says "waits interruptibly until the oldest pending job can be admitted".
            //          If no pending job, it waits until one is added (submit) or close happens?
            //          If queue is empty, submit adds to queue.
            //          So we just return false? No, "waits ... until". If it never happens, it waits forever?
            //          But if closed, we wait for drain.
            //          If not closed, but queue empty and count 0? 
            //          Then we must wait for someone to submit or close.
            //          But we can't wait for submit (we don't know when). 
            //          So we wait for close? Or we just return false?
            //          "Return true if a job ran, false if closed and drained."
            //          If not closed and drained, and no job, what to return? 
            //          Maybe block until close?
            //          Let's assume if not closed, we wait for capacity or drain if blocked, 
            //          otherwise just busy loop? No, "waits interruptibly".
            //          We must wait for something.
            //          If not closed, queue empty, count 0 -> We are not blocked on capacity or close.
            //          Is there a blocking state? 
            //          Maybe we just return false immediately? "false if closed and drained".
            //          If not closed and not drained, we shouldn't return false, we should try to run next?
            //          But there is no next.
            //          So we wait until state changes (close).
            //          So if queue empty, count 0, not closed -> wait for close? Or wait for nothing (spin)?
            //          Given "waits ... until", if the condition is never met, it blocks forever.
            //          If we want to return false when closed and drained, we must detect closed.
            //          So if closed, we wait until drained.
            //          If not closed, and no job to run, do we wait? 
            //          Yes, because "waits ... until the oldest pending job can be admitted".
            //          If queue is empty, it waits until a job is submitted (adds to queue).
            //          But we don't know when.
            //          So we wait for 'not empty' OR 'admissible'.
            //          This is hard to coordinate without a shared object to notify.
            //          We can assume the user will 'submit' and then 'runNext'.
            //          So if queue empty, we just wait. Who notifies? The submit method.
            //          We need to listen to submit? 
            //          We can use a global 'waitingForNextJob' set.
            //          When submit() adds a job, it notifies everyone waiting for next job.
            
            // Refined Plan:
            // We need 4 waiting sets:
            // 1. Waiting for Close (if closed).
            // 2. Waiting for Capacity (if count == limit).
            // 3. Waiting for Drain (if queue empty).
            // 4. Waiting for Next Job (if queue empty and count == 0 and not closed).
            
            // Actually, "Waiting for Drain" covers "Waiting for Next Job" when closed?
            // If closed, we wait until drained.
            // If not closed, queue empty, count 0 -> We wait for next job to be submitted.
            // So:
            // if closed:
            //    if queue empty and count 0: return false.
            //    else: wait on "close/finish".
            // else:
            //    if canAdmit():
            //        run job.
            //    else if queue not empty:
            //        wait for capacity (someone frees space).
            //    else: // queue empty
            //        if count == 0: wait for next job (someone submits).
            //        else: wait for drain (someone finishes job).
            
            // Implementation details:
            // Use a single global Lock object to coordinate all waits? No, synchronized on 'this'.
            // We need to wake up waiters on submit, cancel, close, and job completion.
            
            // Let's implement the loop properly.
            while (true) {
                boolean proceed = false;
                if (closed) {
                    if (pendingQueue.isEmpty() && runningCount == 0) {
                        proceed = false; // Done
                    } else {
                        // Need to wait
                        if (!waitingForClose.contains(Thread.currentThread())) {
                            waitingForClose.add(Thread.currentThread());
                        }
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            // After interrupt, check state again
                            // If still blocked, we are in infinite loop? 
                            // No, we break and loop again.
                            // If we were waiting for close, and we were interrupted because close happened?
                            // Or just interrupt.
                            // If we were waiting for close, and no one notified, we get interrupted.
                            // Then we loop. If closed is true and drained, we break and return false.
                            // But we might be stuck in the 'waiting' block if no one wakes us?
                            // That's the risk of wait/notify. We assume the protocol (cancel/close) will wake us.
                            proceed = false;
                        }
                        // After wake, re-evaluate
                    }
                } else {
                    int limit = (overrideMode == Mode.THROTTLED) ? 1 : normalLimit;
                    if (runningCount < limit) {
                        if (!pendingQueue.isEmpty()) {
                            // Can admit
                            if (!waitingForCapacity.contains(Thread.currentThread())) {
                                // Actually we don't need to wait, we just run.
                                // Wait, multiple threads might try to run at once.
                                // We need to grab the job exclusively.
                                int id = pendingQueue.remove(0);
                                pendingJobs.remove(id);
                                runningCount++;
                                
                                try {
                                    Job job = pendingJobs.get(id);
                                    // Run outside lock
                                    try {
                                        job.run();
                                        // Success
                                        synchronized (this) {
                                            pendingJobs.remove(id); // Remove from pending map
                                            runningCount--;
                                            pendingQueue.removeFirstOccurrence(id); // If still in queue (shouldn't be)
                                            // Wait, we removed from queue above.
                                            // Remove from pendingJobs done.
                                            pendingJobs.remove(id); // Redundant but safe
                                        }
                                        return true;
                                    } catch (RuntimeException re) {
                                        // Fail
                                        synchronized (this) {
                                            runningCount--;
                                        }
                                        throw re;
                                    }
                                } catch (Exception e) {
                                    // Ignore non-runtime? Contract says "Propagate callback RuntimeException".
                                    // If it's a checked exception inside run(), it won't be propagated?
                                    // Assuming run() can throw RuntimeException.
                                    synchronized (this) {
                                        runningCount--;
                                    }
                                }
                                proceed = true; // We ran a job
                            } else {
                                // Race condition protection
                                // If we reached here, another thread stole the job or reduced count.
                                // We must wait for capacity.
                                if (!waitingForCapacity.contains(Thread.currentThread())) {
                                    waitingForCapacity.add(Thread.currentThread());
                                }
                                try {
                                    wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                proceed = false;
                            }
                        } else {
                            // Queue empty
                            if (runningCount == 0) {
                                // Nothing running, nothing pending.
                                // If closed, handled above.
                                // If not closed, we wait for a job to be submitted.
                                // Add to waitingForNextJob (new set)
                                waitingForNextJob.add(Thread.currentThread());
                                try {
                                    wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                proceed = false;
                            } else {
                                // Something running, queue empty.
                                // Wait for drain (someone finishes).
                                if (!waitingForDrain.contains(Thread.currentThread())) {
                                    waitingForDrain.add(Thread.currentThread());
                                }
                                try {
                                    wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                proceed = false;
                            }
                        }
                    } else {
                        // Count == limit, queue not empty (otherwise caught above)
                        // Wait for capacity
                        if (!waitingForCapacity.contains(Thread.currentThread())) {
                            waitingForCapacity.add(Thread.currentThread());
                        }
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        proceed = false;
                    }
                }
                
                if (proceed) {
                    continue; // Try again
                } else {
                    // Check if we should exit loop
                    if (closed && pendingQueue.isEmpty() && runningCount == 0) {
                        return false;
                    }
                    // If we broke out of wait loops, we go back to top of while loop?
                    // No, we need to handle the case where we just woke up.
                    // The 'while(true)' ensures we check state again.
                    // So we continue.
                }
            }
            return false; // Unreachable
        }
    }
    
    // Helper sets for waiting
    private final java.util.Set<Thread> waitingForCapacity = new java.util.HashSet<>();
    private final java.util.Set<Thread> waitingForDrain = new java.util.HashSet<>();
    private final java.util.Set<Thread> waitingForNextJob = new java.util.HashSet<>();
    private final java.util.Set<Thread> waitingForClose = new java.util.HashSet<>(); // Redeclare outside or handle inside. 
    // Wait, I used a map 'waitingOn' earlier, but sets are simpler for thread identification.
    // Let's restructure the class to have all sets as fields.

    // Redefining fields for clarity
    // ...
    
    // Let's rewrite the class structure properly.
    
    // Re-implementing with clean field definitions
}

// Since I cannot edit the previous block, I will write the full class again.

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    public interface Job {
        void run();
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }

    private final int capacity;
    private final int normalLimit;
    private final int low;
    private final int high;
    
    // Pending jobs storage
    private final List<Integer> pendingQueue = new ArrayList<>();
    private final IdentityHashMap<Integer, Job> pendingJobs = new IdentityHashMap<>();
    
    private int runningCount = 0;
    private int load = 50; 
    private Mode mode = Mode.NORMAL;
    private Mode overrideMode = null; 
    
    private volatile boolean closed = false;
    private int nextId = 1;

    // Waiter coordination
    private final Set<Thread> waitingForCapacity = new HashSet<>();
    private final Set<Thread> waitingForDrain = new HashSet<>();
    private final Set<Thread> waitingForNextJob = new HashSet<>();
    private final Set<Thread> waitingForClose = new HashSet<>();

    public ThermostaticBuildOrchestrator(int capacity, int normalLimit, int low, int high) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        if (normalLimit <= 0) throw new IllegalArgumentException("Normal limit must be positive");
        if (low < 0 || high > 100) throw new IllegalArgumentException("Thresholds must be in [0, 100]");
        if (low >= high) throw new IllegalArgumentException("Low must be less than high");
        this.capacity = capacity;
        this.normalLimit = normalLimit;
        this.low = low;
        this.high = high;
    }

    public int submit(Job job) {
        if (job == null) throw new IllegalArgumentException("Job cannot be null");
        synchronized (this) {
            if (closed) throw new IllegalStateException("Orchestrator is closed");
            if (pendingQueue.size() >= capacity) throw new IllegalStateException("Pending queue is full");
        }
        int id = nextId++;
        synchronized (this) {
            pendingJobs.put(id, job);
            pendingQueue.add(id);
            // Notify waiters who might be waiting for capacity or next job
            if (runningCount < capacity) {
                // Capacity available, maybe notify capacity waiters?
                // But capacity check is complex (limit).
                // Just notify all relevant sets.
            }
            wakeWaiters();
        }
        return id;
    }

    public boolean cancel(long jobId) {
        Integer objId = (Integer) jobId;
        synchronized (this) {
            if (closed) return false;
            int index = -1;
            try {
                index = pendingQueue.indexOf(objId);
            } catch (Exception ignored) {}
            
            if (index != -1) {
                pendingQueue.remove(index);
                pendingJobs.remove(objId);
                wakeWaiters();
                return true;
            }
        }
        return false;
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) return;
        synchronized (this) {
            int newLoad = load;
            boolean targetIsThrottled = (newLoad >= high);
            
            if (overrideMode == null) {
                if (targetIsThrottled) {
                    mode = Mode.THROTTLED;
                } else if (newLoad <= low) {
                    mode = Mode.NORMAL;
                }
            } else {
                // Override mode exists, but we update the 'overrideMode' field to track the new load preference
                // until clearOverride.
                if (targetIsThrottled) {
                    overrideMode = Mode.THROTTLED;
                } else if (newLoad <= low) {
                    overrideMode = Mode.NORMAL;
                }
            }
            wakeWaiters();
        }
    }

    public void overrideMode(Mode mode) {
        if (closed) return;
        synchronized (this) {
            this.overrideMode = mode;
            wakeWaiters();
        }
    }

    public void clearOverride() {
        synchronized (this) {
            if (closed) return;
            this.overrideMode = null;
            wakeWaiters();
        }
    }

    public boolean runNext() {
        synchronized (this) {
            int effectiveLimit = (overrideMode == Mode.THROTTLED) ? 1 : normalLimit;
            
            boolean hasJob = false;
            
            while (true) {
                if (closed) {
                    if (pendingQueue.isEmpty() && runningCount == 0) {
                        return false; // Drained
                    }
                    if (waitingForClose.contains(Thread.currentThread())) {
                         // Continue waiting
                    } else {
                        waitingForClose.add(Thread.currentThread());
                    }
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // Re-check
                    if (pendingQueue.isEmpty() && runningCount == 0) {
                        return false;
                    }
                    // Continue loop
                }

                if (pendingQueue.isEmpty()) {
                    if (runningCount == 0) {
                        if (closed) {
                            if (waitingForDrain.contains(Thread.currentThread())) {
                                // Wait for drain
                            } else {
                                waitingForDrain.add(Thread.currentThread());
                            }
                        } else {
                            if (waitingForNextJob.contains(Thread.currentThread())) {
                                // Wait for next job
                            } else {
                                waitingForNextJob.add(Thread.currentThread());
                            }
                        }
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        // Wait for drain
                        if (waitingForDrain.contains(Thread.currentThread())) {}
                        else {
                            waitingForDrain.add(Thread.currentThread());
                        }
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    // Check again after wake
                    if (pendingQueue.isEmpty()) {
                         if (closed) {
                             if (runningCount == 0) return false;
                         }
                         // Continue waiting
                    }
                    // If queue not empty now, we proceed to check admission
                } else {
                    // Queue not empty
                    if (runningCount < effectiveLimit) {
                        int id = pendingQueue.remove(0); // FIFO
                        if (!pendingJobs.containsKey(id)) continue; // Safety
                        
                        Job job = pendingJobs.get(id);
                        pendingJobs.remove(id);
                        runningCount++;
                        
                        try {
                            job.run();
                        } catch (RuntimeException re) {
                            synchronized (this) {
                                runningCount--;
                            }
                            throw re;
                        }
                        
                        pendingJobs.remove(id); // Ensure removal
                        hasJob = true;
                        break;
                    } else {
                        if (!waitingForCapacity.contains(Thread.currentThread())) {
                            waitingForCapacity.add(Thread.currentThread());
                        }
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            return hasJob;
        }
    }

    public void close() {
        synchronized (this) {
            this.closed = true;
            wakeWaiters();
        }
    }

    public void wakeWaiters() {
        synchronized (this) {
            Set<Thread> toRemove = new HashSet<>();
            if (!waitingForCapacity.isEmpty()) {
                for (Thread t : waitingForCapacity) {
                    t.notify();
                }
            }
            if (!waitingForDrain.isEmpty()) {
                for (Thread t : waitingForDrain) {
                    t.notify();
                }
            }
            if (!waitingForNextJob.isEmpty()) {
                for (Thread t : waitingForNextJob) {
                    t.notify();
                }
            }
            if (!waitingForClose.isEmpty()) {
                for (Thread t : waitingForClose) {
                    t.notify();
                }
            }
            // Clear sets
            waitingForCapacity.clear();
            waitingForDrain.clear();
            waitingForNextJob.clear();
            waitingForClose.clear();
        }
    }

    public void snapshot() {
        synchronized (this) {
            System.out.println("--- Snapshot ---");
            System.out.println("Mode: " + (overrideMode != null ? overrideMode : mode));
            System.out.println("Effective Limit: " + ((overrideMode == Mode.THROTTLED) ? 1 : normalLimit));
            System.out.println("Running Count: " + runningCount);
            System.out.println("Load: " + load);
            System.out.println("Closed: " + closed);
            System.out.println("Pending IDs: " + pendingQueue);
        }
    }

    // Helper to calculate effective mode for admission
    public static Mode getEffectiveMode(Mode overrideMode, Mode currentMode) {
        if (overrideMode != null) return overrideMode;
        return currentMode;
    }
}
