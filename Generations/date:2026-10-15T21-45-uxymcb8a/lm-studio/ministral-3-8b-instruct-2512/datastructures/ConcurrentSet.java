@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private Entry[] buckets;
    private volatile int size;

    public ConcurrentSet() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        this.buckets = new Entry[initialCapacity];
        this.size = 0;
    }

    public boolean add(Object element) {
        if (element == null) {
            return false;
        }
        int hash = element.hashCode();
        Entry[] bucketsCopy;
        synchronized (this) {
            bucketsCopy = this.buckets;
        }

        Entry newEntry = new Entry(element, hash, null);
        boolean added = false;

        for (int i = 0; i < bucketsCopy.length && !added; i++) {
            Entry bucketHead = bucketsCopy[i];
            Entry prev = null;
            Entry current = bucketHead;

            while (current != null && current.hash != hash) {
                prev = current;
                current = current.next;
            }

            if (current == null) {
                synchronized (this) {
                    bucketsCopy = this.buckets;
                    if (i >= bucketsCopy.length || bucketsCopy[i] != bucketHead) {
                        continue;
                    }
                }

                Entry last = bucketsCopy[bucketsCopy.length - 1];
                newEntry.next = null;
                if (last == null) {
                    bucketsCopy[i] = newEntry;
                } else {
                    while (last.next != null) {
                        last = last.next;
                    }
                    last.next = newEntry;
                }

                synchronized (this) {
                    if (!contains(element)) {
                        size++;
                    }
                }
                added = true;
            } else if (current.element.equals(element)) {
                return false; // Element already exists
            }
        }

        if (added) {
            synchronized (this) {
                buckets = bucketsCopy;
            }
        }

        return added;
    }

    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        int hash = element.hashCode();
        Entry[] bucketsCopy;
        synchronized (this) {
            bucketsCopy = this.buckets;
        }

        for (int i = 0; i < bucketsCopy.length; i++) {
            Entry bucketHead = bucketsCopy[i];
            Entry prev = null;
            Entry current = bucketHead;

            while (current != null) {
                if (current.hash == hash && current.element.equals(element)) {
                    synchronized (this) {
                        bucketsCopy = this.buckets;
                        if (i >= bucketsCopy.length || bucketsCopy[i] != bucketHead) {
                            continue;
                        }
                    }

                    if (prev == null) {
                        bucketsCopy[i] = current.next;
                    } else {
                        prev.next = current.next;
                    }

                    synchronized (this) {
                        if (!contains(element)) {
                            size--;
                        }
                    }
                    return true;
                }

                prev = current;
                current = current.next;
            }
        }

        return false;
    }

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }
        int hash = element.hashCode();
        Entry[] bucketsCopy;
        synchronized (this) {
            bucketsCopy = this.buckets;
        }

        for (int i = 0; i < bucketsCopy.length; i++) {
            Entry current = bucketsCopy[i];
            while (current != null) {
                if (current.hash == hash && current.element.equals(element)) {
                    return true;
                }
                current = current.next;
            }
        }

        return false;
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            Entry[] bucketsCopy = this.buckets;
            for (int i = 0; i < bucketsCopy.length; i++) {
                Entry current = bucketsCopy[i];
                while (current != null) {
                    Entry next = current.next;
                    current.next = null;
                    current = next;
                }
            }
            this.buckets = new Entry[DEFAULT_CAPACITY];
            size = 0;
        }
    }

    private static class Entry {
        final Object element;
        final int hash;
        Entry next;

        Entry(Object element, int hash, Entry next) {
            this.element = element;
            this.hash = hash;
            this.next = next;
        }
    }
}
