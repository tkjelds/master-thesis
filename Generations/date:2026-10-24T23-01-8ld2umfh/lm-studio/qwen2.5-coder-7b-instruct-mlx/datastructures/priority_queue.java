@PriorityQueue.ThreadSafe
class PriorityQueue {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size;

    public PriorityQueue(int capacity) {
        elements = new Object[capacity];
        this.size = 0;
    }

    public synchronized void insert(Object element) throws InterruptedException {
        while (size == elements.length) {
            wait();
        }
        int index = size;
        elements[index] = element;
        siftUp(index);
        notifyAll();
    }

    public synchronized Object extractMax() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        Object max = elements[0];
        elements[0] = elements[--size];
        siftDown(0);
        notifyAll();
        return max;
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (elements[index].compareTo(elements[parent]) <= 0) {
                break;
            }
            swap(index, parent);
            index = parent;
        }
    }

    private void siftDown(int index) {
        while (index * 2 + 1 < size) {
            int left = index * 2 + 1;
            int right = index * 2 + 2;
            int largest = left;

            if (right < size && elements[right].compareTo(elements[largest]) > 0) {
                largest = right;
            }

            if (elements[index].compareTo(elements[largest]) >= 0) {
                break;
            }

            swap(index, largest);
            index = largest;
        }
    }

    private void swap(int i, int j) {
        Object temp = elements[i];
        elements[i] = elements[j];
        elements[j] = temp;
    }
}
