```java
class PersistedScheduleContext {
    private final ScheduleSink sink;
    private volatile long committedRevision = 0;
    private volatile long durableRevision = 0;
    private final Reservation[] reservations = new Reservation[1024];
    private int nextReservationId = 1;
    private boolean isInFlusherRole = false;
    private Throwable persistenceFailure = null;

    public PersistedScheduleContext(ScheduleSink sink) {
        this.sink = sink;
    }

    public long reserve(long start, long end) throws IllegalStateException {
        synchronized (this) {
            if (persistenceFailure != null) throw new IllegalStateException(persistenceFailure);
            for (int i = 0; i < nextReservationId; i++) {
                if (reservations[i] != null && (start < reservations[i].end && end > reservations[i].start)) {
                    return -1;
                }
            }
            long id = nextReservationId++;
            reservations[id] = new Reservation(id, start, end);
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) throws IllegalStateException {
        synchronized (this) {
            if (persistenceFailure != null) throw new IllegalStateException(persistenceFailure);
            if (reservationId < 1 || reservationId >= nextReservationId || reservations[reservationId] == null) {
                return false;
            }
            reservations[reservationId] = null;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws IllegalStateException, InterruptedException {
        synchronized (this) {
            if (persistenceFailure != null) throw new IllegalStateException(persistenceFailure);
            if (isInFlusherRole) {
                wait();
                return;
            }
            isInFlusherRole = true;
        }

        try {
            long targetRevision;
            synchronized (this) {
                targetRevision = committedRevision;
            }

            ScheduleSnapshot snapshot = new ScheduleSnapshot(committedRevision, durableRevision);
            for (int i = 1; i < nextReservationId; i++) {
                if (reservations[i] != null) {
                    snapshot.add(reservations[i].id, reservations[i].start, reservations[i].end);
                }
            }

            while (true) {
                synchronized (this) {
                    if (durableRevision >= targetRevision) break;
                }
                Thread.sleep(10); // Busy waiting is not allowed, so we use a short sleep
            }

            sink.persist(snapshot);

            synchronized (this) {
                durableRevision = targetRevision;
                persistenceFailure = null;
                isInFlusherRole = false;
                notifyAll();
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                persistenceFailure = e;
                isInFlusherRole = false;
                notifyAll();
            }
            throw new IllegalStateException(e);
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (this) {
            return new ScheduleSnapshot(committedRevision, durableRevision);
        }
    }

    private static class Reservation {
        long id;
        long start;
        long end;

        Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static class ScheduleSnapshot {
        long committedRevision;
        long durableRevision;
        List<long[]> reservations = new ArrayList<>();

        ScheduleSnapshot(long committedRevision, long durableRevision) {
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
        }

        void add(long id, long start, long end) {
            reservations.add(new long[]{id, start, end});
        }
    }
}
```
