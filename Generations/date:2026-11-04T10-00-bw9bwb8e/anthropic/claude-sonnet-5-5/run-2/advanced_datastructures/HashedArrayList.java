import java.lang.*;

@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}


    private static final class Entry {
        Object item;
        int hash;
        int index;
        Entry next;

        Entry(Object item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    private static final int INITIAL_CAPACITY = 8;
    private static final int INITIAL_TABLE = 16;

    private final Object lock = new Object();

    // All fields below are guarded by lock.
    private Entry[] seq = new Entry[INITIAL_CAPACITY];
    private int size = 0;
    private Entry[] table = new Entry[INITIAL_TABLE];

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static int hashOf(Object item) {
        return spread(item.hashCode());
    }

    private Entry find(Object item, int hash) {
        Entry e = table[hash & (table.length - 1)];
        while (e != null) {
            if (e.hash == hash && (e.item == item || item.equals(e.item))) {
                return e;
            }
            e = e.next;
        }
        return null;
    }

    private void linkEntry(Entry e) {
        int b = e.hash & (table.length - 1);
        e.next = table[b];
        table[b] = e;
    }

    private void unlinkEntry(Entry e) {
        int b = e.hash & (table.length - 1);
        Entry cur = table[b];
        Entry prev = null;
        while (cur != null) {
            if (cur == e) {
                if (prev == null) {
                    table[b] = cur.next;
                } else {
                    prev.next = cur.next;
                }
                e.next = null;
                return;
            }
            prev = cur;
            cur = cur.next;
        }
    }

    private void ensureCapacityForOneMore() {
        if (size == seq.length) {
            Entry[] ns = new Entry[seq.length * 2];
            System.arraycopy(seq, 0, ns, 0, size);
            seq = ns;
        }
        // Rehash if load factor would exceed 0.75 after the insertion.
        if ((size + 1) > (table.length / 4) * 3) {
            Entry[] nt = new Entry[table.length * 2];
            Entry[] old = table;
            table = nt;
            for (int i = 0; i < size; i++) {
                linkEntry(seq[i]);
            }
            // old table discarded
            old = null;
        }
    }

    private boolean insertInternal(int index, Object item, int hash) {
        if (find(item, hash) != null) {
            return false;
        }
        ensureCapacityForOneMore();
        if (index < size) {
            System.arraycopy(seq, index, seq, index + 1, size - index);
        }
        Entry e = new Entry(item, hash, index);
        seq[index] = e;
        size++;
        for (int i = index + 1; i < size; i++) {
            seq[i].index = i;
        }
        linkEntry(e);
        return true;
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            return insertInternal(size, item, h);
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            return insertInternal(index, item, h);
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            return (T) seq[index].item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            Entry existing = find(item, h);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("equal item already present at another index");
            }
            Entry old = seq[index];
            Object prev = old.item;
            if (existing != null) {
                // Equal to the item at this index: replace the representative.
                old.item = item;
                old.hash = h;
                return (T) prev;
            }
            unlinkEntry(old);
            Entry e = new Entry(item, h, index);
            seq[index] = e;
            linkEntry(e);
            return (T) prev;
        }
    }

    private Object removeInternal(int index) {
        Entry e = seq[index];
        unlinkEntry(e);
        int last = size - 1;
        if (index < last) {
            System.arraycopy(seq, index + 1, seq, index, last - index);
        }
        seq[last] = null;
        size = last;
        for (int i = index; i < size; i++) {
            seq[i].index = i;
        }
        return e.item;
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            return (T) removeInternal(index);
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            Entry e = find(item, h);
            if (e == null) {
                return false;
            }
            removeInternal(e.index);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            return find(item, h) != null;
        }
    }

    public int indexOf(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            Entry e = find(item, h);
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
