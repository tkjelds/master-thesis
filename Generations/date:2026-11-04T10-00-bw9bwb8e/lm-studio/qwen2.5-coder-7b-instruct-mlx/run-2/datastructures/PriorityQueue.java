@PriorityQueue.ThreadSafe
public class PriorityQueue {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 10;
    private Object[] elements;
    private int size;

    public PriorityQueue() {
        this.elements = new Object[INITIAL_CAPACITY];
        this.size = 0;
    }

    public synchronized void add(Object element) {
        ensureCapacity();
        int index = size;
        while (index > 0 && compare(elements[(index - 1) / 2], element) < 0) {
            elements[index] = elements[(index - 1) / 2];
            index = (index - 1) / 2;
        }
        elements[index] = element;
        size++;
    }

    public synchronized Object poll() {
        if (size == 0) {
            return null;
        }
        Object result = elements[0];
        size--;
        elements[0] = elements[size];
        heapifyDown(0);
        return result;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    private void ensureCapacity() {
        if (size >= elements.length) {
            Object[] newElements = new Object[elements.length * 2];
            System.arraycopy(elements, 0, newElements, 0, elements.length);
            elements = newElements;
        }
    }

    private void heapifyDown(int index) {
        while (index * 2 + 1 < size) {
            int left = index * 2 + 1;
            int right = index * 2 + 2;
            int largest = left;

            if (right < size && compare(elements[right], elements[left]) > 0) {
                largest = right;
            }

            if (compare(elements[largest], elements[index]) > 0) {
                Object temp = elements[index];
                elements[index] = elements[largest];
                elements[largest] = temp;
                index = largest;
            } else {
                break;
            }
        }
    }

    private int compare(Object o1, Object o2) {
        if (o1 instanceof Comparable && o2 instanceof Comparable) {
            return ((Comparable) o1).compareTo(o2);
        } else {
            throw new IllegalArgumentException("Objects must implement Comparable");
        }
    }
}
