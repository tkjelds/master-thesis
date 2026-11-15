public class ConcurrentLinkedList {

    private class Node {
        int data;
        Node next;

        Node(int data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node head;
    private int size;
    private final Object lock = new Object();

    public ConcurrentLinkedList() {
        head = null;
        size = 0;
    }

    public void add(int value) {
        synchronized (lock) {
            Node newNode = new Node(value);
            if (head == null) {
                head = newNode;
            } else {
                Node current = head;
                while (current.next != null) {
                    current = current.next;
                }
                current.next = newNode;
            }
            size++;
        }
    }

    public boolean remove(int value) {
        synchronized (lock) {
            if (head == null) {
                return false;
            }

            if (head.data == value) {
                head = head.next;
                size--;
                return true;
            }

            Node current = head;
            while (current.next != null) {
                if (current.next.data == value) {
                    current.next = current.next.next;
                    size--;
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public int get_size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean contains(int value) {
        synchronized (lock) {
            Node current = head;
            while (current != null) {
                if (current.data == value) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public void remove_all_but_less_than(int threshold) {
        synchronized (lock) {
            if (head == null) {
                return;
            }

            Node current = head;
            Node prev = null;

            while (current != null) {
                if (current.data >= threshold) {
                    if (prev == null) {
                        head = head.next;
                        current = head;
                    } else {
                        prev.next = current.next;
                        current = prev.next;
                    }
                    size--;
                } else {
                    prev = current;
                    current = current.next;
                }
            }
        }
    }

    public int[] toArray() {
        int[] arr = new int[size];
        synchronized (lock) {
            Node current = head;
            int i = 0;
            while (current != null) {
                arr[i++] = current.data;
                current = current.next;
            }
        }
        return arr;
    }

    public static void main(String[] args) {
        ConcurrentLinkedList list = new ConcurrentLinkedList();

        Thread producer1 = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 100; i++) {
                    list.add(i);
                }
            }
        });

        Thread producer2 = new Thread(new Runnable() {
            public void run() {
                for (int i = 100; i < 200; i++) {
                    list.add(i);
                }
            }
        });

        producer1.start();
        producer2.start();

        try {
            producer1.join();
            producer2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Size: " + list.get_size());
    }
}
