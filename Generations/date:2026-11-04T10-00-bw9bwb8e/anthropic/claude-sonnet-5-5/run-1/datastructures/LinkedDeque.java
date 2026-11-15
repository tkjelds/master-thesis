@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}


    // Policy:
    //  - The deque is bounded (capacity given at construction, default Integer.MAX_VALUE).
    //  - addFirst/addLast block while the deque is full.
    //  - removeFirst/removeLast block while the deque is empty.
    //  - offerX / pollX are non-blocking and return false / null immediately if they cannot complete.
    //  - Timed offerX / pollX block up to the given timeout.
    //  - Blocking operations respond to interruption by throwing InterruptedException.
    //  - Null elements are not permitted.

    private static final class Node<E> {
        final E item;
        Node<E> prev;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // All fields below are guarded by 'lock'.
    private Node<E> head;
    private Node<E> tail;
    private int count;

    public LinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
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
        count++;
        lock.notifyAll();
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
        count++;
        lock.notifyAll();
    }

    private E unlinkFirst() {
        Node<E> n = head;
        head = n.next;
        if (head == null) {
            tail = null;
        } else {
            head.prev = null;
        }
        n.next = null;
        count--;
        lock.notifyAll();
        return n.item;
    }

    private E unlinkLast() {
        Node<E> n = tail;
        tail = n.prev;
        if (tail == null) {
            head = null;
        } else {
            tail.next = null;
        }
        n.prev = null;
        count--;
        lock.notifyAll();
        return n.item;
    }

    private static void checkNotNull(Object e) {
        if (e == null) {
            throw new NullPointerException();
        }
    }

    // ---- blocking operations ----

    public void addFirst(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            linkFirst(e);
        }
    }

    public void addLast(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            linkLast(e);
        }
    }

    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    public E removeLast() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    // ---- non-blocking operations ----

    public boolean offerFirst(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            linkFirst(e);
            return true;
        }
    }

    public boolean offerLast(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            linkLast(e);
            return true;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    // ---- timed operations ----

    public boolean offerFirst(E e, long timeoutNanos) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutNanos;
            while (count == capacity) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            linkFirst(e);
            return true;
        }
    }

    public boolean offerLast(E e, long timeoutNanos) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutNanos;
            while (count == capacity) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            linkLast(e);
            return true;
        }
    }

    public E pollFirst(long timeoutNanos) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutNanos;
            while (count == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            return unlinkFirst();
        }
    }

    public E pollLast(long timeoutNanos) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutNanos;
            while (count == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            return unlinkLast();
        }
    }

    // ---- inspection ----

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return count == capacity;
        }
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (lock) {
            Node<E> n = head;
            while (n != null) {
                Node<E> next = n.next;
                n.prev = null;
                n.next = null;
                n = next;
            }
            head = null;
            tail = null;
            count = 0;
            lock.notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[count];
            int i = 0;
            for (Node<E> n = head; n != null; n = n.next) {
                result[i++] = n.item;
            }
            return result;
        }
    }
}
