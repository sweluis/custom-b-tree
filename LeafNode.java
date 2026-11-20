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
 * A leaf of a B+ tree. Every non-root leaf in a B+ tree of order n stores between
 * ceil((n-1)/2) and n-1 (key, record id) pairs and a pointer to its right sibling (i.e. the page
 * number of its right sibling). Moreover, every leaf node is serialized and
 * persisted on a single page; see toBytes and fromBytes for details on how a
 * leaf is serialized. For example, here is an illustration of two order 5
 * leafs connected together:
 *
 *   leaf 1 (stored on some page)          leaf 2 (stored on some other page)
 *   +-------+-------+-------+-------+     +-------+-------+-------+-------+
 *   | k0:r0 | k1:r1 | k2:r2 |       | --> | k3:r3 | k4:r4 |       |       |
 *   +-------+-------+-------+-------+     +-------+-------+-------+-------+
 */
class LeafNode extends BPlusNode {
    // Metadatta about the B+ tree hat this node belongs to.
    private final BPlusTreeMetadata metadata;

    // The page on which this leaf is serialized.
    private final Page page;

    // The keys and record ids of this leaf. `keys` is always sorted in ascending
    // order. The record id at index i corresponds to the key at index i. For
    // example, the keys [a, b, c] and the rids [1, 2, 3] represent the pairing
    // [a:1, b:2, c:3].
    //
    // Note the following subtlety. keys and rids are in-memory caches of the
    // keys and record ids stored on disk. Thus, consider what happens when you
    // create two LeafNode objects that point to the same page:
    //
    //   BPlusTreeMetadata meta = ...;
    //   int pageNum = ...;
    //   Page page = allocator.fetchPage(pageNum);
    //   ByteBuffer buf = page.getByteBuffer();
    //
    //   LeafNode leaf0 = LeafNode.fromBytes(buf, meta, pageNum);
    //   LeafNode leaf1 = LeafNode.fromBytes(buf, meta, pageNum);
    //
    // This scenario looks like this:
    //
    //   HEAP                        | DISK
    //   ===============================================================
    //   leaf0                       | page 42
    //   +-------------------------+ | +-------+-------+-------+-------+
    //   | keys = [k0, k1, k2]     | | | k0:r0 | k1:r1 | k2:r2 |       |
    //   | rids = [r0, r1, r2]     | | +-------+-------+-------+-------+
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //   leaf1                       |
    //   +-------------------------+ |
    //   | keys = [k0, k1, k2]     | |
    //   | rids = [r0, r1, r2]     | |
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //
    // Now imagine we perform on operation on leaf0 like leaf0.put(k3, r3). The
    // in-memory values of leaf0 will be updated and they will be synced to disk.
    // But, the in-memory values of leaf1 will not be updated. That will look
    // like this:
    //
    //   HEAP                        | DISK
    //   ===============================================================
    //   leaf0                       | page 42
    //   +-------------------------+ | +-------+-------+-------+-------+
    //   | keys = [k0, k1, k2, k3] | | | k0:r0 | k1:r1 | k2:r2 | k3:r3 |
    //   | rids = [r0, r1, r2, r3] | | +-------+-------+-------+-------+
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //   leaf1                       |
    //   +-------------------------+ |
    //   | keys = [k0, k1, k2]     | |
    //   | rids = [r0, r1, r2]     | |
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //
    // Make sure your code (or your tests) doesn't use stale in-memory cached
    // values of keys and rids.
    private List<DataBox> keys;
    private List<RecordId> rids;

    // If this leaf is the rightmost leaf, then rightSibling is Optional.empty().
    // Otherwise, rightSibling is Optional.of(n) where n is the page number of
    // this leaf's right sibling.
    private Optional<Integer> rightSibling;

    // Constructors //////////////////////////////////////////////////////////////
    /**
     * Construct a brand new leaf node. The leaf will be persisted on a brand new
     * page allocated by metadata.getAllocator().
     */
    public LeafNode(BPlusTreeMetadata metadata, List<DataBox> keys,
                    List<RecordId> rids, Optional<Integer> rightSibling, BaseTransaction transaction) {
        this(metadata, metadata.getAllocator().allocPage(transaction), keys, rids,
             rightSibling, transaction);
    }

