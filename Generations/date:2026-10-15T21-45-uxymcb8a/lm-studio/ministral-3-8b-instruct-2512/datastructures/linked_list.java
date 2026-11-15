@Node.ThreadSafe
class Node {
    @interface ThreadSafe {}

    int value;
    Node next;

    Node(int value) {
        this.value = value;
        this.next = null;
    }
}

@ConcurrentLinkedList.ThreadSafe
class ConcurrentLinkedList {
    @interface ThreadSafe {}

    private Node head;
    private Node tail;
    private volatile int size;

    public ConcurrentLinkedList() {
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    public void add(int value) {
        Node newNode = new Node(value);
        while (true) {
            Node currentTail;
            synchronized (this) {
                currentTail = this.tail;
            }

            newNode.next = null;

            synchronized (this) {
                if (currentTail == this.tail) {
                    if (currentTail == null) {
                        head = newNode;
                        tail = newNode;
                    } else {
                        currentTail.next = newNode;
                        tail = newNode;
                    }
                    size++;
                    return;
                }
            }

            // Wait for the tail to not change
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public boolean remove(int value) {
        while (true) {
            Node prev = null;
            Node current = head;

            synchronized (this) {
                while (current != null && current.value != value) {
                    prev = current;
                    current = current.next;
                }

                if (current != null) {
                    if (prev == null) {
                        head = current.next;
                    } else {
                        prev.next = current.next;
                    }

                    if (current == tail) {
                        // If the node to remove is the tail, find new tail
                        Node newTail = head;
                        while (newTail != null && newTail.next != current) {
                            newTail = newTail.next;
                        }
                        if (newTail == null) {
                            tail = null; // List is now empty
                        } else {
                            tail = newTail;
                        }
                    }

                    size--;
                    return true;
                } else {
                    // Value not found, try again
                    return false;
                }
            }
        }
    }

    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }

        Node current = head;
        int count = 0;

        while (current != null) {
            if (count == index) {
                return current.value;
            }
            count++;
            current = current.next;
        }

        throw new IndexOutOfBoundsException();
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
