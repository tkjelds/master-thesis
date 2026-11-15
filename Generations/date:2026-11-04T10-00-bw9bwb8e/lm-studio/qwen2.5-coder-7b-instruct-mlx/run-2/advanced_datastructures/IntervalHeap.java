import java.lang.reflect.Array;

interface Order<T> {
    int compare(T left, T right);
}

@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}

    private static final double RESIZE_FACTOR = 1.5;
    private Object[] lowHeap;
    private Object[] highHeap;
    private Handle<T>[] handles;
    private volatile int size;
    private Order<T> order;

    public IntervalHeap(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
        lowHeap = new Object[1];
        highHeap = new Object[1];
        handles = new Handle[1];
    }

    public synchronized Handle<T> add(T item) {
        if (item == null) throw new NullPointerException();
        ensureCapacity();
        int index = size;
        lowHeap[index] = item;
        highHeap[index] = item;
        handles[index] = new Handle<>(index);
        swimLow(index);
        swimHigh(index);
        size++;
        return handles[index];
    }

    public synchronized T findMin() {
        if (size == 0) throw new IllegalStateException();
        return (T) lowHeap[0];
    }

    public synchronized T findMax() {
        if (size == 0) throw new IllegalStateException();
        return (T) highHeap[0];
    }

    public synchronized T deleteMin() {
        if (size == 0) throw new IllegalStateException();
        T result = (T) lowHeap[0];
        handleDelete(0, order.compare(lowHeap[size - 1], highHeap[size - 1]) < 0 ? lowHeap : highHeap, size - 1);
        size--;
        sinkLow(0);
        sinkHigh(0);
        return result;
    }

    public synchronized T deleteMax() {
        if (size == 0) throw new IllegalStateException();
        T result = (T) highHeap[0];
        handleDelete(0, order.compare(lowHeap[size - 1], highHeap[size - 1]) > 0 ? lowHeap : highHeap, size - 1);
        size--;
        sinkLow(0);
        sinkHigh(0);
        return result;
    }

    public synchronized T delete(Handle<T> handle) {
        if (handle == null) throw new NullPointerException();
        int index = handle.index;
        if (index < 0 || index >= size) throw new IllegalArgumentException();
        T result = (T) lowHeap[index];
        handleDelete(index, highHeap, index);
        size--;
        sinkLow(index);
        sinkHigh(index);
        return result;
    }

    public synchronized T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) throw new NullPointerException();
        int index = handle.index;
        if (index < 0 || index >= size) throw new IllegalArgumentException();
        T old = (T) lowHeap[index];
        lowHeap[index] = item;
        highHeap[index] = item;
        swimLow(index);
        swimHigh(index);
        sinkLow(index);
        sinkHigh(index);
        return old;
    }

    public synchronized int size() {
        return size;
    }

    private void ensureCapacity() {
        if (size == lowHeap.length - 1) {
            int newSize = (int) (lowHeap.length * RESIZE_FACTOR);
            lowHeap = newArray(size, newSize);
            highHeap = newArray(size, newSize);
            handles = new Handle[newSize];
        }
    }

    @SuppressWarnings("unchecked")
    private <T1> T1[] newArray(int oldSize, int newSize) {
        return (T1[]) Array.newInstance(lowHeap[0].getClass(), newSize);
    }

    private void swimLow(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (order.compare((T) lowHeap[index], (T) lowHeap[parent]) >= 0) break;
            swap(lowHeap, index, parent);
            swap(handles, index, parent);
            index = parent;
        }
    }

    private void sinkLow(int index) {
        while (index * 2 + 1 < size) {
            int left = index * 2 + 1;
            int right = index * 2 + 2;
            int child = left;
            if (right < size && order.compare((T) lowHeap[right], (T) lowHeap[left]) < 0) {
                child = right;
            }
            if (order.compare((T) lowHeap[index], (T) lowHeap[child]) <= 0) break;
            swap(lowHeap, index, child);
            swap(handles, index, child);
            index = child;
        }
    }

    private void swimHigh(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (order.compare((T) highHeap[index], (T) highHeap[parent]) <= 0) break;
            swap(highHeap, index, parent);
            swap(handles, index, parent);
            index = parent;
        }
    }

    private void sinkHigh(int index) {
        while (index * 2 + 1 < size) {
            int left = index * 2 + 1;
            int right = index * 2 + 2;
            int child = left;
            if (right < size && order.compare((T) highHeap[right], (T) highHeap[left]) > 0) {
                child = right;
            }
            if (order.compare((T) highHeap[index], (T) highHeap[child]) >= 0) break;
            swap(highHeap, index, child);
            swap(handles, index, child);
            index = child;
        }
    }

    private void handleDelete(int index, Object[] heap, int lastIndex) {
        swap(heap, index, lastIndex);
        handleDelete(lastIndex, highHeap == heap ? lowHeap : highHeap, lastIndex - 1);
        sinkLow(index);
        sinkHigh(index);
    }

    private void swap(Object[] array, int i, int j) {
        Object temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

    static class Handle<T> {
        private final int index;

        private Handle(int index) {
            this.index = index;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Handle)) return false;
            Handle<?> handle = (Handle<?>) o;
            return index == handle.index;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(index);
        }
    }
}
