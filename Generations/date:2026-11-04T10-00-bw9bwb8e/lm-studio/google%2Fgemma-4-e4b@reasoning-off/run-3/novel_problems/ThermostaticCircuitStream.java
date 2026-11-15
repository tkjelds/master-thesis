@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
    @interface ThreadSafe {}


    private volatile boolean closed = false;
    private long threshold;
    private long heat = 0;
    private volatile Mode mode = Mode.NOMINAL;
    private byte[] buffer = new byte[0];
    private int cursor = 0;

    public enum Mode {
        NOMINAL, OVERHEATED, CLOSED
    }

    /**
     * Constructs a new ThermostaticCircuitStream.
     * @param initialThreshold The positive long heat threshold.
     */
    public ThermostaticCircuitStream(long initialThreshold) {
        if (initialThreshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive.");
        }
        this.threshold = initialThreshold;
    }

    /**
     * Attempts to write data into the stream when cool.
     * Blocks until eligible, or if interrupted.
     * @param data The payload to write.
     * @throws InterruptedException if the waiting thread is interrupted.
     * @throws IllegalArgumentException if data is invalid or payload exceeds threshold upon trying to write.
     */
    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            return; // Per spec, no mutation occurs if input is invalid/empty.
        }

        if (data == null || data.length == 0) {
            return; // Also per spec, no mutation for null/empty data.
        }

        if (data.length > 0 && this.threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive.");
        }

        // Check for instantaneous rejection based on current state invariants before blocking
        if (this.closed) {
            throw new IllegalStateException("Stream is closed.");
        }

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Stream is closed.");
            }

            while (true) {
                // Predicate check: Eligible to write?
                // 1. Not closed (checked above, but double-check matters in loop)
                if (this.closed) {
                    throw new IllegalStateException("Stream is closed.");
                }

                // 2. Payload size validation (all-or-nothing)
                if (data == null || data.length == 0) {
                    return; // Successfully decided not to proceed with write (due to initial checks, this is unlikely unless we entered the loop weirdly)
                }

                // 3. Threshold check: Does this addition exceed the threshold?
                if (data.length > this.threshold) {
                    throw new IllegalArgumentException("Payload exceeds current threshold.");
                }

                // If we reach here, the write *could* proceed if cool. Wait for coolness.
                while (this.heat >= this.threshold || this.mode == Mode.OVERHEATED) {
                    // Wait condition: Either overheated, or writing would cause overflow (heat + data.length > threshold).
                    // Since we checked data.length <= threshold, the loop condition simplifies to waiting if heat >= threshold.
                    if (this.heat + data.length > this.threshold) {
                         // This case should technically be caught by the initial check if heat was already at threshold, 
                         // but we wait until it's safe to append. If we enter here and cannot write, we must wait/recheck.
                    }
                    this.wait(); // Wait while overheated or pending write would exceed threshold upon immediate append
                }

                // Predicates met: Append atomically and add heat.
                this.buffer = data; // Assume append is replacing/extending in concept, but specification implies append-only.
                                    // Since we don't have byte array resizing logic provided, we treat the new data as the content 
                                    // to be written upon success validation, mimicking a successful atomic append if the underlying structure supported it.
                                    // For this simulation based on constraints, we mimic appending by creating a new conceptual buffer state.
                // In a real scenario, buffer concatenation would happen here. For simplicity matching the strict constraints, 
                // we assume success means appending `data` logically. Since we don't have a dynamic array easily, 
                // we rely on the cursor tracking the *successful* write.
                
                // Simulate append: combine old buffer content (if any) + new data, and update cursor/buffer.
                // Given the constraints, we must assume a successful write updates the conceptual buffer and cursor.
                // If this is the first append, old buffer was empty/consumed conceptually.
                int currentSize = this.buffer == null ? 0 : this.buffer.length;
                int newSize = currentSize + data.length;

                byte[] newBuffer = new byte[newSize];
                if (currentSize > 0) {
                    System.arraycopy(this.buffer, 0, newBuffer, 0, currentSize);
                }
                System.arraycopy(data, 0, newBuffer, currentSize, data.length);
                this.buffer = newBuffer;

                // Update heat and cursor tracking (the read cursor must advance, but write only commits the append)
                this.heat += data.length;
                // Cursor remains where it was, waiting for a read operation to advance it into the new data.

                // Mode check after update
                if (this.heat >= this.threshold) {
                    this.mode = Mode.OVERHEATED;
                } else {
                    this.mode = Mode.NOMINAL;
                }

                // Notify potential readers waiting for NOMINAL mode or availability
                this.notifyAll(); 
                return; // Operation succeeded
            }
        }
    }

    /**
     * Reads bytes when available and in NOMINAL mode. Blocks if unavailable or overheated.
     * @param maxBytes The maximum number of bytes to attempt to read.
     * @return A copied byte array segment, or null if EOF/stream drain occurs during read.
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive.");
        }

        synchronized (this) {
            if (closed) {
                // Drain behavior on read from closed stream
                return drainRead(maxBytes);
            }

            while (true) {
                // Predicate check: Wait until unread bytes exist AND mode is NOMINAL.
                while (this.cursor >= this.buffer == null ? 0 : this.buffer.length || this.mode != Mode.NOMINAL) {
                    if (this.closed) {
                        // Transition to drain mode if closed while waiting for predicates
                        return drainRead(maxBytes);
                    }
                    this.wait(); // Wait until buffer has content or mode changes to NOMINAL
                }

                // Predicates met: Read atomically.
                int bytesToRead = Math.min(maxBytes, this.buffer == null ? 0 : (this.buffer.length - this.cursor));
                if (bytesToRead <= 0) {
                    // Should not happen if the wait condition passed, but safety check.
                    return null; 
                }

                // Consume bytes (generate no heat)
                byte[] result = new byte[bytesToRead];
                System.arraycopy(this.buffer, this.cursor, result, 0, bytesToRead);
                this.cursor += bytesToRead;

                // Check if EOF reached (i.e., cursor equals buffer length)
                if (this.cursor >= this.buffer == null ? 0 : this.buffer.length) {
                    // If cursor hits the end, mode might still be NOMINAL or transition out if write pushes it over.
                    // We return successfully, allowing the next read to check emptiness.
                }

                this.notifyAll(); // Wake up waiting writers/readers based on new cursor position
                return result;
            }
        }
    }
    
    private byte[] drainRead(int maxBytes) {
        synchronized (this) {
            if (closed && this.cursor >= (this.buffer == null ? 0 : this.buffer.length)) {
                return null; // Truly drained and closed
            }
            
            // Drain unread bytes regardless of heat/mode upon closing.
            int available = this.buffer == null ? 0 : (this.buffer.length - this.cursor);
            int bytesToCopy = Math.min(maxBytes, available);

            if (bytesToCopy > 0) {
                byte[] result = new byte[bytesToCopy];
                System.arraycopy(this.buffer, this.cursor, result, 0, bytesToCopy);
                this.cursor += bytesToCopy; // Consume the data atomically upon draining
                return result;
            }
            return null;
        }
    }


    /**
     * Reduces the stored heat, down to zero. Does not drop below zero or mutate if already zero.
     */
    public void cool(long amount) {
        if (amount <= 0) return;

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Stream is closed.");
            }

            if (this.heat > 0) {
                long newHeat = this.heat - amount;
                if (newHeat < 0) {
                    this.heat = 0;
                } else {
                    this.heat = newHeat;
                }

                // Recompute mode based on new heat level
                if (this.heat >= this.threshold) {
                    this.mode = Mode.OVERHEATED;
                } else {
                    this.mode = Mode.NOMINAL;
                }
            }
            // No notification needed as cool operation doesn't change the buffer/cursor state monitored by readers.
        }
    }

    /**
     * Sets a new positive threshold. Triggers mode recomputation and wakes waiters if the change enables transition.
     * @param newThreshold The positive long threshold.
     */
    public void setThreshold(long newThreshold) {
        if (newThreshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive.");
        }

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Stream is closed.");
            }

            long oldThreshold = this.threshold;
            this.threshold = newThreshold;

            // Recompute mode based on existing heat and new threshold
            if (this.heat >= this.threshold) {
                this.mode = Mode.OVERHEATED;
            } else {
                this.mode = Mode.NOMINAL;
            }

            // Notify waiters whose predicate might now be satisfied due to threshold change
            this.notifyAll();
        }
    }

    /**
     * Closes the stream, causing all future reads to drain and return null.
     */
    public void close() {
        synchronized (this) {
            if (!this.closed) {
                this.closed = true;
                this.mode = Mode.CLOSED;
                // Wake up all threads waiting on any predicate (read/write) so they observe the closed state.
                this.notifyAll(); 
            }
        }
    }

    /**
     * Returns a coherent snapshot of the stream's state. Does not consume or mutate.
     */
    public Snapshot snapshot() {
        synchronized (this) {
            // Return copies of volatile/shared primitives to ensure coherence at the moment of snapshotting.
            return new Snapshot(
                    this.buffer == null ? new byte[0] : this.buffer, 
                    this.cursor, 
                    this.heat, 
                    this.threshold, 
                    this.mode, 
                    this.closed
            );
        }
    }

    public static class Snapshot {
        final byte[] bufferSnapshot;
        final int cursorSnapshot;
        final long heatSnapshot;
        final long thresholdSnapshot;
        final Mode modeSnapshot;
        final boolean isClosedSnapshot;

        public Snapshot(byte[] buffer, int cursor, long heat, long threshold, Mode mode, boolean closed) {
            this.bufferSnapshot = buffer; // Storing the current buffer state copy
            this.cursorSnapshot = cursor;
            this.heatSnapshot = heat;
            this.thresholdSnapshot = threshold;
            this.modeSnapshot = mode;
            this.isClosedSnapshot = closed;
        }
    }
}
