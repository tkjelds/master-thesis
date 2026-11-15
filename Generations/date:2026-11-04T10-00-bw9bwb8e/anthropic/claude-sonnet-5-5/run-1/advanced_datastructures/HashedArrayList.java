@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}


    private static final class Entry<T> {
        T item;
        final int hash;
        int index;
        Entry<T> next;

        Entry(T item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    private final Object lock = new Object();

    private Entry<T>[] seq;
    private Entry<T>[] buckets;
    private int size;

    @SuppressWarnings("unchecked")
    public HashedArrayList() {
        seq = (Entry<T>[]) new Entry[16];
        buckets = (Entry<T>[]) new Entry[16];
        size = 0;
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static int bucketIndex(int hash, int length) {
        return hash & (length - 1);
    }

    // Must be called with lock held.
    private Entry<T> find(T item, int hash) {
        Entry<T> e = buckets[bucketIndex(hash, buckets.length)];
        while (e != null) {
            if (e.hash == hash && (e.item == item || item.equals(e.item))) {
                return e;
            }
            e = e.next;
        }
        return null;
    }

    private void linkEntry(Entry<T> e) {
        int b = bucketIndex(e.hash, buckets.length);
        e.next = buckets[b];
        buckets[b] = e;
    }

    private void unlinkEntry(Entry<T> e) {
        int b = bucketIndex(e.hash, buckets.length);
        Entry<T> cur = buckets[b];
        Entry<T> prev = null;
        while (cur != null) {
            if (cur == e) {
                if (prev == null) {
                    buckets[b] = cur.next;
                } else {
                    prev.next = cur.next;
                }
                cur.next = null;
                return;
            }
            prev = cur;
            cur = cur.next;
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureSeqCapacity() {
        if (size == seq.length) {
            Entry<T>[] ns = (Entry<T>[]) new Entry[seq.length * 2];
            System.arraycopy(seq, 0, ns, 0, size);
            seq = ns;
        }
    }

    // Called after size has been incremented; builds new table fully before swapping.
    @SuppressWarnings("unchecked")
    private void maybeRehash() {
        if (size * 4 > buckets.length * 3) {
            int nl = buckets.length * 2;
            Entry<T>[] nb = (Entry<T>[]) new Entry[nl];
            for (int i = 0; i < size; i++) {
                Entry<T> e = seq[i];
                int b = bucketIndex(e.hash, nl);
                e.next = nb[b];
                nb[b] = e;
            }
            buckets = nb;
        }
    }

    private void removeAtLocked(int index) {
        Entry<T> e = seq[index];
        unlinkEntry(e);
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(seq, index + 1, seq, index, moved);
        }
        seq[--size] = null;
        for (int i = index; i < size; i++) {
            seq[i].index = i;
        }
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (find(item, h) != null) return false;
            ensureSeqCapacity();
            Entry<T> e = new Entry<T>(item, h, size);
            seq[size++] = e;
            linkEntry(e);
            maybeRehash();
            return true;
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            if (find(item, h) != null) return false;
            ensureSeqCapacity();
            int moved = size - index;
            if (moved > 0) {
                System.arraycopy(seq, index, seq, index + 1, moved);
            }
            Entry<T> e = new Entry<T>(item, h, index);
            seq[index] = e;
            size++;
            for (int i = index + 1; i < size; i++) {
                seq[i].index = i;
            }
            linkEntry(e);
            maybeRehash();
            return true;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            return seq[index].item;
        }
    }

    public T set(int index, T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            Entry<T> existing = find(item, h);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("equal item already present at another index");
            }
            Entry<T> old = seq[index];
            T prev = old.item;
            unlinkEntry(old);
            Entry<T> e = new Entry<T>(item, h, index);
            seq[index] = e;
            linkEntry(e);
            return prev;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            T prev = seq[index].item;
            removeAtLocked(index);
            return prev;
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            Entry<T> e = find(item, h);
            if (e == null) return false;
            removeAtLocked(e.index);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            return find(item, h) != null;
        }
    }

    public int indexOf(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            Entry<T> e = find(item, h);
            return e == null ? -1 : e.index;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[size];
            for (int i = 0; i < size; i++) {
                out[i] = seq[i].item;
            }
            return out;
        }
    }
}
