@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class ScheduleReservation {
        private final long id;
        private final long start;
        private final long end;

        private ScheduleReservation(long id, long start, long end) {
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
        private final ScheduleReservation[] reservations;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flusherActive;
        private final RuntimeException failure;

        private ScheduleSnapshot(
                ScheduleReservation[] source,
                int count,
                long committedRevision,
                long durableRevision,
                boolean flusherActive,
                RuntimeException failure) {
            this.reservations = new ScheduleReservation[count];
            for (int i = 0; i < count; i++) {
                ScheduleReservation reservation = source[i];
                this.reservations[i] = new ScheduleReservation(
                        reservation.id, reservation.start, reservation.end);
            }
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.failure = failure;
        }

        public ScheduleReservation[] getReservations() {
            ScheduleReservation[] copy = new ScheduleReservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                ScheduleReservation reservation = reservations[i];
                copy[i] = new ScheduleReservation(
                        reservation.id, reservation.start, reservation.end);
            }
            return copy;
        }

        public int getReservationCount() {
            return reservations.length;
        }

        public ScheduleReservation getReservation(int index) {
            ScheduleReservation reservation = reservations[index];
            return new ScheduleReservation(
                    reservation.id, reservation.start, reservation.end);
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

        public RuntimeException getFailure() {
            return failure;
        }
    }

    private final Object stateMonitor = new Object();
    private final ScheduleSink sink;

    private ScheduleReservation[] reservations = new ScheduleReservation[8];
    private int reservationCount;
    private long nextReservationId = 1;
    private boolean reservationIdsExhausted;
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
        synchronized (stateMonitor) {
            throwIfPersistenceFailed();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            int insertionIndex = 0;
            while (insertionIndex < reservationCount) {
                ScheduleReservation current = reservations[insertionIndex];
                if (current.start < end && start < current.end) {
                    return -1;
                }
                if (current.start > start) {
                    break;
                }
                insertionIndex++;
            }

            ensureRevisionCanAdvance();
            if (reservationIdsExhausted) {
                throw new IllegalStateException("Reservation ID counter exhausted");
            }
            ensureCapacity();

            long id = nextReservationId;
            for (int i = reservationCount; i > insertionIndex; i--) {
                reservations[i] = reservations[i - 1];
            }
            reservations[insertionIndex] = new ScheduleReservation(id, start, end);
            reservationCount++;

            if (id == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = id + 1;
            }
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (stateMonitor) {
            throwIfPersistenceFailed();
            if (reservationId <= 0) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int index = findReservation(reservationId);
            if (index < 0) {
                return false;
            }

            ensureRevisionCanAdvance();
            for (int i = index; i < reservationCount - 1; i++) {
                reservations[i] = reservations[i + 1];
            }
            reservations[--reservationCount] = null;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        long targetRevision;
        synchronized (stateMonitor) {
            targetRevision = committedRevision;
            if (flusherActive && flusherThread == Thread.currentThread()) {
                throw new IllegalStateException("The sink must not recursively flush");
            }
            if (durableRevision >= targetRevision) {
                return;
            }
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }

            while (flusherActive) {
                stateMonitor.wait();
                if (durableRevision >= targetRevision) {
                    return;
                }
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
            }

            flusherActive = true;
            flusherThread = Thread.currentThread();
        }

        flushAsFlusher(targetRevision);
    }

    public ScheduleSnapshot snapshot() {
        synchronized (stateMonitor) {
            return createSnapshot(
                    committedRevision, durableRevision, flusherActive, persistenceFailure);
        }
    }

    private void flushAsFlusher(long targetRevision) {
        try {
            for (;;) {
                ScheduleSnapshot work;
                synchronized (stateMonitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }
                    work = createSnapshot(
                            committedRevision, durableRevision, true, persistenceFailure);
                }

                try {
                    sink.persist(work);
                } catch (RuntimeException sinkFailure) {
                    RuntimeException retainedFailure;
                    synchronized (stateMonitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = sinkFailure;
                        }
                        retainedFailure = persistenceFailure;
                        stateMonitor.notifyAll();
                    }
                    throw retainedFailure;
                }

                synchronized (stateMonitor) {
                    if (work.committedRevision > durableRevision) {
                        durableRevision = work.committedRevision;
                    }
                    stateMonitor.notifyAll();
                }
            }
        } finally {
            synchronized (stateMonitor) {
                if (flusherThread == Thread.currentThread()) {
                    flusherActive = false;
                    flusherThread = null;
                }
                stateMonitor.notifyAll();
            }
        }
    }

    private ScheduleSnapshot createSnapshot(
            long committed, long durable, boolean active, RuntimeException failure) {
        return new ScheduleSnapshot(
                reservations, reservationCount, committed, durable, active, failure);
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("Persistence has failed", persistenceFailure);
        }
    }

    private void ensureRevisionCanAdvance() {
        if (committedRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Committed revision counter exhausted");
        }
    }

    private void ensureCapacity() {
        if (reservationCount < reservations.length) {
            return;
        }
        if (reservationCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("Reservation capacity exhausted");
        }

        int newCapacity = reservations.length <= Integer.MAX_VALUE / 2
                ? reservations.length * 2
                : Integer.MAX_VALUE;
        if (newCapacity <= reservationCount) {
            throw new IllegalStateException("Reservation capacity exhausted");
        }

        ScheduleReservation[] expanded = new ScheduleReservation[newCapacity];
        for (int i = 0; i < reservationCount; i++) {
            expanded[i] = reservations[i];
        }
        reservations = expanded;
    }

    private int findReservation(long reservationId) {
        for (int i = 0; i < reservationCount; i++) {
            if (reservations[i].id == reservationId) {
                return i;
            }
        }
        return -1;
    }
}
