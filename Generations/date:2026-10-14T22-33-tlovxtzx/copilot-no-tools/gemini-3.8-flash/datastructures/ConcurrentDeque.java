@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<E> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        Node<T> prev;
        Node<T> next;

        Node(T item, Node<T> prev, Node<T> next) {
            this.item = item;
            this.prev = prev;
            this.next = next;
        }
    }

    private final int capacity;
    private int count;
    private Node<E> head;
    private Node<E> tail;
    private final Object lock = new Object();

    public ConcurrentDeque() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        this.capacity = capacity;
        this.count = 0;
        this.head = null;
        this.tail = null;
    }

    private void linkFirst(E e) {
        Node<E> f = head;
        Node<E> newNode = new Node<E>(e, null, f);
        head = newNode;
        if (f == null) {
            tail = newNode;
        } else {
            f.prev = newNode;
        }
        count++;
        lock.notifyAll();
    }

    private void linkLast(E e) {
        Node<E> t = tail;
        Node<E> newNode = new Node<E>(e, t, null);
        tail = newNode;
        if (t == null) {
            head = newNode;
        } else {
            t.next = newNode;
        }
        count++;
        lock.notifyAll();
    }

    private E unlinkFirst() {
        Node<E> f = head;
        E item = f.item;
        Node<E> next = f.next;
        f.item = null;
        f.next = null;
        head = next;
        if (next == null) {
            tail = null;
        } else {
            next.prev = null;
        }
        count--;
        lock.notifyAll();
        return item;
    }

    private E unlinkLast() {
        Node<E> t = tail;
        E item = t.item;
        Node<E> prev = t.prev;
        t.item = null;
        t.prev = null;
        tail = prev;
        if (prev == null) {
            head = null;
        } else {
            prev.next = null;
        }
        count--;
        lock.notifyAll();
        return item;
    }

    private void unlink(Node<E> x) {
        Node<E> prev = x.prev;
        Node<E> next = x.next;

        if (prev == null) {
            head = next;
        } else {
            prev.next = next;
            x.prev = null;
        }

        if (next == null) {
            tail = prev;
        } else {
            next.prev = prev;
            x.next = null;
        }

        x.item = null;
        count--;
        lock.notifyAll();
    }

    public void putFirst(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            linkFirst(e);
        }
    }

    public void putLast(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            linkLast(e);
        }
    }

    public boolean offerFirst(E e) {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            linkFirst(e);
            return true;
        }
    }

    public boolean offerLast(E e) {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            linkLast(e);
            return true;
        }
    }

    public boolean offerFirst(E e, long timeoutMillis) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        if (timeoutMillis <= 0) {
            return offerFirst(e);
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long deadline = (timeoutMillis > Long.MAX_VALUE - now) ? Long.MAX_VALUE : (now + timeoutMillis);
            while (count == capacity) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(remaining);
            }
            linkFirst(e);
            return true;
        }
    }

    public boolean offerLast(E e, long timeoutMillis) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        if (timeoutMillis <= 0) {
            return offerLast(e);
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long deadline = (timeoutMillis > Long.MAX_VALUE - now) ? Long.MAX_VALUE : (now + timeoutMillis);
            while (count == capacity) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(remaining);
            }
            linkLast(e);
            return true;
        }
    }

    public void addFirst(E e) {
        if (!offerFirst(e)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public void addLast(E e) {
        if (!offerLast(e)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            return unlinkLast();
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

    public E pollFirst(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0) {
            return pollFirst();
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long deadline = (timeoutMillis > Long.MAX_VALUE - now) ? Long.MAX_VALUE : (now + timeoutMillis);
            while (count == 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining);
            }
            return unlinkFirst();
        }
    }

    public E pollLast(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0) {
            return pollLast();
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long deadline = (timeoutMillis > Long.MAX_VALUE - now) ? Long.MAX_VALUE : (now + timeoutMillis);
            while (count == 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining);
            }
            return unlinkLast();
        }
    }

    public E removeFirst() {
        E x = pollFirst();
        if (x == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return x;
    }

    public E removeLast() {
        E x = pollLast();
        if (x == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return x;
    }

    public E peekFirst() {
        synchronized (lock) {
            return (head == null) ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return (tail == null) ? null : tail.item;
        }
    }

    public E getFirst() {
        E x = peekFirst();
        if (x == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return x;
    }

    public E getLast() {
        E x = peekLast();
        if (x == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return x;
    }

    public void push(E e) {
        addFirst(e);
    }

    public E pop() {
        return removeFirst();
    }

    public void add(E e) {
        addLast(e);
    }

    public boolean offer(E e) {
        return offerLast(e);
    }

    public void put(E e) throws InterruptedException {
        putLast(e);
    }

    public boolean offer(E e, long timeoutMillis) throws InterruptedException {
        return offerLast(e, timeoutMillis);
    }

    public E remove() {
        return removeFirst();
    }

    public E poll() {
        return pollFirst();
    }

    public E take() throws InterruptedException {
        return takeFirst();
    }

    public E poll(long timeoutMillis) throws InterruptedException {
        return pollFirst(timeoutMillis);
    }

    public E element() {
        return getFirst();
    }

    public E peek() {
        return peekFirst();
    }

    public boolean removeFirstOccurrence(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            for (Node<E> p = head; p != null; p = p.next) {
                if (o.equals(p.item)) {
                    unlink(p);
                    return true;
                }
            }
            return false;
        }
    }

    public boolean removeLastOccurrence(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            for (Node<E> p = tail; p != null; p = p.prev) {
                if (o.equals(p.item)) {
                    unlink(p);
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            for (Node<E> p = head; p != null; p = p.next) {
                if (o.equals(p.item)) {
                    return true;
                }
            }
            return false;
        }
    }

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

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - count;
        }
    }

    public int getCapacity() {
        return capacity;
    }

    public void clear() {
        synchronized (lock) {
            for (Node<E> p = head; p != null; ) {
                Node<E> next = p.next;
                p.item = null;
                p.prev = null;
                p.next = null;
                p = next;
            }
            head = null;
            tail = null;
            count = 0;
            lock.notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] a = new Object[count];
            int i = 0;
            for (Node<E> p = head; p != null; p = p.next) {
                a[i++] = p.item;
            }
            return a;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            if (head == null) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (Node<E> p = head; p != null; p = p.next) {
                sb.append(p.item == this ? "(this Deque)" : p.item);
                if (p.next != null) {
                    sb.append(", ");
                }
            }
            sb.append(']');
            return sb.toString();
        }
    }
}
