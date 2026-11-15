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
        private final boolean flusherActive;
        private final RuntimeException failure;

        private ScheduleSnapshot(Reservation[] reservations,
                                 long committedRevision,
                                 long durableRevision,
                                 boolean flusherActive,
                                 RuntimeException failure) {
            this.reservations = copyReservations(reservations);
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.failure = failure;
        }

        public Reservation[] getReservations() {
            return copyReservations(reservations);
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

    private Reservation[] reservations = new Reservation[0];
    private long committedRevision;
    private long durableRevision;
    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted;
    private boolean flusherActive;
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

            for (int i = 0; i < reservations.length; i++) {
                Reservation existing = reservations[i];
                if (start < existing.end && existing.start < end) {
                    return -1L;
                }
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation IDs exhausted");
            }
            if (reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("too many reservations");
            }

            long id = nextReservationId;
            Reservation[] updated = new Reservation[reservations.length + 1];
            System.arraycopy(reservations, 0, updated, 0, reservations.length);
            updated[reservations.length] = new Reservation(id, start, end);

            reservations = updated;
            committedRevision++;
            if (id == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = id + 1L;
            }
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (stateMonitor) {
            throwIfPersistenceFailed();
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
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision exhausted");
            }

            Reservation[] updated = new Reservation[reservations.length - 1];
            if (found > 0) {
                System.arraycopy(reservations, 0, updated, 0, found);
            }
            if (found < updated.length) {
                System.arraycopy(reservations, found + 1, updated, found,
                                 updated.length - found);
            }

            reservations = updated;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        final long targetRevision;
        synchronized (stateMonitor) {
            targetRevision = committedRevision;
            while (durableRevision < targetRevision) {
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (!flusherActive) {
                    flusherActive = true;
                    break;
                }
                stateMonitor.wait();
            }
            if (durableRevision >= targetRevision) {
                return;
            }
        }

        try {
            while (true) {
                ScheduleSnapshot toPersist;
                synchronized (stateMonitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }
                    toPersist = new ScheduleSnapshot(
                            reservations,
                            committedRevision,
                            durableRevision,
                            flusherActive,
                            persistenceFailure);
                }

                try {
                    sink.persist(toPersist);
                } catch (RuntimeException failure) {
                    synchronized (stateMonitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = failure;
                        }
                        stateMonitor.notifyAll();
                    }
                    throw failure;
                }

                synchronized (stateMonitor) {
                    if (toPersist.committedRevision > durableRevision) {
                        durableRevision = toPersist.committedRevision;
                    }
                    stateMonitor.notifyAll();
                }
            }
        } finally {
            synchronized (stateMonitor) {
                flusherActive = false;
                stateMonitor.notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (stateMonitor) {
            return new ScheduleSnapshot(
                    reservations,
                    committedRevision,
                    durableRevision,
                    flusherActive,
                    persistenceFailure);
        }
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("schedule persistence has failed",
                                            persistenceFailure);
        }
    }

    private static Reservation[] copyReservations(Reservation[] source) {
        Reservation[] copy = new Reservation[source.length];
        for (int i = 0; i < source.length; i++) {
            Reservation reservation = source[i];
            copy[i] = new Reservation(reservation.id, reservation.start,
                                      reservation.end);
        }
        return copy;
    }
}
