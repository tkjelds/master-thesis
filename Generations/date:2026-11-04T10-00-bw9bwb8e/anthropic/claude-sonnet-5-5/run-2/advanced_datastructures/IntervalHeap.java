import java.lang.SuppressWarnings;

interface Order<T> {
    int compare(T left, T right);
}

/**
 * Opaque handle identifying one insertion into an IntervalHeap.
 * It exposes no state; the heap tracks the entry's location internally.
 */
@Handle.ThreadSafe
abstract class Handle<T> {
    @interface ThreadSafe {}

    Handle() {
    }
}

@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}


    /** Entry identity object; also serves as the handle returned to callers. */
    private static final class Node<T> extends Handle<T> {
        T item;
        int pos;          // slot index in the heap array, guarded by the heap lock
        Object owner;     // owning heap
        boolean live;     // false permanently once removed

        Node(T item, int pos, Object owner) {
            this.item = item;
            this.pos = pos;
            this.owner = owner;
            this.live = true;
        }
    }

    /*
     * Layout: node i occupies slots 2i (low) and 2i+1 (high). Parent of node i is (i-1)/2.
     * Slots 0..n-1 are used. If n is odd, the last node is a singleton that acts as both
     * the low and the high endpoint.
     */
    private final Object lock = new Object();
    private final Order<T> order;
    private Node<T>[] a;
    private int n;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.a = (Node<T>[]) new Node[16];
        this.n = 0;
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (n == a.length) {
                grow();
            }
            Node<T> nd = new Node<T>(item, n, this);
            a[n] = nd;
            n++;
            repair(n - 1);
            return nd;
        }
    }

    public T findMin() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return a[0].item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return a[hs(0)].item;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            if (n == 0) {
                throw new IllegalStateException("empty");
            }
            return removeAt(hs(0));
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (lock) {
            Node<T> nd = validate(handle);
            return removeAt(nd.pos);
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
            Node<T> nd = validate(handle);
            T old = nd.item;
            nd.item = item;
            repair(nd.pos);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return n;
        }
    }

    // ---------------------------------------------------------------- internals

    @SuppressWarnings("unchecked")
    private Node<T> validate(Handle<T> handle) {
        if (!(handle instanceof Node)) {
            throw new IllegalArgumentException("foreign handle");
        }
        Node<T> nd = (Node<T>) handle;
        if (nd.owner != this || !nd.live) {
            throw new IllegalArgumentException("invalid handle");
        }
        int p = nd.pos;
        if (p < 0 || p >= n || a[p] != nd) {
            throw new IllegalArgumentException("invalid handle");
        }
        return nd;
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        int newLen = a.length * 2;
        if (newLen < 0) {
            throw new OutOfMemoryError("heap too large");
        }
        Node<T>[] b = (Node<T>[]) new Node[newLen];
        System.arraycopy(a, 0, b, 0, n);
        a = b;
    }

    private int cmp(int i, int j) {
        return order.compare(a[i].item, a[j].item);
    }

    /** High slot of node c (its low slot if it is a singleton). */
    private int hs(int c) {
        return (2 * c + 1 < n) ? 2 * c + 1 : 2 * c;
    }

    private void swap(int s, int t) {
        Node<T> x = a[s];
        Node<T> y = a[t];
        a[s] = y;
        a[t] = x;
        y.pos = s;
        x.pos = t;
    }

    /** Removes the entry at slot s, returns its item and permanently invalidates it. */
    private T removeAt(int s) {
        Node<T> r = a[s];
        T item = r.item;
        int last = n - 1;
        if (s == last) {
            a[last] = null;
            n--;
        } else {
            Node<T> l = a[last];
            a[last] = null;
            n--;
            a[s] = l;
            l.pos = s;
            repair(s);
        }
        r.live = false;
        r.pos = -1;
        r.owner = null;
        r.item = null;
        return item;
    }

    /**
     * Restores the invariants when the entry at slot s may violate constraints
     * involving it, all other constraints holding.
     */
    private void repair(int s) {
        int i = s >> 1;
        boolean hasHigh = (2 * i + 1 < n);
        if ((s & 1) == 0) {
            if (!hasHigh) {
                bubbleUpMin(i);
                bubbleUpMax(i);
            } else if (cmp(s, s + 1) > 0) {
                swap(s, s + 1);
                siftDownMin(i);
                bubbleUpMax(i);
            } else {
                bubbleUpMin(i);
                siftDownMin(i);
            }
        } else {
            if (cmp(s - 1, s) > 0) {
                swap(s - 1, s);
                siftDownMax(i);
                bubbleUpMin(i);
            } else {
                bubbleUpMax(i);
                siftDownMax(i);
            }
        }
    }

    private void bubbleUpMin(int i) {
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (cmp(2 * i, 2 * p) < 0) {
                swap(2 * i, 2 * p);
                i = p;
            } else {
                break;
            }
        }
    }

    private void bubbleUpMax(int i) {
        while (i > 0) {
            int p = (i - 1) >> 1;
            int h = hs(i);
            if (cmp(h, 2 * p + 1) > 0) {
                swap(h, 2 * p + 1);
                i = p;
            } else {
                break;
            }
        }
    }

    private void siftDownMin(int i) {
        while (true) {
            int c = 2 * i + 1;
            if (2 * c >= n) {
                return;
            }
            int d = c + 1;
            if (2 * d < n && cmp(2 * d, 2 * c) < 0) {
                c = d;
            }
            if (cmp(2 * c, 2 * i) >= 0) {
                return;
            }
            swap(2 * i, 2 * c);
            if (2 * c + 1 < n && cmp(2 * c, 2 * c + 1) > 0) {
                swap(2 * c, 2 * c + 1);
            }
            i = c;
        }
    }

    private void siftDownMax(int i) {
        while (true) {
            int c = 2 * i + 1;
            if (2 * c >= n) {
                return;
            }
            int d = c + 1;
            int hc = hs(c);
            if (2 * d < n) {
                int hd = hs(d);
                if (cmp(hd, hc) > 0) {
                    c = d;
                    hc = hd;
                }
            }
            int hi = 2 * i + 1;
            if (cmp(hc, hi) <= 0) {
                return;
            }
            swap(hi, hc);
            if (hc == 2 * c + 1 && cmp(2 * c, 2 * c + 1) > 0) {
                swap(2 * c, 2 * c + 1);
            }
            i = c;
        }
    }
}
