@ConcurrentSet.ThreadSafe
public class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final E[] elements;
    private int size;

    @SuppressWarnings("unchecked")
    public ConcurrentSet(int capacity) {
        elements = (E[]) new Object[capacity];
        size = 0;
    }

    public synchronized boolean add(E element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                return false;
            }
        }
        elements[size++] = element;
        notifyAll();
        return true;
    }

    public synchronized boolean remove(E element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                System.arraycopy(elements, i + 1, elements, i, size - i - 1);
                size--;
                notifyAll();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean contains(E element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        while (size > 0) {
            remove(elements[0]);
        }
    }

    public synchronized Object[] toArray() {
        return Arrays.copyOf(elements, size);
    }
}
