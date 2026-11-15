@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private int[] heap;
    private int size;

    public ConcurrentPriorityQueue() {
        this(16);
    }

    public ConcurrentPriorityQueue(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        heap = new int[initialCapacity];
    }

    public void offer(int value) {
        synchronized (lock) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) / 2;
                if (heap[parent] <= value) {
                    break;
                }
                heap[index] = heap[parent];
                index = parent;
            }
            heap[index] = value;
            lock.notifyAll();
        }
    }

    public int take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeMinimum();
        }
    }

    public boolean tryPoll(int[] result) {
        checkResultArray(result);
        synchronized (lock) {
            if (size == 0) {
                return false;
            }
            result[0] = removeMinimum();
            return true;
        }
    }

    public boolean tryPeek(int[] result) {
        checkResultArray(result);
        synchronized (lock) {
            if (size == 0) {
                return false;
            }
            result[0] = heap[0];
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

    private int removeMinimum() {
        int minimum = heap[0];
        int last = heap[--size];

        if (size > 0) {
            int index = 0;
            while (true) {
                int left = index * 2 + 1;
                if (left >= size) {
                    break;
                }

                int right = left + 1;
                int child = right < size && heap[right] < heap[left] ? right : left;
                if (heap[child] >= last) {
                    break;
                }

                heap[index] = heap[child];
                index = child;
            }
            heap[index] = last;
        }

        return minimum;
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }
        if (heap.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("Priority queue is too large");
        }

        int newCapacity = heap.length == 0 ? 1 : heap.length + heap.length / 2 + 1;
        if (newCapacity < 0 || newCapacity <= heap.length) {
            newCapacity = Integer.MAX_VALUE;
        }

        int[] expanded = new int[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    private static void checkResultArray(int[] result) {
        if (result == null || result.length == 0) {
            throw new IllegalArgumentException("result must contain at least one element");
        }
    }
}
