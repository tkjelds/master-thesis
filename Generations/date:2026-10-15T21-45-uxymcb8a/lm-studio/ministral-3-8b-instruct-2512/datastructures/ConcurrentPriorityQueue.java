@ConcurrentPriorityQueue.ThreadSafe
public class ConcurrentPriorityQueue {
    @interface ThreadSafe {}

    private static class Node {
        final int priority;
        final Object value;
        Node next;

        Node(int priority, Object value) {
            this.priority = priority;
            this.value = value;
        }
    }

    private Node[] heap;
    private int size;
    private volatile boolean changed;

    public ConcurrentPriorityQueue(int capacity) {
        this.heap = new Node[capacity];
    }

    public void insert(int priority, Object value) {
        synchronized (this) {
            if (size >= heap.length) {
                try {
                    while (size == heap.length) {
                        wait();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            Node newNode = new Node(priority, value);
            heap[size] = newNode;
            size++;

            int current = size - 1;
            while (current > 0 && heap[current].priority < heap[(current - 1) / 2].priority) {
                swap(current, (current - 1) / 2);
                current = (current - 1) / 2;
            }

            changed = true;
            notifyAll();
        }
    }

    public Object extractMin() {
        synchronized (this) {
            while (size == 0 && !changed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            if (size == 0) {
                return null;
            }

            Object minValue = heap[0].value;
            heap[0] = heap[size - 1];
            size--;

            int current = 0;
            while (true) {
                int leftChild = 2 * current + 1;
                int rightChild = 2 * current + 2;
                int minChild = leftChild;

                if (leftChild >= size && rightChild >= size) {
                    break;
                }

                if (rightChild < size && heap[rightChild].priority < heap[leftChild].priority) {
                    minChild = rightChild;
                }

                if (heap[current].priority <= heap[minChild].priority) {
                    break;
                }

                swap(current, minChild);
                current = minChild;
            }

            changed = true;
            notifyAll();
            return minValue;
        }
    }

    public Object peekMin() {
        synchronized (this) {
            while (size == 0 && !changed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            if (size == 0) {
                return null;
            }

            return heap[0].value;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    private void swap(int i, int j) {
        Node temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }
}
