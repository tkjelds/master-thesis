@PersistedScheduleContext.ThreadSafe
public class PersistedScheduleContext {
    @interface ThreadSafe {}

    private static final long INVALID_REVISION = -1;
    private static final int MAX_REVISION = 2147483647;

    private volatile long committedRevision = 0;
    private volatile long durableRevision = 0;
    private boolean flusherClaimed = false;
    private Throwable persistenceFailure = null;

    private final ScheduleSink sink;
    private final Object lock = new Object();
    private final Reservation[] reservations;

    public PersistedScheduleContext(ScheduleSink sink, int capacity) {
        this.sink = sink;
        this.reservations = new Reservation[capacity];
    }

    public long reserve(long start, long end) {
        synchronized (lock) {
            if (persistenceFailure != null) {
                throw new IllegalStateException("Persistence failure: " + persistenceFailure.getMessage());
            }
            if (start >= end || start < 0 || end > MAX_REVISION) {
                throw new IllegalArgumentException("Invalid reservation range");
            }
            for (Reservation res : reservations) {
                if (res != null && res.start < end && start < res.end) {
                    return -1;
                }
            }
            long reservationId = System.currentTimeMillis();
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i] == null) {
                    reservations[i] = new Reservation(reservationId, start, end);
                    committedRevision++;
                    return reservationId;
                }
            }
            throw new IllegalStateException("Reservation capacity exceeded");
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (lock) {
            if (persistenceFailure != null) {
                throw new IllegalStateException("Persistence failure: " + persistenceFailure.getMessage());
            }
            boolean existed = false;
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i] != null && reservations[i].id == reservationId) {
                    reservations[i] = null;
                    committedRevision++;
                    existed = true;
                }
            }
            return existed;
        }
    }

    public void flush() throws InterruptedException {
        synchronized (lock) {
            if (persistenceFailure != null) {
                throw new IllegalStateException("Persistence failure: " + persistenceFailure.getMessage());
            }
            if (flusherClaimed) {
                return;
            }
            flusherClaimed = true;
            long targetRevision = committedRevision;
            while (true) {
                if (durableRevision >= targetRevision) {
                    flusherClaimed = false;
                    return;
                }
                if (Thread.interrupted()) {
                    flusherClaimed = false;
                    throw new InterruptedException();
                }
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    flusherClaimed = false;
                    throw new InterruptedException();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (lock) {
            if (persistenceFailure != null) {
                return new ScheduleSnapshot(persistenceFailure, committedRevision, durableRevision);
            }
            Reservation[] snapshotReservations = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                snapshotReservations[i] = reservations[i];
            }
            return new ScheduleSnapshot(null, committedRevision, durableRevision);
        }
    }

    public static class Reservation {
        final long id;
        final long start;
        final long end;

        Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static class ScheduleSnapshot {
        final Throwable failure;
        final long committedRevision;
        final long durableRevision;

        ScheduleSnapshot(Throwable failure, long committedRevision, long durableRevision) {
            this.failure = failure;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
        }

        public Throwable getFailure() {
            return failure;
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }
    }

    private static class PersistedScheduleContextImpl implements ScheduleSink {
        private final PersistedScheduleContext context;

        public PersistedScheduleContextImpl(PersistedScheduleContext context) {
            this.context = context;
        }

        @Override
        public void persist(ScheduleSnapshot snapshot) {
            if (snapshot.failure != null) {
                synchronized (context.lock) {
                    context.persistenceFailure = snapshot.failure;
                    context.notifyAll();
                }
            } else {
                synchronized (context.lock) {
                    if (snapshot.committedRevision > context.durableRevision) {
                        for (int i = 0; i < context.reservations.length; i++) {
                            if (context.reservations[i] != null && context.reservations[i].id < snapshot.committedRevision) {
                                context.reservations[i] = null;
                            }
                        }
                        context.durableRevision = snapshot.committedRevision;
                        context.notifyAll();
                    }
                }
            }
        }
    }

    public static ScheduleSink createScheduleSink(PersistedScheduleContext context) {
        return new PersistedScheduleContextImpl(context);
    }
}
