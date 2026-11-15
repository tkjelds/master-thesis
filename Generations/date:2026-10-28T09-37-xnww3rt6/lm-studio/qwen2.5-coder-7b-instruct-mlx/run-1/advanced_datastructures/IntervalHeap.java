import java.util.concurrent.atomic.AtomicInteger;

interface Order<T> {
    int compare(T left, T right);
}

@Handle.ThreadSafe
class Handle<T> {
    @interface ThreadSafe {}

    private static final AtomicInteger nextId = new AtomicInteger(0);
    private final int id;
    private T item;

    Handle(T item) {
        this.id = nextId.getAndIncrement();
        this.item = item;
    }

    int getId() {
        return id;
    }

    T getItem() {
        return item;
    }

    void setItem(T item) {
        this.item = item;
    }
}

@IntervalHeap.ThreadSafe
class IntervalHeap<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private T[] items;
    private int size;
    private Order<T> order;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
        items = (T[]) new Object[DEFAULT_CAPACITY];
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int minCapacity) {
        if (minCapacity - items.length > 0) {
            int oldCapacity = items.length;
            int newCapacity = oldCapacity + (oldCapacity >> 1);
            if (newCapacity - minCapacity < 0) newCapacity = minCapacity;
            if (newCapacity - MAX_ARRAY_SIZE > 0) throw new OutOfMemoryError();
            items = (T[]) new Object[newCapacity];
        }
    }

    private int parent(int index) {
        return (index - 1) / 2;
    }

    private int left(int index) {
        return 2 * index + 1;
    }

    private int right(int index) {
        return 2 * index + 2;
    }

    private void swap(int i, int j) {
        T temp = items[i];
        items[i] = items[j];
        items[j] = temp;
    }

    private void siftUp(int index) {
        while (index > 0 && less(index, parent(index))) {
            swap(index, parent(index));
            index = parent(index);
        }
    }

    private void siftDown(int index) {
        while (left(index) < size) {
            int left = left(index);
            int right = right(index);
            int largest = index;

            if (left < size && less(left, largest)) {
                largest = left;
            }
            if (right < size && less(right, largest)) {
                largest = right;
            }

            if (largest != index) {
                swap(index, largest);
                index = largest;
            } else break;
        }
    }

    private boolean less(int i, int j) {
        return order.compare(items[i], items[j]) < 0;
    }

    public Handle<T> add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        ensureCapacity(size + 1);
        int index = size++;
        items[index] = item;
        siftUp(index);
        return new Handle<>(item);
    }

    public T findMin() {
        if (size == 0) throw new IllegalStateException("Heap is empty");
        return items[0];
    }

    public T findMax() {
        if (size == 0) throw new IllegalStateException("Heap is empty");
        return items[size - 1];
    }

    public T deleteMin() {
        if (size == 0) throw new IllegalStateException("Heap is empty");
        T root = items[0];
        items[0] = items[--size];
        siftDown(0);
        return root;
    }

    public T deleteMax() {
        if (size == 0) throw new IllegalStateException("Heap is empty");
        T root = items[size - 1];
        items[--size] = null;
        siftDown(0);
        return root;
    }

    public T delete(Handle<T> handle) {
        if (handle == null) throw new NullPointerException("Handle cannot be null");
        int index = findIndex(handle);
        if (index == -1) throw new IllegalArgumentException("Invalid handle");
        T item = items[index];
        items[index] = items[--size];
        siftDown(index);
        return item;
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) throw new NullPointerException("Item and handle cannot be null");
        int index = findIndex(handle);
        if (index == -1) throw new IllegalArgumentException("Invalid handle");
        T oldItem = items[index];
        items[index] = item;
        siftUp(index);
        siftDown(index);
        return oldItem;
    }

    public int size() {
        return size;
    }

    private int findIndex(Handle<T> handle) {
        for (int i = 0; i < size; i++) {
            if (items[i] == handle.getItem()) return i;
        }
        return -1;
    }
}