    /**
     * Construct a leaf node that is persisted to page `pageNum` allocated by
     * metadata.getAllocator().
     */
    private LeafNode(BPlusTreeMetadata metadata, int pageNum, List<DataBox> keys,
                     List<RecordId> rids, Optional<Integer> rightSibling, BaseTransaction transaction) {
        assert(keys.size() == rids.size());

        this.metadata = metadata;
        this.page = metadata.getAllocator().fetchPage(transaction, pageNum);
        this.keys = keys;
        this.rids = rids;
        this.rightSibling = rightSibling;
        sync(transaction);
    }

    // Core API //////////////////////////////////////////////////////////////////
    // See BPlusNode.get.
    @Override
    public LeafNode get(BaseTransaction transaction, DataBox key) {
        // leaf has no children to search. get says find leaf key
        // would belong to which has to leaf
        return this;
    }

    // See BPlusNode.getLeftmostLeaf.
    @Override
    public LeafNode getLeftmostLeaf(BaseTransaction transaction) {
        return this;
    }

    // See BPlusNode.put.
    @Override
    public Optional<Pair<DataBox, Integer>> put(BaseTransaction transaction, DataBox key, RecordId rid)
    throws BPlusTreeException {
        // This B+ tree stores only one record for each key.
        if (this.keys.contains(key)) {
            throw new BPlusTreeException("Duplicate keys are not allowed.");
        }

        // Insert the key and its record id at the same position so the keys stay
        // sorted and each key remains paired with the correct record id.
        int insertionIndex = numLessThan(key, this.keys);
        this.keys.add(insertionIndex, key);
        this.rids.add(insertionIndex, rid);

        // If the new entry still fits, save the leaf and tell the parent that
        // there was no split.
        int maximumEntries = this.metadata.getOrder() - 1;
        if (this.keys.size() <= maximumEntries) {
            sync(transaction);
            return Optional.empty();
        }

        // The leaf overflowed. The current leaf stays on the left and keeps the
        // first ceil(n / 2) entries.
        int numberOfEntriesToKeep = (this.metadata.getOrder() + 1) / 2;

        // Make independent lists for both halves instead of keeping subList
        // views that would still be connected to the original lists.
        List<DataBox> leftKeys = new ArrayList<>(
                this.keys.subList(0, numberOfEntriesToKeep));
        List<RecordId> leftRids = new ArrayList<>(
                this.rids.subList(0, numberOfEntriesToKeep));
        List<DataBox> rightKeys = new ArrayList<>(
                this.keys.subList(numberOfEntriesToKeep, this.keys.size()));
        List<RecordId> rightRids = new ArrayList<>(
                this.rids.subList(numberOfEntriesToKeep, this.rids.size()));

        this.keys = leftKeys;
        this.rids = leftRids;

        // The new right leaf takes over the current leaf's old right sibling.
        LeafNode rightLeaf = new LeafNode(
                this.metadata, rightKeys, rightRids, this.rightSibling, transaction);
        int rightLeafPageNum = rightLeaf.getPage().getPageNum();

        // Link the current leaf to the new right leaf.
        this.rightSibling = Optional.of(rightLeafPageNum);

        sync(transaction);

        // The parent needs the first key in the right leaf and the page where
        // that leaf was stored.
        DataBox splitKey = rightKeys.get(0);
        return Optional.of(new Pair<>(splitKey, rightLeafPageNum));
    }

