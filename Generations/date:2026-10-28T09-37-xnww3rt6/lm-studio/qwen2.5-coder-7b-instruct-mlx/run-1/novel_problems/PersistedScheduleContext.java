@ScheduleSnapshot.ThreadSafe
class ScheduleSnapshot {
    @interface ThreadSafe {}

    long committedRev, durableRev;
    boolean flushing;
    Throwable failure;

    public ScheduleSnapshot(long committedRev, long durableRev, boolean flushing, Throwable failure) {
        this.committedRev = committedRev;
        this.durableRev = durableRev;
        this.flushing = flushing;
        this.failure = failure;
    }

    public long getCommittedRev() { return committedRev; }
    public long getDurableRev() { return durableRev; }
    public boolean isFlushing() { return flushing; }
    public Throwable getFailure() { return failure; }
}

@PersistedScheduleContext.ThreadSafe
class PersistedScheduleContext {
    @interface ThreadSafe {}

    private long committedRev = 0, durableRev = 0;
    private boolean flushing = false;
    private Throwable failure = null;

    public synchronized ScheduleSnapshot snapshot() {
        return new ScheduleSnapshot(committedRev, durableRev, flushing, failure);
    }

    public synchronized long reserve(long start, long end) {
        if (failure != null) throw new IllegalStateException(failure);
        for (; committedRev > durableRev; ) {
            try { wait(); } catch (InterruptedException e) {}
        }
        if (start >= end || start <= committedRev) return -1;
        for (; ; ) {
            long last = committedRev, next = last + 1;
            ScheduleSnapshot snapshot = new ScheduleSnapshot(last, durableRev, flushing, failure);
            if (snapshot.getDurableRev() == last) {
                committedRev = next;
                notifyAll();
                return next;
            }
        }
    }

    public synchronized boolean cancel(long reservationId) {
        if (failure != null) throw new IllegalStateException(failure);
        for (; committedRev > durableRev; ) {
            try { wait(); } catch (InterruptedException e) {}
        }
        if (reservationId <= committedRev || reservationId > durableRev) return false;
        for (; ; ) {
            long last = committedRev, next = last + 1;
            ScheduleSnapshot snapshot = new ScheduleSnapshot(last, durableRev, flushing, failure);
            if (snapshot.getDurableRev() == last) {
                committedRev = next;
                notifyAll();
                return true;
            }
        }
    }

    public synchronized void flush() throws RuntimeException {
        if (failure != null) throw new IllegalStateException(failure);
        for (; flushing; ) {
            try { wait(); } catch (InterruptedException e) {}
        }
        while (!flushing && durableRev < committedRev) {
            flushing = true;
            notifyAll();
            try { wait(); } catch (InterruptedException e) {}
        }
    }

    private void doPersist(ScheduleSnapshot snapshot, Runnable after) {
        try {
            // Simulate persistence
            Thread.sleep(100);
        } catch (InterruptedException e) { return; }
        durableRev = snapshot.getDurableRev();
        failure = null;
        flushing = false;
        notifyAll();
    }

    public synchronized void doFlush() {
        if (failure != null) throw new IllegalStateException(failure);
        for (; flushing; ) {
            try { wait(); } catch (InterruptedException e) {}
        }
        if (durableRev >= committedRev) return;
        flushing = true;
        notifyAll();
        try {
            while (true) {
                ScheduleSnapshot snapshot = new ScheduleSnapshot(committedRev, durableRev, flushing, failure);
                doPersist(snapshot, () -> {
                    for (; ; ) {
                        long last = durableRev;
                        notifyAll();
                        if (durableRev >= committedRev) break;
                        try { wait(); } catch (InterruptedException e) {}
                    }
                });
            }
        } finally {
            flushing = false;
            notifyAll();
        }
    }

    public synchronized void failed(Throwable e) {
        failure = e;
        flushing = false;
        notifyAll();
    }
}
