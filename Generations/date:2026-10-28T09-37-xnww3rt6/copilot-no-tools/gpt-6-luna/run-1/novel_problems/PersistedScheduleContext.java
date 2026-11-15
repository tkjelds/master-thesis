@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class Reservation {
        private final long id;
        private final long start;
        private final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long getId() {
            return id;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }

    public static final class ScheduleSnapshot {
        private final Reservation[] reservations;
        private final long revision;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flusherActive;
        private final RuntimeException persistenceFailure;

        private ScheduleSnapshot(
                Reservation[] reservations,
                long revision,
                long committedRevision,
                long durableRevision,
                boolean flusherActive,
                RuntimeException persistenceFailure) {
            this.reservations = copyReservations(reservations);
            this.revision = revision;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.persistenceFailure = persistenceFailure;
        }

        public Reservation[] getReservations() {
            return copyReservations(reservations);
        }

        public long getRevision() {
            return revision;
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public boolean isFlusherActive() {
            return flusherActive;
        }

        public RuntimeException getPersistenceFailure() {
            return persistenceFailure;
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;
    private Reservation[] reservations = new Reservation[0];
    private long nextReservationId = 1L;
    private long committedRevision;
    private long durableRevision;
    private boolean flusherActive;
    private Thread flusherThread;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        synchronized (monitor) {
            rejectMutationAfterFailure();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            for (int i = 0; i < reservations.length; i++) {
                Reservation existing = reservations[i];
                if (start < existing.end && existing.start < end) {
                    return -1L;
                }
            }

            if (nextReservationId == Long.MAX_VALUE) {
                throw new ArithmeticException("reservation ID counter overflow");
            }
            ensureRevisionCanAdvance();
            if (reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("too many reservations");
            }

            long id = nextReservationId;
            Reservation[] updated = new Reservation[reservations.length + 1];
            for (int i = 0; i < reservations.length; i++) {
                updated[i] = reservations[i];
            }
            updated[reservations.length] = new Reservation(id, start, end);

            reservations = updated;
            nextReservationId = id + 1L;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            rejectMutationAfterFailure();
            if (reservationId <= 0L) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int found = -1;
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i].id == reservationId) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }

            ensureRevisionCanAdvance();
            Reservation[] updated = new Reservation[reservations.length - 1];
            for (int i = 0, j = 0; i < reservations.length; i++) {
                if (i != found) {
                    updated[j++] = reservations[i];
                }
            }

            reservations = updated;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        Thread current = Thread.currentThread();
        long targetRevision;

        synchronized (monitor) {
            targetRevision = committedRevision;
            if (flusherActive && flusherThread == current) {
                throw new IllegalStateException("flush cannot be called recursively by the sink");
            }

            while (durableRevision < targetRevision) {
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (!flusherActive) {
                    flusherActive = true;
                    flusherThread = current;
                    break;
                }
                monitor.wait();
            }

            if (durableRevision >= targetRevision) {
                return;
            }
        }

        runAsFlusher(targetRevision, current);
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return new ScheduleSnapshot(
                    reservations,
                    committedRevision,
                    committedRevision,
                    durableRevision,
                    flusherActive,
                    persistenceFailure);
        }
    }

    private void runAsFlusher(long targetRevision, Thread current) {
        try {
            while (true) {
                ScheduleSnapshot pending;
                synchronized (monitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }

                    long revisionToPersist = committedRevision;
                    pending = new ScheduleSnapshot(
                            reservations,
                            revisionToPersist,
                            committedRevision,
                            durableRevision,
                            true,
                            persistenceFailure);
                }

                try {
                    sink.persist(pending);
                } catch (RuntimeException failure) {
                    synchronized (monitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = failure;
                        }
                    }
                    throw failure;
                }

                synchronized (monitor) {
                    if (pending.revision > durableRevision) {
                        durableRevision = pending.revision;
                    }
                    monitor.notifyAll();
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                }
            }
        } finally {
            synchronized (monitor) {
                if (flusherActive && flusherThread == current) {
                    flusherActive = false;
                    flusherThread = null;
                }
                monitor.notifyAll();
            }
        }
    }

    private void rejectMutationAfterFailure() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("persistence has failed", persistenceFailure);
        }
    }

    private void ensureRevisionCanAdvance() {
        if (committedRevision == Long.MAX_VALUE) {
            throw new ArithmeticException("committed revision counter overflow");
        }
    }

    private static Reservation[] copyReservations(Reservation[] source) {
        Reservation[] copy = new Reservation[source.length];
        for (int i = 0; i < source.length; i++) {
            Reservation reservation = source[i];
            copy[i] = new Reservation(reservation.id, reservation.start, reservation.end);
        }
        return copy;
    }
}
