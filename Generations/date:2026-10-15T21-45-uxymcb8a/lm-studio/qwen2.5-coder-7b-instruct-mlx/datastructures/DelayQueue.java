import java.util.LinkedList;

@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private final LinkedList<T> queue = new LinkedList<>();
    private final Object lock = new Object();

    public void put(T item) throws InterruptedException {
        synchronized (lock) {
            queue.offer(item);
            lock.notifyAll();
        }
    }

    public T take() throws InterruptedException {
        while (true) {
            synchronized (lock) {
                if (!queue.isEmpty()) {
                    return queue.poll();
                } else {
                    lock.wait();
                }
            }
        }
    }

    public T peek() throws InterruptedException {
        while (true) {
            synchronized (lock) {
                if (!queue.isEmpty()) {
                    return queue.peek();
                } else {
                    lock.wait();
                }
            }
        }
    }

    public boolean putIfAbsent(T item) throws InterruptedException {
        synchronized (lock) {
            if (queue.contains(item)) {
                return false;
            } else {
                queue.offer(item);
                lock.notifyAll();
                return true;
            }
        }
    }

    public int remainingCapacity() {
        return Integer.MAX_VALUE; // DelayQueue does not have a capacity
    }

    public int size() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return queue.isEmpty();
        }
    }

    public boolean contains(Object o) {
        synchronized (lock) {
            return queue.contains(o);
        }
    }

    public void clear() {
        synchronized (lock) {
            queue.clear();
            lock.notifyAll();
        }
    }

    public boolean remove(Object o) {
        synchronized (lock) {
            if (queue.remove(o)) {
                lock.notifyAll();
                return true;
            } else {
                return false;
            }
        }
    }

    public boolean removeAll(Collection<?> c) {
        synchronized (lock) {
            boolean modified = queue.removeAll(c);
            if (modified) {
                lock.notifyAll();
            }
            return modified;
        }
    }

    public boolean retainAll(Collection<?> c) {
        synchronized (lock) {
            boolean modified = queue.retainAll(c);
            if (modified) {
                lock.notifyAll();
            }
            return modified;
        }
    }

    public T poll() {
        synchronized (lock) {
            return queue.poll();
        }
    }

    public T element() throws NoSuchElementException {
        synchronized (lock) {
            if (queue.isEmpty()) {
                throw new NoSuchElementException();
            } else {
                return queue.element();
            }
        }
    }

    public T remove() throws NoSuchElementException {
        synchronized (lock) {
            if (queue.isEmpty()) {
                throw new NoSuchElementException();
            } else {
                T item = queue.remove();
                lock.notifyAll();
                return item;
            }
        }
    }

    public boolean add(T e) throws IllegalStateException {
        put(e);
        return true;
    }
}
