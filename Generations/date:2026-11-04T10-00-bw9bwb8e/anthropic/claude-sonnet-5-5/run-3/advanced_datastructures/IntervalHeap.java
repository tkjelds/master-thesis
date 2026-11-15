interface Order<T> {
    int compare(T left, T right);
}

interface Handle<T> {
}

@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}


    private static final class Entry<T> implements Handle<T> {
        final IntervalHeap<T> owner;
        T item;
        int pos;
        boolean active;

        Entry(IntervalHeap<T> owner, T item) {
            this.owner = owner;
            this.item = item;
            this.pos = -1;
            this.active = false;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Entry<T>[] heap;
    private int n;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.heap = (Entry<T>[]) new Entry[16];
        this.n = 0;
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Entry<T> e = new Entry<T>(this, item);
            insert(e);
            return e;
        }
    }

    public T findMin() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return heap[0].item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return (n == 1 ? heap[0] : heap[1]).item;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            Entry<T> e = heap[0];
            removeAt(0);
            e.active = false;
            e.pos = -1;
            return e.item;
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            int idx = (n == 1) ? 0 : 1;
            Entry<T> e = heap[idx];
            removeAt(idx);
            e.active = false;
            e.pos = -1;
            return e.item;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (lock) {
            Entry<T> e = validate(handle);
            removeAt(e.pos);
            e.active = false;
            e.pos = -1;
            return e.item;
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Entry<T> e = validate(handle);
            T old = e.item;
            removeAt(e.pos);
            e.item = item;
            insert(e);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return n;
        }
    }

    // ---- internals; all called with lock held ----

    @SuppressWarnings("unchecked")
    private Entry<T> validate(Handle<T> handle) {
        if (!(handle instanceof Entry)) {
            throw new IllegalArgumentException("foreign handle");
        }
        Entry<?> raw = (Entry<?>) handle;
        if (raw.owner != this) {
            throw new IllegalArgumentException("foreign handle");
        }
        Entry<T> e = (Entry<T>) raw;
        if (!e.active || e.pos < 0 || e.pos >= n || heap[e.pos] != e) {
            throw new IllegalArgumentException("invalid handle");
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    private void insert(Entry<T> e) {
        if (n == heap.length) {
            if (n >= Integer.MAX_VALUE / 2) {
                throw new IllegalStateException("capacity exceeded");
            }
            Entry<T>[] bigger = (Entry<T>[]) new Entry[n * 2];
            for (int i = 0; i < n; i++) {
                bigger[i] = heap[i];
            }
            heap = bigger;
        }
        heap[n] = e;
        e.pos = n;
        e.active = true;
        n++;
        repair(n - 1);
    }

    // Removes the entry at index i from the structure; does not alter its flags.
    private void removeAt(int i) {
        Entry<T> last = heap[n - 1];
        heap[n - 1] = null;
        n--;
        if (i < n) {
            heap[i] = last;
            last.pos = i;
            repair(i);
        }
    }

    private int cmp(int i, int j) {
        return order.compare(heap[i].item, heap[j].item);
    }

    private void swap(int i, int j) {
        Entry<T> a = heap[i];
        Entry<T> b = heap[j];
        heap[i] = b;
        heap[j] = a;
        b.pos = i;
        a.pos = j;
    }

    private void repair(int i) {
        if ((i & 1) == 0) {
            int p = i + 1;
            if (p < n) {
                if (cmp(i, p) > 0) {
                    swap(i, p);
                    siftDownMin(i);
                    siftUpMax(p);
                } else {
                    int j = siftUpMin(i);
                    if (j == i) {
                        siftDownMin(i);
                    }
                }
            } else {
                singletonUp(i);
            }
        } else {
            int l = i - 1;
            if (cmp(l, i) > 0) {
                swap(l, i);
                siftUpMin(l);
                siftDownMax(i);
            } else {
                int j = siftUpMax(i);
                if (j == i) {
                    siftDownMax(i);
                }
            }
        }
    }

    private void singletonUp(int i) {
        int k = i >> 1;
        if (k == 0) {
            return;
        }
        int p = (k - 1) >> 1;
        if (cmp(i, 2 * p) < 0) {
            siftUpMin(i);
        } else if (cmp(i, 2 * p + 1) > 0) {
            siftUpMax(i);
        }
    }

    private int siftUpMin(int i) {
        int k = i >> 1;
        while (k > 0) {
            int p = (k - 1) >> 1;
            int pl = 2 * p;
            if (cmp(i, pl) < 0) {
                swap(i, pl);
                i = pl;
                k = p;
            } else {
                break;
            }
        }
        return i;
    }

    private int siftUpMax(int i) {
        int k = i >> 1;
        while (k > 0) {
            int p = (k - 1) >> 1;
            int ph = 2 * p + 1;
            if (cmp(i, ph) > 0) {
                swap(i, ph);
                i = ph;
                k = p;
            } else {
                break;
            }
        }
        return i;
    }

    private void siftDownMin(int i) {
        while (true) {
            int k = i >> 1;
            int c = 2 * k + 1;
            if (2 * c >= n) {
                return;
            }
            int best = 2 * c;
            int c2 = c + 1;
            if (2 * c2 < n && cmp(2 * c2, best) < 0) {
                best = 2 * c2;
            }
            if (cmp(best, i) >= 0) {
                return;
            }
            swap(i, best);
            if (best + 1 < n && cmp(best, best + 1) > 0) {
                swap(best, best + 1);
            }
            i = best;
        }
    }

    private int hi(int c) {
        return (2 * c + 1 < n) ? 2 * c + 1 : 2 * c;
    }

    private void siftDownMax(int i) {
        while (true) {
            int k = i >> 1;
            int c = 2 * k + 1;
            if (2 * c >= n) {
                return;
            }
            int best = hi(c);
            int c2 = c + 1;
            if (2 * c2 < n) {
                int h2 = hi(c2);
                if (cmp(h2, best) > 0) {
                    best = h2;
                }
            }
            if (cmp(best, i) <= 0) {
                return;
            }
            swap(i, best);
            if ((best & 1) == 1 && cmp(best - 1, best) > 0) {
                swap(best - 1, best);
            }
            i = best;
        }
    }
}
