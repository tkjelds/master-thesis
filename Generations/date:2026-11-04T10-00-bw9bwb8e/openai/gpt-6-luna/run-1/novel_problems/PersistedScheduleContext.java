@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class ScheduleSnapshot {
        private final long[] reservationIds;
        private final long[] starts;
        private final long[] ends;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flushing;
        private final RuntimeException failure;

        private ScheduleSnapshot(
                long[] reservationIds,
                long[] starts,
                long[] ends,
                long committedRevision,
                long durableRevision,
                boolean flushing,
                RuntimeException failure) {
            this.reservationIds = reservationIds;
            this.starts = starts;
            this.ends = ends;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
        }

        public int getReservationCount() {
            return reservationIds.length;
        }

        public long getReservationId(int index) {
            return reservationIds[index];
        }

        public long getStart(int index) {
            return starts[index];
        }

        public long getEnd(int index) {
            return ends[index];
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public boolean isFlushing() {
            return flushing;
        }

        public RuntimeException getFailure() {
            return failure;
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;

    private long[] reservationIds = new long[0];
    private long[] starts = new long[0];
    private long[] ends = new long[0];
    private int reservationCount;

    private long nextId = 1L;
    private boolean idExhausted;
    private long committedRevision;
    private long durableRevision;
    private boolean flushing;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    /**
     * Commits a reservation in memory. Returns -1 if it overlaps an existing
     * reservation; otherwise returns its unique positive ID.
     */
    public long reserve(long start, long end) {
        synchronized (monitor) {
            throwIfPersistenceFailed();

            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            for (int i = 0; i < reservationCount; i++) {
                if (start < ends[i] && starts[i] < end) {
                    return -1L;
                }
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new ArithmeticException("Committed revision overflow");
            }
            if (idExhausted) {
                throw new ArithmeticException("Reservation ID overflow");
            }
            if (reservationCount == Integer.MAX_VALUE) {
                throw new ArithmeticException("Reservation count overflow");
            }

            ensureCapacity(reservationCount + 1);

            long id = nextId;
            int insertAt = 0;
            while (insertAt < reservationCount && starts[insertAt] < start) {
                insertAt++;
            }

            for (int i = reservationCount; i > insertAt; i--) {
                reservationIds[i] = reservationIds[i - 1];
                starts[i] = starts[i - 1];
                ends[i] = ends[i - 1];
            }

            reservationIds[insertAt] = id;
            starts[insertAt] = start;
            ends[insertAt] = end;
            reservationCount++;

            if (id == Long.MAX_VALUE) {
                idExhausted = true;
            } else {
                nextId = id + 1L;
            }
            committedRevision++;
            return id;
        }
    }

    /**
     * Removes an existing reservation and commits the change in memory.
     */
    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            throwIfPersistenceFailed();

            if (reservationId <= 0L) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int index = -1;
            for (int i = 0; i < reservationCount; i++) {
                if (reservationIds[i] == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new ArithmeticException("Committed revision overflow");
            }

            for (int i = index; i < reservationCount - 1; i++) {
                reservationIds[i] = reservationIds[i + 1];
                starts[i] = starts[i + 1];
                ends[i] = ends[i + 1];
            }
            reservationCount--;
            committedRevision++;
            return true;
        }
    }

    /**
     * Persists a complete schedule snapshot covering the committed revision
     * observed when this call began. Waiting for another flusher is interruptible.
     */
    public void flush() throws InterruptedException {
        final long targetRevision;
        boolean ownsFlusher = false;

        synchronized (monitor) {
            targetRevision = committedRevision;

            while (true) {
                if (durableRevision >= targetRevision) {
                    return;
                }
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (!flushing) {
                    flushing = true;
                    ownsFlusher = true;
                    break;
                }
                monitor.wait();
            }
        }

        try {
            while (true) {
                final ScheduleSnapshot snapshotToPersist;
                final long snapshotRevision;

                synchronized (monitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }

                    snapshotRevision = committedRevision;
                    snapshotToPersist = copySnapshotLocked();
                }

                try {
                    sink.persist(snapshotToPersist);
                } catch (RuntimeException exception) {
                    RuntimeException retained;
                    synchronized (monitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = exception;
                        }
                        retained = persistenceFailure;
                        monitor.notifyAll();
                    }
                    throw retained;
                }

                synchronized (monitor) {
                    if (snapshotRevision > durableRevision) {
                        durableRevision = snapshotRevision;
                    }
                    monitor.notifyAll();
                }
            }
        } finally {
            if (ownsFlusher) {
                synchronized (monitor) {
                    flushing = false;
                    monitor.notifyAll();
                }
            }
        }
    }

    /**
     * Returns a detached, coherent view of the current state.
     */
    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return copySnapshotLocked();
        }
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("Schedule persistence has failed", persistenceFailure);
        }
    }

    private void ensureCapacity(int required) {
        if (required <= reservationIds.length) {
            return;
        }

        int oldCapacity = reservationIds.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 4;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }
        if (newCapacity < required) {
            newCapacity = required;
        }

        long[] newIds = new long[newCapacity];
        long[] newStarts = new long[newCapacity];
        long[] newEnds = new long[newCapacity];

        for (int i = 0; i < reservationCount; i++) {
            newIds[i] = reservationIds[i];
            newStarts[i] = starts[i];
            newEnds[i] = ends[i];
        }

        reservationIds = newIds;
        starts = newStarts;
        ends = newEnds;
    }

    private ScheduleSnapshot copySnapshotLocked() {
        long[] idsCopy = new long[reservationCount];
        long[] startsCopy = new long[reservationCount];
        long[] endsCopy = new long[reservationCount];

        for (int i = 0; i < reservationCount; i++) {
            idsCopy[i] = reservationIds[i];
            startsCopy[i] = starts[i];
            endsCopy[i] = ends[i];
        }

        return new ScheduleSnapshot(
                idsCopy,
                startsCopy,
                endsCopy,
                committedRevision,
                durableRevision,
                flushing,
                persistenceFailure);
    }
}
