import java.util.Arrays;

@ThreadSafePriorityQueue.ThreadSafe
public class ThreadSafePriorityQueue {
    @interface ThreadSafe {}

    private int[] heap;
    private int size;

    public ThreadSafePriorityQueue(int capacity) {
        heap = new int[capacity];
        size = 0;
    }

    public synchronized void offer(int value) {
        if (size == heap.length) {
            resize();
        }
        heap[size++] = value;
        bubbleUp(size - 1);
    }

    public synchronized int poll() {
        if (size == 0) {
            throw new IllegalStateException("Queue underflow");
        }
        int root = heap[0];
        heap[0] = heap[--size];
        bubbleDown(0);
        return root;
    }

    public synchronized int peek() {
        if (size == 0) {
            throw new IllegalStateException("Queue underflow");
        }
        return heap[0];
    }

    private void resize() {
        int newCapacity = heap.length * 2;
        int[] newHeap = new int[newCapacity];
        System.arraycopy(heap, 0, newHeap, 0, size);
        heap = newHeap;
    }

    private void bubbleUp(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (heap[index] >= heap[parent]) {
                break;
            }
            swap(index, parent);
            index = parent;
        }
    }

    private void bubbleDown(int index) {
        while (index < size) {
            int left = 2 * index + 1;
            int right = 2 * index + 2;
            int largest = index;

            if (left < size && heap[left] > heap[largest]) {
                largest = left;
            }
            if (right < size && heap[right] > heap[largest]) {
                largest = right;
            }

            if (largest != index) {
                swap(index, largest);
                index = largest;
            } else {
                break;
            }
        }
    }

    private void swap(int i, int j) {
        int temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }

    public synchronized int size() {
        return size;
    }
}
