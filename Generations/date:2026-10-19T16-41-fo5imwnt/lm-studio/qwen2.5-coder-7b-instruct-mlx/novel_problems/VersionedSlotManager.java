@VersionedSlotManager.ThreadSafe
class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final int MAX_REVISION = Integer.MAX_VALUE;
    private static final long INVALID_ID = -1;

    private final Object lock = new Object();
    private int revision;
    private long nextId;
    private Slot[] slots;

    public VersionedSlotManager() {
        this.revision = 0;
        this.nextId = 1;
        this.slots = new Slot[0];
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            return new Snapshot(revision, slots.clone());
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid interval or negative expected revision");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(STALE_REVISION, revision);
            }

            for (Slot slot : slots) {
                if (slot.overlaps(start, end)) {
                    return new MutationResult(CONFLICT, revision);
                }
            }

            Slot[] newSlots = Arrays.copyOf(slots, slots.length + 1);
            newSlots[slots.length] = new Slot(nextId, start, end);
            slots = newSlots;
            nextId++;

            if (nextId > MAX_REVISION) {
                throw new IllegalStateException("Overflowing id space");
            }

            revision++;

            return new MutationResult(SUCCESS, revision, nextId - 1);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative expected revision");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(STALE_REVISION, revision);
            }

            boolean found = false;
            Slot[] newSlots = new Slot[slots.length];

            for (int i = 0; i < slots.length; i++) {
                if (slots[i].id != reservationId) {
                    newSlots[i - found] = slots[i];
                } else {
                    found = true;
                }
            }

            if (!found) {
                return new MutationResult(NOT_FOUND, revision);
            }

            slots = newSlots;
            revision++;

            return new MutationResult(SUCCESS, revision);
        }
    }

    private static class Slot {
        final long id;
        final long start;
        final long end;

        Slot(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        boolean overlaps(long start, long end) {
            return this.start < end && start < this.end;
        }

        @Override
        public String toString() {
            return "(" + id + ", " + start + ", " + end + ")";
        }
    }

    private static class Snapshot {
        final int revision;
        final Slot[] slots;

        Snapshot(int revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }

        @Override
        public String toString() {
            return "Snapshot{" +
                    "revision=" + revision +
                    ", slots=" + Arrays.toString(slots) +
                    '}';
        }
    }

    private static class MutationResult {
        final int status;
        final int revision;
        final long id;

        MutationResult(int status, int revision) {
            this(status, revision, INVALID_ID);
        }

        MutationResult(int status, int revision, long id) {
            this.status = status;
            this.revision = revision;
            this.id = id;
        }

        @Override
        public String toString() {
            return "MutationResult{" +
                    "status=" + status +
                    ", revision=" + revision +
                    ", id=" + id +
                    '}';
        }
    }

    private static final int SUCCESS = 0;
    private static final int STALE_REVISION = 1;
    private static final int CONFLICT = 2;
    private static final int NOT_FOUND = 3;
}
