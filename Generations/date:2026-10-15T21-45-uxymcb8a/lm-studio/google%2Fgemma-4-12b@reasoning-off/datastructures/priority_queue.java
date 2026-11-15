@PriorityQueue_Impl.ThreadSafe
class PriorityQueue_Impl {
    @interface ThreadSafe {}


    private Comparable[] heap;
    private int size;
    private int capacity;

    public PriorityQueue_Impl(int capacity) {
        this.capacity = capacity;
        this.heap = new Comparable[capacity];
        this.size = 0;
    }

    public void insert(Comparable element) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size < capacity) {
                    heap[size] = element;
                    siftUp(size);
                    size++;
                    return;
                } else {
                    // Block until there is space
                    int currentSize = size;
                    while (currentSize == capacity) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            // Handle interruption if necessary, 
                            // but standard behavior is to re-interrupt or throw.
                        }
                    }
                }
            }
        }
    }

    public Comparable extractMax() throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size > 0) {
                    Comparable result = heap[0];
                    heap[0] = heap[size - 1];
                    heap[size - 1] = null; // Clear reference for GC
                    size--;
                    siftDown(0);
                    
                    // Notify waiting inserters
                    notifyAll();
                    return result;
                } else {
                    // Block until there is an item
                    while (size == 0) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            // Thread interrupted while waiting
                        }
                    }
                }
            }
        }
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (heap[index].compareTo(heap[parent]) > 0) {
                swap(index, parent);
                index = parent;
            } else {
                break;
            }
        }
    }

    private void siftDown(int index) {
        while (true) {
            int largest = index;
            int left = 2 * index + 1;
            int right = 2 * index + 2;

            if (left < size && heap[left].compareTo(heap[largest]) > 0) {
                largest = left;
            }
            if (right < size && heap[right].compareTo(heap[largest]) > 0) {
                largest = right;
            }

            if (largest != index) {
                swap(index, largest);
                index = largest;
            } else {
                break;
            }
        }
    }

    private void swap(int i, int j) {
        Comparable temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }
}
