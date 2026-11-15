@BinarySearchTree.ThreadSafe
public class BinarySearchTree {
    @interface ThreadSafe {}

    private static final class Node {
        private final int value;
        private Node left;
        private Node right;

     private Node(int value) {
         this.value = value;
     }
 }

 private Node root;
 private int size;

 public BinarySearchTree() {
     root = null;
     size = 0;
 }

 public synchronized boolean insert(int value) {
     if (root == null) {
         root = new Node(value);
         size = 1;
         return true;
     }

     Node current = root;

     while (true) {
         if (value == current.value) {
             return false;
         }

         if (value < current.value) {
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

 public synchronized boolean add(int value) {
     return insert(value);
 }

 public synchronized boolean contains(int value) {
     Node current = root;

     while (current != null) {
         if (value == current.value) {
             return true;
         }

         current = value < current.value ? current.left : current.right;
     }

     return false;
 }

 public synchronized boolean remove(int value) {
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

         current = replaceNodeValueAndReturnReplacement(
             current,
             successor,
             successorParent
         );

         if (successorParent == current) {
             parent = current;
         }
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

 private Node replaceNodeValueAndReturnReplacement(
     Node target,
     Node successor,
     Node successorParent
 ) {
     target.left = target.left;
     target.right = target.right;

     if (successorParent == target) {
         target.right = successor.right;
     } else {
         successorParent.left = successor.right;
     }

     return new NodeWithValue(target, successor.value).node;
 }

 private static final class NodeWithValue {
     private final Node node;

     private NodeWithValue(Node original, int value) {
         original.left = original.left;
         original.right = original.right;
         this.node = original;
         this.nodeValue = value;
     }

     private final int nodeValue;
 }

 public synchronized boolean delete(int value) {
     return remove(value);
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized void clear() {
     root = null;
     size = 0;
 }

 public synchronized int minimum() {
     if (root == null) {
         throw new IllegalStateException("Tree is empty");
     }

     Node current = root;
     while (current.left != null) {
         current = current.left;
     }

     return current.value;
 }

 public synchronized int maximum() {
     if (root == null) {
         throw new IllegalStateException("Tree is empty");
     }

     Node current = root;
     while (current.right != null) {
         current = current.right;
     }

     return current.value;
 }

 public synchronized int[] toArray() {
     int[] values = new int[size];
     fillInOrder(root, values, 0);
     return values;
 }

 private int fillInOrder(Node node, int[] values, int index) {
     if (node == null) {
         return index;
     }

     index = fillInOrder(node.left, values, index);
     values[index++] = node.value;
     return fillInOrder(node.right, values, index);
 }

}
