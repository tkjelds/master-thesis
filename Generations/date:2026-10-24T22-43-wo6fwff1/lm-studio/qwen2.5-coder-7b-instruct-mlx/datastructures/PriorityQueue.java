```java
public class PriorityQueue<T extends Comparable<T>> {
    private static final int DEFAULT_CAPACITY = 16;
    private T[] elements;
    private int size;

    @SuppressWarnings("unchecked")
    public PriorityQueue() {
        this.elements = (T[]) new Comparable[DEFAULT_CAPACITY];
        this.size = 0;
    }

    private void ensureCapacity() {
        if (size == elements.length) {
            @SuppressWarnings("unchecked")
            T[] newElements = (T[]) new Comparable[elements.length * 2];
            System.arraycopy(elements, 0, newElements, 0, size);
            elements = newElements;
        }
    }

    public synchronized void offer(T element) {
        ensureCapacity();
        elements[size++] = element;
        siftUp(size - 1);
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (elements[index].compareTo(elements[parent]) > 0) {
                T temp = elements[index];
                elements[index] = elements[parent];
                elements[parent] = temp;
                index = parent;
            } else {
                break;
            }
        }
    }

    public synchronized T poll() {
        if (size == 0) {
            return null;
        }
        T root = elements[0];
        elements[0] = elements[--size];
        siftDown(0);
        return root;
    }

    private void siftDown(int index) {
        while (index < size) {
            int left = 2 * index + 1;
            int right = 2 * index + 2;
            int largest = index;

            if (left < size && elements[left].compareTo(elements[largest]) > 0) {
                largest = left;
            }

            if (right < size && elements[right].compareTo(elements[largest]) > 0) {
                largest = right;
            }

            if (largest != index) {
                T temp = elements[index];
                elements[index] = elements[largest];
                elements[largest] = temp;
                index = largest;
            } else {
                break;
            }
        }
    }

    public synchronized T peek() {
        if (size == 0) {
            return null;
        }
        return elements[0];
    }

    public synchronized int size() {
        return size;
    }
}
```
