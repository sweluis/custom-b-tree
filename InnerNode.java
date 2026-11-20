package edu.umd.cs424.database.index;

import java.nio.ByteBuffer;
import java.util.*;

import edu.umd.cs424.database.BaseTransaction;
import edu.umd.cs424.database.common.Buffer;
import edu.umd.cs424.database.common.Pair;
import edu.umd.cs424.database.databox.DataBox;
import edu.umd.cs424.database.databox.Type;
import edu.umd.cs424.database.io.Page;
import edu.umd.cs424.database.table.RecordId;

/**
 * A inner node of a B+ tree. Every non-root inner node in a B+ tree of order n
 * stores between ceil(n/2) and n "pointers" to children nodes, i.e. between
 * ceil(n/2) - 1 and n - 1 keys. An inner node with k keys stores k + 1 "pointers" to
 * children nodes (where a pointer is just a page number). Moreover, every
 * inner node is serialized and persisted on a single page; see toBytes and
 * fromBytes for details on how an inner node is serialized. For example, here
 * is an illustration of an order 5 inner node:
 * <p>
 *     +----+----+----+----+
 *     | 10 | 20 | 30 |    |
 *     +----+----+----+----+
 *    /     |    |     \
 */
class InnerNode extends BPlusNode {
    // Metadata about the B+ tree that this node belongs to.
    private final BPlusTreeMetadata metadata;

    // The page on which this leaf is serialized.
    private final Page page;

    // The keys and child pointers of this inner node. See the comment above
    // LeafNode.keys and LeafNode.rids in LeafNode.java for a warning on the
    // difference between the keys and children here versus the keys and children
    // stored on disk.
    private List<DataBox> keys;
    private List<Integer> children;

    // Constructors //////////////////////////////////////////////////////////////
    /**
     * Construct a brand new inner node. The inner node will be persisted on a
     * brand new page allocated by metadata.getAllocator().
     */
    public InnerNode(BPlusTreeMetadata metadata, List<DataBox> keys,
                     List<Integer> children, BaseTransaction transaction) {
        this(metadata, metadata.getAllocator().allocPage(transaction), keys, children, transaction);
    }

    /**
     * Construct an inner node that is persisted to page `pageNum` allocated by
     * metadata.getAllocator().
     */
    private InnerNode(BPlusTreeMetadata metadata, int pageNum, List<DataBox> keys,
                      List<Integer> children, BaseTransaction transaction) {
        assert(keys.size() <= metadata.getOrder() - 1);
        assert(keys.size() + 1 == children.size());

        this.metadata = metadata;
        this.page = metadata.getAllocator().fetchPage(transaction, pageNum);
        this.keys = keys;
        this.children = children;
        sync(transaction);
    }

    // Core API //////////////////////////////////////////////////////////////////
    // See BPlusNode.get.
    @Override
    public LeafNode get(BaseTransaction transaction, DataBox key) {
        // counts how many separator keys are less then or eq to search key
        int childIndex = numLessThanEqual(key, this.keys);

        BPlusNode child = getChild(transaction, childIndex);
        return child.get(transaction, key);
    }

    // See BPlusNode.getLeftmostLeaf.
    @Override
    public LeafNode getLeftmostLeaf(BaseTransaction transaction) {
        // 0 index is going to be smallest key in subtree
        BPlusNode leftmostChild = getChild(transaction, 0);
        return leftmostChild.getLeftmostLeaf(transaction);
    }

