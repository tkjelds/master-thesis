@Handle.ThreadSafe
abstract class Handle<T> {
    @interface ThreadSafe {}

    Handle() {
    }
}

interface Order<T> {
    int compare(T left, T right);
}

@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}


    private static final class Entry<T> extends Handle<T> {
        final IntervalHeap<T> owner;
        T item;
        int pos; // slot index in array, -1 when removed

        Entry(IntervalHeap<T> owner, T item) {
            this.owner = owner;
            this.item = item;
            this.pos = -1;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Entry<T>[] arr;
    private int n; // number of occupied slots; node k owns slots 2k (low) and 2k+1 (high)

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.arr = (Entry<T>[]) new Entry[16];
        this.n = 0;
    }

    // ---------- internal helpers (call with lock held) ----------

    private int cmp(Entry<T> a, Entry<T> b) {
        return order.compare(a.item, b.item);
    }

    private void set(int i, Entry<T> e) {
        arr[i] = e;
        e.pos = i;
    }

    private int hs(int k) {
        return (2 * k + 1 < n) ? 2 * k + 1 : 2 * k;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity() {
        if (n < arr.length) {
            return;
        }
        int newCap = arr.length * 2;
        if (newCap < 0) {
            throw new IllegalStateException("capacity exceeded");
        }
        Entry<T>[] na = (Entry<T>[]) new Entry[newCap];
        System.arraycopy(arr, 0, na, 0, n);
        arr = na;
    }

    private void bubbleUpLow(int k, Entry<T> e) {
        while (k > 0) {
            int p = (k - 1) >> 1;
            if (cmp(e, arr[2 * p]) < 0) {
                set(2 * k, arr[2 * p]);
                k = p;
            } else {
                break;
            }
        }
        set(2 * k, e);
    }

    private void bubbleUpHigh(int k, Entry<T> e) {
        int slot = hs(k);
        while (k > 0) {
            int p = (k - 1) >> 1;
            if (cmp(e, arr[2 * p + 1]) > 0) {
                set(slot, arr[2 * p + 1]);
                k = p;
                slot = 2 * p + 1;
            } else {
                break;
            }
        }
        set(slot, e);
    }

    private void siftDownLow(int k, Entry<T> x) {
        while (true) {
            int c1 = 2 * k + 1;
            int c2 = 2 * k + 2;
            int best = -1;
            if (2 * c1 < n) {
                best = c1;
                if (2 * c2 < n && cmp(arr[2 * c2], arr[2 * c1]) < 0) {
                    best = c2;
                }
            }
            if (best < 0 || cmp(x, arr[2 * best]) <= 0) {
                break;
            }
            set(2 * k, arr[2 * best]);
            k = best;
        }
        int h = hs(k);
        if (h != 2 * k) {
            if (cmp(x, arr[h]) > 0) {
                set(2 * k, arr[h]);
                set(h, x);
                bubbleUpHigh(k, x);
            } else {
                set(2 * k, x);
            }
        } else {
            set(2 * k, x);
            bubbleUpHigh(k, x);
        }
    }

    private void siftDownHigh(int k, Entry<T> x) {
        while (true) {
            int c1 = 2 * k + 1;
            int c2 = 2 * k + 2;
            int best = -1;
            if (2 * c1 < n) {
                best = c1;
                if (2 * c2 < n && cmp(arr[hs(c2)], arr[hs(c1)]) > 0) {
                    best = c2;
                }
            }
            if (best < 0 || cmp(x, arr[hs(best)]) >= 0) {
                break;
            }
            set(hs(k), arr[hs(best)]);
            k = best;
        }
        int h = hs(k);
        if (h != 2 * k) {
            if (cmp(x, arr[2 * k]) < 0) {
                set(h, arr[2 * k]);
                set(2 * k, x);
                bubbleUpLow(k, x);
            } else {
                set(h, x);
            }
        } else {
            set(2 * k, x);
            bubbleUpLow(k, x);
        }
    }

    // Place e into slot i, whose previous occupant is gone or is being
    // re-evaluated (valid in the old structure), and restore the invariants.
    private void fix(int i, Entry<T> e) {
        int k = i >> 1;
        if ((i & 1) == 0) {
            int h = hs(k);
            if (h == i) {
                set(i, e);
                if (k > 0) {
                    int p = (k - 1) >> 1;
                    if (cmp(e, arr[2 * p]) < 0) {
                        bubbleUpLow(k, e);
                    } else if (cmp(e, arr[2 * p + 1]) > 0) {
                        bubbleUpHigh(k, e);
                    }
                }
                return;
            }
            Entry<T> hi = arr[h];
            if (cmp(e, hi) <= 0) {
                set(i, e);
                if (k > 0 && cmp(e, arr[2 * ((k - 1) >> 1)]) < 0) {
                    bubbleUpLow(k, e);
                } else {
                    siftDownLow(k, e);
                }
            } else {
                set(i, hi);
                set(h, e);
                bubbleUpHigh(k, e);
                siftDownLow(k, hi);
            }
        } else {
            int l = i - 1;
            Entry<T> lo = arr[l];
            if (cmp(e, lo) >= 0) {
                set(i, e);
                if (k > 0 && cmp(e, arr[2 * ((k - 1) >> 1) + 1]) > 0) {
                    bubbleUpHigh(k, e);
                } else {
                    siftDownHigh(k, e);
                }
            } else {
                set(i, lo);
                set(l, e);
                bubbleUpLow(k, e);
                siftDownHigh(k, lo);
            }
        }
    }

    private void removeAt(int i) {
        Entry<T> removed = arr[i];
        int last = n - 1;
        Entry<T> moved = arr[last];
        arr[last] = null;
        n--;
        removed.pos = -1;
        if (i != last) {
            fix(i, moved);
        }
    }

    private Entry<T> validate(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (!(handle instanceof Entry)) {
            throw new IllegalArgumentException("foreign handle");
        }
        @SuppressWarnings("unchecked")
        Entry<T> e = (Entry<T>) handle;
        if (e.owner != this) {
            throw new IllegalArgumentException("foreign handle");
        }
        if (e.pos < 0) {
            throw new IllegalArgumentException("invalid handle");
        }
        return e;
    }

    // ---------- public operations ----------

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            ensureCapacity();
            Entry<T> e = new Entry<T>(this, item);
            int i = n;
            n++;
            set(i, e);
            int k = i >> 1;
            if ((i & 1) == 1) {
                Entry<T> lo = arr[i - 1];
                if (cmp(e, lo) < 0) {
                    set(i, lo);
                    set(i - 1, e);
                    bubbleUpLow(k, e);
                } else {
                    bubbleUpHigh(k, e);
                }
            } else if (k > 0) {
                int p = (k - 1) >> 1;
                if (cmp(e, arr[2 * p]) < 0) {
                    bubbleUpLow(k, e);
                } else {
                    bubbleUpHigh(k, e);
                }
            }
            return e;
        }
    }

    public T findMin() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return arr[0].item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return (n == 1 ? arr[0] : arr[1]).item;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            T item = arr[0].item;
            removeAt(0);
            return item;
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            int i = (n == 1) ? 0 : 1;
            T item = arr[i].item;
            removeAt(i);
            return item;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (lock) {
            Entry<T> e = validate(handle);
            T item = e.item;
            removeAt(e.pos);
            return item;
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
            e.item = item;
            fix(e.pos, e);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return n;
        }
    }
}
