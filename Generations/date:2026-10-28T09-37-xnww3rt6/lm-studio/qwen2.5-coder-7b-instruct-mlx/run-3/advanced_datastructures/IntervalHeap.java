import java.util.concurrent.atomic.AtomicInteger;

interface Order<T> {
    int compare(T left, T right);
}

@IntervalHeap.ThreadSafe
class IntervalHeap<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private int[] lowHeaps, highHeaps, lowIndexMap, highIndexMap;
    private AtomicInteger size;
    private final Object lock;

    public IntervalHeap(Order<T> order) {
        this.order = order;
        this.lowHeaps = new int[8];
        this.highHeaps = new int[8];
        this.lowIndexMap = new int[8];
        this.highIndexMap = new int[8];
        this.size = new AtomicInteger(0);
        this.lock = new Object();
    }

    Handle<T> add(T item) {
        synchronized (lock) {
            ensureCapacity(1);
            int index = size.getAndIncrement();
            lowHeaps[index] = highHeaps[index] = index + 1; // Singleton node
            lowIndexMap[index] = highIndexMap[index] = index;
            repairLowHeap(index);
            repairHighHeap(index);
            return new Handle<>(index, item);
        }
    }

    T findMin() {
        if (size.get() == 0) throw new IllegalStateException();
        return items[lowHeaps[0] - 1];
    }

    T findMax() {
        if (size.get() == 0) throw new IllegalStateException();
        return items[highHeaps[0] - 1];
    }

    T deleteMin() {
        T item = findMin();
        delete(lowHeaps[0] - 1);
        return item;
    }

    T deleteMax() {
        T item = findMax();
        delete(highHeaps[0] - 1);
        return item;
    }

    T delete(Handle<T> handle) {
        if (handle == null || !isValid(handle)) throw new IllegalArgumentException();
        return delete(items[handle.index() - 1]);
    }

    T replace(Handle<T> handle, T item) {
        if (handle == null || !isValid(handle)) throw new IllegalArgumentException();
        synchronized (lock) {
            T oldItem = items[handle.index() - 1];
            items[handle.index() - 1] = item;
            int index = handle.index() - 1;
            repairLowHeap(index);
            repairHighHeap(index);
            return oldItem;
        }
    }

    int size() {
        return size.get();
    }

    private boolean isValid(Handle<T> handle) {
        int index = handle.index() - 1;
        return index >= 0 && index < size.get() && lowIndexMap[index] == handle.index();
    }

    private void ensureCapacity(int additional) {
        int newSize = size.get() + additional;
        if (newSize > lowHeaps.length) {
            int newLength = Math.max(lowHeaps.length << 1, newSize);
            lowHeaps = java.util.Arrays.copyOf(lowHeaps, newLength);
            highHeaps = java.util.Arrays.copyOf(highHeaps, newLength);
            lowIndexMap = java.util.Arrays.copyOf(lowIndexMap, newLength);
            highIndexMap = java.util.Arrays.copyOf(highIndexMap, newLength);
        }
    }

    private void repairLowHeap(int index) {
        while (index > 0 && order.compare(items[lowHeaps[(index - 1) / 2] - 1], items[index]) > 0) {
            swapLow(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void repairHighHeap(int index) {
        while (index > 0 && order.compare(items[highHeaps[(index - 1) / 2] - 1], items[index]) < 0) {
            swapHigh(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void swapLow(int i, int j) {
        lowHeaps[i] = highHeaps[j];
        lowHeaps[j] = i + 1;
        int tempLowIndex = lowIndexMap[i];
        lowIndexMap[i] = highIndexMap[j];
        highIndexMap[j] = tempLowIndex;
    }

    private void swapHigh(int i, int j) {
        highHeaps[i] = lowHeaps[j];
        highHeaps[j] = i + 1;
        int tempHighIndex = highIndexMap[i];
        highIndexMap[i] = lowIndexMap[j];
        lowIndexMap[j] = tempHighIndex;
    }

    private T delete(int index) {
        synchronized (lock) {
            int lastIndex = size.decrementAndGet();
            if (lastIndex != index) {
                items[index] = items[lastIndex];
                repairLowHeap(index);
                repairHighHeap(index);
            }
            return items[index];
        }
    }

    private T[] items = new T[8];

    private static class Handle<T> {
        private final int index;
        private final T item;

        public Handle(int index, T item) {
            this.index = index;
            this.item = item;
        }

        public int index() {
            return index;
        }

        public T item() {
            return item;
        }
    }
}
