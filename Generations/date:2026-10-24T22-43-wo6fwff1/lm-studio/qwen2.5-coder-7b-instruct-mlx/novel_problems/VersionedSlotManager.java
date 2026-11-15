```java
class VersionedSlotManager {
    static class Slot {
        long start;
        long end;
        long id;

        Slot(long start, long end, long id) {
            this.start = start;
            this.end = end;
            this.id = id;
        }

        @Override
        public String toString() {
            return "Slot{" +
                    "start=" + start +
                    ", end=" + end +
                    ", id=" + id +
                    '}';
        }
    }

    static class Snapshot {
        int revision;
        Slot[] slots;

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

    static class MutationResult {
        boolean success;
        int revision;
        Long id;

        MutationResult(boolean success, int revision, Long id) {
            this.success = success;
            this.revision = revision;
            this.id = id;
        }
    }

    private static final long MAX_REVISION = Long.MAX_VALUE - 1;
    private static final long INVALID_ID = -1;

    private final Slot[] slots;
    private int revision;
    private long nextId;

    public VersionedSlotManager(int capacity) {
        this.slots = new Slot[capacity];
        this.revision = 0;
        this.nextId = 1;
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            Slot[] snapshotSlots = Arrays.copyOf(slots, nextId - 1);
            return new Snapshot(revision, snapshotSlots);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid interval or negative expected revision");
        }

        synchronized (this) {
            if (revision != expectedRevision) {
                return new MutationResult(false, revision, null);
            }

            if (nextId >= MAX_REVISION) {
                throw new IllegalStateException("Revision overflow");
            }

            long id = nextId++;
            Slot slot = new Slot(start, end, id);

            for (int i = 0; i < nextId - 1; ++i) {
                if (slots[i] != null && (slot.start < slots[i].end || slot.end > slots[i].start)) {
                    return new MutationResult(false, revision, null);
                }
            }

            for (int i = 0; i < nextId - 1; ++i) {
                if (slots[i] == null) {
                    slots[i] = slot;
                    break;
                }
            }

            ++revision;
            return new MutationResult(true, revision, id);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative expected revision");
        }

        synchronized (this) {
            if (revision != expectedRevision) {
                return new MutationResult(false, revision, null);
            }

            for (int i = 0; i < nextId - 1; ++i) {
                if (slots[i] != null && slots[i].id == reservationId) {
                    slots[i] = null;
                    break;
                }
            }

            ++revision;
            return new MutationResult(true, revision, null);
        }
    }
}
```
