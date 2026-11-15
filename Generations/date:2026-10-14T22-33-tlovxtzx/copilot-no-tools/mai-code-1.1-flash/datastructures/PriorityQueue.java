@PriorityQueue.ThreadSafe
public class PriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object[] heap;
    private int size;
    private final Object monitor;

    public PriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero.");
        }
        this.heap = new Object[capacity];
        this.size = 0;
        this.monitor = new Object();
    }

    public boolean offer(E value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (monitor) {
            while (size >= heap.length) {
                monitor.wait();
            }

            heap[size] = value;
            siftUp(size);
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public void put(E value) throws InterruptedException {
        offer(value);
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }

            E result = removeRoot();
            monitor.notifyAll();
            return result;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }

            E result = removeRoot();
            monitor.notifyAll();
            return result;
        }
    }

    public E peek() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }

            @SuppressWarnings("unchecked")
            E result = (E) heap[0];
            return result;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return heap.length - size;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            monitor.notifyAll();
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                @SuppressWarnings("unchecked")
                E current = (E) heap[i];
                if (current.compareTo(value) == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    public E remove() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("Priority queue is empty.");
            }

            E result = removeRoot();
            monitor.notifyAll();
            return result;
        }
    }

    public boolean offerNonBlocking(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (monitor) {
            if (size >= heap.length) {
                return false;
            }

            heap[size] = value;
            siftUp(size);
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public E pollNonBlocking() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }

            E result = removeRoot();
            monitor.notifyAll();
            return result;
        }
    }

    private E removeRoot() {
        @SuppressWarnings("unchecked")
        E result = (E) heap[0];

        int lastIndex = --size;
        if (lastIndex == 0) {
            heap[0] = null;
            return result;
        }

        heap[0] = heap[lastIndex];
        heap[lastIndex] = null;

        siftDown(0);
        return result;
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;

            @SuppressWarnings("unchecked")
            E current = (E) heap[index];
            @SuppressWarnings("unchecked")
            E parentValue = (E) heap[parent];

            if (current.compareTo(parentValue) >= 0) {
                break;
            }

            swap(index, parent);
            index = parent;
        }
    }

    private void siftDown(int index) {
        while (true) {
            int left = 2 * index + 1;
            int right = left + 1;
            int smallest = index;

            if (left < size) {
                @SuppressWarnings("unchecked")
                E leftValue = (E) heap[left];
                @SuppressWarnings("unchecked")
                E smallestValue = (E) heap[smallest];

                if (leftValue.compareTo(smallestValue) < 0) {
                    smallest = left;
                }
            }

            if (right < size) {
                @SuppressWarnings("unchecked")
                E rightValue = (E) heap[right];
                @SuppressWarnings("unchecked")
                E smallestValue = (E) heap[smallest];

                if (rightValue.compareTo(smallestValue) < 0) {
                    smallest = right;
                }
            }

            if (smallest == index) {
                break;
            }

            swap(index, smallest);
            index = smallest;
        }
    }

    private void swap(int i, int j) {
        Object temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }
}
