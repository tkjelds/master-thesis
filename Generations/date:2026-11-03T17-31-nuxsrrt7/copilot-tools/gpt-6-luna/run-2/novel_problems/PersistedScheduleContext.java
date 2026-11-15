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

        public RuntimeException getFailure() {
            return failure;
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;
    private Reservation[] reservations = new Reservation[0];
    private int reservationCount;
    private long nextReservationId = 1L;
    private long committedRevision;
    private long durableRevision;
    private boolean flushing;
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
            throw new IllegalArgumentException("start must be less than end");
        }

        synchronized (monitor) {
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

            if (nextReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID counter overflow");
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision overflow");
            }

            long id = nextReservationId;
            Reservation reservation = new Reservation(id, start, end);
            ensureCapacity();

            for (int i = reservationCount; i > insertionIndex; i--) {
                reservations[i] = reservations[i - 1];
            }
            reservations[insertionIndex] = reservation;
            reservationCount++;
            nextReservationId++;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0L) {
            throw new IllegalArgumentException("reservation ID must be positive");
        }

        synchronized (monitor) {
            throwIfPersistenceFailed();

            int index = 0;
            while (index < reservationCount
                    && reservations[index].id != reservationId) {
                index++;
            }
            if (index == reservationCount) {
                return false;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision overflow");
            }

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
        long targetRevision;
        Thread currentThread = Thread.currentThread();

        synchronized (monitor) {
            if (flusherThread == currentThread) {
                throw new IllegalStateException(
                        "recursive flush from the sink is not allowed");
            }
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
                    flusherThread = currentThread;
                    break;
                }
                monitor.wait();
            }
        }

        try {
            while (true) {
                ScheduleSnapshot toPersist;
                long snapshotRevision;

                synchronized (monitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }

                    snapshotRevision = committedRevision;
                    Reservation[] copiedReservations =
                            new Reservation[reservationCount];
                    for (int i = 0; i < reservationCount; i++) {
                        Reservation reservation = reservations[i];
                        copiedReservations[i] = new Reservation(
                                reservation.id, reservation.start, reservation.end);
                    }
                    toPersist = new ScheduleSnapshot(
                            copiedReservations,
                            committedRevision,
                            durableRevision,
                            flushing,
                            persistenceFailure);
                }

                try {
                    sink.persist(toPersist);
                } catch (RuntimeException failure) {
                    RuntimeException retainedFailure;
                    boolean targetIsDurable;
                    synchronized (monitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = failure;
                        }
                        retainedFailure = persistenceFailure;
                        targetIsDurable = durableRevision >= targetRevision;
                        monitor.notifyAll();
                    }
                    if (targetIsDurable) {
                        return;
                    }
                    throw retainedFailure;
                }

                synchronized (monitor) {
                    if (snapshotRevision > durableRevision) {
                        durableRevision = snapshotRevision;
                    }
                    monitor.notifyAll();
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                }
            }
        } finally {
            synchronized (monitor) {
                if (flusherThread == currentThread) {
                    flushing = false;
                    flusherThread = null;
                    monitor.notifyAll();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            Reservation[] copiedReservations = new Reservation[reservationCount];
            for (int i = 0; i < reservationCount; i++) {
                Reservation reservation = reservations[i];
                copiedReservations[i] = new Reservation(
                        reservation.id, reservation.start, reservation.end);
            }
            return new ScheduleSnapshot(
                    copiedReservations,
                    committedRevision,
                    durableRevision,
                    flushing,
                    persistenceFailure);
        }
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "schedule persistence has failed", persistenceFailure);
        }
    }

    private void ensureCapacity() {
        if (reservationCount < reservations.length) {
            return;
        }
        if (reservations.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("reservation capacity exhausted");
        }

        int newCapacity;
        if (reservations.length == 0) {
            newCapacity = 4;
        } else if (reservations.length > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = reservations.length * 2;
        }

        Reservation[] expanded = new Reservation[newCapacity];
        for (int i = 0; i < reservationCount; i++) {
            expanded[i] = reservations[i];
        }
        reservations = expanded;
    }
}
