@ConcurrentPriorityQueue.ThreadSafe
public class ConcurrentPriorityQueue {
    @interface ThreadSafe {}

    private final int capacity;
    private Object[] elements;
    private volatile int size;

    public ConcurrentPriorityQueue(int capacity) {
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.size = 0;
    }

    public synchronized boolean add(Object element) {
        if (size == capacity) {
            waitNonEmpty();
        }
        insertElement(element);
        notifyAll();
        return true;
    }

    public synchronized Object poll() {
        if (size == 0) {
            waitNonFull();
        }
        Object element = elements[0];
        removeElement(element);
        notifyAll();
        return element;
    }

    private void waitNonEmpty() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void waitNonFull() {
        while (size == capacity) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void insertElement(Object element) {
        int index = size;
        elements[index] = element;
        while (index > 0) {
            int parentIndex = (index - 1) / 2;
            Object parentElement = elements[parentIndex];
            if (compare(parentElement, element) <= 0) {
                break;
            }
            elements[index] = parentElement;
            index = parentIndex;
        }
        elements[index] = element;
        size++;
    }

    private void removeElement(Object element) {
        int index = 0;
        while (index < size) {
            Object currentElement = elements[index];
            if (currentElement == element) {
                break;
            }
            index++;
        }
        if (index < size - 1) {
            elements[index] = elements[size - 1];
        }
        size--;
        while (index < size) {
            int leftChildIndex = 2 * index + 1;
            int rightChildIndex = 2 * index + 2;
            if (leftChildIndex >= size) {
                break;
            }
            int minIndex = leftChildIndex;
            if (rightChildIndex < size && compare(elements[leftChildIndex], elements[rightChildIndex]) > 0) {
                minIndex = rightChildIndex;
            }
            Object childElement = elements[minIndex];
            if (compare(currentElement, childElement) <= 0) {
                break;
            }
            elements[index] = childElement;
            index = minIndex;
        }
    }

    private int compare(Object obj1, Object obj2) {
        // Assuming the elements are Comparable or use some other way to compare them
        return ((Comparable<? super Object>) obj1).compareTo(obj2);
    }
}
