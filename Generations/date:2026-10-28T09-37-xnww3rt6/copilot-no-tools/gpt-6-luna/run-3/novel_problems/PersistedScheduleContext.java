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
        public final Reservation[] reservations;
        public final long committedRevision;
        public final long durableRevision;
        public final boolean flushing;
        public final RuntimeException persistenceFailure;

        private ScheduleSnapshot(
                Reservation[] reservations,
                int size,
                long committedRevision,
                long durableRevision,
                boolean flushing,
                RuntimeException persistenceFailure) {
            this.reservations = new Reservation[size];
            for (int i = 0; i < size; i++) {
                this.reservations[i] = reservations[i];
            }
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.persistenceFailure = persistenceFailure;
        }

        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                copy[i] = reservations[i];
            }
            return copy;
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

        public RuntimeException getPersistenceFailure() {
            return persistenceFailure;
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;

    private Reservation[] reservations = new Reservation[0];
    private int reservationCount;
    private long committedRevision;
    private long durableRevision;
    private long lastReservationId;
    private boolean flushing;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        synchronized (monitor) {
            ensureWritable();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            for (int i = 0; i < reservationCount; i++) {
                Reservation existing = reservations[i];
                if (existing.start < end && start < existing.end) {
                    return -1L;
                }
            }

            ensureRevisionCanAdvance();
            if (lastReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID counter exhausted");
            }
            if (reservationCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("reservation capacity exhausted");
            }

            long id = lastReservationId + 1L;
            Reservation reservation = new Reservation(id, start, end);
            Reservation[] nextReservations = reservations;

            if (reservationCount == reservations.length) {
                int newCapacity;
                if (reservations.length == 0) {
                    newCapacity = 4;
                } else if (reservations.length > Integer.MAX_VALUE / 2) {
                    newCapacity = Integer.MAX_VALUE;
                } else {
                    newCapacity = reservations.length * 2;
                }

                if (newCapacity <= reservationCount) {
                    throw new IllegalStateException("reservation capacity exhausted");
                }

                nextReservations = new Reservation[newCapacity];
                for (int i = 0; i < reservationCount; i++) {
                    nextReservations[i] = reservations[i];
                }
            }

            int insertAt = 0;
            while (insertAt < reservationCount
                    && nextReservations[insertAt].start < start) {
                insertAt++;
            }
            for (int i = reservationCount; i > insertAt; i--) {
                nextReservations[i] = nextReservations[i - 1];
            }
            nextReservations[insertAt] = reservation;

            reservations = nextReservations;
            reservationCount++;
            lastReservationId = id;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            ensureWritable();

            int index = -1;
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }

            ensureRevisionCanAdvance();
            for (int i = index; i < reservationCount - 1; i++) {
                reservations[i] = reservations[i + 1];
            }
            reservations[reservationCount - 1] = null;
            reservationCount--;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        final long targetRevision;

        synchronized (monitor) {
            targetRevision = committedRevision;

            if (durableRevision >= targetRevision) {
                return;
            }
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }

            while (flushing) {
                monitor.wait();
                if (durableRevision >= targetRevision) {
                    return;
                }
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
            }

            flushing = true;
        }

        try {
            ScheduleSnapshot outgoing;
            synchronized (monitor) {
                outgoing = createSnapshot();
            }

            try {
                sink.persist(outgoing);
            } catch (RuntimeException failure) {
                synchronized (monitor) {
                    if (persistenceFailure == null) {
                        persistenceFailure = failure;
                    }
                }
                throw failure;
            }

            synchronized (monitor) {
                if (outgoing.committedRevision > durableRevision) {
                    durableRevision = outgoing.committedRevision;
                }
                monitor.notifyAll();
            }
        } finally {
            synchronized (monitor) {
                flushing = false;
                monitor.notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return createSnapshot();
        }
    }

    private ScheduleSnapshot createSnapshot() {
        return new ScheduleSnapshot(
                reservations,
                reservationCount,
                committedRevision,
                durableRevision,
                flushing,
                persistenceFailure);
    }

    private void ensureWritable() {
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "persistence has failed; schedule is no longer writable",
                    persistenceFailure);
        }
    }

    private void ensureRevisionCanAdvance() {
        if (committedRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("committed revision counter exhausted");
        }
    }
}
