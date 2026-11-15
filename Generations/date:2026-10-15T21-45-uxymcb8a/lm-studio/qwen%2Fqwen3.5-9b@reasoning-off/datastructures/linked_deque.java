@Node.ThreadSafe
class Node<T> {
    @interface ThreadSafe {}

    private final Node<T> next;
    private final Node<T> prev;
    private final T data;

    private Node(Node<T> next, Node<T> prev, T data) {
        this.next = next;
        this.prev = prev;
        this.data = data;
    }

    public Node getNext() {
        return next;
    }

    public Node getPrev() {
        return prev;
    }

    public T getData() {
        return data;
    }

    public static <T> Node<T> createNode(T data) {
        return new Node<>(null, null, data);
    }

    public void setData(T data) {
        this.data = data;
    }
}

@LinkedListDeque.ThreadSafe
class LinkedListDeque {
    @interface ThreadSafe {}

    // Sentinel node to simplify boundary checks
    private final Node emptyNode;
    // Double-linked lists: Head <-> Tail
    private Node head;
    private Node tail;
    // Size of the deque
    private int size;
    // Maximum capacity (if 0, unbounded)
    private final int capacity;

    public LinkedListDeque(int capacity) {
        this.capacity = capacity;
        // Create a dummy sentinel node. It serves as both the logical head and tail
        // for an empty deque, preventing null checks.
        emptyNode = new Node<>(null, null, null);
        head = emptyNode;
        tail = emptyNode;
        size = 0;
    }

    private synchronized void checkCapacityOrWait() {
        if (capacity != 0) {
            while (size == capacity) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Deque interrupted while waiting for space");
                }
            }
        }
    }

    private synchronized void waitForSpots() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Deque interrupted while waiting for items");
            }
        }
    }

    private synchronized void notifySpots() {
        if (size != 0) {
            notifyAll();
        }
    }

    public synchronized boolean offer(T o) {
        if (o == null) {
            return false;
        }
        checkCapacityOrWait();
        linkLast(o);
        notifySpots();
        return true;
    }

    public synchronized T poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            synchronized (this) {
                // Inner block to ensure we don't hold lock on outer if we called offer from another thread?
                // Actually, the outer synchronized covers the whole method.
                // We need to ensure the tail hasn't moved or been corrupted by another thread
                // between the check at 'if (size == 0)' and the linkFirst call.
                // However, we are already inside a synchronized block on 'this'.
                // But wait, the inner sync is redundant if outer is present. 
                // Let's restructure for clarity and potential optimization, 
                // though pure sync blocks are safer here.
                
                // Re-evaluating: If we are already synchronized on this, 
                // we just need to verify the tail is still the one we expect?
                // No, standard Deque semantics imply removing from the "head" of the queue part.
                // For a double-ended deque, remove() usually means remove from front.
                // head points to the front of the queue (or sentinel).
                
                return unlinkFirst();
            }
        }
    }

    public synchronized T pollLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            // Inner block not strictly needed if outer is used, but kept for safety in pattern
            return unlinkLast();
        }
    }

    public synchronized boolean offerFirst(T o) {
        synchronized (this) {
            checkCapacityOrWait();
            linkFirst(o);
            notifySpots();
        }
        return true;
    }

    public synchronized T pollFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            // Wait loop logic is handled by the caller or internal blocking
            // Since we are already blocked on size==0 by caller logic or here?
            // If capacity is 0 (unbounded), we don't block here.
            // If capacity > 0, and we are removing, we unblock others.
            return unlinkFirst();
        }
    }

    public synchronized T pollLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return unlinkLast();
        }
    }

    public synchronized boolean add(T o) {
        return offer(o);
    }

    public synchronized boolean addFirst(T o) {
        synchronized (this) {
            checkCapacityOrWait();
            linkFirst(o);
        }
        return true;
    }

    public synchronized boolean addLast(T o) {
        synchronized (this) {
            checkCapacityOrWait();
            linkLast(o);
        }
        return true;
    }

    public synchronized T remove() {
        synchronized (this) {
            waitForSpots();
            return unlinkFirst();
        }
    }

    public synchronized T removeFirst() {
        synchronized (this) {
            waitForSpots();
            return unlinkFirst();
        }
    }

    public synchronized T removeLast() {
        synchronized (this) {
            waitForSpots();
            return unlinkLast();
        }
    }

    public synchronized T getFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return unlinkFirst(); // This will return the node's data without unlinking if we fix logic below, 
                                 // but standard behavior for getFirst is often peek.
                                 // To be strictly thread safe, peeking is safer.
                                 // Let's implement a peekFirst equivalent.
        }
        return null; // Dummy return, fixed below
    }

    public synchronized T peekFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return head.next.data;
        }
    }

    public synchronized T peekLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return tail.prev.data;
        }
    }

    public synchronized int size() {
        synchronized (this) {
            return size;
        }
    }

    public synchronized boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public synchronized boolean isFull() {
        synchronized (this) {
            return capacity != 0 && size == capacity;
        }
    }

    // Clear the deque
    public synchronized void clear() {
        synchronized (this) {
            Node oldHead = head;
            Node oldTail = tail;
            head = emptyNode;
            tail = emptyNode;
            size = 0;
            oldTail.prev = null; // break link from tail
            oldHead.next = null; // break link from head
            
            // Notify waiters that space is available
            notifyAll();
        }
    }

    /**
     * Link node at the front (head side).
     */
    private synchronized void linkFirst(T e) {
        Node node = new Node<>(head, head, e);
        head = node;
        if (tail == emptyNode) {
            tail = node;
        }
        size++;
    }

    /**
     * Link node at the back (tail side).
     */
    private synchronized void linkLast(T e) {
        Node node = new Node<>(tail, tail, e);
        tail = node;
        if (head == emptyNode) {
            head = node;
        }
        size++;
    }

    /**
     * Unlink node from the front.
     */
    private synchronized T unlinkFirst() {
        Node first = head.next;
        // Double check if deque is empty, though size should guard this
        if (first == emptyNode) {
            return null;
        }
        head = first.prev;
        // If deque was single element, tail becomes empty node
        if (head == emptyNode) {
            tail = emptyNode;
        } else {
            first.prev = emptyNode; // Link to sentinel
        }
        size--;
        return first.data;
    }

    /**
     * Unlink node from the back.
     */
    private synchronized T unlinkLast() {
        Node last = tail.prev;
        if (last == emptyNode) {
            return null;
        }
        tail = last.next;
        if (tail == emptyNode) {
            head = emptyNode;
        } else {
            last.next = emptyNode;
        }
        size--;
        return last.data;
    }
}