    // See BPlusNode.put.
    @Override
    public Optional<Pair<DataBox, Integer>> put(BaseTransaction transaction, DataBox key, RecordId rid)
    throws BPlusTreeException {
        // Find the child whose subtree should contain this key, then let that
        // child handle the insertion.
        int childIndex = numLessThanEqual(key, this.keys);
        BPlusNode child = getChild(transaction, childIndex);
        Optional<Pair<DataBox, Integer>> childSplit =
                child.put(transaction, key, rid);

        // If the child did not split, this inner node does not need to change.
        if (childSplit.isEmpty()) {
            return Optional.empty();
        }

        // Add the separator and new right-child pointer returned by the child.
        Pair<DataBox, Integer> splitInformation = childSplit.get();
        DataBox newSeparator = splitInformation.getFirst();
        int newRightChildPageNum = splitInformation.getSecond();
        this.keys.add(childIndex, newSeparator);
        this.children.add(childIndex + 1, newRightChildPageNum);

        // If the new pointer still fits, save this node and report no split to
        // its parent.
        int maximumChildren = this.metadata.getOrder();
        if (this.children.size() <= maximumChildren) {
            sync(transaction);
            return Optional.empty();
        }

        // This node now has n + 1 children. Keep ceil((n + 1) / 2) children in
        // the current node and move the key between the two halves up.
        int numberOfChildrenToKeep = (this.metadata.getOrder() + 2) / 2; //+2 bc java rounds down
        int separatorKeyIndex = numberOfChildrenToKeep - 1;

        DataBox separatorKey = this.keys.get(separatorKeyIndex);

        // Copy each half into its own lists. The separator key is intentionally
        // left out of both inner nodes.
        List<DataBox> leftKeys = new ArrayList<>(
                this.keys.subList(0, separatorKeyIndex));
        List<Integer> leftChildren = new ArrayList<>(
                this.children.subList(0, numberOfChildrenToKeep));
        List<DataBox> rightKeys = new ArrayList<>(
                this.keys.subList(separatorKeyIndex + 1, this.keys.size()));
        List<Integer> rightChildren = new ArrayList<>(
                this.children.subList(numberOfChildrenToKeep, this.children.size()));

        // The current node remains the left half of the split.
        this.keys = leftKeys;
        this.children = leftChildren;

        InnerNode rightInnerNode = new InnerNode(
                this.metadata, rightKeys, rightChildren, transaction);
        int rightInnerPageNum = rightInnerNode.getPage().getPageNum();

        sync(transaction);

        // Tell the parent which key moved up and where the new right node lives.
        return Optional.of(new Pair<>(separatorKey, rightInnerPageNum));
    }

    // See BPlusNode.bulkLoad.
    @Override
    public Optional<Pair<DataBox, Integer>> bulkLoad(BaseTransaction transaction, Iterator<Pair<DataBox, RecordId>> data, float fillFactor)
    throws BPlusTreeException {
        // Bulk-loaded keys arrive in sorted order, so new entries always belong
        // in the rightmost child.
        while (data.hasNext()) {
            int rightmostChildIndex = this.children.size() - 1;
            BPlusNode rightmostChild = getChild(transaction, rightmostChildIndex);
            Optional<Pair<DataBox, Integer>> childSplit =
                    rightmostChild.bulkLoad(transaction, data, fillFactor);

            // A child returns empty only after all remaining data fit in its
            // subtree, so there is nothing else for this node to add.
            if (childSplit.isEmpty()) {
                sync(transaction);
                return Optional.empty();
            }

            // Since the split came from the rightmost child, its separator and
            // new page pointer belong at the end of this node.
            Pair<DataBox, Integer> splitInformation = childSplit.get();
            this.keys.add(splitInformation.getFirst());
            this.children.add(splitInformation.getSecond());

            // Keep filling the rightmost child until this inner node overflows
            // or the iterator runs out of data.
            int maximumChildren = this.metadata.getOrder();
            if (this.children.size() <= maximumChildren) {
                continue;
            }

            // Split an overflowing inner node exactly as in put: keep the left
            // group here, move the separator up, and create a new right node.
            int numberOfChildrenToKeep = (this.metadata.getOrder() + 2) / 2;
            int separatorKeyIndex = numberOfChildrenToKeep - 1;
            DataBox separatorKey = this.keys.get(separatorKeyIndex);

            List<DataBox> leftKeys = new ArrayList<>(
                    this.keys.subList(0, separatorKeyIndex));
            List<Integer> leftChildren = new ArrayList<>(
                    this.children.subList(0, numberOfChildrenToKeep));
            List<DataBox> rightKeys = new ArrayList<>(
                    this.keys.subList(separatorKeyIndex + 1, this.keys.size()));
            List<Integer> rightChildren = new ArrayList<>(
                    this.children.subList(numberOfChildrenToKeep, this.children.size()));

            this.keys = leftKeys;
            this.children = leftChildren;

            InnerNode rightInnerNode = new InnerNode(
                    this.metadata, rightKeys, rightChildren, transaction);
            int rightInnerPageNum = rightInnerNode.getPage().getPageNum();

            sync(transaction);
            return Optional.of(new Pair<>(separatorKey, rightInnerPageNum));
        }

        // Reaching this point means there was no data left to load.
        sync(transaction);
        return Optional.empty();
    }