    // See BPlusNode.bulkLoad.
    @Override
    public Optional<Pair<DataBox, Integer>> bulkLoad(BaseTransaction transaction,
            Iterator<Pair<DataBox, RecordId>> data,
            float fillFactor)
    throws BPlusTreeException {
        // Bulk-load input is already sorted, so entries can be added directly
        // to the end of the leaf.
        int maximumEntries = this.metadata.getOrder() - 1;
        int targetEntries = (int) Math.ceil(fillFactor * maximumEntries);

        while (data.hasNext() && this.keys.size() < targetEntries) {
            Pair<DataBox, RecordId> nextEntry = data.next();
            this.keys.add(nextEntry.getFirst());
            this.rids.add(nextEntry.getSecond());
        }

        // If all of the data fit, save the leaf and report no split.
        if (!data.hasNext()) {
            sync(transaction);
            return Optional.empty();
        }

        // The next entry starts a new right leaf. That leaf takes over this
        // leaf's old right sibling.
        Pair<DataBox, RecordId> firstRightEntry = data.next();
        List<DataBox> rightKeys = new ArrayList<>();
        List<RecordId> rightRids = new ArrayList<>();
        rightKeys.add(firstRightEntry.getFirst());
        rightRids.add(firstRightEntry.getSecond());

        LeafNode rightLeaf = new LeafNode(
                this.metadata, rightKeys, rightRids, this.rightSibling, transaction);
        int rightLeafPageNum = rightLeaf.getPage().getPageNum();

        // Link this leaf to the new right leaf and save the updated pointer.
        this.rightSibling = Optional.of(rightLeafPageNum);
        sync(transaction);

        // The parent uses the first key in the right leaf as its new separator.
        DataBox separatorKey = firstRightEntry.getFirst();
        return Optional.of(new Pair<>(separatorKey, rightLeafPageNum));
    }

    // See BPlusNode.remove.
    @Override
    public boolean remove(BaseTransaction transaction, DataBox key) {
        // Find the key's position in this leaf. indexOf returns -1 when the key
        // is not stored here.
        int keyIndex = this.keys.indexOf(key);

        // A missing key does not change the leaf. Returning false tells the
        // parent that this leaf does not need to be repaired.
        if (keyIndex == -1) {
            return false;
        }

        // Each key and its RecordId use the same list position, so both values
        // must be removed at the same index.
        this.keys.remove(keyIndex);
        this.rids.remove(keyIndex);

        // Write the updated leaf to its page.
        sync(transaction);

        // If the deletion made this leaf too small, true tells its parent to
        // repair it by borrowing from a sibling or merging with a sibling.
        return isUnderfull();
    }

    /** Returns whether this leaf has fewer entries than a non-root leaf needs. */
    private boolean isUnderfull() {
        // Every non-root leaf needs at least ceil((order - 1) / 2) entries.
        int minimumEntries = (int) Math.ceil(
                (this.metadata.getOrder() - 1) / 2.0);
        return this.keys.size() < minimumEntries;
    }

    /** Returns whether this leaf and its right sibling fit in one leaf. */
    boolean canMergeWith(LeafNode rightLeaf) {
        int maximumEntries = this.metadata.getOrder() - 1;
        return this.keys.size() + rightLeaf.keys.size() <= maximumEntries;
    }

    /** Merges the right leaf into this leaf and repairs the sibling chain. */
    void mergeWithRight(BaseTransaction transaction, LeafNode rightLeaf) {
        // Move every entry from the right leaf to the end of this left leaf.
        this.keys.addAll(rightLeaf.keys);
        this.rids.addAll(rightLeaf.rids);

        // Skip over the removed right leaf in the linked list of leaves.
        this.rightSibling = rightLeaf.rightSibling;
        sync(transaction);
    }

    /** Borrows the last entry from the leaf immediately to the left. */
    DataBox borrowFromLeft(BaseTransaction transaction, LeafNode leftLeaf) {
        // Take the largest entry from the left leaf.
        int lastEntryIndex = leftLeaf.keys.size() - 1;
        DataBox borrowedKey = leftLeaf.keys.remove(lastEntryIndex);
        RecordId borrowedRid = leftLeaf.rids.remove(lastEntryIndex);

        // Because this key came from the left, it becomes this leaf's first
        // (smallest) entry.
        this.keys.add(0, borrowedKey);
        this.rids.add(0, borrowedRid);

        leftLeaf.sync(transaction);
        sync(transaction);
        // The parent must use this leaf's new first key as its separator.
        return this.keys.get(0);
    }

