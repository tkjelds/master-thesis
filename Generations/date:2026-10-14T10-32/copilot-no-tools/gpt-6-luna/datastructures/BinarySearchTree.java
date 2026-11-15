public final class BinarySearchTree {
    private static final class Node {
        int value;
        Node left;
        Node right;

     Node(int value) {
         this.value = value;
     }
 }

 private final Object lock = new Object();
 private Node root;
 private int size;

 /**
  * Inserts value if it is not already present.
  *
  * @return true if the value was inserted; false if it was already present
  */
 public boolean insert(int value) {
     synchronized (lock) {
         if (root == null) {
             root = new Node(value);
             size++;
             return true;
         }

         Node current = root;
         while (true) {
             if (value == current.value) {
                 return false;
             } else if (value < current.value) {
                 if (current.left == null) {
                     current.left = new Node(value);
                     size++;
                     return true;
                 }
                 current = current.left;
             } else {
                 if (current.right == null) {
                     current.right = new Node(value);
                     size++;
                     return true;
                 }
                 current = current.right;
             }
         }
     }
 }

 public boolean contains(int value) {
     synchronized (lock) {
         Node current = root;
         while (current != null) {
             if (value == current.value) {
                 return true;
             }
             current = value < current.value ? current.left : current.right;
         }
         return false;
     }
 }

 /**
  * Removes value if present.
  *
  * @return true if the value was removed; false if it was not present
  */
 public boolean remove(int value) {
     synchronized (lock) {
         Node parent = null;
         Node current = root;

         while (current != null && current.value != value) {
             parent = current;
             current = value < current.value ? current.left : current.right;
         }

         if (current == null) {
             return false;
         }

         if (current.left != null && current.right != null) {
             Node successorParent = current;
             Node successor = current.right;
             while (successor.left != null) {
                 successorParent = successor;
                 successor = successor.left;
             }

             current.value = successor.value;
             parent = successorParent;
             current = successor;
         }

         Node replacement = current.left != null ? current.left : current.right;
         if (parent == null) {
             root = replacement;
         } else if (parent.left == current) {
             parent.left = replacement;
         } else {
             parent.right = replacement;
         }

         size--;
         return true;
     }
 }

 public int size() {
     synchronized (lock) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (lock) {
         return size == 0;
     }
 }

 public void clear() {
     synchronized (lock) {
         root = null;
         size = 0;
     }
 }

 /**
  * Returns the tree values in ascending order.
  */
 public int[] toArray() {
     synchronized (lock) {
         int[] values = new int[size];
         writeInOrder(root, values, 0);
         return values;
     }
 }

 private int writeInOrder(Node node, int[] values, int index) {
     if (node == null) {
         return index;
     }

     index = writeInOrder(node.left, values, index);
     values[index++] = node.value;
     return writeInOrder(node.right, values, index);
 }

}
