@RedBlackTree.ThreadSafe
class RedBlackTree {
    @interface ThreadSafe {}

    static final boolean RED = true;
    static final boolean BLACK = false;

    class Node {
        int key;
        Node left, right;
        Node parent;
        boolean color;

        Node(int item) {
            key = item;
            left = right = parent = null;
            color = RED;
        }
    }

    Node root;
    Node TNULL;

    RedBlackTree() {
        TNULL = new Node(0);
        TNULL.color = BLACK;
        root = TNULL;
    }

    Node searchTree(int k) {
        return searchTreeHelper(this.root, k);
    }

    Node searchTreeHelper(Node node, int key) {
        if (node == TNULL || key == node.key) return node;

        if (key < node.key) {
            return searchTreeHelper(node.left, key);
        } else {
            return searchTreeHelper(node.right, key);
        }
    }

    void leftRotate(Node x) {
        Node y = x.right;
        x.right = y.left;
        if (y.left != TNULL) {
            y.left.parent = x;
        }
        y.parent = x.parent;
        if (x.parent == null) {
            this.root = y;
        } else if (x == x.parent.left) {
            x.parent.left = y;
        } else {
            x.parent.right = y;
        }
        y.left = x;
        x.parent = y;
    }

    void rightRotate(Node x) {
        Node y = x.left;
        x.left = y.right;

        if (y.right != TNULL) {
            y.right.parent = x;
        }

        y.parent = x.parent;

        if (x.parent == null) {
            this.root = y;
        } else if (x == x.parent.right) {
            x.parent.right = y;
        } else {
            x.parent.left = y;
        }

        y.right = x;
        x.parent = y;
    }

    void insert(int key) {
        Node node = new Node(key);
        node.parent = null;
        node.data = key;
        node.left = TNULL;
        node.right = TNULL;
        node.color = RED;

        Node y = null;
        Node x = this.root;

        while (x != TNULL) {
            y = x;
            if (node.data < x.data) {
                x = x.left;
            } else {
                x = x.right;
            }
        }

        node.parent = y;
        if (y == null) {
            root = node;
        } else if (node.data < y.data) {
            y.left = node;
        } else {
            y.right = node;
        }

        if (node.parent == null) {
            node.color = BLACK;
            return;
        }
        if (node.parent.parent == null) {
            return;
        }

        fixInsert(node);
    }

    void fixInsert(Node k) {
        while (k.parent.color == RED) {
            if (k.parent == k.parent.parent.right) {
                Node u = k.parent.parent.left;

                if (u.color == RED) {
                    u.color = BLACK;
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    k = k.parent.parent;
                } else {
                    if (k == k.parent.left) {
                        k = k.parent;
                        rightRotate(k);
                    }
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    leftRotate(k.parent.parent);
                }
            } else {
                Node u = k.parent.parent.right;

                if (u.color == RED) {
                    u.color = BLACK;
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    k = k.parent.parent;
                } else {
                    if (k == k.parent.right) {
                        k = k.parent;
                        leftRotate(k);
                    }
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    rightRotate(k.parent.parent);
                }
            }

            if (k == root) break;
        }
        root.color = BLACK;
    }

    Node minimum(Node node) {
        while (node.left != TNULL) {
            node = node.left;
        }
        return node;
    }

    Node maximum(Node node) {
        while (node.right != TNULL) {
            node = node.right;
        }
        return node;
    }

    Node successor(Node x) {
        if (x.right != TNULL) {
            return minimum(x.right);
        }
        Node y = x.parent;
        while (y != TNULL && x == y.right) {
            x = y;
            y = y.parent;
        }
        return y;
    }

    Node predecessor(Node x) {
        if (x.left != TNULL) {
            return maximum(x.left);
        }
        Node y = x.parent;
        while (y != TNULL && x == y.left) {
            x = y;
            y = y.parent;
        }
        return y;
    }

    void delete(int data) {
        deleteNodeHelper(this.root, data);
    }

    Node deleteNodeHelper(Node root, int key) {
        Node z = TNULL;
        Node x, y;
        while (root != null) {
            if (root.data == key) {
                z = root;
            }

            if (root.data <= key) {
                root = root.right;
            } else {
                root = root.left;
            }
        }

        if (z == TNULL) return null;

        y = z;
        boolean y_original_color = y.color;
        if (z.left == TNULL) {
            x = z.right;
            transplant(root, z, z.right);
        } else if (z.right == TNULL) {
            x = z.left;
            transplant(root, z, z.left);
        } else {
            y = minimum(z.right);
            y_original_color = y.color;
            x = y.right;
            if (y.parent == z) {
                x.parent = y;
            } else {
                transplant(root, y, y.right);
                y.right = z.right;
                y.right.parent = y;
            }
            transplant(root, z, y);
            y.left = z.left;
            y.left.parent = y;
            y.color = z.color;
        }
        if (y_original_color == BLACK) {
            fixDelete(x);
        }
        return root;
    }

    void fixDelete(Node x) {
        while (x != root && x.color == BLACK) {
            if (x == x.parent.left) {
                Node s = x.parent.right;

                if (s.color == RED) {
                    s.color = BLACK;
                    x.parent.color = RED;
                    leftRotate(x.parent);
                    s = x.parent.right;
                }

                if (s.left.color == BLACK && s.right.color == BLACK) {
                    s.color = RED;
                    x = x.parent;
                } else {
                    if (s.right.color == BLACK) {
                        s.left.color = BLACK;
                        s.color = RED;
                        rightRotate(s);
                        s = x.parent.right;
                    }

                    s.color = x.parent.color;
                    x.parent.color = BLACK;
                    s.right.color = BLACK;
                    leftRotate(x.parent);
                    x = root;
                }
            } else {
                Node s = x.parent.left;

                if (s.color == RED) {
                    s.color = BLACK;
                    x.parent.color = RED;
                    rightRotate(x.parent);
                    s = x.parent.left;
                }

                if (s.right.color == BLACK && s.left.color == BLACK) {
                    s.color = RED;
                    x = x.parent;
                } else {
                    if (s.left.color == BLACK) {
                        s.right.color = BLACK;
                        s.color = RED;
                        leftRotate(s);
                        s = x.parent.left;
                    }

                    s.color = x.parent.color;
                    x.parent.color = BLACK;
                    s.left.color = BLACK;
                    rightRotate(x.parent);
                    x = root;
                }
            }
        }
        x.color = BLACK;
    }

    void transplant(Node u, Node v) {
        if (u.parent == null) {
            root = v;
        } else if (u == u.parent.left) {
            u.parent.left = v;
        } else {
            u.parent.right = v;
        }
        v.parent = u.parent;
    }

    void printTree() {
        if (root != null) {
            printHelper(this.root, "", true);
        }
    }

    void printHelper(Node node, String indent, boolean last) {
        if (node != TNULL) {
            System.out.print(indent);
            if (last) {
                System.out.print("R----");
                indent += "   ";
            } else {
                System.out.print("L----");
                indent += "|  ";
            }

            String sColor = (node.color == RED) ? "RED" : "BLACK";
            System.out.println(node.data + "(" + sColor + ")");

            printHelper(node.left, indent, false);
            printHelper(node.right, indent, true);
        }
    }

    public static void main(String[] args) {
        RedBlackTree tree = new RedBlackTree();
        int[] a = {21, 23, 9, 14, 18, 50};
        for (int i = 0; i < a.length; i++) {
            tree.insert(a[i]);
        }
        tree.printTree();
    }
}