    /** Borrows the first entry from the leaf immediately to the right. */
    DataBox borrowFromRight(BaseTransaction transaction, LeafNode rightLeaf) {
        // Take the smallest entry from the right leaf and append it here.
        DataBox borrowedKey = rightLeaf.keys.remove(0);
        RecordId borrowedRid = rightLeaf.rids.remove(0);

        this.keys.add(borrowedKey);
        this.rids.add(borrowedRid);

        rightLeaf.sync(transaction);
        sync(transaction);
        // The right leaf's new first key becomes the parent's new separator.
        return rightLeaf.keys.get(0);
    }

    // Iterators /////////////////////////////////////////////////////////////////
    /** Return the record id associated with `key`. */
    public Optional<RecordId> getKey(DataBox key) {
        int index = keys.indexOf(key);
        return index == -1 ? Optional.empty() : Optional.of(rids.get(index));
    }

    /**
     * Returns an iterator over all the keys present in this node
     */
    public Iterator<DataBox> scanKeys() {
        return keys.iterator();
    }

    /**
     * Returns an iterator over the record ids of this leaf in ascending order of
     * their corresponding keys.
     */
    public Iterator<RecordId> scanAll() {
        return rids.iterator();
    }

    // Helpers ///////////////////////////////////////////////////////////////////
    @Override
    public Page getPage() {
        return page;
    }

    /** Returns the right sibling of this leaf, if it has one. */
    public Optional<LeafNode> getRightSibling(BaseTransaction transaction) {
        return rightSibling.flatMap(pageNum -> Optional.of(LeafNode.fromBytes(transaction, metadata, pageNum)));
    }

    /**
     * Returns the largest order n such that the serialization of a LeafNode
     * with n-1 entries will fit on a single page of size `pageSizeInBytes`.
     */
    public static int maxOrder(int pageSizeInBytes, Type keySchema) {
        // A leaf node with k entries takes up the following number of bytes:
        //
        //   1 + 4 + 4 + k * (keySize + ridSize)
        //
        // where
        //
        //   - 1 is the number of bytes used to store isLeaf,
        //   - 4 is the number of bytes used to store a sibling pointer,
        //   - 4 is the number of bytes used to store k,
        //   - keySize is the number of bytes used to store a DataBox of type
        //     keySchema, and
        //   - ridSize is the number of bytes of a RecordId.
        //
        // Solving the following equation
        //
        //   k * (keySize + ridSize) + 9 <= pageSizeInBytes
        //
        // we get
        //
        //   k = (pageSizeInBytes - 9) / (keySize + ridSize)
        //
        // The order n is one more than the number of entries k.
        int keySize = keySchema.getSizeInBytes();
        int ridSize = RecordId.getSizeInBytes();
        int k = (pageSizeInBytes - 9) / (keySize + ridSize);
        return k + 1;
    }

    // For testing only.
    List<DataBox> getKeys() {
        return keys;
    }

    // For testing only.
    List<RecordId> getRids() {
        return rids;
    }

    // Pretty Printing ///////////////////////////////////////////////////////////
    @Override
    public String toString() {
        return String.format("LeafNode(pageNum=%s, keys=%s, rids=%s)",
                             page.getPageNum(), keys, rids);
    }

    @Override
    public String toSexp(BaseTransaction transaction) {
        var ss = new ArrayList<String>();
        for (int i = 0; i < keys.size(); ++i) {
            String key = keys.get(i).toString();
            String rid = rids.get(i).toSexp();
            ss.add(String.format("(%s %s)", key, rid));
        }
        return String.format("(%s)", String.join(" ", ss));
    }

    /**
     * Given a leaf with page number 1 and three (key, rid) pairs (0, (0, 0)),
     * (1, (1, 1)), and (2, (2, 2)), the corresponding dot fragment is:
     * <p>
     *   node1[label = "{0: (0 0)|1: (1 1)|2: (2 2)}"];
     */
    @Override
    public String toDot(BaseTransaction transaction) {
        var ss = new ArrayList<String>();
        for (int i = 0; i < keys.size(); ++i) {
            ss.add(String.format("%s: %s", keys.get(i), rids.get(i).toSexp()));
        }
        int pageNum = getPage().getPageNum();
        String s = String.join("|", ss);
        return String.format("  node%d[label = \"{%s}\"];", pageNum, s);
    }

