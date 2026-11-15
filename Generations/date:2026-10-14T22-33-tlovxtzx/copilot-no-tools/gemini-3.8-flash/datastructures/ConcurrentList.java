/**
 * A thread-safe, concurrent, bounded list data structure implemented using
 * Java intrinsic object monitors and synchronized blocks.
 *
 * <p>Concurrency & Synchronization Policy:
 * <ul>
 *   <li>All public operations are synchronized on a private, final internal lock object.</li>
 *   <li>No synchronized method modifiers are used; synchronization is achieved strictly
 *       through synchronized blocks within method bodies.</li>
 *   <li>Visibility and atomicity are guaranteed across all concurrent operations.</li>
 * </ul>
 *
 * <p>Policy for Operations that Cannot Complete Immediately:
 * <ul>
 *   <li><b>Full Bounded Structure:</b>
 *     <ul>
 *       <li>Blocking insertion operations ({@link #put(Object)}, {@link #put(int, Object)})
 *           suspend the calling thread using {@code lock.wait()} within a {@code while} loop
 *           guard until capacity becomes available.</li>
 *       <li>Non-blocking insertion operations ({@link #offer(Object)}, {@link #offer(int, Object)})
 *           immediately return {@code false} when the list is full.</li>
 *       <li>Immediate-rejection operations ({@link #add(Object)}, {@link #add(int, Object)})
 *           throw {@link IllegalStateException} if the list is full.</li>
 *     </ul>
 *   </li>
 *   <li><b>Empty Structure:</b>
 *     <ul>
 *       <li>Blocking retrieval/removal operations ({@link #take()}, {@link #takeFirst()},
 *           {@link #takeLast()}, {@link #take(int)}) suspend the calling thread using
 *           {@code lock.wait()} within a {@code while} loop guard until an element is present.</li>
 *       <li>Non-blocking retrieval operations ({@link #poll()}, {@link #pollFirst()},
 *           {@link #pollLast()}) return {@code null} immediately when the list is empty.</li>
 *       <li>Positional operations ({@link #get(int)}, {@link #remove(int)}, {@link #set(int, Object)})
 *           validate indices against current size and throw {@link IndexOutOfBoundsException}
 *           if the index is not currently occupied.</li>
 *     </ul>
 *   </li>
 *   <li><b>Notification:</b> Every operation that alters the size of the list invokes
 *       {@code lock.notifyAll()} to ensure that waiting producers and consumers are awakened.</li>
 *   <li><b>Null Policy:</b> {@code null} elements are strictly prohibited to avoid ambiguity
 *       with return values from polling operations. Attempting to insert {@code null} throws
 *       {@link NullPointerException}.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this list
 */
@ConcurrentList.ThreadSafe
public class ConcurrentList<E> {
    @interface ThreadSafe {}


    /** Default maximum capacity for unbounded-mode construction. */
    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    /** Internal monitor lock object ensuring encapsulation. */
    private final Object lock = new Object();

    /** Maximum allowed capacity of the list. */
    private final int maxCapacity;

    /** Storage buffer for list elements. */
    private Object[] elements;

    /** Current number of elements in the list. */
    private int size;

    /**
     * Constructs an unbounded concurrent list with a default initial buffer size
     * and a capacity ceiling of {@link Integer#MAX_VALUE}.
     */
    public ConcurrentList() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Constructs a bounded concurrent list with the specified maximum capacity.
     *
     * @param maxCapacity the maximum number of elements the list can hold
     * @throws IllegalArgumentException if {@code maxCapacity <= 0}
     */
    public ConcurrentList(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        int initCap = (maxCapacity < DEFAULT_INITIAL_CAPACITY) ? maxCapacity : DEFAULT_INITIAL_CAPACITY;
        this.elements = new Object[initCap];
        this.size = 0;
    }

