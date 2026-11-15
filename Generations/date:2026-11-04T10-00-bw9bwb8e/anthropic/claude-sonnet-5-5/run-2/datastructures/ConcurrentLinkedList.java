import java.lang.*;

@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}


    // Policy: the list is unbounded, so insertions never block.
    // Null elements are not permitted (null is used as the "absent" result).
    // takeFirst/takeLast block while the list is empty (guarded by while loops).
    // pollFirst/pollLast/peek* are non-blocking and return null when empty.
    // A timed pollFirst(timeout) is also provided.

    private static final class Node<E> {
        E value;
        Node<E> prev;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();

    // All fields below are guarded by 'lock'.
    private final Node<T> head = new Node<T>(null); // sentinel
    private final Node<T> tail = new Node<T>(null); // sentinel
    private int size;

    public ConcurrentLinkedList() {
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    // ---- internal helpers (must be called with lock held) ----

    private void linkBefore(Node<T> succ, T value) {
        Node<T> pred = succ.prev;
        Node<T> n = new Node<T>(value);
        n.prev = pred;
        n.next = succ;
        pred.next = n;
        succ.prev = n;
        size++;
    }

    private T unlink(Node<T> n) {
        T v = n.value;
        n.prev.next = n.next;
        n.next.prev = n.prev;
        n.prev = null;
        n.next = null;
        n.value = null;
        size--;
        return v;
    }

    private Node<T> nodeAt(int index) {
        Node<T> cur;
        if (index < (size >> 1)) {
            cur = head.next;
            for (int i = 0; i < index; i++) {
                cur = cur.next;
            }
        } else {
            cur = tail;
            for (int i = size; i > index; i--) {
                cur = cur.prev;
            }
        }
        return cur;
    }

    private static boolean same(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    // ---- public operations ----

    public void addFirst(T value) {
        if (value == null) throw new NullPointerException("null elements not permitted");
        synchronized (lock) {
            linkBefore(head.next, value);
            lock.notifyAll();
        }
    }

    public void addLast(T value) {
        if (value == null) throw new NullPointerException("null elements not permitted");
        synchronized (lock) {
            linkBefore(tail, value);
            lock.notifyAll();
        }
    }

    public void add(T value) {
        addLast(value);
    }

    public void add(int index, T value) {
        if (value == null) throw new NullPointerException("null elements not permitted");
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            Node<T> succ = (index == size) ? tail : nodeAt(index);
            linkBefore(succ, value);
            lock.notifyAll();
        }
    }

    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return nodeAt(index).value;
        }
    }

    public T set(int index, T value) {
        if (value == null) throw new NullPointerException("null elements not permitted");
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            Node<T> n = nodeAt(index);
            T old = n.value;
            n.value = value;
            return old;
        }
    }

    public T remove(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return unlink(nodeAt(index));
        }
    }

    public boolean remove(Object value) {
        if (value == null) return false;
        synchronized (lock) {
            for (Node<T> cur = head.next; cur != tail; cur = cur.next) {
                if (same(value, cur.value)) {
                    unlink(cur);
                    return true;
                }
            }
            return false;
        }
    }

    public T pollFirst() {
        synchronized (lock) {
            if (size == 0) return null;
            return unlink(head.next);
        }
    }

    public T pollLast() {
        synchronized (lock) {
            if (size == 0) return null;
            return unlink(tail.prev);
        }
    }

    // Waits up to timeoutMillis for an element; returns null on timeout.
    public T pollFirst(long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            if (timeoutMillis <= 0) {
                return size == 0 ? null : unlink(head.next);
            }
            long deadline = System.nanoTime() + timeoutMillis * 1000000L;
            while (size == 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) return null;
                long ms = remainingNanos / 1000000L;
                int ns = (int) (remainingNanos % 1000000L);
                if (ms == 0 && ns == 0) ns = 1;
                lock.wait(ms, ns);
            }
            return unlink(head.next);
        }
    }

    // Blocks while the list is empty.
    public T takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlink(head.next);
        }
    }

    // Blocks while the list is empty.
    public T takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlink(tail.prev);
        }
    }

    public T peekFirst() {
        synchronized (lock) {
            return size == 0 ? null : head.next.value;
        }
    }

    public T peekLast() {
        synchronized (lock) {
            return size == 0 ? null : tail.prev.value;
        }
    }

    public boolean contains(Object value) {
        return indexOf(value) >= 0;
    }

    public int indexOf(Object value) {
        if (value == null) return -1;
        synchronized (lock) {
            int i = 0;
            for (Node<T> cur = head.next; cur != tail; cur = cur.next, i++) {
                if (same(value, cur.value)) return i;
            }
            return -1;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            Node<T> cur = head.next;
            while (cur != tail) {
                Node<T> next = cur.next;
                cur.prev = null;
                cur.next = null;
                cur.value = null;
                cur = next;
            }
            head.next = tail;
            tail.prev = head;
            size = 0;
        }
    }

    // Atomic snapshot of the contents.
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int i = 0;
            for (Node<T> cur = head.next; cur != tail; cur = cur.next) {
                result[i++] = cur.value;
            }
            return result;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder("[");
            for (Node<T> cur = head.next; cur != tail; cur = cur.next) {
                if (cur != head.next) sb.append(", ");
                sb.append(String.valueOf(cur.value));
            }
            sb.append(']');
            return sb.toString();
        }
    }
}