    // See BPlusNode.remove.
    @Override
    public boolean remove(BaseTransaction transaction, DataBox key) {
        // The separator keys tell us which child subtree may contain the key.
        int childIndex = numLessThanEqual(key, this.keys);
        BPlusNode child = getChild(transaction, childIndex);

        // The child removes the key recursively. A false result means either the
        // key was not present or the child still has enough entries afterward.
        if (!child.remove(transaction, key)) {
            return false;
        }

        // An underfull child needs one adjacent sibling for a merge or borrow.
        // Use the sibling on the left when one exists. The first child has no
        // left sibling, so it must use the sibling on its right.
        int siblingIndex;
        boolean siblingIsOnLeft;
        if (childIndex > 0) {
            siblingIndex = childIndex - 1;
            siblingIsOnLeft = true;
        } else {
            siblingIndex = childIndex + 1;
            siblingIsOnLeft = false;
        }

        // Naming both nodes by position makes the merge direction unambiguous:
        // the right node is always merged into the left node.
        int leftChildIndex = Math.min(childIndex, siblingIndex);
        int rightChildIndex = leftChildIndex + 1;
        BPlusNode leftChild = getChild(transaction, leftChildIndex);
        BPlusNode rightChild = getChild(transaction, rightChildIndex);

        // Leaf nodes and inner nodes move different kinds of entries, so they
        // have separate merge and borrow logic.
        if (leftChild instanceof LeafNode) {
            LeafNode leftLeaf = (LeafNode) leftChild;
            LeafNode rightLeaf = (LeafNode) rightChild;

            // Merge when every key from both leaves fits in one leaf.
            if (leftLeaf.canMergeWith(rightLeaf)) {
                leftLeaf.mergeWithRight(transaction, rightLeaf);
            } else {
                // If they cannot merge, the underfull leaf borrows exactly one
                // entry from its sibling. The helper returns the separator key
                // that this parent must store after the entry moves.
                DataBox newSeparator;
                if (siblingIsOnLeft) {
                    newSeparator = rightLeaf.borrowFromLeft(transaction, leftLeaf);
                } else {
                    newSeparator = leftLeaf.borrowFromRight(transaction, rightLeaf);
                }
                this.keys.set(leftChildIndex, newSeparator);
                sync(transaction);
                return false;
            }
        } else {
            InnerNode leftInner = (InnerNode) leftChild;
            InnerNode rightInner = (InnerNode) rightChild;
            DataBox separator = this.keys.get(leftChildIndex);
            int combinedChildren = leftInner.children.size()
                    + rightInner.children.size();

            // Two inner nodes can merge when their child pointers fit in a
            // single inner node. The separator moves down between their keys.
            if (combinedChildren <= this.metadata.getOrder()) {
                leftInner.mergeWithRight(transaction, separator, rightInner);
            } else {
                // Otherwise, the underfull inner node borrows one child pointer.
                // Its sibling provides a replacement for the parent's separator.
                DataBox newSeparator;
                if (siblingIsOnLeft) {
                    newSeparator = rightInner.borrowFromLeft(
                            transaction, separator, leftInner);
                } else {
                    newSeparator = leftInner.borrowFromRight(
                            transaction, separator, rightInner);
                }
                this.keys.set(leftChildIndex, newSeparator);
                sync(transaction);
                return false;
            }
        }

        // Borrowing returned above, so only a merge reaches this point. The
        // right node was merged into the left node and is no longer part of the
        // tree. Remove the separator and the pointer that led to the right node.
        this.keys.remove(leftChildIndex);
        this.children.remove(rightChildIndex);
        sync(transaction);

        // A merge made this inner node smaller. Tell this node's parent whether
        // this node now has fewer than ceil(order / 2) child pointers.
        int minimumChildren = (int) Math.ceil(
                this.metadata.getOrder() / 2.0);
        return this.children.size() < minimumChildren;
    }

