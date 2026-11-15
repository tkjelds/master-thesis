```java
public class ThreadSafePriorityQueue {
    private static final int DEFAULT_CAPACITY = 10;
    private int[] array;
    private int size;

    public ThreadSafePriorityQueue() {
        this.array = new int[DEFAULT_CAPACITY];
        this.size = 0;
    }

    public synchronized void insert(int item) {
        ensureCapacity();
        int i = size;
        array[i] = item;
        siftUp(i);
        size++;
    }

    public synchronized int remove() throws InterruptedException {
        if (size == 0) {
            wait();
        }
        int item = array[0];
        size--;
        array[0] = array[size];
        siftDown(0);
        notifyAll();
        return item;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    private void ensureCapacity() {
        if (size >= array.length) {
            int newCapacity = array.length * 2;
            int[] newArray = new int[newCapacity];
            System.arraycopy(array, 0, newArray, 0, size);
            array = newArray;
        }
    }

    private void siftUp(int i) {
        while (i > 0 && array[i] < array[parent(i)]) {
            swap(i, parent(i));
            i = parent(i);
        }
    }

    private void siftDown(int i) {
        while (true) {
            int left = leftChild(i);
            int right = rightChild(i);
            int smallest = i;

            if (left < size && array[left] < array[smallest]) {
                smallest = left;
            }
            if (right < size && array[right] < array[smallest]) {
                smallest = right;
            }

            if (smallest != i) {
                swap(i, smallest);
                i = smallest;
            } else {
                break;
            }
        }
    }

    private int parent(int i) {
        return (i - 1) / 2;
    }

    private int leftChild(int i) {
        return 2 * i + 1;
    }

    private int rightChild(int i) {
        return 2 * i + 2;
    }

    private void swap(int i, int j) {
        int temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }
}
```
