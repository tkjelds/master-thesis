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

        private ScheduleSnapshot(
                long[] ids,
                long[] starts,
                long[] ends,
                int size,
                long committedRevision,
                long durableRevision,
                boolean flushing,
                RuntimeException failure) {
            this.reservations = new Reservation[size];
            for (int i = 0; i < size; i++) {
                this.reservations[i] = new Reservation(ids[i], starts[i], ends[i]);
            }
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
        }

        public Reservation[] getReservations() {
            return reservations.clone();
        }

        public int getReservationCount() {
            return reservations.length;
        }

        public Reservation getReservationAt(int index) {
            return reservations[index];
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
    private long[] reservationStarts = new long[0];
    private long[] reservationEnds = new long[0];
    private int reservationCount;

    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted;
    private long committedRevision;
    private long durableRevision;
    private boolean flusherActive;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        synchronized (monitor) {
            throwIfPersistenceFailed();

            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            for (int i = 0; i < reservationCount; i++) {
                if (start < reservationEnds[i] && reservationStarts[i] < end) {
                    return -1L;
                }
            }

            if (reservationIdsExhausted) {
                throw new IllegalStateException("Reservation ID counter exhausted");
            }
            ensureRevisionCanAdvance();

            ensureReservationCapacity();
            long id = nextReservationId;

            reservationIds[reservationCount] = id;
            reservationStarts[reservationCount] = start;
            reservationEnds[reservationCount] = end;
            reservationCount++;

            if (id == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = id + 1L;
            }
            committedRevision++;
            return id;
        }
    }

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

            ensureRevisionCanAdvance();

            for (int i = index; i < reservationCount - 1; i++) {
                reservationIds[i] = reservationIds[i + 1];
                reservationStarts[i] = reservationStarts[i + 1];
                reservationEnds[i] = reservationEnds[i + 1];
            }
            reservationCount--;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        final long targetRevision;

        synchronized (monitor) {
            targetRevision = committedRevision;

            while (true) {
                if (durableRevision >= targetRevision) {
                    return;
                }
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (!flusherActive) {
                    flusherActive = true;
                    break;
                }
                monitor.wait();
            }
        }

        try {
            while (true) {
                ScheduleSnapshot work;

                synchronized (monitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }

                    work = new ScheduleSnapshot(
                            reservationIds,
                            reservationStarts,
                            reservationEnds,
                            reservationCount,
                            committedRevision,
                            durableRevision,
                            flusherActive,
                            persistenceFailure);
                }

                try {
                    sink.persist(work);
                } catch (RuntimeException failure) {
                    synchronized (monitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = failure;
                        }
                        monitor.notifyAll();
                    }
                    throw persistenceFailure;
                }

                synchronized (monitor) {
                    if (work.getCommittedRevision() > durableRevision) {
                        durableRevision = work.getCommittedRevision();
                    }
                    monitor.notifyAll();
                }
            }
        } finally {
            synchronized (monitor) {
                flusherActive = false;
                monitor.notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return new ScheduleSnapshot(
                    reservationIds,
                    reservationStarts,
                    reservationEnds,
                    reservationCount,
                    committedRevision,
                    durableRevision,
                    flusherActive,
                    persistenceFailure);
        }
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

    private void ensureReservationCapacity() {
        if (reservationCount < reservationIds.length) {
            return;
        }
        if (reservationCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("Reservation capacity exhausted");
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
        if (newCapacity <= reservationCount) {
            throw new IllegalStateException("Reservation capacity exhausted");
        }

        long[] newIds = new long[newCapacity];
        long[] newStarts = new long[newCapacity];
        long[] newEnds = new long[newCapacity];

        for (int i = 0; i < reservationCount; i++) {
            newIds[i] = reservationIds[i];
            newStarts[i] = reservationStarts[i];
            newEnds[i] = reservationEnds[i];
        }

        reservationIds = newIds;
        reservationStarts = newStarts;
        reservationEnds = newEnds;
    }
}