    // Serialization /////////////////////////////////////////////////////////////
    @Override
    public byte[] toBytes() {
        // When we serialize a leaf node, we write:
        //
        //   a. the literal value 1 (1 byte) which indicates that this node is a
        //      leaf node,
        //   b. the value 1 or 0 (1 byte) which indicates whether this node has a right sibling
        //   c. the page id (4 bytes) of our right sibling, this field is ignored if the
        //      right sibling indicator is 0.
        //   d. the number (4 bytes) of keys (K) this leaf node contains {key, rid} pairs this leaf node contains.
        //   e. the K {key, rid} pairs in array style. [ {k1, r1}, {k2, r2}, ..., ]
        //
        // For example, the following bytes:
        //                                              key 1            rid 1            key 2            rid 2
        //                                           ___________   _________________   ___________   _________________
        //                                          /           \ /                 \ /           \ /                 \
        //   +----+----+-------------+-------------+-------------+-------------------+-------------+-------------------+
        //   | 01 | 01 | 00 00 00 04 | 00 00 00 02 | 00 00 00 05 | 00 00 00 03 00 01 | 00 00 00 07 | 00 00 00 04 00 06 |
        //   +----+----+-------------+-------------+-------------+-------------------+-------------+-------------------+
        //    \__/ \__/ \___________/ \___________/ \_________________________________________________________________/
        //     a     b        c             d                                        e
        //
        // represent a leaf node having a sibling on page 4, two keys with its corresponding records [{5, (3, 1)}, {7, (4, 6)}]
        int isLeafSize = 1;
        int hasRightSiblingSize = 1;
        int rightSiblingSize = Integer.BYTES;
        int numKeysSize = Integer.BYTES;
        int keySize = this.metadata.getKeySchema().getSizeInBytes();
        int recordIDSize = RecordId.getSizeInBytes();
        // size of e
        int entriesSize = this.keys.size() * (keySize + recordIDSize);

        int totalSize = isLeafSize + hasRightSiblingSize + rightSiblingSize
            + numKeysSize + entriesSize;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);

        buf.put((byte) 1);
        buf.put((byte) (this.rightSibling.isPresent() ? 1 : 0));
        buf.putInt(this.rightSibling.orElse(-1));
        buf.putInt(this.keys.size());

        for (int i = 0; i < this.keys.size(); ++i) {
            buf.put(this.keys.get(i).toBytes());
            buf.put(this.rids.get(i).toBytes());
        }

        return buf.array();

    }

    /**
     * LeafNode.fromBytes(m, p) loads a LeafNode from page p of
     * meta.getAllocator().
     */
    public static LeafNode fromBytes(BaseTransaction transaction, BPlusTreeMetadata metadata,
                                     int pageNum) {
        Page page = metadata.getAllocator().fetchPage(transaction, pageNum);
        Buffer buf = page.getBuffer(transaction);

        assert(buf.get() == (byte) 1);

        boolean hasRightSibling = buf.get() == (byte) 1;
        int rightSiblingPageNum = buf.getInt();
        Optional<Integer> rightSibling;
        if (hasRightSibling) {
            rightSibling = Optional.of(rightSiblingPageNum);
        } else {
            rightSibling = Optional.empty();
        }

        int numKeys = buf.getInt();
        List<DataBox> keys = new ArrayList<>();
        List<RecordId> rids = new ArrayList<>();

        for (int i = 0; i < numKeys; ++i) {
            keys.add(DataBox.fromBytes(buf, metadata.getKeySchema()));
            rids.add(RecordId.fromBytes(buf));
        }

        return new LeafNode(metadata, pageNum, keys, rids, rightSibling, transaction);
    }

    // Builtins //////////////////////////////////////////////////////////////////
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof LeafNode node)) {
            return false;
        }
        return page.getPageNum() == node.page.getPageNum() &&
               keys.equals(node.keys) &&
               rids.equals(node.rids) &&
               rightSibling.equals(node.rightSibling);
    }

    @Override
    public int hashCode() {
        return Objects.hash(page.getPageNum(), keys, rids, rightSibling);
    }
}
