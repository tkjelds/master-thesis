@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        Node<T> prev;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    // Single intrinsic lock guarding all state. Every read and write of head,
    // tail, size and node links happens inside synchronized (lock), which also
    // gives the needed happens-before edges (safe publication and visibility).
    private final Object lock = new Object();

    private Node<E> head;
    private Node<E> tail;
    private int size;

    // Policy: null elements are rejected with NullPointerException.
    // Insertions never block (the list is unbounded).
    // take* operations block while the list is empty; poll*/remove* operations
    // return immediately (null / false / exception on failure as documented).

    public ConcurrentLinkedList() {
    }

    // ---- internal helpers (must be called while holding lock) ----

    private void linkFirst(E e) {
        Node<E> n = new Node<E>(e);
        if (head == null) {
            head = n;
            tail = n;
        } else {
            n.next = head;
            head.prev = n;
            head = n;
        }
        size++;
    }

    private void linkLast(E e) {
        Node<E> n = new Node<E>(e);
        if (tail == null) {
            head = n;
            tail = n;
        } else {
            n.prev = tail;
            tail.next = n;
            tail = n;
        }
        size++;
    }

    private E unlink(Node<E> n) {
        E item = n.item;
        Node<E> p = n.prev;
        Node<E> nx = n.next;
        if (p == null) {
            head = nx;
        } else {
            p.next = nx;
        }
        if (nx == null) {
            tail = p;
        } else {
            nx.prev = p;
        }
        n.item = null;
        n.prev = null;
        n.next = null;
        size--;
        return item;
    }

    private Node<E> nodeAt(int index) {
        if (index < (size >> 1)) {
            Node<E> cur = head;
            for (int i = 0; i < index; i++) {
                cur = cur.next;
            }
            return cur;
        } else {
            Node<E> cur = tail;
            for (int i = size - 1; i > index; i--) {
                cur = cur.prev;
            }
            return cur;
        }
    }

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }

    // ---- public operations ----

    public void addFirst(E e) {
        requireNonNull(e);
        synchronized (lock) {
            linkFirst(e);
            lock.notifyAll();
        }
    }

    public void addLast(E e) {
        requireNonNull(e);
        synchronized (lock) {
            linkLast(e);
            lock.notifyAll();
        }
    }

    public void add(E e) {
        addLast(e);
    }

    public void add(int index, E e) {
        requireNonNull(e);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (index == size) {
                linkLast(e);
            } else if (index == 0) {
                linkFirst(e);
            } else {
                Node<E> succ = nodeAt(index);
                Node<E> n = new Node<E>(e);
                Node<E> pred = succ.prev;
                n.prev = pred;
                n.next = succ;
                pred.next = n;
                succ.prev = n;
                size++;
            }
            lock.notifyAll();
        }
    }

    /** Removes and returns the first element, or null if empty. */
    public E pollFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            return unlink(head);
        }
    }

    /** Removes and returns the last element, or null if empty. */
    public E pollLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }
            return unlink(tail);
        }
    }

    /** Removes and returns the first element, blocking while the list is empty. */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return unlink(head);
        }
    }

    /** Removes and returns the last element, blocking while the list is empty. */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (tail == null) {
                lock.wait();
            }
            return unlink(tail);
        }
    }

    /** Returns the first element or null if empty (without removing). */
    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    /** Returns the last element or null if empty (without removing). */
    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return nodeAt(index).item;
        }
    }

    public E set(int index, E e) {
        requireNonNull(e);
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            Node<E> n = nodeAt(index);
            E old = n.item;
            n.item = e;
            return old;
        }
    }

    public E removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return unlink(nodeAt(index));
        }
    }

    /** Removes the first occurrence of the element; returns true if removed. */
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            for (Node<E> cur = head; cur != null; cur = cur.next) {
                if (o.equals(cur.item)) {
                    unlink(cur);
                    return true;
                }
            }
            return false;
        }
    }

    public int indexOf(Object o) {
        if (o == null) {
            return -1;
        }
        synchronized (lock) {
            int i = 0;
            for (Node<E> cur = head; cur != null; cur = cur.next, i++) {
                if (o.equals(cur.item)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    /** Atomically adds the element only if it is not already present. */
    public boolean addIfAbsent(E e) {
        requireNonNull(e);
        synchronized (lock) {
            for (Node<E> cur = head; cur != null; cur = cur.next) {
                if (e.equals(cur.item)) {
                    return false;
                }
            }
            linkLast(e);
            lock.notifyAll();
            return true;
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
            Node<E> cur = head;
            while (cur != null) {
                Node<E> next = cur.next;
                cur.item = null;
                cur.prev = null;
                cur.next = null;
                cur = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    /** Returns a consistent snapshot of the contents. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] arr = new Object[size];
            int i = 0;
            for (Node<E> cur = head; cur != null; cur = cur.next) {
                arr[i++] = cur.item;
            }
            return arr;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (Node<E> cur = head; cur != null; cur = cur.next) {
                if (cur != head) {
                    sb.append(", ");
                }
                sb.append(cur.item);
            }
            sb.append(']');
            return sb.toString();
        }
    }
}