    /** Merges the right inner node into this inner node. */
    private void mergeWithRight(BaseTransaction transaction, DataBox separator,
                                InnerNode rightInner) {
        this.keys.add(separator);
        this.keys.addAll(rightInner.keys);
        this.children.addAll(rightInner.children);
        sync(transaction);
    }

    /** Borrows one key and child pointer from the inner node on the left. */
    private DataBox borrowFromLeft(BaseTransaction transaction, DataBox separator,
                                   InnerNode leftInner) {
        int lastKeyIndex = leftInner.keys.size() - 1;
        int lastChildIndex = leftInner.children.size() - 1;

        int borrowedChildPageNum = leftInner.children.remove(lastChildIndex);
        DataBox newSeparator = leftInner.keys.remove(lastKeyIndex);

        this.children.add(0, borrowedChildPageNum);
        this.keys.add(0, separator);

        leftInner.sync(transaction);
        sync(transaction);
        return newSeparator;
    }

    /** Borrows one key and child pointer from the inner node on the right. */
    private DataBox borrowFromRight(BaseTransaction transaction, DataBox separator,
                                    InnerNode rightInner) {
        int borrowedChildPageNum = rightInner.children.remove(0);
        DataBox newSeparator = rightInner.keys.remove(0);

        this.children.add(borrowedChildPageNum);
        this.keys.add(separator);

        rightInner.sync(transaction);
        sync(transaction);
        return newSeparator;
    }

    // Helpers ///////////////////////////////////////////////////////////////////
    @Override
    public Page getPage() {
        return page;
    }

    private BPlusNode getChild(BaseTransaction transaction, int i) {
        int pageNum = children.get(i);
        return BPlusNode.fromBytes(transaction, metadata, pageNum);
    }

    // Just for testing.
    List<DataBox> getKeys() {
        return keys;
    }

    // Just for testing.
    List<Integer> getChildren() {
        return children;
    }

    /**
     * Returns the largest order n such that the serialization of an InnerNode
     * with n-1 keys will fit on a single page of size `pageSizeInBytes`.
     */
    public static int maxOrder(int pageSizeInBytes, Type keySchema) {
        // A leaf node with k entries takes up the following number of bytes:
        //
        //   1 + 4 + (k * keySize) + ((k + 1) * 4)
        //
        // where
        //
        //   - 1 is the number of bytes used to store isLeaf,
        //   - 4 is the number of bytes used to store k,
        //   - keySize is the number of bytes used to store a DataBox of type
        //     keySchema, and
        //   - 4 is the number of bytes used to store a child pointer.
        //
        // Solving the following equation
        //
        //   5 + (k * keySize) + ((k + 1) * 4) <= pageSizeInBytes
        //
        // we get
        //
        //   k = (pageSizeInBytes - 9) / (keySize + 4)
        //
        // The order n is one more than the number of keys k.
        int keySize = keySchema.getSizeInBytes();
        int k = (pageSizeInBytes - 9) / (keySize + 4);
        return k + 1;
    }

    // Pretty Printing ///////////////////////////////////////////////////////////
    @Override
    public String toString() {
        String s = "(";
        for (int i = 0; i < keys.size(); ++i) {
            s += children.get(i) + " " + keys.get(i) + " ";
        }
        s += children.get(children.size() - 1) + ")";
        return s;
    }

    @Override
    public String toSexp(BaseTransaction transaction) {
        String s = "(";
        for (int i = 0; i < keys.size(); ++i) {
            s += getChild(transaction, i).toSexp(transaction);
            s += " " + keys.get(i) + " ";
        }
        s += getChild(transaction, children.size() - 1).toSexp(transaction) + ")";
        return s;
    }

