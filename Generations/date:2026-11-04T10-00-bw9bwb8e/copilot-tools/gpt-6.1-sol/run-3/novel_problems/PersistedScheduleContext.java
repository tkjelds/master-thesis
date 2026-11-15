@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class ScheduleSnapshot {
        /**
         * A detached array ordered by start time. Changing its entries cannot
         * change the context or any other snapshot.
         */
        public final Reservation[] reservations;
        public final long committedRevision;
        public final long durableRevision;
        public final boolean flushing;
        public final RuntimeException failure;

        private ScheduleSnapshot(
                Reservation[] reservations,
                long committedRevision,
                long durableRevision,
                boolean flushing,
                RuntimeException failure) {
            this.reservations = reservations;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
        }
    }

    private static final long MAX_COUNTER = 0x7fffffffffffffffL;

    private final Object monitor = new Object();
    private final ScheduleSink sink;

    // All mutable state below is guarded by monitor.
    private Reservation[] reservations = new Reservation[0];
    private long lastReservationId;
    private long committedRevision;
    private long durableRevision;
    private Thread flusher;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    /**
     * Commits immediately in memory, returning -1 if the interval conflicts.
     * IDs are positive and never reused.
     */
    public long reserve(long start, long end) {
        synchronized (monitor) {
            requireHealthy();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            int position = 0;
            while (position < reservations.length
                    && reservations[position].start < start) {
                position++;
            }

            if ((position > 0 && reservations[position - 1].end > start)
                    || (position < reservations.length
                        && reservations[position].start < end)) {
                return -1;
            }

            requireRevisionCapacity();
            if (lastReservationId == MAX_COUNTER) {
                throw new IllegalStateException("Reservation ID counter exhausted");
            }
            if (reservations.length == 0x7fffffff) {
                throw new IllegalStateException("Reservation array capacity exhausted");
            }

            long id = lastReservationId + 1;
            Reservation[] updated = new Reservation[reservations.length + 1];
            for (int i = 0; i < position; i++) {
                updated[i] = reservations[i];
            }
            updated[position] = new Reservation(id, start, end);
            for (int i = position; i < reservations.length; i++) {
                updated[i + 1] = reservations[i];
            }

            reservations = updated;
            lastReservationId = id;
            committedRevision++;
            return id;
        }
    }

    /**
     * Commits removal immediately in memory; absent positive IDs return false.
     */
    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            requireHealthy();
            if (reservationId <= 0) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int position = 0;
            while (position < reservations.length
                    && reservations[position].id != reservationId) {
                position++;
            }
            if (position == reservations.length) {
                return false;
            }

            requireRevisionCapacity();
            Reservation[] updated = new Reservation[reservations.length - 1];
            for (int i = 0; i < position; i++) {
                updated[i] = reservations[i];
            }
            for (int i = position + 1; i < reservations.length; i++) {
                updated[i - 1] = reservations[i];
            }

            reservations = updated;
            committedRevision++;
            return true;
        }
    }

    /**
     * Waits interruptibly for another flusher, or persists a complete snapshot.
     * Only the revision captured upon entering the monitor must become durable.
     * The sink must not call flush recursively on this context.
     */
    public void flush() throws InterruptedException {
        Thread caller = Thread.currentThread();
        final long target;

        synchronized (monitor) {
            if (flusher == caller) {
                throw new IllegalStateException("Recursive flush is not permitted");
            }

            target = committedRevision;
            while (durableRevision < target) {
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (flusher == null) {
                    flusher = caller;
                    break;
                }
                monitor.wait();
            }

            if (durableRevision >= target) {
                return;
            }
        }

        // Every exit after claiming the role, including an Error, releases it.
        try {
            final ScheduleSnapshot pending;
            synchronized (monitor) {
                pending = copySnapshot();
            }

            try {
                sink.persist(pending);
            } catch (RuntimeException failure) {
                synchronized (monitor) {
                    persistenceFailure = failure;
                }
                throw failure;
            }

            synchronized (monitor) {
                // Commits made during persist are deliberately not included.
                durableRevision = pending.committedRevision;
                monitor.notifyAll();
            }
        } finally {
            synchronized (monitor) {
                flusher = null;
                monitor.notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return copySnapshot();
        }
    }

    // The following helpers require ownership of monitor.
    private void requireHealthy() {
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "Persistence has permanently failed", persistenceFailure);
        }
    }

    private void requireRevisionCapacity() {
        if (committedRevision == MAX_COUNTER) {
            throw new IllegalStateException("Committed revision counter exhausted");
        }
    }

    private ScheduleSnapshot copySnapshot() {
        Reservation[] copy = new Reservation[reservations.length];
        for (int i = 0; i < reservations.length; i++) {
            // Reservation objects are immutable; only the array needs copying.
            copy[i] = reservations[i];
        }
        return new ScheduleSnapshot(
                copy,
                committedRevision,
                durableRevision,
                flusher != null,
                persistenceFailure);
    }
}
