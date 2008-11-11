/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package com.bigdata.btree;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;

import org.CognitiveWeb.extser.LongPacker;

import com.bigdata.btree.compression.IDataSerializer;
import com.bigdata.btree.compression.PrefixSerializer;
import com.bigdata.btree.keys.IKeyBuilder;
import com.bigdata.btree.keys.IKeyBuilderFactory;
import com.bigdata.io.SerializerUtil;
import com.bigdata.isolation.IConflictResolver;
import com.bigdata.mdi.LocalPartitionMetadata;
import com.bigdata.rawstore.Bytes;
import com.bigdata.rawstore.IRawStore;
import com.bigdata.resources.DefaultSplitHandler;
import com.bigdata.sparse.SparseRowStore;

/**
 * <p>
 * The persistent and mostly immutable metadata for a {@link AbstractBTree}.
 * This class allows you to configured several very important aspects of the
 * B+Tree behavior. Read on.
 * </p>
 * <p>
 * An instance of this class is required in order to create a {@link BTree} or
 * {@link IndexSegment}. Further, when registering a scale-out index you will
 * first create an instance of this class that will serve as the metadata
 * <i>template</i> for all index resources which are part of that scale-out
 * index.
 * </p>
 * <h2>Delete markers, version timestamps, and isolatable indices</h2>
 * <p>
 * By default a {@link BTree} does not maintain delete markers and a request to
 * delete an entry under a key will cause that entry to be removed from the live
 * version of the index. However, such indices do not support "overflow" (they
 * can not be evicted onto read-only {@link IndexSegment}s) and as such they do
 * not support scale-out).
 * </p>
 * <p>
 * The {@link SparseRowStore} handles a "delete" of a property value by writing
 * a <code>null</code> value under a key and does NOT require the use of index
 * entry delete markers, even in scale-out deployments. A compacting merge of a
 * {@link SparseRowStore} applies a history policy based on a consideration of
 * the timestamped property values, including values bound to a
 * <code>null</code>.
 * </p>
 * <p>
 * Delete markers combined with an ordered set of index resources is sufficient
 * to support all features of range-partitioned indices, including compacting
 * merge. Given three index resources {A,B,C} for a single index partition, the
 * order over the resources gives us the guarentee that any index entry in A
 * will be more recent than any index enty in B or C. So when reading a fused
 * view we always stop once we have an index entry for a key, even if that entry
 * has the deleted flag set.
 * </p>
 * <p>
 * Delete markers occupy very little space in the leaf data structure (one bit
 * each), however when they are used a deleted index entry is NOT removed from
 * the index. Instead, the key remains in the leaf paired to a delete bit and a
 * <code>null</code> value (or simply elided). These "deleted" entries can
 * only be removed from the index by a compacting merge. When transactional
 * isolation is used, the criteria for removing deleted entries are stronger -
 * they must no longer be visible to any active or allowable transaction as
 * determined by the transaction manager, see below for more on this.
 * </p>
 * <p>
 * Transaction isolation requires delete markers <i>plus</i> version
 * timestamps. The version timestamps in the unisolated index (the live index)
 * give the timestamp of the commit during which the index entry was last
 * updated. The timestamp in the write set of transaction is copy from the index
 * view corresponding to the ground state of the transaction the first time that
 * index entry is overwritten within that transaction (there is a special case
 * when the index entry was not pre-existing - we assign the start time of the
 * transaction in that case so when we validate we are only concerned that the
 * entry is either not found (never written) or that the entry exists with the
 * same timestamp - other conditions are write-write conflicts). On commit we
 * write the commit time on the updated index entries in the unisolated index.
 * </p>
 * <h2>History policies and timestamps</h2>
 * <p>
 * There are in fact two kinds of timestamps in use - isolatable indices place a
 * timestamp on the index entry itself while the {@link SparseRowStore} places a
 * timestamp in the <i>key</i>. One consequence of this is that it is very
 * efficient to read historical data from the {@link SparseRowStore} since the
 * data are right there in key order. On the other hand, reading historical data
 * from an isolatable index requires reading from each historical commit state
 * of that index which is not interest (this is NOT efficient). This is why the
 * {@link SparseRowStore} design places timestamps in the key - so that the
 * application can efficiently read both the current and historical property
 * values within a logical row.
 * </p>
 * <p>
 * Regardless of whether the timestamp is on the index entry (as it always is
 * for isolatable indices) or in the key ({@link SparseRowStore}), the
 * existence of timestamps makes it possible for an application to specify a
 * history policy governing when property values will be deleted.
 * </p>
 * <p>
 * When an index participates in transactions the transaction manager manages
 * the life cycle of overwritten and deleted index entries (and the resources on
 * which the indices exist). This is done by preserving such data until no
 * transaction exists that can read from those resources. Unless an immortal
 * store is desired, the "purge time" is set at a time no more recent than the
 * earliest fully isolated transaction (either a read-only tx as of the start
 * time of the tx or a read-write tx as of its start time). The role of a
 * "history policy" with transactions is therefore how much history to buffer
 * between the earliest running tx and the choosen "purge time". When the
 * transaction manager updates the "purge time" it notifies the journal/data
 * services. Resources having no data later than the purge time may be deleted
 * and SHOULD NOT be carried forward when building new index segments.
 * </p>
 * <p>
 * History policies for non-transactional indices are somewhat different. An
 * scale-out index without timestamps will buffer historical data only until the
 * next compacting merge of a given index partition. The compacting merge uses
 * the fused view of the resources comprising the index partition and only
 * writes out the undeleted index entries.
 * </p>
 * <p>
 * If an application instead chooses to use timestamps in a non-transactional
 * index then (a) timestamps must be assigned by either the client or the data
 * service; and (b) applications can specify a history policy where data older
 * than a threshold time (but not #of versions) will be eradicated. This
 * approach is possible, but not well-supported in the higher level APIs.
 * </p>
 * <p>
 * The {@link SparseRowStore} design is more flexible since it allows (a) fast
 * access to historical property values for the same "row"; and (b) history
 * policies that may be specified in terms of the #of versions, the age of a
 * datum, and that can keep at least N versions of a datum. This last point is
 * quite important as it allows you to retain the entirety of the most current
 * revision of the logical row, even when some datums are much older than
 * others.
 * </p>
 * 
 * <h2>Serialization</h2>
 * 
 * <p>
 * Note: Derived classes SHOULD extend the {@link Externalizable} interface and
 * explicitly manage serialization versions so that their metadata may evolve in
 * a backward compatible manner.
 * </p>
 * 
 * @todo Consider just having a property set since there are so many possible
 *       properties.
 * 
 * @todo Write (de-)serialization tests for both kinds of metadata including
 *       with the optional metadata (pmd, etc).
 * 
 * @todo Make sure that metadata for index partition "zones" propagates with the
 *       partition metadata so that appropriate policies are enforcable locally
 *       (access control, latency requirements, replication, purging of
 *       historical deleted versions, etc).
 * 
 * @todo add optional property containing IndexMetadata to be used as of the
 *       next overflow so that people can swap out key and value serializers and
 *       the like during overflow operations. the conops is that you map the new
 *       metadata over the index partitions and either lazily or eagerly
 *       overflow is triggered and the resulting {@link BTree} and
 *       {@link IndexSegment} objects will begin to use the new key / value
 *       serializers, etc. while the existing objects will still have their old
 *       key/val serializers and therefore can still be read.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class IndexMetadata implements Serializable, Externalizable, Cloneable, IKeyBuilderFactory {

    private static final long serialVersionUID = 4370669592664382720L;

    /**
     * Address that can be used to read this metadata record from the store.
     * <p>
     * Note: This is not persisted since we do not have the address until after
     * we have written out the state of this record. However the value is
     * written into each {@link Checkpoint} record.
     */
    private transient /*final*/ long addrMetadata;

    /**
     * Address that can be used to read this metadata record from the store.
     * <p>
     * Note: This is not a persistent property. However the value is set when
     * the metadata record is read from, or written on, the store. It is zero
     * when you {@link #clone()} a metadata record until it's been written onto
     * the store.
     */
    final public long getMetadataAddr() {
        
        return addrMetadata;
        
    }

    /*
     * @todo consider allowing distinct values for the branching factor (already
     * done), the class name, and possibly some other properties (record
     * compression, checksum) for the index segments vs the mutable btrees.
     */

    private UUID indexUUID;
    private String name;
    private int branchingFactor;
    private int indexSegmentBranchingFactor;
    private LocalPartitionMetadata pmd;
    private String className;
    private String checkpointClassName;
    private IAddressSerializer addrSer;
    private IDataSerializer nodeKeySer;
    private ITupleSerializer tupleSer;
    private IConflictResolver conflictResolver;
    private boolean deleteMarkers;
    private boolean versionTimestamps;
