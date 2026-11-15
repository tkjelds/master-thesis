@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue {
    @interface ThreadSafe {}

    private int[] elements;
    private int size;

    public ConcurrentPriorityQueue() {
        elements = new int[16];
        size = 0;
    }

    public void insert(int value) {
        synchronized (this) {
            ensureCapacity();
            int index = size++;

            while (index > 0) {
                int parent = (index - 1) / 2;
                if (elements[parent] <= value) {
                    break;
                }
                elements[index] = elements[parent];
                index = parent;
            }

            elements[index] = value;
            notifyAll();
        }
    }

    public int removeMin() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }

            int minimum = elements[0];
            int last = elements[--size];

            if (size > 0) {
                int index = 0;
                while (true) {
                    int left = index * 2 + 1;
                    if (left >= size) {
                        break;
                    }

                    int right = left + 1;
                    int child = right < size && elements[right] < elements[left]
                            ? right : left;

                    if (elements[child] >= last) {
                        break;
                    }

                    elements[index] = elements[child];
                    index = child;
                }
                elements[index] = last;
            }

            return minimum;
        }
    }

    public int peekMin() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            return elements[0];
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }

        if (elements.length > Integer.MAX_VALUE / 2) {
            throw new OutOfMemoryError("Priority queue capacity exceeded");
        }

        int[] larger = new int[elements.length * 2];
        for (int i = 0; i < size; i++) {
            larger[i] = elements[i];
        }
        elements = larger;
    }
}
