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
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flushing;
        private final RuntimeException failure;

        private ScheduleSnapshot(Reservation[] reservations,
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

        public Reservation[] getReservations() {
            return copyReservations(reservations);
        }

        public Reservation[] reservations() {
            return getReservations();
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long committedRevision() {
            return committedRevision;
        }

        public long getRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public long durableRevision() {
            return durableRevision;
        }

        public boolean isFlushing() {
            return flushing;
        }

        public boolean isFlusherActive() {
            return flushing;
        }

        public RuntimeException getFailure() {
            return failure;
        }

        public RuntimeException failure() {
            return failure;
        }
    }

    private final Object state = new Object();
    private final ScheduleSink sink;
    private Reservation[] reservations = new Reservation[0];
    private int reservationCount;
    private long committedRevision;
    private long durableRevision;
    private long nextReservationId = 1L;
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
        if (start >= end) {
            throw new IllegalArgumentException("reservation must have start < end");
        }

        synchronized (state) {
            throwIfPersistenceFailed();
            int insertionIndex = 0;
            while (insertionIndex < reservationCount
                    && reservations[insertionIndex].start < start) {
                insertionIndex++;
            }

            if (insertionIndex > 0
                    && reservations[insertionIndex - 1].end > start) {
                return -1L;
            }
            if (insertionIndex < reservationCount
                    && reservations[insertionIndex].start < end) {
                return -1L;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision exhausted");
            }
            if (nextReservationId == 0L) {
                throw new IllegalStateException("reservation ID space exhausted");
            }

            ensureCapacity(reservationCount + 1);
            long id = nextReservationId;
            if (id == Long.MAX_VALUE) {
                nextReservationId = 0L;
            } else {
                nextReservationId = id + 1L;
            }
            for (int i = reservationCount; i > insertionIndex; i--) {
                reservations[i] = reservations[i - 1];
            }
            reservations[insertionIndex] = new Reservation(id, start, end);
            reservationCount++;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0L) {
            throw new IllegalArgumentException("reservation ID must be positive");
        }

        synchronized (state) {
            throwIfPersistenceFailed();
            int found = -1;
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision exhausted");
            }
            for (int i = found; i < reservationCount - 1; i++) {
                reservations[i] = reservations[i + 1];
            }
            reservations[--reservationCount] = null;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        long targetRevision;
        synchronized (state) {
            targetRevision = committedRevision;
            if (flusherActive && flusherThread == Thread.currentThread()) {
                throw new IllegalStateException("flush cannot be called recursively by the sink");
            }

            while (true) {
                if (durableRevision >= targetRevision) {
                    return;
                }
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (!flusherActive) {
                    flusherActive = true;
                    flusherThread = Thread.currentThread();
                    break;
                }
                state.wait();
            }
        }

        boolean ownsFlusherRole = true;
        try {
            while (true) {
                ScheduleSnapshot toPersist;
                synchronized (state) {
                    if (durableRevision >= targetRevision) {
                        releaseFlusherRole();
                        ownsFlusherRole = false;
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }
                    toPersist = makeSnapshot(true);
                }

                try {
                    sink.persist(toPersist);
                } catch (RuntimeException e) {
                    synchronized (state) {
                        if (persistenceFailure == null) {
                            persistenceFailure = e;
                        }
                        releaseFlusherRole();
                        ownsFlusherRole = false;
                    }
                    throw persistenceFailure;
                }

                synchronized (state) {
                    if (toPersist.committedRevision > durableRevision) {
                        durableRevision = toPersist.committedRevision;
                    }
                    state.notifyAll();
                    if (durableRevision >= targetRevision) {
                        releaseFlusherRole();
                        ownsFlusherRole = false;
                        return;
                    }
                }
            }
        } finally {
            if (ownsFlusherRole) {
                synchronized (state) {
                    releaseFlusherRole();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (state) {
            return makeSnapshot(flusherActive);
        }
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("persistence has failed", persistenceFailure);
        }
    }

    private void ensureCapacity(int required) {
        if (required <= reservations.length) {
            return;
        }
        int newCapacity;
        if (reservations.length == 0) {
            newCapacity = 8;
        } else if (reservations.length <= Integer.MAX_VALUE / 2) {
            newCapacity = reservations.length * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }
        if (newCapacity < required) {
            throw new IllegalStateException("reservation capacity exhausted");
        }
        Reservation[] expanded = new Reservation[newCapacity];
        for (int i = 0; i < reservationCount; i++) {
            expanded[i] = reservations[i];
        }
        reservations = expanded;
    }

    private ScheduleSnapshot makeSnapshot(boolean flushing) {
        Reservation[] copy = new Reservation[reservationCount];
        for (int i = 0; i < reservationCount; i++) {
            Reservation reservation = reservations[i];
            copy[i] = new Reservation(reservation.id, reservation.start, reservation.end);
        }
        return new ScheduleSnapshot(copy, committedRevision, durableRevision,
                flushing, persistenceFailure);
    }

    private static Reservation[] copyReservations(Reservation[] source) {
        Reservation[] copy = new Reservation[source.length];
        for (int i = 0; i < source.length; i++) {
            Reservation reservation = source[i];
            copy[i] = new Reservation(reservation.id, reservation.start, reservation.end);
        }
        return copy;
    }

    private void releaseFlusherRole() {
        flusherActive = false;
        flusherThread = null;
        state.notifyAll();
    }
}