    /**
     * Constructs a bounded concurrent list with the specified initial buffer capacity
     * and maximum capacity bound.
     *
     * @param initialCapacity the initial array buffer capacity
     * @param maxCapacity the maximum capacity limit
     * @throws IllegalArgumentException if parameters are invalid
     */
    public ConcurrentList(int initialCapacity, int maxCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity cannot be negative: " + initialCapacity);
        }
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Max capacity must be positive: " + maxCapacity);
        }
        if (initialCapacity > maxCapacity) {
            throw new IllegalArgumentException("Initial capacity cannot exceed max capacity");
        }
        this.maxCapacity = maxCapacity;
        int init = (initialCapacity == 0) ? 1 : initialCapacity;
        this.elements = new Object[init];
        this.size = 0;
    }

    /* ========================================================================= */
    /*                         Blocking Operations                               */
    /* ========================================================================= */

    /**
     * Appends the specified element to the end of this list, waiting if necessary
     * until space becomes available if the list is full.
     *
     * @param element the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if the specified element is null
     */
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            while (size >= maxCapacity) {
                lock.wait();
            }
            insertLast(element);
            lock.notifyAll();
        }
    }

    /**
     * Inserts the specified element at the specified position in this list, waiting
     * if necessary until space becomes available if the list is full.
     *
     * @param index the index at which the specified element is to be inserted
     * @param element the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IndexOutOfBoundsException if the index is out of range (index < 0 || index > size)
     * @throws NullPointerException if the specified element is null
     */
    public void put(int index, E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            while (size >= maxCapacity) {
                lock.wait();
                if (index > size) {
                    throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
                }
            }
            insertAt(index, element);
            lock.notifyAll();
        }
    }

    /**
     * Retrieves and removes the first element of this list, waiting if necessary
     * until an element becomes available if the list is empty.
     *
     * @return the first element of this list
     * @throws InterruptedException if interrupted while waiting
     */
    public E take() throws InterruptedException {
        return takeFirst();
    }

    /**
     * Retrieves and removes the first element of this list, waiting if necessary
     * until an element becomes available if the list is empty.
     *
     * @return the first element of this list
     * @throws InterruptedException if interrupted while waiting
     */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = removeAt(0);
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Retrieves and removes the last element of this list, waiting if necessary
     * until an element becomes available if the list is empty.
     *
     * @return the last element of this list
     * @throws InterruptedException if interrupted while waiting
     */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = removeAt(size - 1);
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Retrieves and removes the element at the specified index, waiting if necessary
     * until an element exists at that index.
     *
     * @param index the position from which to take an element
     * @return the element previously at the specified position
     * @throws InterruptedException if interrupted while waiting
     * @throws IndexOutOfBoundsException if index is out of capacity bounds
     */
    public E take(int index) throws InterruptedException {
        synchronized (lock) {
            if (index < 0 || index >= maxCapacity) {
                throw new IndexOutOfBoundsException("Index out of capacity bounds: " + index);
            }
            while (size <= index) {
                lock.wait();
            }
            E item = removeAt(index);
            lock.notifyAll();
            return item;
        }
    }

    /* ========================================================================= */
    /*                       Non-Blocking Operations                             */
    /* ========================================================================= */

    /**
     * Inserts the specified element at the end of this list if possible without
     * exceeding capacity, returning immediately.
     *
     * @param element the element to add
     * @return {@code true} if the element was added, or {@code false} if the list is full
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (size >= maxCapacity) {
                return false;
            }
            insertLast(element);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Inserts the specified element at the specified position in this list if
     * possible without exceeding capacity, returning immediately.
     *
     * @param index the index at which the element is to be inserted
     * @param element the element to add
     * @return {@code true} if the element was added, or {@code false} if the list is full
     * @throws IndexOutOfBoundsException if the index is out of range (index < 0 || index > size)
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(int index, E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (size >= maxCapacity) {
                return false;
            }
            insertAt(index, element);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Retrieves and removes the first element of this list, or returns {@code null}
     * immediately if this list is empty.
     *
     * @return the first element of this list, or {@code null} if empty
     */
    public E poll() {
        return pollFirst();
    }

    /**
     * Retrieves and removes the first element of this list, or returns {@code null}
     * immediately if this list is empty.
     *
     * @return the first element of this list, or {@code null} if empty
     */
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = removeAt(0);
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Retrieves and removes the last element of this list, or returns {@code null}
     * immediately if this list is empty.
     *
     * @return the last element of this list, or {@code null} if empty
     */
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = removeAt(size - 1);
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Retrieves, but does not remove, the first element of this list, or returns
     * {@code null} if this list is empty.
     *
     * @return the first element of this list, or {@code null} if empty
     */
    @SuppressWarnings("unchecked")
    public E peekFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (E) elements[0];
        }
    }

    /**
     * Retrieves, but does not remove, the last element of this list, or returns
     * {@code null} if this list is empty.
     *
     * @return the last element of this list, or {@code null} if empty
     */
    @SuppressWarnings("unchecked")
    public E peekLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (E) elements[size - 1];
        }
    }

    /* ========================================================================= */
    /*                         Standard List Operations                          */
    /* ========================================================================= */

    /**
     * Appends the specified element to the end of this list.
     *
     * @param element element to be appended to this list
     * @return {@code true}
     * @throws IllegalStateException if the list is full
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (size >= maxCapacity) {
                throw new IllegalStateException("List is full (capacity: " + maxCapacity + ")");
            }
            insertLast(element);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Inserts the specified element at the specified position in this list.
     * Shifts the element currently at that position and any subsequent elements.
     *
     * @param index index at which the specified element is to be inserted
     * @param element element to be inserted
     * @throws IllegalStateException if the list is full
     * @throws IndexOutOfBoundsException if the index is out of range (index < 0 || index > size)
     * @throws NullPointerException if the specified element is null
     */
    public void add(int index, E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (size >= maxCapacity) {
                throw new IllegalStateException("List is full (capacity: " + maxCapacity + ")");
            }
            insertAt(index, element);
            lock.notifyAll();
        }
    }

    /**
     * Returns the element at the specified position in this list.
     *
     * @param index index of the element to return
     * @return the element at the specified position in this list
     * @throws IndexOutOfBoundsException if the index is out of range (index < 0 || index >= size)
     */
    @SuppressWarnings("unchecked")
    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return (E) elements[index];
        }
    }

    /**
     * Returns the first element in this list.
     *
     * @return the first element in this list
     * @throws IndexOutOfBoundsException if the list is empty
     */
    @SuppressWarnings("unchecked")
    public E getFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IndexOutOfBoundsException("List is empty");
            }
            return (E) elements[0];
        }
    }

    /**
     * Returns the last element in this list.
     *
     * @return the last element in this list
     * @throws IndexOutOfBoundsException if the list is empty
     */
    @SuppressWarnings("unchecked")
    public E getLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IndexOutOfBoundsException("List is empty");
            }
            return (E) elements[size - 1];
        }
    }

    /**
     * Replaces the element at the specified position in this list with the specified element.
     *
     * @param index index of the element to replace
     * @param element element to be stored at the specified position
     * @return the element previously at the specified position
     * @throws IndexOutOfBoundsException if the index is out of range (index < 0 || index >= size)
     * @throws NullPointerException if the specified element is null
     */
    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            checkElementIndex(index);
            E old = (E) elements[index];
            elements[index] = element;
            return old;
        }
    }

    /**
     * Removes the element at the specified position in this list.
     * Shifts any subsequent elements to the left and wakes waiting threads.
     *
     * @param index the index of the element to be removed
     * @return the element that was removed from the list
     * @throws IndexOutOfBoundsException if the index is out of range (index < 0 || index >= size)
     */
    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            E item = removeAt(index);
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Removes the first occurrence of the specified element from this list, if present.
     *
     * @param o element to be removed from this list, if present
     * @return {@code true} if this list contained the specified element
     */
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (o.equals(elements[i])) {
                    removeAt(i);
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns the index of the first occurrence of the specified element in this list,
     * or -1 if this list does not contain the element.
     *
     * @param o element to search for
     * @return the index of the first occurrence of the specified element, or -1 if not found
     */
    public int indexOf(Object o) {
        if (o == null) {
            return -1;
        }
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Returns the index of the last occurrence of the specified element in this list,
     * or -1 if this list does not contain the element.
     *
     * @param o element to search for
     * @return the index of the last occurrence of the specified element, or -1 if not found
     */
    public int lastIndexOf(Object o) {
        if (o == null) {
            return -1;
        }
        synchronized (lock) {
            for (int i = size - 1; i >= 0; i--) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Returns {@code true} if this list contains the specified element.
     *
     * @param o element whose presence in this list is to be tested
     * @return {@code true} if this list contains the specified element
     */
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    /**
     * Removes all of the elements from this list and notifies all waiting threads.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    /**
     * Returns the number of elements in this list.
     *
     * @return the number of elements in this list
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns {@code true} if this list contains no elements.
     *
     * @return {@code true} if this list contains no elements
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns {@code true} if this list has reached its maximum capacity.
     *
     * @return {@code true} if full, {@code false} otherwise
     */
    public boolean isFull() {
        synchronized (lock) {
            return size >= maxCapacity;
        }
    }

    /**
     * Returns the maximum capacity bound of this list.
     *
     * @return maximum capacity
     */
    public int capacity() {
        return maxCapacity;
    }

    /**
     * Returns the number of additional elements that this list can accept
     * before reaching maximum capacity.
     *
     * @return remaining capacity
     */
    public int remainingCapacity() {
        synchronized (lock) {
            return maxCapacity - size;
        }
    }

    /**
     * Returns an array containing all of the elements in this list in proper sequence.
     * The returned array is a safe snapshot and modifications to it will not affect this list.
     *
     * @return an array containing all of the elements in this list
     */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i];
            }
            return copy;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            if (size == 0) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < size; i++) {
                sb.append(elements[i]);
                if (i < size - 1) {
                    sb.append(", ");
                }
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /* ========================================================================= */
    /*                         Internal Helper Methods                           */
    /* ========================================================================= */

    private void insertLast(E element) {
        ensureCapacityInternal(size + 1);
        elements[size] = element;
        size++;
    }

    private void insertAt(int index, E element) {
        ensureCapacityInternal(size + 1);
        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
        }
        elements[index] = element;
        size++;
    }

    @SuppressWarnings("unchecked")
    private E removeAt(int index) {
        E old = (E) elements[index];
        int numMoved = size - index - 1;
        if (numMoved > 0) {
            for (int i = index; i < size - 1; i++) {
                elements[i] = elements[i + 1];
            }
        }
        elements[size - 1] = null;
        size--;
        return old;
    }

    private void ensureCapacityInternal(int minCapacity) {
        if (minCapacity > elements.length) {
            int newCap = elements.length + (elements.length >> 1);
            if (newCap - minCapacity < 0) {
                newCap = minCapacity;
            }
            if (newCap > maxCapacity || newCap < 0) {
                newCap = maxCapacity;
            }
            Object[] newElements = new Object[newCap];
            for (int i = 0; i < size; i++) {
                newElements[i] = elements[i];
            }
            elements = newElements;
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }
}
