@PriorityBlockingQueue.ThreadSafe
public class PriorityBlockingQueue<T> {
    @interface ThreadSafe {}

    private T[] elements;
    private int size;
    private final Object lock = new Object();
    private volatile int head;
    private volatile int tail;

    @SuppressWarnings("unchecked")
    public PriorityBlockingQueue() {
        elements = (T[]) new Object[16];
    }

    public void put(T element) throws InterruptedException {
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            enqueue(element);
        }
    }

    public T take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return dequeue();
        }
    }

    private void enqueue(T element) {
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        size++;
        lock.notifyAll();
    }

    private T dequeue() {
        T element = elements[head];
        head = (head + 1) % elements.length;
        size--;
        return element;
    }

    private void grow() {
        @SuppressWarnings("unchecked")
        T[] newElements = (T[]) new Object[elements.length * 2];
        int i = head;
        for (int j = 0; j < size; j++) {
            newElements[j] = elements[i];
            i = (i + 1) % elements.length;
        }
        elements = newElements;
        head = 0;
        tail = size;
    }

    private void maintainPriority(int index) {
        while (index > 0 && compare(elements[index], elements[(index - 1) / 2]) < 0) {
            swap(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void maintainHeap(int index) {
        while (true) {
            int left = index * 2 + 1;
            int right = index * 2 + 2;
            int smallest = index;

            if (left < size && compare(elements[left], elements[smallest]) < 0) {
                smallest = left;
            }
            if (right < size && compare(elements[right], elements[smallest]) < 0) {
                smallest = right;
            }
            if (smallest != index) {
                swap(index, smallest);
                index = smallest;
            } else {
                break;
            }
        }
    }

    private int compare(T a, T b) {
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable<T>) a).compareTo(b);
        } else {
            throw new IllegalArgumentException("Elements must implement Comparable");
        }
    }

    private void swap(int i, int j) {
        T temp = elements[i];
        elements[i] = elements[j];
        elements[j] = temp;
    }
}
