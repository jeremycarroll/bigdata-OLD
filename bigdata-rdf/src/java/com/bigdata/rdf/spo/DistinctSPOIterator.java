package com.bigdata.rdf.spo;

import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Set;

import com.bigdata.BigdataStatics;
import com.bigdata.btree.BTree;
import com.bigdata.btree.keys.KeyBuilder;
import com.bigdata.rawstore.Bytes;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.store.IRawTripleStore;
import com.bigdata.relation.accesspath.BlockingBuffer;
import com.bigdata.striterator.ICloseableIterator;

/**
 * Iterator using a {@link BTree} filter out duplicate (s,p,o) tuples.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 * @see SPORelation#distinctSPOIterator(ICloseableIterator)
 */
public class DistinctSPOIterator implements ICloseableIterator<ISPO> {
    /**
     * The backing relation, which is only used to obtain the {@link BTree}
     * instance in {@link #overflowToBTree(Set)}.
     */
    private SPORelation spoRelation;

    /**
     * The source iterator.
     */
    private ICloseableIterator<ISPO> src;

    /**
     * Hash set is allocated when the first {@link ISPO} is visited and is used
     * until the {@link #maxHashXSetCapacity} is reached, at which point the
     * {@link #btreeSet} is allocated.
     */
    private Set<ISPO> hashSet;

    /**
     * B+Tree is used once the {@link #maxHashXSetCapacity} is reached. The
     * B+Tree is slowed than the {@link #hashSet}, but can spill onto the disk
     * and is appropriate for very large distinct sets.
     */
    private BTree btreeSet;

    /**
     * Buffer reused for each (s,p,o) key. The buffer is allocated to the exact
     * size when the {@link #btreeSet} is allocated.
     */
    private KeyBuilder keyBuilder;

    /**
     * The next element to be visited or <code>null</code> if we need to scan
     * ahead.
     */
    private ISPO next = null;
  
    private ISPO first = null;

    /**
     * <code>true</code> iff the iterator has been proven to be exhausted.
     */
    private boolean exhausted = false;

    /**
     * <code>true</code> iff the iterator has been {@link #close()}ed.
     */
    private boolean closed = false;

    /**
     * The #of distinct {@link ISPO}s read from the {@link #src} iterator so
     * far.
     */
    private int ndistinct = 0;
    
    /**
     * The #of {@link ISPO}s read from the {@link #src} iterator.
     */
    private int nscanned = 0;

    /**
     * After this many entries we create the {@link #btreeSet} which can spill
     * out onto the disk.
     */
     final int maxHashXSetCapacity;

    /**
     * 
     * @param src
     *            The source iterator.
     */
    public DistinctSPOIterator(final SPORelation spoRelation,
            final ICloseableIterator<ISPO> src,
            final int maxHashSetCapacity) {

        if (spoRelation == null)
            throw new IllegalArgumentException();

        if (src == null)
            throw new IllegalArgumentException();

        this.spoRelation = spoRelation;

        this.src = src;

        this.maxHashXSetCapacity=maxHashSetCapacity;
    }

    public void close() {

        if (closed)
            return;

        closed = true;

        /*
         * Close the source iterator.
         */

        src.close();

        /*
         * Close the btree. This will discard all of its buffers.
         */

        if (btreeSet != null) {

            btreeSet.close();

        }

        spoRelation = null;

        src = null;

        hashSet = null;

        btreeSet = null;

        keyBuilder = null;

    }

    /**
     * Returns immediately if there is an element waiting. Otherwise, scans
     * ahead until it finds an element which has not already been visited. It
     * then add the element to the set of elements already seen and saves a
     * reference to that element to be returned by {@link #next()}.
     */
    public boolean hasNext() {

        if (exhausted || closed)
            return false;

        if (next != null)
            return true;

        if (btreeSet == null && ndistinct >= maxHashXSetCapacity) {

            /*
             * Open B+Tree. We will not put anything new into the hashSet,
             * but we will continue to test first against the hashSet and
             * then against the B+Tree. New distinct ISPOs are inserted into
             * the B+Tree.
             */

            if (BigdataStatics.debug)
                System.err.println("Distinct SPO iterator overflow");

            // allocate buffer.
            keyBuilder = new KeyBuilder(3 * Bytes.SIZEOF_LONG);

            // allocate B+Tree w/ bloom filter.
            btreeSet = spoRelation.getSPOOnlyBTree(true/* bloomFilter */);

        }

        // scan for the next distinct ISPO from the src iterator.
        return _hasNext();

    }

    /**
     * Scan for the next distinct {@link ISPO} from the src iterator and set it
     * on {@link #next}.
     * 
     * @return <code>true</code> if another distinct {@link ISPO} was found.
     */
    private boolean _hasNext() {

        while (next == null && src.hasNext()) {

            /*
             * Read another ISPO from the iterator and strip off the context
             * position.
             * 
             * Note: distinct is enforced on (s,p,o). By stripping off the
             * context and statement type information first, we ensure that
             * (s,p,o) duplicates will be recognized as such.
             * 
             * Note: this approach requires us to discard the statement type
             * metadata.
             */

            // read next from the source iterator.
            ISPO tmp = src.next(); nscanned++;

            // strip off the context (and statement type).
            //tmp = new SPO(tmp.s(), tmp.p(), tmp.o(), (IV) null/* c */);

            if (btreeSet == null) {
                // Insert into the hash set.
                if (hashSet == null&&first!=null) {
                    hashSet = new LinkedHashSet<ISPO>();
                    hashSet.add(first);
                } 
                if (hashSet!=null&&!hashSet.add(tmp)) {

                    // duplicate, keep scanning.
                    continue;

                }

            } else {

                // First, test the hash set.
                if (hashSet.contains(tmp)) {

                    // duplicate, keep scanning.
                    continue;

                }

                // Next, test the B+Tree.
                final byte[] key = SPOKeyOrder.SPO.encodeKey(keyBuilder, tmp);

                if (btreeSet.contains(key)) {

                    // duplicate, keep scanning.
                    continue;

                }

                // Finally, insert into the B+Tree.
                btreeSet.insert(key, null);

            }

            // found a new distinct spo.
            next = tmp;
            if(first==null){
                first=next;
            }
            ndistinct++;

        } // while(...)

        if (next == null) {

            exhausted = true;

            return false;
            
        }

        return true;

    }

    public ISPO next() {

        if (!hasNext())
            throw new NoSuchElementException();

        assert next != null;

        final ISPO tmp = next;

        next = null;

        return tmp;

    }

    public void remove() {

        throw new UnsupportedOperationException();

    }

}
