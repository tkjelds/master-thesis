import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream implements AutoCloseable {
    @interface ThreadSafe {}


    /**
     * Enumeration of the stream states.
     */
    public enum State {
        OPEN,
        READING,
        COMPLETING,
        CLOSED,
        FAILED
    }

    /**
     * Represents a successful chunk of data read from the source.
     */
    public static class Result {
        public final byte[] bytes;
        public final long count;
        public final int phase;
        public final Throwable failure;

        private Result(byte[] bytes, long count, int phase, Throwable failure) {
            this.bytes = (bytes != null) ? bytes.clone() : null;
            this.count = count;
            this.phase = phase;
            this.failure = failure;
        }

        @Override
        public String toString() {
            return String.format("Result(count=%d, phase=%d, failure=%s)", count, phase, failure);
        }
    }

    /**
     * Listener interface for completion.
     */
    public interface CompletionListener {
        void complete(Result result);
    }

    /**
     * Interface for the data source.
     */
    public interface ChunkSource {
        byte[] read() throws IOException;
        void close() throws IOException;
    }

    // Thread fields
    private volatile State state;
    private volatile byte[] retainedBytes;
    private volatile long retainedCount = 0L;
    private volatile int retainedPhase = 0;
    
    // Synchronization
    private final Object monitor = new Object();
    
    // Wait queue for threads waiting for a read
    private final List<Thread> waitingReaders;
    private volatile boolean closePending;
    private volatile boolean closeProcessing = false;
    
    // Completion tracking to ensure single invocation
    private volatile boolean completionInvoked = false;
    private volatile List<CompletionListener> listeners = new ArrayList<>();
    
    // Internal buffer for the snapshot
    private byte[] buffer;

    // Source
    private ChunkSource source;

    // Lock for the entire stream lifecycle to prevent structural changes during operation
    private final Object lifecycleLock = new Object();

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listeners.add(listener);
        this.waitingReaders = new ArrayList<>();
        this.state = State.OPEN;
        this.retainChunk(null); // Initial empty state
        this.buffer = new byte[0]; // Start with empty internal buffer
        this.retainedCount = 0;
        this.retainedPhase = 0;
    }

    /**
     * Reads a single chunk from the source.
     * Waits if the stream is open but no source operation is currently in flight.
     * Returns null if the stream is in a terminal state (CLOSED, FAILED, COMPLETING).
     * 
     * Logic:
     * 1. Check state (must be OPEN or READING).
     * 2. Wait for slot if another reader is holding it.
     * 3. Perform read.
     * 4. Check for EOF.
     * 5. Retain chunk and transition state.
     */
    public byte[] readChunk() throws IOException {
        synchronized (lifecycleLock) {
            if (state == State.FAILED || state == State.CLOSED) {
                // Terminal states
                return null;
            }

            // Try to acquire the active read slot.
            // In our model, READING means a read is in flight.
            // We will use the state transition logic to handle the single active reader.
            // If we transition to READING, we own the slot.
            // However, we must ensure only one READING exists at a time.
            // We use a specific lock inside the read flow to prevent multiple READINGs.
            
            boolean acquiredSlot = false;
            synchronized (monitor) {
                if (state == State.OPEN) {
                    try {
                        source.read();
                    } catch (Exception e) {
                        fail(e);
                        return null;
                    }
                    state = State.READING;
                    acquiredSlot = true;
                }
                if (state == State.FAILED) {
                    return null;
                }
            }

            if (!acquiredSlot) {
                // Register ourselves as a waiter
                synchronized (waitingReaders) {
                    waitingReaders.add(Thread.currentThread());
                }
                synchronized (monitor) {
                    if (!Thread.interrupted()) {
                        try {
                            while (state != State.OPEN && state != State.COMPLETING && state != State.READING && state != State.FAILED && state != State.CLOSED) {
                                monitor.wait();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            // If interrupted, check if we can just exit or if we need to clean up
                            // per requirements: "Interruption of a waiting reader must not strand the active read".
                            // We must exit the wait loop.
                            break;
                        }
                        if (state != State.OPEN && state != State.READING) {
                            // If we are still here, we failed to wake up as a waiter.
                            // This implies the stream became terminal (CLOSED or FAILED).
                            if (state == State.CLOSED || state == State.FAILED) {
                                // Try to get result from snapshot or check termination.
                                synchronized (lifecycleLock) {
                                    return state == State.CLOSED ? retainedBytes : null;
                                }
                            }
                            return null;
                        }
                        // If we woke up and are OPEN/READING, we proceed.
                    } else {
                        // Interrupted
                        synchronized (monitor) {
                            // Check if we should wake everyone up to avoid stranding?
                            // Requirement: "Interruption ... must not strand the active read".
                            // So we must NOT call notifyAll. We must just return.
                        }
                        Thread.currentThread().interrupt();
                        synchronized (monitor) {
                            while (state != State.OPEN && state != State.COMPLETING && state != State.FAILED && state != State.CLOSED) {
                                monitor.wait();
                            }
                        }
                        if (state == State.FAILED) {
                            throw new RuntimeException("Cancelled");
                        }
                        if (state != State.OPEN && state != State.READING) {
                            // Becoming terminal due to other reasons
                            if (state == State.FAILED) {
                                synchronized (lifecycleLock) {
                                    return state == State.CLOSED ? retainedBytes : null;
                                }
                            }
                        }
                    }
                }
                return null; // Should not happen if logic is sound, but safety net.
            }

            // Perform the actual read
            synchronized (monitor) {
                // Ensure we are still single reader after returning from wait (double check)
                if (state != State.OPEN) {
                    return null; // Slot stolen
                }
            }

            byte[] chunk;
            synchronized (monitor) {
                try {
                    if (state != State.OPEN) return null; // Another reader took it?
                    try {
                        chunk = source.read();
                    } catch (Exception e) {
                        fail(e);
                        return null;
                    }
                } catch (Exception e) {
                    fail(e);
                    return null;
                }
                
                if (chunk == null) {
                    // EOF.
                    // We are the one who triggered EOF.
                    // We must perform the final transition to COMPLETING.
                    // But wait, if we are already READING, we transition here.
                    // Then we retain the chunk.
                    // Then we complete.
                    chunk = retainedBytes; // The last chunk we kept? No, retainedBytes is from PREVIOUS read.
                    // Actually, the contract says: "read returns null at EOF".
                    // "Retain its successful chunk before completion."
                    // This implies: Read returns chunk 1, 2... null.
                    // When null is returned, we finalize the previous chunk into the buffer.
                    
                    // Let's refine the flow based on contract:
                    // 1. Read returns non-null -> Add to buffer, update phase/count.
                    // 2. Read returns null -> We are done reading.
                    //    We must ensure the LAST chunk (the one that made the stream complete) is retained.
                    //    Currently, 'chunk' is the one just read.
                    
                    // Wait, if read() returns null, it means EOF is hit.
                    // The last *non-null* chunk has already been processed (added to buffer).
                    // So if this read returns null, we are done.
                    
                    // However, the requirement says: "Retain its successful chunk before completion."
                    // This might refer to the case where the *last* read returns the final chunk,
                    // and we transition to closed/completing.
                    
                    // Let's assume standard buffering:
                    // Chunk 1: read -> buffer += 100, phase += hash(100).
                    // Chunk 2: read -> buffer += 200, phase += hash(200).
                    // Chunk 3: read -> returns null.
                    // Now we complete.
                    // The listener gets buffer + chunk 3? Or just buffer?
                    // "Retain its successful chunk" implies the buffer accumulates them.
                    // snapshot returns the buffer.
                    
                    // So if chunk is null:
                    // We transition to COMPLETING.
                    // The 'chunk' variable here is null.
                    // We don't add null to buffer.
                    // We just signal completion.
                    
                    finishCompletion(null);
                    return null;
                } else {
                    // Process chunk
                    synchronized (monitor) {
                        buffer = concat(buffer, chunk);
                        long countDelta = chunk.length;
                        long newCount = retainedCount + countDelta;
                        
                        // Update phase: sum of unsigned byte values modulo 360
                        // We need to compute hash(chunk) efficiently?
                        // Or just the sum of values.
                        int p = computePhase(chunk);
                        int newPhase = (retainedPhase + p) % 360;
                        
                        retainedCount = newCount;
                        retainedPhase = newPhase;
                        
                        // Retain the chunk
                        retainedBytes = chunk; // Clone is done in retainChunk helper usually, but here let's clone now
                        byte[] cloned = (chunk == null) ? null : chunk.clone();
                        retainedBytes = cloned;

                        state = State.READING; // Wait for next read?
                        // Actually, after processing a chunk, we immediately start the NEXT read?
                        // No, the contract says: "Only one source read may be in flight."
                        // So we must wait for the NEXT caller to take the slot.
                        
                        // If we are processing chunk 1, we finish that chunk.
                        // Then we go back to sleep waiting for the next chunk?
                        // Or does the stream stay OPEN until EOF?
                        // "Concurrent readChunk callers wait interruptibly while another read is running."
                        // This suggests cooperative multiplexing.
                        // Thread A reads Chunk 1. State -> READING.
                        // Thread A puts chunk 1 in buffer.
                        // Thread A releases lock.
                        // Thread B wakes up. Sees State.READING. Waits.
                        // Thread A calls source.read() again.
                        
                        // So after processing a chunk, we should wait for the next reader if there are any?
                        // Or we simply loop back to waiting if there are more readers?
                        // Actually, the easiest pattern:
                        // 1. Acquire slot (set READING).
                        // 2. Read chunk.
                        // 3. Process chunk.
                        // 4. Release slot (set OPEN or READING).
                        // 5. Wake up a waiter if any.
                        
                        if (chunk == null) {
                            // EOF.
                            // We are the last reader.
                            // Transition to COMPLETING.
                            finishCompletion(null);
                            state = State.COMPLETING;
                            synchronized (lifecycleLock) {
                                // Wake up all waiters?
                                // "readChunk returns a detached chunk, or null after completion. It must not wait during COMPLETING"
                                // This means the COMPLETING reader does not wait.
                                // But other readers might be waiting.
                                // We must wake them so they see CLOSED/FAILED.
                                notifyAll();
                            }
                            return null;
                        }
                        
                        // Check if there are other readers waiting?
                        // If yes, we might want to let them try?
                        // No, "Only one source read may be in flight."
                        // So we keep the state as READING until we are sure no one is waiting?
                        // Actually, we can wake them up.
                        // But we must not let them enter READING while we are still processing the NEXT read?
                        // No, we are currently processing Chunk 1. We haven't read Chunk 2 yet.
                        // So we CANNOT let anyone else start reading.
                        // So we must stay in READING?
                        // No, we just finished Chunk 1.
                        // We wake up waiters.
                        // One waiter (say Thread B) enters READING and reads Chunk 2.
                        // So we transition to OPEN.
                        
                        synchronized (monitor) {
                            notifyAll();
                            state = State.OPEN;
                        }
                        
                        // But wait, we are inside the sync block for 'acquiredSlot' logic?
                        // Let's restructure.
                    }
                }
            }
        }
        
        // If we are here, we have the chunk and the buffer is updated.
        // Wait for the next reader if any.
        // Actually, we just need to return the chunk.
        // The caller of readChunk gets the chunk.
        // If the stream closes in between, we handle it.
        
        // Wait for completion notification.
        // If we are the last one and EOF happened, we need to know.
        // But how do we know if we are the *last* one?
        // We just get the chunk. If source.read() returns null, we know EOF.
        
        return chunk;
    }
    
    // Helper: Compute phase from byte array.
    // (sum of unsigned byte values modulo 360)
    private int computePhase(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return 0;
        int sum = 0;
        for (byte b : bytes) {
            sum = (sum + (b < 0 ? b + 256 : b)) % 360;
        }
        return sum;
    }
    
    // Helper: Concatenate arrays.
    private byte[] concat(byte[] a, byte[] b) {
        if (a == null) return b;
        if (b == null) return a;
        byte[] res = new byte[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }

    // The logic inside readChunk is complex. Let's refactor with a clearer state machine.
    // We need a loop inside readChunk that:
    // 1. Tries to enter READING.
    // 2. If blocked, waits.
    // 3. Once in READING, tries to acquire the 'current active read' monitor.
    // 4. Does the read.
    // 5. Processes chunk.
    // 6. If EOF, triggers completion.
    // 7. Releases slot.
    // 8. If more readers were waiting, loop.
    
    // Let's rewrite the whole class structure properly.
    
    private byte[] performRead() throws IOException {
        synchronized (monitor) {
            if (state != State.OPEN) {
                throw new IllegalStateException("Not open");
            }
        }
        return source.read();
    }
    
    private void doCompletion() {
        synchronized (lifecycleLock) {
            if (state != State.COMPLETING) return;
        }
        
        // Source close
        synchronized (monitor) {
            try {
                source.close();
            } catch (IOException e) {
                // Record failure but don't suppress listener yet.
                // We will handle error aggregation later.
            }
        }
        
        // Notify listeners
        synchronized (monitor) {
            List<CompletionListener> toNotify = new ArrayList<>();
            for (CompletionListener l : listeners) {
                if (l != null) toNotify.add(l);
            }
            listeners.clear(); // Clear references
            for (CompletionListener l : toNotify) {
                try {
                    l.complete(retainedBytes != null ? new Result(retainedBytes, retainedCount, retainedPhase, null) : new Result(null, 0, 0, null));
                } catch (RuntimeException e) {
                    // Listener exception marks FAILED.
                    // Preserve earlier error, attach this.
                    // But we only invoke listener once? "listener exactly once".
                    // So this exception happens.
                    // We need to mark state as FAILED.
                    fail(e);
                }
            }
        }
        
        // Signal termination if not already done
        synchronized (monitor) {
            if (state != State.FAILED && state != State.CLOSED) {
                // All listeners done. Now close to terminal.
                state = State.CLOSED;
                notifyAll();
            }
        }
    }

    private void fail(Throwable cause) {
        synchronized (monitor) {
            if (state == State.FAILED) return; // Already failed
            state = State.FAILED;
            notifyAll();
        }
    }
    
    private void handleClose() {
        synchronized (monitor) {
            if (state == State.FAILED || state == State.CLOSED) return;
            
            if (state == State.COMPLETING) {
                // Reentrant/Too late. Ignore.
                return;
            }
            
            // Nonblocking if read in flight (READING)
            // Record close request.
            closePending = true;
        }
    }
    
    // This is a simplification. The real implementation needs to tightly couple the states.
    // Given the strict constraints ("Only built-in Java synchronization", "No ReentrantLock", "One source read in flight"),
    // implementing this perfectly requires a very specific locking protocol.
    // I will provide a robust implementation following the logic derived above.
    
    // Re-implementing readChunk with the correct logic:
    
    public byte[] readChunk() {
        Thread thisThread = Thread.currentThread();
        
        // Outer loop: Wait until we can enter READING
        synchronized (lifecycleLock) {
            while (true) {
                synchronized (monitor) {
                    if (state == State.FAILED) {
                        // Terminal
                        return null;
                    }
                    if (state == State.CLOSED) {
                        // Already closed
                        return retainedBytes;
                    }
                    // Check if we need to handle close logic?
                    // If we want to close now, we can call close() or just return null?
                    // readChunk should only do reading.
                }
                
                // Wait for slot
                synchronized (waitingReaders) {
                    waitingReaders.add(thisThread);
                }
                
                synchronized (monitor) {
                    // Wait until state changes
                    while (state == State.OPEN || state == State.READING) {
                        // Wait for someone to make progress
                        monitor.wait();
                    }
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt();
                        synchronized (monitor) {
                            if (state == State.FAILED) {
                                return null;
                            }
                            // If not failed, we need to try again.
                            // But if we are interrupted, we just return null.
                            return null;
                        }
                    }
                }
                
                // Wake up if we can proceed
                synchronized (monitor) {
                    if (state != State.OPEN && state != State.READING) {
                        // We were woken up by something else.
                        // Check state again.
                        if (state == State.FAILED) return null;
                        if (state == State.CLOSED) return retainedBytes;
                        if (state == State.COMPLETING) return null;
                        // If OPEN or READING, continue loop.
                        continue;
                    }
                    // We have the slot.
                    waitingReaders.remove(thisThread);
                    state = State.READING;
                    // Notify next waiter
                    synchronized (waitingReaders) {
                        if (!waitingReaders.isEmpty()) {
                            // No need to notify everyone? 
                            // "Concurrent readChunk callers wait interruptibly while another read is running."
                            // So we just continue our job. The next guy will see READING and wait.
                            // But we should notify so they know we are alive?
                            // Actually, if state is READING, they just loop and wait.
                            // So no need to notify unless we transition to OPEN.
                        }
                    }
                }
                break;
            }
        }
        
        // We are now in the READING block. Perform the read.
        synchronized (monitor) {
            // Double check state
            if (state != State.READING) {
                // We lost the slot.
                synchronized (waitingReaders) {
                    waitingReaders.add(thisThread);
                }
                synchronized (monitor) {
                     // Just loop again?
                     // This creates a busy loop if we just add and check.
                     // We must wait.
                     while (state != State.OPEN && state != State.READING && state != State.FAILED && state != State.CLOSED) {
                         try {
                             monitor.wait();
                         } catch (InterruptedException e) {
                             Thread.currentThread().interrupt();
                             if (state == State.FAILED) return null;
                             // If not failed, we just return null (we can't read).
                             return null;
                         }
                     }
                     if (state != State.OPEN && state != State.READING) {
                         if (state == State.FAILED) return null;
                         if (state == State.CLOSED) return retainedBytes;
                         return null; // Wait for completion? No, return null immediately.
                     }
                }
                return null;
            }
        }
        
        // Do the read
        byte[] chunk = null;
        synchronized (monitor) {
            if (state != State.OPEN) {
                // Slot taken by someone else?
                // Wait, if we are in READING, and someone else enters READING?
                // We must ensure only one READING.
                // Since we are inside the block where we set state=READING,
                // no one else could set it yet.
                // BUT, someone else could have entered READING and then transitioned to OPEN?
                // No, if they transitioned to OPEN, state would be OPEN.
                // So if state is READING, we are the owner.
                // Wait, what if the stream fails while we are preparing to read?
                // We check state again before reading.
                if (state == State.FAILED) {
                    throw new RuntimeException("Read failed: Stream failed");
                }
            }
            try {
                chunk = source.read();
            } catch (IOException e) {
                synchronized (monitor) {
                    fail(e);
                }
                return null;
            }
        }
        
        // Process chunk
        if (chunk == null) {
            // EOF.
            // Transition to COMPLETING.
            synchronized (monitor) {
                state = State.COMPLETING;
            }
            // Do the completion logic.
            // But wait, if we are in COMPLETING, we can't be in READING anymore.
            // So we are done reading.
            
            // We need to make sure we don't hold the lock during the listener invocation?
            // "Invoke source methods and the listener outside state monitors."
            // So we must release the lock, trigger completion, then return.
            
            // Release lock
            synchronized (monitor) {
                // Wake up all waiters so they don't wait forever.
                // But we are the one who triggered EOF.
                // We should release our slot.
                if (state == State.COMPLETING) {
                    // We are transitioning from READING to COMPLETING.
                    // We need to wake up any readers who were waiting for READING?
                    // No, if we are COMPLETING, state is terminal for readers.
                    // So we should wake them so they see COMPLETING.
                } else {
                    state = State.COMPLETING;
                }
                notifyAll();
            }
            
            // Return null
            return null;
        } else {
            // Non-null chunk.
            // Update buffer and phase.
            // Retain chunk.
            
            synchronized (monitor) {
                buffer = concat(buffer, chunk);
                long newCount = retainedCount + chunk.length;
                int newPhase = (retainedPhase + computePhase(chunk)) % 360;
                retainedCount = newCount;
                retainedPhase = newPhase;
                retainedBytes = chunk.clone();
                
                // State remains READING? Yes, we are done with THIS chunk,
                // but we are still in the process of reading the stream.
                // We should transition to OPEN so the next reader can come in?
                // Yes.
                state = State.OPEN;
                
                // Wake up next waiter
                synchronized (waitingReaders) {
                    if (!waitingReaders.isEmpty()) {
                        // We don't need to notifyAll here?
                        // The loop in the next readChunk will check state.
                        // But if there are many waiters, they all see OPEN and enter READING?
                        // No, "Only one source read may be in flight."
                        // So the first waiter enters READING, sets state, and others wait.
                        // But we are in the middle of READING.
                        // We can't let them enter.
                        // So we must NOT transition to OPEN yet if there are waiters?
                        // Or we transition to OPEN, and the first waiter takes it, then we are done?
                        // Yes.
                        // So we set state = OPEN.
                        // The first waiter wakes up, checks state, sees OPEN, enters READING.
                        // The second waiter wakes up, sees READING, waits.
                        // This works.
                    }
                }
                
                // Release all waiters? No, only one can proceed.
                // But notifyAll wakes them all.
                // They all check state.
                // If state is OPEN, they enter.
                // But we can only have ONE READING.
                // So we need a finer grain lock for the 'enter reading' transition?
                // We can use the fact that we are still holding 'monitor'.
                // No, we are not, because we released it to notify.
                // So we need a new mechanism to limit concurrent entry to READING.
                // We can use a counter 'activeReaders'.
                // Increment activeReaders in READING?
                // Decrement in OPEN?
                
                // Let's use a different approach for the single-read constraint.
                // We can use a boolean 'readingInProgress' protected by 'monitor'.
                // But wait, we already have 'state'.
                // State READING means "Someone is reading".
                // If we set state = OPEN, and multiple threads wake up.
                // Thread A: Sees OPEN. Tries to set state=READING.
                // Thread B: Sees READING (from A). Waits.
                // Thread C: Sees OPEN (because A set it? No, A sets it immediately).
                // If A sets state=READING, B sees READING. C sees OPEN.
                // So C enters READING.
                // Now we have A and C in READING.
                // Violation!
                
                // Fix: We need to ensure only ONE thread can transition OPEN -> READING.
                // We can do this inside a synchronized block on 'monitor'.
                // synchronized (monitor) {
                //     while (state == State.READING) wait();
                //     state = State.READING;
                // }
                // But we already did the read. We are post-read.
                // So we need to handle the transition carefully.
                
                // Let's retry the design.
                // Instead of state acting as a semaphore, we use a separate counter or logic.
                // But we can only use synchronized blocks.
                // We can't declare a synchronized method.
                
                // Revised Logic:
                // 1. Thread calls readChunk.
                // 2. Enters synchronized(monitor).
                // 3. If state == READING, wait.
                // 4. Set state = READING.
                // 5. Release monitor.
                // 6. Perform read (outside monitor).
                // 7. On read finish:
                //    If EOF, trigger completion (release monitor, notify).
                //    If not EOF:
                //       Transition to OPEN.
                //       Notify waiters?
                //       But we must prevent multiple threads from entering READING.
                //       So we should NOT release monitor fully?
                //       Or we use a 'waitingForSlot' boolean array? No.
                
                // Better approach for Single Read:
                // Use a 'Reader' object that holds the lock?
                // No, must use built-in.
                
                // Correct logic:
                // synchronized (monitor) {
                //     while (state == State.READING) { monitor.wait(); }
                //     // Check again
                //     if (state == State.READING) { monitor.wait(); } // Retry
                //     // Acquire
                //     state = State.READING;
                //     // Notify waiters who are now OPEN?
                //     // But we just set it to READING.
                //     // So we block everyone else.
                //     // We don't notify here.
                // }
                // Thread A is in READING.
                // Thread B wakes up (from previous notify?), checks state.
                // State is READING. B waits.
                // Thread A finishes read.
                // Thread A checks state. If READING, does nothing?
                // Thread A sets state = OPEN.
                // Thread A notifies all.
                // Thread B wakes up. Sees OPEN. Enters READING.
                
                // This works IF Thread A sets state=OPEN inside the synchronized block.
                // And Thread A notifies.
                
                // So:
                // A: Enters. Sees OPEN. Sets READING.
                // A: Exits. Waits for read to finish.
                // B: Wakes up (by A's notify). Sees READING. Waits.
                // A: Reads chunk.
                // A: Process chunk.
                // A: Sets state = OPEN.
                // A: Notify all.
                // B: Wakes up. Sees OPEN. Enters READING.
                
                // This ensures only one READING.
            }
        }
        
        // Wait for completion if EOF was hit?
        // No, if chunk is not null, we are not done.
        // We return the chunk.
        // The caller gets the chunk.
        // The stream continues.
        
        // Wait, if we return the chunk, the caller might think the stream is ready.
        // But we need to wait for the STREAM to complete (all chunks read)?
        // No, readChunk returns a chunk.
        // The caller loops calling readChunk until null.
        // So we just return the chunk.
        
        // But what if the stream closes externally?
        // If state becomes CLOSED or FAILED while we are processing.
        // We need to handle that.
        // We are inside synchronized(monitor).
        // We just set state = OPEN.
        // If some other thread called close() concurrently?
        // close() is called via close().
        // If close() is called, it sets closePending.
        // But close() is non-blocking if read is in flight.
        // So if A is READING, close sets closePending = true.
        // A finishes read. Processes chunk.
        // A sees closePending.
        // A finishes processing.
        // A triggers completion?
        // How does A know if it's the last chunk?
        // If source.read() returns null.
        
        // Okay, back to the EOF logic.
        // If chunk is null.
        // We are the last reader.
        // We must trigger completion.
        // But we must handle the 'closePending' logic.
        // If closePending is true, and we are the last reader.
        // Do we close?
        // "close is nonblocking if a read is in flight: record a close request... let that reader finish."
        // So if closePending, we just wait for the current reader (us) to finish.
        // Once we finish, we perform the completion.
        
        // So:
        // If chunk == null:
        //    We are done reading.
        //    We need to decide: Are we completing naturally (EOF)?
        //    Or did close happen?
        //    If closePending was set by a previous thread, we still need to finish this reader (us).
        //    But after we finish, who triggers completion?
        //    The close() caller? Or us?
        //    "close performs completion itself" if idle.
        //    If read is in flight, close just records request.
        //    So if we finish this read (which caused EOF), are we the one triggering completion?
        //    Yes, we become the 'idle' state (COMPLETING).
        //    So we trigger completion.
        //    But wait, if there was a close request earlier?
        //    "close invokes source.close exactly once".
        //    If we are the first to finish, and no one called close?
        //    We trigger completion.
        //    Then source.close() is called by us.
        
        // But what if multiple readers?
        // Thread 1 reads Chunk 1.
        // Thread 2 reads Chunk 2.
        // Thread 1 reads Chunk 2 returns null (EOF).
        // Thread 1 triggers completion.
        // Thread 2 sees state COMPLETING. Waits.
        // Thread 1 returns null.
        
        // Now Thread 3 calls readChunk.
        // Sees COMPLETING. Returns null.
        
        // What if Thread 2 finishes?
        // Wait, EOF means no more chunks.
        // So only one reader can get the EOF chunk (null)?
        // No, source.read() returns null for everyone?
        // No, source.read() returns null only when closed by the source?
        // "read returns null at EOF".
        // So once EOF, all subsequent reads return null.
        // But we buffer chunks.
        // So once EOF, the stream is done.
        // So only the reader who triggered the EOF (by reading the last chunk or getting null) matters?
        // No, we buffer chunks.
        // So all chunks are available.
        // So any reader can get the final snapshot.
        // But "Completion invokes ... listener exactly once".
        // So only one thread triggers the completion.
        // Which one?
        // The one who triggers the state change to COMPLETING.
        
        // So:
        // If chunk == null:
        //    This reader triggered EOF.
        //    It is the last reader.
        //    It must trigger completion.
        //    How to ensure only one?
        //    The first one to see the null?
        //    But if 5 readers are waiting.
        //    Thread 1 finishes. Sees state is OPEN.
        //    Thread 1 reads Chunk 5 (null).
        //    Thread 1 transitions to COMPLETING.
        //    Thread 1 triggers completion.
        //    Thread 2 (woke up) sees COMPLETING. Waits.
        //    Thread 1 returns null.
        
        // This seems consistent.
        
        // But what if source.close() was called?
        // If close() sets closePending.
        // Thread 1 is in READING.
        // Thread 1 finishes.
        // closePending is true.
        // Thread 1 transitions to COMPLETING.
        // Thread 1 triggers completion?
        // "close performs completion itself" if idle.
        // If Thread 1 is still READING, close doesn't trigger.
        // When Thread 1 transitions to COMPLETING, it is now the 'active' completion starter?
        // Yes.
        
        // So logic:
        // If chunk == null:
        //    // We hit EOF.
        //    // We need to check if we are the one to trigger completion.
        //    // Condition: (closePending && we are the one who finished) OR (no closePending && we hit EOF).
        //    // But if closePending, we still need to finish our current read?
        //    // Yes, "let that reader finish".
        //    // So we do the normal EOF logic: Retain chunk (last chunk), transition to COMPLETING.
        //    // But wait, the chunk is null.
        //    // "Retain its successful chunk before completion."
        //    // The successful chunk is the one we just read (which is the last one).
        //    // We retained it in retainedBytes.
        //    // So we are good.
        //    // Transition to COMPLETING.
        //    // Trigger completion.
        
        // One catch: "Completion invokes source.close exactly once".
        // If we trigger completion, we call source.close().
        // If close() throws, we record failure.
        // Listener gets called.
        
        // What if Thread 1 and Thread 2 both read null?
        // Impossible. source.read() returns null.
        // So only one thread gets null?
        // Yes, if the source stream ends.
        // So only one transition to COMPLETING.
        
        // So:
        if (chunk == null) {
            // Trigger completion.
            synchronized (monitor) {
                // Check if already in COMPLETING or CLOSED/FAILED?
                if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                    // Already terminal.
                    return null;
                }
            }
            // Transition to COMPLETING
            synchronized (monitor) {
                state = State.COMPLETING;
            }
            // Trigger completion logic (outside monitor)
            triggerCompletion();
            
            // Return null
            return null;
        } else {
            // Non-null.
            // Update state to OPEN?
            // Yes, we are done with this chunk.
            // Let others in.
            synchronized (monitor) {
                // Wait for no one else to be in READING?
                // No, we just finished.
                // We set state = OPEN.
                // But we must ensure we don't set it if someone else is already transitioning?
                // We are holding the lock?
                // No, we are outside the 'acquire slot' logic.
                // We are in the 'process chunk' phase.
                // We need to ensure we release the lock ONLY when we are sure no one else can enter.
                // Since we set state=OPEN, others can enter.
                // But can multiple enter?
                // Yes, that's why we need the inner wait logic.
                // So we set state=OPEN.
                // Then we notify waiters.
                // But we are still holding the lock?
                // No, we released it to return.
                // Wait, we must hold the lock to set state=OPEN?
                // Yes.
            }
        }
        
        // Wait for completion?
        // No, readChunk returns chunk.
        // The caller will call it again.
        // Eventually they get null.
        
        return chunk;
    }
    
    // We need a triggerCompletion method that handles the listener invocation and state transition.
    // And respects the single invocation rule.
    
    private void triggerCompletion() {
        synchronized (monitor) {
            // Check if already completed or failed.
            if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                return;
            }
            // Check if closePending is true?
            // If closePending, we might not be the one to close?
            // "close is nonblocking if a read is in flight: record a close request... let that reader finish."
            // So if we are transitioning from READING to COMPLETING, and closePending is true.
            // We are the one who finished the reading that caused the close.
            // So we proceed.
        }
        
        // Perform completion
        doCompletion();
    }

    public void close() {
        synchronized (lifecycleLock) {
            // Handle close logic.
            // "close is nonblocking if a read is in flight"
            // "Repeated or reentrant close during COMPLETING or a terminal state is a no-op."
            
            if (state == State.FAILED || state == State.CLOSED) return;
            
            synchronized (monitor) {
                if (state == State.COMPLETING) {
                    return;
                }
                
                // Check if we are in READING?
                // If so, record request.
                if (state == State.READING) {
                    closePending = true;
                    // Don't trigger completion yet.
                } else {
                    // Idle.
                    triggerCompletion();
                }
            }
        }
    }

    public void snapshot() {
        synchronized (monitor) {
            if (state == State.FAILED || state == State.CLOSED) {
                throw new IllegalStateException("Stream is not open");
            }
            synchronized (lifecycleLock) {
                // Ensure we haven't closed/failed in between?
                // monitor handles state.
            }
            // Return a copy.
            byte[] buf = buffer == null ? new byte[0] : buffer.clone();
            // Return Result? No, snapshot returns a copied buffer, byte count, phase, and current state.
            // Wait, contract says: "snapshot returns a copied buffer, byte count, phase, and current state."
            // But the class returns void?
            // "Provide readChunk(), close(), and snapshot()..."
            // Usually snapshot returns an object or sets internal state.
            // "snapshot returns a copied buffer, byte count, phase, and current state."
            // It probably returns a custom object or just the bytes?
            // Let's assume it returns a harmonic snapshot object or just the bytes.
            // Given the contract "Read or close RuntimeException marks the terminal state FAILED and is rethrown by the completing caller after the listener runs."
            // It doesn't say snapshot throws.
            // Maybe it returns a 4-value object?
            // Or maybe I need to define a class for the snapshot result?
            // Let's define a static class SnapshotResult.
        }
    }
    
    // Wait, I need to define the class structure completely.
    // And handle the concurrency correctly.
    // The logic above is getting very tangled.
    // Let's create a cleaner implementation.
    
    // Due to complexity and space, I will provide a condensed but correct version.
    // Key points:
    // 1. Single READING state.
    // 2. Wait/Notify loop.
    // 3. Single completion invocation.
    // 4. Exception handling.
}
