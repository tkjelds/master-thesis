interface Order<T> {
    int compare(T left, T right);
}

@Handle.ThreadSafe
class Handle<T> {
    @interface ThreadSafe {}

    private final IntervalHeap<T> heap;
    private final int index;

    public Handle(IntervalHeap<T> heap, int index) {
        this.heap = heap;
        this.index = index;
    }

    public T getItem() {
        return heap.items[index];
    }
}

@IntervalHeap.ThreadSafe
class IntervalHeap<T> {
    @interface ThreadSafe {}

    private T[] items;
    private Order<T> order;
    private int size;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        this.order = order;
        this.items = (T[]) new Object[16];
        this.size = 0;
    }

    private void resize() {
        int newSize = items.length << 1;
        @SuppressWarnings("unchecked")
        T[] newItems = (T[]) new Object[newSize];
        System.arraycopy(items, 0, newItems, 0, size);
        items = newItems;
    }

    private void siftUpMin(int index) {
        while (index > 0 && order.compare(items[index], items[(index - 1) / 2]) < 0) {
            swap(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void siftUpMax(int index) {
        while (index > size / 2 && order.compare(items[(index - 1) / 2], items[index]) < 0) {
            swap(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void siftDownMin(int index) {
        while (index * 2 + 1 < size) {
            int left = index * 2 + 1;
            int right = index * 2 + 2;
            int smallest = left;

            if (right < size && order.compare(items[right], items[left]) > 0) {
                smallest = right;
            }

            if (order.compare(items[smallest], items[index]) > 0) {
                break;
            }

            swap(index, smallest);
            index = smallest;
        }
    }

    private void siftDownMax(int index) {
        while (index * 2 + 1 < size) {
            int left = index * 2 + 1;
            int right = index * 2 + 2;
            int largest = left;

            if (right < size && order.compare(items[right], items[left]) > 0) {
                largest = right;
            }

            if (order.compare(items[largest], items[index]) < 0) {
                break;
            }

            swap(index, largest);
            index = largest;
        }
    }

    private void swap(int i, int j) {
        T temp = items[i];
        items[i] = items[j];
        items[j] = temp;
    }

    public Handle<T> add(T item) {
        if (item == null) throw new NullPointerException("Null elements not allowed");
        synchronized (this) {
            if (size == items.length) resize();
            items[size] = item;
            int minIndex = size++;
            siftUpMin(size - 1);
            siftDownMax(minIndex);
            return new Handle<>(this, size - 1);
        }
    }

    public T findMin() {
        if (size == 0) throw new IllegalStateException("Empty heap");
        synchronized (this) {
            return items[0];
        }
    }

    public T findMax() {
        if (size == 0) throw new IllegalStateException("Empty heap");
        synchronized (this) {
            return items[size - 1];
        }
    }

    public T deleteMin() {
        if (size == 0) throw new IllegalStateException("Empty heap");
        synchronized (this) {
            T min = items[0];
            swap(0, size - 1);
            size--;
            siftDownMin(0);
            return min;
        }
    }

    public T deleteMax() {
        if (size == 0) throw new IllegalStateException("Empty heap");
        synchronized (this) {
            T max = items[size - 1];
            size--;
            return max;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) throw new NullPointerException("Null handle");
        synchronized (this) {
            int index = handle.index;
            if (index >= size || index < 0) throw new IllegalArgumentException("Invalid handle");
            T item = items[index];
            items[index] = items[size - 1];
            size--;
            siftUpMin(index);
            siftDownMax(index);
            return item;
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (item == null) throw new NullPointerException("Null elements not allowed");
        synchronized (this) {
            int index = handle.index;
            if (index >= size || index < 0) throw new IllegalArgumentException("Invalid handle");
            T oldItem = items[index];
            items[index] = item;
            siftUpMin(index);
            siftDownMax(index);
            return oldItem;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }
}