    /**
     * An inner node on page 0 with a single key k and two children on page 1 and
     * 2 is turned into the following DOT fragment:
     *
     *   node0[label = "<f0>|k|<f1>"];
     *   ... // children
     *   "node0":f0 -> "node1";
     *   "node0":f1 -> "node2";
     */
    @Override
    public String toDot(BaseTransaction transaction) {
        var ss = new ArrayList<String>();
        for (int i = 0; i < keys.size(); ++i) {
            ss.add(String.format("<f%d>", i));
            ss.add(keys.get(i).toString());
        }
        ss.add(String.format("<f%d>", keys.size()));

        int pageNum = getPage().getPageNum();
        String s = String.join("|", ss);
        String node = String.format("  node%d[label = \"%s\"];", pageNum, s);

        var lines = new ArrayList<String>();
        lines.add(node);
        for (int i = 0; i < children.size(); ++i) {
            BPlusNode child = getChild(transaction, i);
            int childPageNum = child.getPage().getPageNum();
            lines.add(child.toDot(transaction));
            lines.add(String.format("  \"node%d\":f%d -> \"node%d\";",
                                    pageNum, i, childPageNum));
        }

        return String.join("\n", lines);
    }

    // Serialization /////////////////////////////////////////////////////////////
    @Override
    public byte[] toBytes() {
        // When we serialize an inner node, we write:
        //
        //   a. the literal value 0 (1 byte) which indicates that this node is not
        //      a leaf node,
        //   b. the number (4 bytes) of keys (K) this inner node contains (which is
        //      one less than the number of children pointers),
        //   c. the first child pointer (4 bytes),
        //   d. the K following key, child entries in sequence. [ k1, c2, k2, c3, ..., ].
        //
        //      thus, c and d together will represent [ c1, k1, c2, k2, ..., kK, c{K+1} ].
        //
        // For example, the following bytes:
        //
        //                          child 1        key 1        child 2        key 2      child {k+1}
        //                        ___________   ___________   ___________   ___________   ___________
        //                       /           \ /           \ /           \ /           \ /           \
        //   +----+-------------+-------------+-------------+-------------+-------------+-------------+
        //   | 00 | 00 00 00 02 | 00 00 00 03 | 00 00 00 05 | 00 00 00 01 | 00 00 00 08 | 00 00 00 07 |
        //   +----+-------------+-------------+-------------+-------------+-------------+-------------+
        //    \__/ \___________/ \___________/ \_____________________________________________________/
        //     a         b             c                                  d
        //
        // would represent an inner node with 2 keys (i.e. 5 and 8) with three
        // children pointers (i.e. page 3, page 1, and page 7). This inner node
        // would be represented diagrammatically as:
        //
        //      +---+---+----+
        //      | 5 | 8 |    |
        //      +---+---+----+
        //     /    |    \
        //    3     1     7
        //
        // where 3, 1, 7 are page numbers.

        // All sizes are in bytes.
        int isLeafSize = 1;
        int numKeysSize = Integer.BYTES;
        int keysSize = metadata.getKeySchema().getSizeInBytes() * keys.size();
        int childrenSize = Integer.BYTES * children.size();
        int size = isLeafSize + numKeysSize + keysSize + childrenSize;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put((byte) 0);
        buf.putInt(keys.size());

        buf.putInt(children.get(0));
        for (int i = 0; i < keys.size(); ++i) {
            buf.put(keys.get(i).toBytes());
            buf.putInt(children.get(i + 1));
        }
        return buf.array();
    }

    /**
     * InnerNode.fromBytes(t, meta, p) loads a InnerNode from page p of
     * meta.getAllocator().
     */
    public static InnerNode fromBytes(BaseTransaction transaction, BPlusTreeMetadata metadata,
                                      int pageNum) {
        Page page = metadata.getAllocator().fetchPage(transaction, pageNum);
        Buffer buf = page.getBuffer(transaction);

        assert(buf.get() == (byte) 0);

        var keys = new ArrayList<DataBox>();
        var children = new ArrayList<Integer>();

        int k = buf.getInt();

        children.add(buf.getInt());
        for (int i = 0; i < k; ++i) {
            keys.add(DataBox.fromBytes(buf, metadata.getKeySchema()));
            children.add(buf.getInt());
        }

        assert(children.size() == k + 1);

        return new InnerNode(metadata, pageNum, keys, children, transaction);
    }

    // Builtins //////////////////////////////////////////////////////////////////
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof InnerNode)) {
            return false;
        }
        InnerNode n = (InnerNode) o;
        return page.getPageNum() == n.page.getPageNum() &&
               keys.equals(n.keys) &&
               children.equals(n.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(page.getPageNum(), keys, children);
    }
}
