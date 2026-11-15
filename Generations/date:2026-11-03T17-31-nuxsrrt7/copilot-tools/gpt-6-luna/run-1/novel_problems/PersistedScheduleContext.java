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
        private final long revision;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flushing;
        private final RuntimeException failure;
        private final Reservation[] reservations;

        private ScheduleSnapshot(
                long revision,
                long committedRevision,
                long durableRevision,
                boolean flushing,
                RuntimeException failure,
                Reservation[] reservations) {
            this.revision = revision;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
            this.reservations = reservations;
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

        public boolean isFlushing() {
            return flushing;
        }

        public RuntimeException getFailure() {
            return failure;
        }

        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            System.arraycopy(reservations, 0, copy, 0, reservations.length);
            return copy;
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;

    private Reservation[] reservations = new Reservation[0];
    private long committedRevision;
    private long durableRevision;
    private long lastReservationId;
    private boolean flushing;
    private Thread flusherThread;
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

            int insertionIndex = 0;
            while (insertionIndex < reservations.length
                    && reservations[insertionIndex].start < start) {
                insertionIndex++;
            }

            if (insertionIndex > 0
                    && reservations[insertionIndex - 1].end > start) {
                return -1;
            }
            if (insertionIndex < reservations.length
                    && reservations[insertionIndex].start < end) {
                return -1;
            }

            if (lastReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("Reservation ID counter overflow");
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Committed revision counter overflow");
            }
            if (reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many reservations");
            }

            long id = lastReservationId + 1;
            Reservation[] updated = new Reservation[reservations.length + 1];
            System.arraycopy(reservations, 0, updated, 0, insertionIndex);
            updated[insertionIndex] = new Reservation(id, start, end);
            System.arraycopy(
                    reservations,
                    insertionIndex,
                    updated,
                    insertionIndex + 1,
                    reservations.length - insertionIndex);

            reservations = updated;
            lastReservationId = id;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            throwIfPersistenceFailed();

            if (reservationId <= 0) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int index = 0;
            while (index < reservations.length
                    && reservations[index].id != reservationId) {
                index++;
            }
            if (index == reservations.length) {
                return false;
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Committed revision counter overflow");
            }

            Reservation[] updated = new Reservation[reservations.length - 1];
            System.arraycopy(reservations, 0, updated, 0, index);
            System.arraycopy(
                    reservations,
                    index + 1,
                    updated,
                    index,
                    reservations.length - index - 1);

            reservations = updated;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        long targetRevision;
        Thread currentThread = Thread.currentThread();

        synchronized (monitor) {
            if (flusherThread == currentThread) {
                throw new IllegalStateException("A sink cannot recursively flush");
            }

            targetRevision = committedRevision;
            while (durableRevision < targetRevision) {
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

            if (durableRevision >= targetRevision) {
                return;
            }
        }

        runFlusher(targetRevision);
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            Reservation[] copy = new Reservation[reservations.length];
            System.arraycopy(reservations, 0, copy, 0, reservations.length);
            return new ScheduleSnapshot(
                    committedRevision,
                    committedRevision,
                    durableRevision,
                    flushing,
                    persistenceFailure,
                    copy);
        }
    }

    private void runFlusher(long targetRevision) {
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

                    Reservation[] copy = new Reservation[reservations.length];
                    System.arraycopy(reservations, 0, copy, 0, reservations.length);
                    work = new ScheduleSnapshot(
                            committedRevision,
                            committedRevision,
                            durableRevision,
                            flushing,
                            persistenceFailure,
                            copy);
                }

                try {
                    sink.persist(work);
                } catch (RuntimeException failure) {
                    synchronized (monitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = failure;
                        }
                    }
                    throw failure;
                }

                synchronized (monitor) {
                    if (work.revision > durableRevision) {
                        durableRevision = work.revision;
                    }
                    monitor.notifyAll();
                }
            }
        } finally {
            synchronized (monitor) {
                if (flusherThread == Thread.currentThread()) {
                    flushing = false;
                    flusherThread = null;
                }
                monitor.notifyAll();
            }
        }
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "Persistence has failed", persistenceFailure);
        }
    }
}
