@PriorityQueue.ThreadSafe
public class PriorityQueue {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 10;
    private Object[] elements;
    private int size;

    public PriorityQueue() {
        this.elements = new Object[DEFAULT_CAPACITY];
    }

    public synchronized void enqueue(Object element) {
        while (size == elements.length - 1) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (size == 0) {
            elements[size++] = element;
        } else {
            int insertIndex = size++;
            while (insertIndex > 0 && compare(elements[(insertIndex - 1) / 2], element) < 0) {
                elements[insertIndex] = elements[(insertIndex - 1) / 2];
                insertIndex = (insertIndex - 1) / 2;
            }
            elements[insertIndex] = element;
        }
        notifyAll();
    }

    public synchronized Object dequeue() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Object result = elements[0];
        size--;
        elements[0] = elements[size];
        heapify(0);
        notifyAll();
        return result;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized void heapify(int index) {
        while (true) {
            int left = 2 * index + 1;
            int right = 2 * index + 2;
            int largest = index;

            if (left < size && compare(elements[left], elements[largest]) > 0) {
                largest = left;
            }
            if (right < size && compare(elements[right], elements[largest]) > 0) {
                largest = right;
            }
            if (largest != index) {
                Object temp = elements[index];
                elements[index] = elements[largest];
                elements[largest] = temp;
                index = largest;
            } else {
                break;
            }
        }
    }

    private int compare(Object left, Object right) {
        if (left instanceof Comparable && right instanceof Comparable) {
            return ((Comparable) left).compareTo(right);
        } else {
            throw new IllegalArgumentException("Elements must be Comparable");
        }
    }

    public static void main(String[] args) {
        PriorityQueue pq = new PriorityQueue();
        pq.enqueue(3);
        pq.enqueue(1);
        pq.enqueue(4);
        System.out.println(pq.dequeue()); // Output: 1
        System.out.println(pq.dequeue()); // Output: 3
        pq.enqueue(2);
        System.out.println(pq.dequeue()); // Output: 2
        System.out.println(pq.size());    // Output: 0
    }
}