//    private double errorRate;
    private BloomFilterFactory bloomFilterFactory;
    private IOverflowHandler overflowHandler;
    private ISplitHandler splitHandler;
//    private Object historyPolicy;

    /**
     * The unique identifier for the index whose data is stored in this B+Tree
     * data structure. When using a scale-out index the same <i>indexUUID</i>
     * MUST be assigned to each mutable and immutable B+Tree having data for any
     * partition of that scale-out index. This makes it possible to work
     * backwards from the B+Tree data structures and identify the index to which
     * they belong.
     */
    public final UUID getIndexUUID() {return indexUUID;}

    /**
     * The name associated with the index -or- <code>null</code> iff the index
     * is not named (internal indices are generally not named while application
     * indices are always named).
     * <p>
     * Note: When the index is a scale-out index, this is the name of the
     * scale-out index NOT the name under which an index partition is
     * registered.
     * <p>
     * Note: When the index is a metadata index, then this is the name of the
     * metadata index itself NOT the name of the managed scale-out index.
     */
    public final String getName() {return name;}
    
    /**
     * The branching factor for a mutable {@link BTree}. The branching factor
     * is the #of children in a node or values in a leaf and must be an integer
     * greater than or equal to three (3). Larger branching factors result in
     * trees with fewer levels. However there is a point of diminishing returns
     * at which the amount of copying performed to move the data around in the
     * nodes and leaves exceeds the performance gain from having fewer levels.
     * The branching factor for the read-only {@link IndexSegment}s is
     * generally much larger in order to reduce the number of disk seeks.
     */
    public final int getBranchingFactor() {return branchingFactor;}
    
    /**
     * The branching factor used when building an {@link IndexSegment} (default
     * is 4096). Index segments are read-only B+Tree resources. The are built
     * using a bulk index build procedure and typically have a much higher
     * branching factor than the corresponding mutable index on the journal.
     * <p>
     * Note: the value of this property will determine the branching factor of
     * the {@link IndexSegment}. When the {@link IndexSegment} is built, it
     * will be given a {@link #clone()} of this {@link IndexMetadata} and the
     * actual branching factor for the {@link IndexSegment} be set on the
     * {@link #getBranchingFactor()} at that time.
     * <p>
     * Note: a branching factor of 256 for an index segment and split limits of
     * (1M,5M) imply an average B+Tree height of 1.5 to 1.8. With a 10ms seek
     * time and NO CACHE that is between 15 and 18ms average seek time.
     * <p>
     * Note: a branching factor of 512 for an index segment and split limits of
     * (1M,5M) imply an average B+Tree height of 1.2 to 1.5. With a 10ms seek
     * time and NO CACHE that is between 12 and 15ms average seek time.
     * <p>
     * Note: the actual size of the index segment of course depends heavily on
     * (a) whether or now block references are being stored since the referenced
     * blocks are also stored in the index segment; (b) the size of the keys and
     * values stored in the index; and (c) the key, value, and record
     * compression options in use.
     */
    public final int getIndexSegmentBranchingFactor() {return indexSegmentBranchingFactor;}
    
    /**
     * When non-<code>null</code>, this is the description of the view of
     * this index partition. This will be <code>null</code> iff the
     * {@link BTree} is not part of a scale-out index. This is updated when the
     * view composition for the index partition is changed.
     */
    public final LocalPartitionMetadata getPartitionMetadata() {return pmd;}
    
    /**
     * The name of a class derived from {@link BTree} that will be used to
     * re-load the index.
     * 
     * @todo allow class name for the index segment as well or encourage a view
     *       of the object rather than subclassing for specialized behaviors
     *       e.g., using a delegate model for {@link IIndex}? Note that index
     *       partitions are in general views (of one or more resources).
     *       Therefore only unpartitioned indices can be meaningfully
     *       specialized in terms of the {@link BTree} base class.
     */
    public final String getClassName() {return className;}

    /**
     * The name of the {@link Checkpoint} class used by the index. This may be
     * overriden to store additional state with each {@link Checkpoint} record.
     */
    public final String getCheckpointClassName() {return checkpointClassName;}

    public final void setCheckpointClassName(String className) {
        
        if (className == null)
            throw new IllegalArgumentException();

        this.checkpointClassName = className;
        
    }

    /**
     * Object used to (de-)serialize the addresses of the children of a node.
     */
    public final IAddressSerializer getAddressSerializer() {return addrSer;}
    
    /**
     * Object used to (de-)serialize/(de-)compress the keys in a node.
     * <p>
     * Note: The keys for nodes are separator keys for the leaves. Since they
     * are choosen to be the minimum length separator keys dynamically when a
     * leaf is split or joined the keys in the node typically DO NOT conform to
     * application expectations and are normally assigned a different serializer
     * for that reason.
     * <p>
     * Note: This handles the "serialization" of the <code>byte[][]</code>
     * containing all of the keys for some node of the index. As such it may be
     * used to provide compression across the already serialized node for the
     * leaf.
     * 
     * @see #getTupleSerializer()
     */
    public final IDataSerializer getNodeKeySerializer() {return nodeKeySer;}
    
    /**
     * The object used to form unsigned byte[] keys from Java objects, to
     * (de-)serialize Java object stored in the index, and to (de-)compress the
     * keys and values when stored in a leaf or {@link ResultSet}.
     * <p>
     * Note: If you change this value in a manner that is not backward
     * compatable once entries have been written on the index then you may be
     * unable to any read data already written.
     */
    public final ITupleSerializer getTupleSerializer() {return tupleSer;}
    
    /**
     * The optional object for handling write-write conflicts.
     * <p>
     * The concurrency control strategy detects write-write conflict resolution
     * during backward validation. If a write-write conflict is detected and a
     * conflict resolver is defined, then the conflict resolver is expected to
     * make a best attempt using data type specific rules to reconcile the state
     * for two versions of the same persistent identifier. If the conflict can
     * not be resolved, then validation will fail. State-based conflict
     * resolution when combined with validation (aka optimistic locking) is
     * capable of validating the greatest number of interleavings of
     * transactions (aka serialization orders).
     * 
     * @return The conflict resolver to be applied during validation or
     *         <code>null</code> iff no conflict resolution will be performed.
     */
    public final IConflictResolver getConflictResolver() {return conflictResolver;}
    
    /**
     * When <code>true</code> the index will write a delete marker when an
     * attempt is made to delete the entry under a key. Delete markers will be
     * retained until a compacting merge of an index partition. When
     * <code>false</code> the index entry will be removed from the index
     * immediately.
     * <p>
     * Delete markers MUST be enabled to use scale-out indices.
     */
    public final boolean getDeleteMarkers() {return deleteMarkers;}
    
    public final void setDeleteMarkers(boolean deleteMarkers) {

        this.deleteMarkers = deleteMarkers;
        
    }

    /**
     * When <code>true</code> the index will maintain a per-index entry
     * timestamp. The primary use of this is in support of transactional
     * isolation.
     */
    public final boolean getVersionTimestamps() {return versionTimestamps;}
    
    public final void setVersionTimestamps(boolean versionTimestamps) {
        
        this.versionTimestamps = versionTimestamps;
        
    }

    /**
     * True iff the index supports transactional isolation (both delete markers
     * and version timestamps are required).
     */
    public final boolean isIsolatable() {
        
        return deleteMarkers && versionTimestamps;
        
    }

    public void setIsolatable(boolean isolatable) {

        setDeleteMarkers(isolatable);
        
        setVersionTimestamps(isolatable);
        
    }

    public void setPartitionMetadata(LocalPartitionMetadata pmd) {
        
        this.pmd = pmd;
        
    }

    public void setAddressSerializer(IAddressSerializer addrSer) {
        
        if (addrSer == null)
            throw new IllegalArgumentException();
        
        this.addrSer = addrSer;
        
    }

    public void setNodeKeySerializer(IDataSerializer nodeKeySer) {
        
        if (nodeKeySer == null)
            throw new IllegalArgumentException();
        
        this.nodeKeySer = nodeKeySer;
        
    }

    public void setTupleSerializer(ITupleSerializer tupleSer) {

        if (tupleSer == null)
            throw new IllegalArgumentException();

        this.tupleSer = tupleSer;
        
    }

    /**
     * The branching factor MAY NOT be changed once an {@link AbstractBTree}
     * object has been created.
     * 
     * @param branchingFactor
     */
    public void setBranchingFactor(int branchingFactor) {
        
        if(branchingFactor < BTree.MIN_BRANCHING_FACTOR) {
            
            throw new IllegalArgumentException();
            
        }
        
        this.branchingFactor = branchingFactor;
        
    }

    public void setIndexSegmentBranchingFactor(int branchingFactor) {

        if(branchingFactor < BTree.MIN_BRANCHING_FACTOR) {
            
            throw new IllegalArgumentException();
            
        }
        
        this.indexSegmentBranchingFactor = branchingFactor;
        
    }

    public void setClassName(String className) {

        if (className == null)
            throw new IllegalArgumentException();

        this.className = className;
        
    }

    public void setConflictResolver(IConflictResolver conflictResolver) {

        this.conflictResolver = conflictResolver;
        
    }

