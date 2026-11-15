@PriorityQueue.ThreadSafe
public class PriorityQueue {
    @interface ThreadSafe {}

    private int[] heap;
    private int size;

    public PriorityQueue(int capacity) {
        heap = new int[capacity + 1];
        size = 0;
    }

    public synchronized void insert(int value) {
        if (size >= heap.length - 1) {
            throw new IllegalStateException("Queue is full");
        }
        size++;
        heap[size] = value;
        int current = size;
        while (current > 1 && heap[current] < heap[parent(current)]) {
            swap(current, parent(current));
            current = parent(current);
        }
    }

    public synchronized int extractMin() {
        if (size <= 0) {
            throw new IllegalStateException("Queue is empty");
        }
        int min = heap[1];
        heap[1] = heap[size];
        size--;
        heapify(1);
        return min;
    }

    private int parent(int i) {
        return i / 2;
    }

    private int left(int i) {
        return 2 * i;
    }

    private int right(int i) {
        return 2 * i + 1;
    }

    private void heapify(int i) {
        int smallest = i;
        int left = left(i);
        int right = right(i);

        if (left <= size && heap[left] < heap[smallest]) {
            smallest = left;
        }
        if (right <= size && heap[right] < heap[smallest]) {
            smallest = right;
        }
        if (smallest != i) {
            swap(i, smallest);
            heapify(smallest);
        }
    }

    private void swap(int i, int j) {
        int temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }
}
