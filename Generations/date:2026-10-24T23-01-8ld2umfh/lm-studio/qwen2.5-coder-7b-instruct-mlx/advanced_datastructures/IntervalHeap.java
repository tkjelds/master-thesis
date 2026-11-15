import java.util.concurrent.atomic.AtomicInteger;

interface Order<T> {
    int compare(T left, T right);
}

@Handle.ThreadSafe
class Handle<T> {
    @interface ThreadSafe {}

    private final IntervalHeap<T> heap;
    private int index;

    Handle(IntervalHeap<T> heap, int index) {
        this.heap = heap;
        this.index = index;
    }

    public T getItem() {
        return heap.getElement(index);
    }
}

@IntervalHeap.ThreadSafe
class IntervalHeap<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private T[] elements;
    private int[] lowIndices;
    private int[] highIndices;
    private AtomicInteger size;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        this.order = order;
        this.elements = (T[]) new Object[16];
        this.lowIndices = new int[8];
        this.highIndices = new int[8];
        this.size = new AtomicInteger(0);
    }

    public synchronized Handle<T> add(T item) {
        if (item == null) throw new NullPointerException();
        size.incrementAndGet();
        resizeIfNeeded();

        int index = elements.length;
        elements[index] = item;
        lowIndices[size.get()] = index;
        highIndices[size.get()] = index;

        bubbleUpLow(index);
        bubbleUpHigh(index);

        return new Handle<>(this, size.get() - 1);
    }

    public synchronized T findMin() {
        if (size.get() == 0) throw new IllegalStateException();
        return elements[lowIndices[0]];
    }

    public synchronized T findMax() {
        if (size.get() == 0) throw new IllegalStateException();
        return elements[highIndices[0]];
    }

    public synchronized T deleteMin() {
        if (size.get() == 0) throw new IllegalStateException();
        return delete(0);
    }

    public synchronized T deleteMax() {
        if (size.get() == 0) throw new IllegalStateException();
        return delete(size.get() - 1);
    }

    public synchronized T delete(Handle<T> handle) {
        if (handle.heap != this) throw new IllegalArgumentException();
        return delete(handle.index);
    }

    public synchronized T replace(Handle<T> handle, T item) {
        if (handle.heap != this || item == null) throw new IllegalArgumentException();
        int index = handle.index;
        T oldItem = elements[index];
        elements[index] = item;

        if (index == lowIndices[0]) {
            bubbleDownLow(index);
        } else if (index == highIndices[0]) {
            bubbleDownHigh(index);
        }

        return oldItem;
    }

    public synchronized int size() {
        return size.get();
    }

    private T delete(int index) {
        int lastLowIndex = lowIndices[size.get() - 1];
        int lastHighIndex = highIndices[size.get() - 1];

        lowIndices[index] = lastLowIndex;
        highIndices[index] = lastHighIndex;

        elements[lastLowIndex] = null;
        elements[lastHighIndex] = null;

        size.decrementAndGet();

        if (index != lowIndices[0]) {
            bubbleDownLow(index);
        }

        if (index != lastHighIndex) {
            bubbleDownHigh(index);
        }

        return elements[lastLowIndex];
    }

    private void resizeIfNeeded() {
        if (elements.length == size.get()) {
            grow();
        }
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        int newSize = elements.length * 2;
        T[] newElements = (T[]) new Object[newSize];
        int[] newLowIndices = new int[newSize / 2];
        int[] newHighIndices = new int[newSize / 2];

        System.arraycopy(elements, 0, newElements, 0, elements.length);
        System.arraycopy(lowIndices, 0, newLowIndices, 0, lowIndices.length);
        System.arraycopy(highIndices, 0, newHighIndices, 0, highIndices.length);

        elements = newElements;
        lowIndices = newLowIndices;
        highIndices = newHighIndices;
    }

    private void bubbleUpLow(int index) {
        while (index > 0 && order.compare(elements[index], elements[lowIndices[(index - 1) / 2]]) < 0) {
            swapLow(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void bubbleUpHigh(int index) {
        while (index > 0 && order.compare(elements[index], elements[highIndices[(index - 1) / 2]]) > 0) {
            swapHigh(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void bubbleDownLow(int index) {
        int left = 2 * index + 1;
        int right = 2 * index + 2;

        while (left < lowIndices.length) {
            int smallest = left;
            if (right < lowIndices.length && order.compare(elements[lowIndices[right]], elements[lowIndices[left]]) < 0) {
                smallest = right;
            }

            if (order.compare(elements[lowIndices[index]], elements[lowIndices[smallest]]) > 0) {
                swapLow(index, smallest);
                index = smallest;
                left = 2 * index + 1;
                right = 2 * index + 2;
            } else {
                break;
            }
        }
    }

    private void bubbleDownHigh(int index) {
        int left = 2 * index + 1;
        int right = 2 * index + 2;

        while (left < highIndices.length) {
            int largest = left;
            if (right < highIndices.length && order.compare(elements[highIndices[right]], elements[highIndices[left]]) > 0) {
                largest = right;
            }

            if (order.compare(elements[highIndices[index]], elements[highIndices[largest]]) < 0) {
                swapHigh(index, largest);
                index = largest;
                left = 2 * index + 1;
                right = 2 * index + 2;
            } else {
                break;
            }
        }
    }

    private void swapLow(int i, int j) {
        lowIndices[i] = highIndices[j];
        lowIndices[j] = i;
    }

    private void swapHigh(int i, int j) {
        highIndices[i] = lowIndices[j];
        highIndices[j] = i;
    }

    @SuppressWarnings("unchecked")
    private T getElement(int index) {
        return (T) elements[index];
    }
}