//    /**
//     * A value in [0:1] that is interpreted as an allowable false positive error
//     * rate for an optional bloom filter. When zero (0.0), the bloom filter is
//     * not constructed. The bloom filter provides efficient fast rejection of
//     * keys that are not in the index. If the bloom filter reports that a key is
//     * in the index then the index MUST be tested to verify that the result is
//     * not a false positive. Bloom filters are great if you have a lot of point
//     * tests to perform but they are not used if you are doing range scans.
//     * <p>
//     * Maintaing a bloom filter is fairly expensive and this option should only
//     * be enabled if you know that point access tests are a hotspot for an
//     * index.
//     * 
//     * @todo consider changing this to a boolean flag and then computing a
//     *       suitable capacity for the bloom filter based on a reasonable
//     *       expectation of the #of index entries that might be observed.
//     */
//    public double getErrorRate() {
//        
//        return errorRate;
//        
//    }
//    
//    public void setErrorRate(final double errorRate) {
//
//        if (errorRate < 0d || errorRate > 1d)
//            throw new IllegalArgumentException();
//        
//        this.errorRate = errorRate;
//        
//    }

    /**
     * Return the bloom filter factory.
     * <p>
     * Bloom filters provide fast rejection for point tests in a space efficient
     * manner with a configurable probability of a false positive. Since the
     * bloom filter does not give positive results with 100% certainity, the
     * index is tested iff the bloom filter states that the key exists.
     * <p>
     * Note: Bloom filters are NOT enabled by default since point tests are not
     * a bottleneck (or even used) for some indices. Also, when multiple indices
     * represent different access paths for the same information, you only need
     * a bloom filter on one of those indices.
     * 
     * @return Return the object that will be used to configure an optional
     *         bloom filter for a {@link BTree} or {@link IndexSegment}. When
     *         <code>null</code> the index WILL NOT use a bloom filter.
     * 
     * @see BloomFilterFactory
     * @see BloomFilterFactory#DEFAULT
     */
    public BloomFilterFactory getBloomFilterFactory() {
        
        return bloomFilterFactory;
        
    }
    
    /**
     * Set the bloom filter factory.
     * <p>
     * Bloom filters provide fast rejection for point tests in a space efficient
     * manner with a configurable probability of a false positive. Since the
     * bloom filter does not give positive results with 100% certainity, the
     * index is tested iff the bloom filter states that the key exists.
     * 
     * @param bloomFilterFactory
     *            The new value (may be null).
     * 
     * @see BloomFilterFactory#DEFAULT
     */
    public void setBloomFilterFactory(BloomFilterFactory bloomFilterFactory) {
        
        this.bloomFilterFactory = bloomFilterFactory;
        
    }
    
    /**
     * An optional object that may be used to inspect, and possibly operate on,
     * each index entry as it is copied into an {@link IndexSegment}.
     */
    public IOverflowHandler getOverflowHandler() {
        
        return overflowHandler;
        
    }

    public void setOverflowHandler(IOverflowHandler overflowHandler) {
        
        this.overflowHandler = overflowHandler;
        
    }

    /**
     * Object which decides whether and where to split an index partition into 2
     * or more index partitions. The default is a {@link DefaultSplitHandler}
     * using some reasonable values for its properties. You can override this
     * property in order to tune or constrain the manner in which index split
     * points are choosen.
     * 
     * @return The {@link ISplitHandler}. If <code>null</code> then index
     *         splits will be disabled.
     */
    public ISplitHandler getSplitHandler() {

        return splitHandler;
        
    }
    
    public void setSplitHandler(ISplitHandler splitHandler) {
        
        this.splitHandler = splitHandler;
        
    }
    
    /**
     * De-serialization constructor.
     */
    public IndexMetadata() {
        
    }
    
    /**
     * Constructor used to configure a new <em>unnamed</em> B+Tree. The index
     * UUID is set to the given value and all other fields are defaulted. Those
     * defaults may be overriden using the various setter methods.
     * 
     * @param indexUUID
     *            The indexUUID.
     * 
     * @throws IllegalArgumentException
     *             if the indexUUID is <code>null</code>.
     */
    public IndexMetadata(final UUID indexUUID) {

        this(null, indexUUID);
        
    }
        
    /**
     * Constructor used to configure a new <em>named</em> B+Tree. The index
     * UUID is set to the given value and all other fields are defaulted. Those
     * defaults may be overriden using the various setter methods.
     * 
     * @param name
     *            The index name. When this is a scale-out index, the same
     *            <i>name</i> is specified for each index resource. However
     *            they will be registered on the journal under different names
     *            depending on the index partition to which they belong.
     * 
     * @param indexUUID
     *            The indexUUID. The same index UUID MUST be used for all
     *            component indices in a scale-out index.
     * 
     * @throws IllegalArgumentException
     *             if the indexUUID is <code>null</code>.
     */
    public IndexMetadata(final String name, final UUID indexUUID) {

        if (indexUUID == null)
            throw new IllegalArgumentException();
        
        this.name = name;
        
        this.indexUUID = indexUUID;
        
        this.branchingFactor = BTree.DEFAULT_BRANCHING_FACTOR;

        this.indexSegmentBranchingFactor = 512;

        // Note: default assumes NOT an index partition.
        this.pmd = null;
        
        this.className = BTree.class.getName();
        
        this.checkpointClassName = Checkpoint.class.getName();
        
        /*
         * FIXME Experiment with performance using a packed address serializer
         * (or a nibble-based one). This effects how we serialize the child
         * addresses for a Node. (PackedAddressSerialized appears to be broken
         * - see the NodeSerialier test suite).
         */
        this.addrSer = AddressSerializer.INSTANCE;
        
//        this.nodeKeySer = SimplePrefixSerializer.INSTANCE;
        this.nodeKeySer = PrefixSerializer.INSTANCE;
        
        this.tupleSer = DefaultTupleSerializer.newInstance();

        this.conflictResolver = null;
        
        this.deleteMarkers = false;
        
        this.versionTimestamps = false;
        
//        this.errorRate = 0.0;
        this.bloomFilterFactory = null;
  
        this.overflowHandler = null;
        
        /*
         * Note: Use these settings to trigger splits sooner and thus enter the
         * more interesting regions of the phase space more quickly BUT DO NOT
         * use these settings for deployment!
         */
//        final int minimumEntryCount = 1 * Bytes.kilobyte32;
//        final int entryCountPerSplit = 5 * Bytes.megabyte32;
        
        /*
         * Use these settings as the default for release/deployment.
         */
        final int minimumEntryCount = 500 * Bytes.kilobyte32;
        final int entryCountPerSplit = 1 * Bytes.megabyte32;

        this.splitHandler = new DefaultSplitHandler(
                minimumEntryCount,
                entryCountPerSplit,
                1.5, // overCapacityMultiplier
                .75, // underCapacityMultiplier
                20   // sampleRate
                );

    }

    /**
     * Write out the metadata record for the btree on the store and return the
     * address.
     * 
     * @param store
     *            The store on which the metadata record is being written.
     * 
     * @return The address of the metadata record is set on this object as a
     *         side effect.
     * 
     * @throws IllegalStateException
     *             if the record has already been written on the store.
     * @throws IllegalStateException
     *             if the {@link #indexUUID} field is <code>null</code> - this
     *             generally indicates that you used the de-serialization
     *             constructor rather than one of the constructor variants that
     *             accept the required UUID parameter.
     */
    public void write(final IRawStore store) {

        if (addrMetadata != 0L) {

            throw new IllegalStateException("Already written.");
            
        }

        if (indexUUID == null) {
            
            throw new IllegalStateException("No indexUUID : wrong constructor?");
            
        }

        // write on the store, setting address as side-effect.
        this.addrMetadata = store.write(ByteBuffer.wrap(SerializerUtil
                .serialize(this)));

    }

    /**
     * Read the metadata record from the store.
     * 
     * @param store
     *            the store.
     * @param addr
     *            the address of the metadata record.
     * 
     * @return the metadata record. The address from which it was loaded is set
     *         on the metadata record as a side-effect.
     */
    public static IndexMetadata read(final IRawStore store, final long addr) {
        
        final IndexMetadata metadata = (IndexMetadata) SerializerUtil
                .deserialize(store.read(addr));
        
        // save the address from which the metadata record was loaded.
        metadata.addrMetadata = addr;
        
        return metadata;
        
    }

    /**
     * A human readable representation of the metadata record.
     */
    public String toString() {
        
        StringBuilder sb = new StringBuilder();

        // transient
        sb.append("addrMetadata=" + addrMetadata);

        // persistent
        sb.append(", name=" + (name == null ? "N/A" : name));
        sb.append(", indexUUID=" + indexUUID);
        sb.append(", branchingFactor=" + branchingFactor);
        sb.append(", indexSegmentBranchingFactor=" + indexSegmentBranchingFactor);
        sb.append(", pmd=" + pmd);
        sb.append(", class=" + className);
        sb.append(", checkpointClass=" + checkpointClassName);
        sb.append(", childAddrSerializer=" + addrSer.getClass().getName());
        sb.append(", nodeKeySerializer=" + nodeKeySer.getClass().getName());
        sb.append(", tupleSerializer=" + tupleSer.getClass().getName());
        sb.append(", conflictResolver="
                + (conflictResolver == null ? "N/A" : conflictResolver
                        .getClass().getName()));
        sb.append(", deleteMarkers=" + deleteMarkers);
        sb.append(", versionTimestamps=" + versionTimestamps);
        sb.append(", isolatable=" + isIsolatable());
        sb.append(", bloomFilterFactory=" + (bloomFilterFactory == null ? "N/A"
                : bloomFilterFactory.toString())); 
        sb.append(", overflowHandler="
                + (overflowHandler == null ? "N/A" : overflowHandler.getClass()
                        .getName()));
        sb.append(", splitHandler="
                + (splitHandler == null ? "N/A" : splitHandler.getClass()
                        .getName()));

        return sb.toString();
        
    }

    /**
     * The initial version.
     */
    private static transient final int VERSION0 = 0x0;
    
    /**
     * This version drops the errorRate field and replaces it with a serialized
     * {@link BloomFilterFactory}. When reading an older version it creates a
     * {@link BloomFilterFactory} instance using the de-serialized error rate
     * and an expected index entry count of 1M.
     */
    private static transient final int VERSION1 = 0x1;

    /**
     * @todo review generated record for compactness.
     */
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {

        final int version = (int) LongPacker.unpackLong(in);

        if (version != VERSION0 && version != VERSION1) {

            throw new IOException("Unknown version: version=" + version);

        }

        // immutable

        final boolean hasName = in.readBoolean();

        if (hasName) {

            name = in.readUTF();

        }
        
        indexUUID = new UUID(in.readLong()/*MSB*/,in.readLong()/*LSB*/);

        branchingFactor = (int)LongPacker.unpackLong(in);

        indexSegmentBranchingFactor = (int)LongPacker.unpackLong(in);
        
        pmd = (LocalPartitionMetadata)in.readObject();
        
        className = in.readUTF();
        
        checkpointClassName = in.readUTF();
        
        addrSer = (IAddressSerializer)in.readObject();
        
        nodeKeySer = (IDataSerializer)in.readObject();

        tupleSer = (ITupleSerializer)in.readObject();
        
        conflictResolver = (IConflictResolver)in.readObject();
        
        deleteMarkers = in.readBoolean();
        
        versionTimestamps = in.readBoolean();

        if (version == VERSION0) {
            
            final double errorRate = in.readDouble();
            
            if (errorRate != 0d) {
            
                final int n = 1000000; // 1M
                
                bloomFilterFactory = new BloomFilterFactory(n, errorRate,
                        errorRate * 10);
                
            }
            
        } else {

            bloomFilterFactory = (BloomFilterFactory) in.readObject();
            
        }

        overflowHandler = (IOverflowHandler)in.readObject();

        splitHandler = (ISplitHandler)in.readObject();

    }

    public void writeExternal(ObjectOutput out) throws IOException {
        
        LongPacker.packLong(out, VERSION1);

        // immutable
        
        // hasName?
        out.writeBoolean(name != null ? true : false);
        
        // the name
        if (name != null) {

            out.writeUTF(name);
            
        }
        
        out.writeLong(indexUUID.getMostSignificantBits());
        
        out.writeLong(indexUUID.getLeastSignificantBits());
        
        LongPacker.packLong(out, branchingFactor);

        LongPacker.packLong(out, indexSegmentBranchingFactor);

        out.writeObject(pmd);
        
        out.writeUTF(className);

        out.writeUTF(checkpointClassName);
        
        out.writeObject(addrSer);
        
        out.writeObject(nodeKeySer);

        out.writeObject(tupleSer);
        
        out.writeObject(conflictResolver);

        out.writeBoolean(deleteMarkers);
        
        out.writeBoolean(versionTimestamps);

        // Note: VERSION0
        // out.writeDouble(errorRate);
        // Note: VERSION1 (replaces the errorRate)
        out.writeObject(bloomFilterFactory);
        
        out.writeObject(overflowHandler);

        out.writeObject(splitHandler);

    }

    /**
     * Makes a copy of the persistent data, clearing the 
     */
    public IndexMetadata clone() {
        
        try {

            final IndexMetadata copy = (IndexMetadata) super.clone();
            
            copy.addrMetadata = 0L;
            
            return copy;
            
        } catch (CloneNotSupportedException e) {
            
            throw new RuntimeException(e);
            
        }
        
    }

    /**
     * Create an initial {@link Checkpoint} for a new {@link BTree} described by
     * this metadata record.
     * <p>
     * The caller is responsible for writing the {@link Checkpoint} record onto
     * the store.
     * <p>
     * The class identified by {@link #getCheckpointClassName()} MUST declare a
     * public constructor with the following method signature
     * 
     * <pre>
     *  ...( IndexMetadata metadata )
     * </pre>
     * 
     * @return The {@link Checkpoint}.
     */
    final public Checkpoint firstCheckpoint() {

        try {
            
            Class cl = Class.forName(getCheckpointClassName());
            
            /*
             * Note: A NoSuchMethodException thrown here means that you did not
             * declare the required public constructor.
             */
            
            Constructor ctor = cl.getConstructor(new Class[] {
                    IndexMetadata.class//
                    });

            Checkpoint checkpoint = (Checkpoint) ctor.newInstance(new Object[] { //
                    this //
                    });
            
            return checkpoint;
            
        } catch(Exception ex) {
            
            throw new RuntimeException(ex);
            
        }

    }
    
    /**
     * Variant used when an index overflows onto a new backing store.
     * <p>
     * The caller is responsible for writing the {@link Checkpoint} record onto
     * the store.
     * <p>
     * The class identified by {@link #getCheckpointClassName()} MUST declare a
     * public constructor with the following method signature
     * 
     * <pre>
     *  ...( IndexMetadata metadata, Checkpoint oldCheckpoint )
     * </pre>
     * 
     * @param oldCheckpoint
     *            The last checkpoint for the index of the old backing store.
     * 
     * @return The first {@link Checkpoint} for the index on the new backing
     *         store.
     * 
     * @throws IllegalArgumentException
     *             if the oldCheckpoint is <code>null</code>.
     */
    final public Checkpoint overflowCheckpoint(Checkpoint oldCheckpoint) {
       
        if (oldCheckpoint == null) {
         
            throw new IllegalArgumentException();
            
        }
        
        try {
            
            Class cl = Class.forName(getCheckpointClassName());
            
            /*
             * Note: A NoSuchMethodException thrown here means that you did not
             * declare the required public constructor.
             */
            
            Constructor ctor = cl.getConstructor(new Class[] {
                    IndexMetadata.class, //
                    Checkpoint.class//
                    });

            Checkpoint checkpoint = (Checkpoint) ctor.newInstance(new Object[] { //
                    this, //
                    oldCheckpoint//
                    });
            
            // sanity check makes sure the counter is propagated to the new store.
            assert checkpoint.getCounter() == oldCheckpoint.getCounter();
            
            return checkpoint;
            
        } catch(Exception ex) {
            
            throw new RuntimeException(ex);
            
        }
        
    }
    
    /**
     * Create a {@link Checkpoint} for a {@link BTree}.
     * <p>
     * The caller is responsible for writing the {@link Checkpoint} record onto
     * the store.
     * <p>
     * The class identified by {@link #getCheckpointClassName()} MUST declare a
     * public constructor with the following method signature
     * 
     * <pre>
     *   ...( BTree btree )
     * </pre>
     * 
     * @param btree
     *            The {@link BTree}.
     * 
     * @return The {@link Checkpoint}.
     */
    final public Checkpoint newCheckpoint(BTree btree) {
        
        try {
            
            Class cl = Class.forName(getCheckpointClassName());
            
            /*
             * Note: A NoSuchMethodException thrown here means that you did not
             * declare the required public constructor.
             */
            
            Constructor ctor = cl.getConstructor(new Class[] {
                    BTree.class //
                    });

            Checkpoint checkpoint = (Checkpoint) ctor.newInstance(new Object[] { //
                    btree //
                    });
            
            return checkpoint;
            
        } catch(Exception ex) {
            
            throw new RuntimeException(ex);
            
        }
        
    }

    /**
     * <p>
     * Factory for thread-safe {@link IKeyBuilder} objects for use by
     * {@link ITupleSerializer#serializeKey(Object)} and possibly others.
     * </p>
     * <p>
     * Note: A mutable B+Tree is always single-threaded. However, read-only
     * B+Trees allow concurrent readers. Therefore, thread-safety requirement is
     * <em>safe for either a single writers -or- for concurrent readers</em>.
     * </p>
     * <p>
     * Note: If you change this value in a manner that is not backward
     * compatable once entries have been written on the index then you may be
     * unable to any read data already written.
     * </p>
     * <p>
     * Note: This method delegates to {@link ITupleSerializer#getKeyBuilder()}.
     * This {@link IKeyBuilder} SHOULD be used to form all keys for <i>this</i>
     * index. This is critical for indices that have Unicode data in their
     * application keys as the formation of Unicode sort keys from Unicode data
     * depends on the {@link IKeyBuilderFactory}. If you use a locally
     * configured {@link IKeyBuilder} then your Unicode keys will be encoded
     * based on the {@link Locale} configured for the JVM NOT the factory
     * specified for <i>this</i> index.
     * </p>
     */
    public IKeyBuilder getKeyBuilder() {

        return getTupleSerializer().getKeyBuilder();
        
    }
        
}
