/**

The Notice below must appear in each file of the Source Code of any
copy you distribute of the Licensed Product.  Contributors to any
Modifications may add their own copyright notices to identify their
own contributions.

License:

The contents of this file are subject to the CognitiveWeb Open Source
License Version 1.1 (the License).  You may not copy or use this file,
in either source code or executable form, except in compliance with
the License.  You may obtain a copy of the License from

  http://www.CognitiveWeb.org/legal/license/

Software distributed under the License is distributed on an AS IS
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.  See
the License for the specific language governing rights and limitations
under the License.

Copyrights:

Portions created by or assigned to CognitiveWeb are Copyright
(c) 2003-2003 CognitiveWeb.  All Rights Reserved.  Contact
information for CognitiveWeb is available at

  http://www.CognitiveWeb.org

Portions Copyright (c) 2002-2003 Bryan Thompson.

Acknowledgements:

Special thanks to the developers of the Jabber Open Source License 1.0
(JOSL), from which this License was derived.  This License contains
terms that differ from JOSL.

Special thanks to the CognitiveWeb Open Source Contributors for their
suggestions and support of the Cognitive Web.

Modifications:

*/
/*
 * Created on Oct 16, 2006
 */

package com.bigdata.journal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


/**
 * This is a prototype implementation in order to proof out the concept for
 * transactional isolation. This implementation NOT persistence capable.
 * 
 * FIXME Write lots of tests for this interface as well as for transactional
 * isolation at the {@link Journal} and {@link Tx} API level. We will reuse
 * those for the persistence capable object index implementation.
 * 
 * @todo Write a persistence capable version that is efficient for all the
 *       things that we actually use the object index for. We need benchmarks
 *       that drive all of those activities (including migration to the
 *       read-optimized database and deletion of versions that are no longer
 *       accessible) in order to drive the algorithm, implementation, and
 *       performance tuning.<br>
 *       Some of the complex points are handling transaction isolation
 *       efficiently with minimal duplication of data and high performance and,
 *       in interaction with the slot allocation index, providing fast
 *       deallocation of slots no longer used by any active transaction - the
 *       current scheme essentially forces visitation of the slots to be
 *       deleted.
 * 
 * @todo A larger branching factor in the object index will result in fewer
 *       accesses to resolve a given identifier. This does not matter much when
 *       the journal is fully buffered, but it is critical when a journal uses a
 *       page buffer (unless the base object index and the per transaction
 *       object indices can be wired into memory).<br>
 *       A large branching factor is naturally in opposition to a smaller slot
 *       size. If larger nodes are used with smaller slot sizes then each index
 *       node will occupy multiple slots. It is possible to force allocation of
 *       those slots such that they are contiguous - this approach has the
 *       advantage that the journal remains an append-only store, but introduces
 *       complexity in allocation of index nodes. My expectation is that most
 *       allocations will be much smaller than index node allocations, but that
 *       many allocations will be made together since writes are buffered on a
 *       per tx basis before being applied to the journal. If it is also true
 *       that we tend to release the allocations for entire transactions at
 *       once, then this reduces the likelyhood that we will exhaust an extent
 *       for index node allocations through fragmentation. <br>
 *       Another approach would partition the journal (either within one file or
 *       into two files) so that part was reserved for object index nodes and
 *       part was reserved for slots. This would, pragmatically, result in two
 *       memory spaces each having fixed length slots - one for the objects and
 *       the slot allocation index blocks and one for the object index nodes.
 *       However, partitioning also goes directly against the requirement for an
 *       append-only store and would result in, essentially, twice as many
 *       append only data structures - and hence twice the disk head movement as
 *       a design that does not rely on either within file partition or a two
 *       file scheme.<br>
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class SimpleObjectIndex implements IObjectIndex {

    /**
     * Interface for an entry (aka value) in the {@link IObjectIndex}. The
     * entry stores either the slots on which the data version for the
     * corresponding persistent identifier was written or notes that the
     * persistent identifier has been deleted. When there is an overwrite of a
     * pre-existing version (one that exists in the base object index scope),
     * then the slots allocated to that pre-existing version are copied into the
     * entry so that they may be efficiently processed later when the
     * transaction is garbage collected.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    static interface IObjectIndexEntry /*extends Cloneable*/ {

        /**
         * True iff the persistent identifier for this entry has been deleted.
         * 
         * @return True if the persistent identifier has been deleted.
         */
        public boolean isDeleted();
        
        /**
         * True iff a pre-existing version has been overwritten (or deleted). If
         * this transaction commits, then the slots allocated to that
         * pre-existing version will eventually be garbage collected.
         * 
         * @return True if a pre-existing version has been overwritten.
         */
        public boolean isPreExistingVersionOverwritten();
        
        /**
         * Non-null iff there is a current version for the persistent identifier
         * that indexes this entry and null iff the persistent version for the
         * entry has been deleted within the current transactional scope.
         * 
         * @see #isDeleted()
         */
        public ISlotAllocation getCurrentVersionSlots();
        
        /**
         * The first slot in the chain of slots allocated to the version.
         * 
         * @return The first slot.
         * 
         * @exception IllegalStateException
         *                if there is no current version allocation (i.e., if
         *                the persistent identifier has been deleted).
         */
        public int firstSlot();
        
        /**
         * <p>
         * When non-null, the slots containing a pre-existing version that was
         * overwritten during a transaction. This is used to support garbage
         * collection of pre-existing versions overwritten within a transaction
         * once they are no longer accessible to any active transaction.
         * </p>
         * <p>
         * Note: If there is a pre-existing version that is overwritten multiple
         * times within a transactions, then this field is set the first time to
         * the slots for the pre-existing version and is thereafter immutable.
         * This is because versions created within a transaction may be
         * overwritten immediately while a restart safe record of overwritten
         * pre-existing versions must be retained until we can GC the
         * transaction in which the overwrite was performed.
         * </p>
         * <p>
         * Note: If no version was pre-existing when the transaction began, then
         * writes and overwrites within that transaction do NOT cause this field
         * to be set and the slots for the overwritten versions are
         * synchronously deallocated.
         * </p>
         * <p>
         * Note: For the purposes of garbage collection, we treat a delete as an
         * overwrite. Therefore, if the delete was for a pre-existing version,
         * then this field contains the slots for that pre-existing version. If
         * the delete was for a version created within this transaction, then
         * the slots for that version are synchronously deallocated and this
         * field will be <code>null</code>.
         * </p>
         */
        public ISlotAllocation getPreExistingVersionSlots();
   
//        /**
//         * Clone an entry.
//         * 
//         * @return An entry with the references to the same instance data.
//         */
//        public IObjectIndexEntry clone();
        
    }

    /**
     * A non-persistence capable implementation of {@link IObjectIndexEntry}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    static class SimpleEntry implements IObjectIndexEntry {

        private ISlotAllocation currentVersionSlots;
        private ISlotAllocation preExistingVersionSlots;

        SimpleEntry() {

            // NOP
            
        }
        
        public boolean isDeleted() {
            
            return currentVersionSlots == null;
            
        }
 
        public int firstSlot() {
        
            if( currentVersionSlots == null ) {
                
                throw new IllegalStateException();
                
            }
            
            return currentVersionSlots.firstSlot();
            
        }
        
        public boolean isPreExistingVersionOverwritten() {
            
            return preExistingVersionSlots != null;
            
        }
        
        public ISlotAllocation getCurrentVersionSlots() {

            return currentVersionSlots;
            
        }
        
        public ISlotAllocation getPreExistingVersionSlots() {
            
            return preExistingVersionSlots;
            
        }
        
//        public IObjectIndexEntry clone() {
//            
//            SimpleEntry clone = new SimpleEntry();
//            
//            clone.currentVersionSlots = currentVersionSlots;
//            
//            clone.preExistingVersionSlots = preExistingVersionSlots;
//            
//            return clone;
//            
//        }
        
    }

    /**
     * Map from the int32 within segment persistent identifier to
     * {@link IObjectIndexEntry} for that identifier.
     * 
     * @see IObjectIndexEntry
     */
    final Map<Integer,IObjectIndexEntry> objectIndex;

    /**
     * When non-null, this is the base (or inner) object index that represents
     * the committed object index state as of the time that a transaction began.
     */
    final SimpleObjectIndex baseObjectIndex;
    
    /**
     * Constructor used for the base object index (outside of any transactional
     * scope).
     */
    public SimpleObjectIndex() {

        this.objectIndex = new HashMap<Integer,IObjectIndexEntry>();

        this.baseObjectIndex = null;

    }

    /**
     * Private constructor creates a read-only (unmodifiable) deep-copy of the
     * supplied object index state.  This is used to provide a strong guarentee
     * that object index modifications can not propagate through to the inner
     * layer using this API.
     * 
     * @param objectIndex
     */
    private SimpleObjectIndex(Map<Integer,IObjectIndexEntry> objectIndex ) {

        Map<Integer,IObjectIndexEntry> copy = new HashMap<Integer,IObjectIndexEntry>();
        copy.putAll( objectIndex );
        
        /*
         * Note: this does not prevent code from directly modifying the fields
         * in an IObjectIndexEntry. However, the ISlotAllocation interface
         * should prevent simply changing the record of the slots allocated to
         * some version.
         */
        this.objectIndex = Collections.unmodifiableMap(copy);

        this.baseObjectIndex = null;

    }
    
    /**
     * Constructor used to isolate a transaction by a read-only read-through
     * view of some committed object index state.
     * 
     * @param baseObjectIndex
     *            Misses on the primary object index read through to this object
     *            index. Writes are ONLY performed on the primary object index.
     *            The base object index is always read-only.
     * 
     * @todo This makes an eager deep copy of the current object index. This
     *       provides isolation against changes to the object index on the
     *       journal. An efficient implementation MUST NOT make an eager deep
     *       copy. Instead, {@link #mergeWithGlobalObjectIndex(Journal)} MUST
     *       use copy on write semantics can cause lazy cloning of the global
     *       object index structure so that changes do not get forced into
     *       concurrent transactions but are only visible within new
     *       transactions that begin from the post-commit state of the isolated
     *       transaction.
     */
    public SimpleObjectIndex(SimpleObjectIndex baseObjectIndex) {

        assert baseObjectIndex != null;

        this.objectIndex = new HashMap<Integer,IObjectIndexEntry>();

        this.baseObjectIndex = new SimpleObjectIndex(baseObjectIndex.objectIndex);
        
    }

    /**
     * Read an entry from the object index. If there is a miss on the outer
     * index and an inner index is defined then try a read on the inner index.
     * 
     * @param id
     *            The int32 within segment persistent identifier.
     * 
     * @return The entry for that persistent identifier or null if no entry was
     *         found.
     * 
     * @see #hitOnOuterIndex
     */
    private IObjectIndexEntry get(int id) {
        
        IObjectIndexEntry entry = objectIndex.get(id );
        
        if( entry == null ) {
            
            hitOnOuterIndex = false;

            if( baseObjectIndex != null ) {

                entry = baseObjectIndex.objectIndex.get(id);
            
            }

            // MAY be null.
            return entry;
            
        }   
        
        hitOnOuterIndex = true;
        
        return entry;
        
    }
    
    /**
     * This field is set by {@link #get(int)} each time it is invoked. The field
     * will be true iff there was a hit on the object index and the hit occurred
     * on the outer layer - that is, the layer to which the request was
     * directed. When true, this flag may be interpreted as indicating that a
     * version already exists within the transaction scope IFF this object index
     * is providing transactional isolation (vs the journal's native level
     * object index). In this situation the slots allocated to the version that
     * is being overwritten MUST be immediately released on the journal since it
     * is impossible for any transaction to read the version stored on those
     * slots.
     */
    private boolean hitOnOuterIndex = false;
    
    public ISlotAllocation getSlots( int id ) {

        IObjectIndexEntry entry = get(id);
        
        if( entry == null ) return null;
        
        if( entry.isDeleted() ) {

            throw new DataDeletedException(id);

        }
    
        ISlotAllocation slots = entry.getCurrentVersionSlots(); 
        
        assert slots != null;
        
        return slots; 
        
    }
    
    public void mapIdToSlots( int id, ISlotAllocation slots, ISlotAllocationIndex allocationIndex ) {
        
        if( slots == null ) throw new IllegalArgumentException();
        
        if( allocationIndex == null ) throw new IllegalArgumentException();

        // Integer variant since we use it several times.
        final Integer id2 = id;
        
        /* 
         * Get the object index entry.  This can read through into the base
         * object index.
         * 
         * Note: [hitOnOuterIndex] is set as a side effect.
         */

        IObjectIndexEntry entry = get(id2);

        if( entry == null ) {
            
            /*
             * This is the first write of a version for the persistent
             * identifier. We create a new entry and insert it into the outer
             * map.
             */
            
            SimpleEntry newEntry = new SimpleEntry();
            
            newEntry.currentVersionSlots = slots;
            
            newEntry.preExistingVersionSlots = null;
            
            objectIndex.put( id2, newEntry );
            
            return;
            
        }
        
        if( entry.isDeleted() ) {

            /*
             * You can not write on a persistent identifier that has been
             * deleted.
             */
            
            throw new DataDeletedException(id);
            
        }
        
        if( hitOnOuterIndex ) {

            /*
             * If we hit on the outer index, then we can immediately deallocate
             * the slots for the prior version since they MUST have been
             * allocated within this transaction.
             */

            // deallocate slots for the prior version.
            allocationIndex.clear(entry.getCurrentVersionSlots());
            
            // save the slots allocated for the new version.
            ((SimpleEntry)entry).currentVersionSlots = slots;
            
            // @todo mark index node as dirty!
            
        } else {

            /*
             * If we hit on the inner index then we create a new entry (we NEVER
             * modify the inner index directly while a transaction is running).
             * The currentVersion is set to the provided slots. The
             * preExistingVersion is set to the slots found on the current
             * version in the _inner_ index. Those slots will get GC'd
             * eventually if this transaction commits.
             */
            
            SimpleEntry newEntry = new SimpleEntry();
            
            // save the slots allocated for the new version.
            newEntry.currentVersionSlots = slots;

            // copy the slots for the pre-existing version for later GC of tx.
            newEntry.preExistingVersionSlots = entry.getCurrentVersionSlots(); 
            
            // add new entry into the outer index.
            objectIndex.put(id2, newEntry);
            
        }
                
//        /*
//         * Note: everything below here is just to validate our expectation
//         * concerning whether or not we are overwriting an existing version. In
//         * order to find the entry for the overwritten layer (since the object
//         * index map has two layers) we have to read on the inner layer if the
//         * put() on the outer layer does not return a pre-existing entry.
//         * 
//         * Note: I expect that this will all change drammatically when we move
//         * to a persistent and restart safe object index data structure.
//         */
//        
//        if( oldSlot == null && baseObjectIndex != null ) {
//            
//            // read the entry from the inner layer.
//            
//            oldSlot = baseObjectIndex.objectIndex.get(id);
//            
//        }
//        
//        if( overwrite ) {
//
//            if( oldSlot == null ) {
//                
//                throw new IllegalStateException(
//                        "Identifier is not mapped: id="
//                        + id + ", slot=" + slot + ", overwrite=" + overwrite);
//                
//            }
//            
//        } else {
//        
//            if (oldSlot != null) {
//
//                throw new IllegalStateException(
//                        "Identifier already mapped: id=" + id + ", oldSlot="
//                                + oldSlot + ", newSlot=" + slot);
//
//            }
//
//        }
        
    }

    public void delete(int id, ISlotAllocationIndex allocationIndex ) {

        /* 
         * Get the object index entry.  This can read through into the base
         * object index.
         */

        IObjectIndexEntry entry = get(id);

        if( entry == null ) {
            
            throw new IllegalArgumentException("Not found: id="+id);
            
        }

        if( entry.isDeleted() ) {

            /*
             * It is an error to double-delete an object.
             */
            
            throw new DataDeletedException(id);
            
        }
        
        if( hitOnOuterIndex ) {

            /*
             * If we hit on the outer index, then we can immediately deallocate
             * the slots for the current versions since they MUST have been
             * allocated within this transaction.
             */

            // deallocate slots for the current version.
            allocationIndex.clear(entry.getCurrentVersionSlots());
            
            // mark the current version as deleted.
            ((SimpleEntry)entry).currentVersionSlots = null;
            
            // @todo mark index node as dirty!
            
        } else {

            /*
             * If we hit on the inner index then we create a new entry. The
             * currentVersion is set to null since the persistent identifier is
             * deleted. The preExistingVersion is set to the slots found on the
             * current version in the _inner_ index.  Those slots will get GC'd
             * eventually if this transaction commits.
             */
            
            SimpleEntry newEntry = new SimpleEntry();
            
            // mark version as deleted.
            newEntry.currentVersionSlots = null;

            // copy the slots for the pre-existing version for later GC of tx.
            newEntry.preExistingVersionSlots = entry.getCurrentVersionSlots(); 
            
            // add new entry into the outer index.
            objectIndex.put(id, newEntry);
            
        }
        
    }

//    public int removeDeleted(int id) {
//    
//        /*
//         * Remove the object index entry, recovering its contents.
//         * 
//         * Note: Unlike all other methods that read on the object index, this
//         * one does NOT read through to the inner index. The reason is that
//         * deleted entries are only written on the outer index. If we read
//         * through to the inner index then we will always find the un-deleted
//         * entry (if there is any entry for the identifier).
//         */ 
//
//        Integer negIndex = objectIndex.remove(id);
//        
//        if( negIndex == null ) {
//
//            throw new IllegalArgumentException("Not found: id="+id);
//            
//        }
//
//        // Convert to int.
//        final int negIndex2 = negIndex.intValue();
//        
//        if( negIndex2 >= 0 ) {
//            
//            /*
//             * A negative slot index is used to mark objects that have been
//             * deleted from the object index but whose slots have not yet been
//             * released.
//             */
//            
//            throw new IllegalStateException("Not deleted: id=" + id);
//            
//        }
//        
//        // Convert back to a non-negative slot index.
//        final int firstSlot = (-negIndex - 1);
//
//        return firstSlot;
//    
//    }

    /**
     * <p>
     * Merge the transaction scope object index onto the global scope object
     * index.
     * </p>
     * <p>
     * This method is invoked by a transaction during commit processing to merge
     * the write set of its object index into the global scope. This operation
     * does NOT check for conflicts. The pre-condition is that the transaction
     * has already been validated (hence, there will be no conflicts). The
     * method exists on the object index so that we can optimize the traversal
     * of the object index in an implementation specific manner (vs exposing an
     * iterator).
     * </p>
     * 
     * @todo For a persistence capable implementation of the object index we
     *       could clear currentVersionSlots during this operation since there
     *       should be no further access to that field. The only time that we
     *       will go re-visit the committed object index for the transaction is
     *       when we GC the pre-existing historical versions overwritten during
     *       that transaction. Given that, we do not even need to store the
     *       object index root for a committed transaction (unless we want to
     *       provide a feature for reading historical states, which is NOT part
     *       of the journal design). So another option is to just write a chain
     *       of {@link ISlotAllocation} objects. (Note, per the item below GC
     *       also needs to remove entries from the global object index so this
     *       optimization may not be practical).  This could be a single long
     *       run-encoded slot allocation spit out onto a series of slots during
     *       PREPARE. When we GC the transaction, we just read the chain,
     *       deallocate the slots found on that chain, and then release the
     *       chain itself (it could have its own slots added to the end so that
     *       it is self-consuming). Just pay attention to ACID deallocation so
     *       that a partial operation does not have side-effects (at least, side
     *       effects that we do not want). This might require a 3-bit slot
     *       allocation index so that we can encode the conditional transition
     *       from (allocated + committed) to (deallocated + uncommitted) and
     *       know that on restart the state should be reset to (allocated +
     *       committed).
     * 
     * @todo GC should remove the 'deleted' entries from the global object index
     *       so that the index size does not grow without limit simply due to
     *       deleted versions. This makes it theoretically possible to reuse a
     *       persistent identifier once it has been deleted, is no longer
     *       visible to any active transaction, and has had the slots
     *       deallocated for its last valid version. However, in practice this
     *       would require that the logic minting new persistent identifiers
     *       received notice as old identifiers were expired and available for
     *       reuse. (Note that applications SHOULD use names to recover root
     *       objects from the store rather than their persistent identifiers.)
     * 
     * FIXME Validation of the object index MUST specifically treat the case
     * when no version for a persistent identifier exists in the ground state
     * for a tx, another tx begins and commits having written a version for that
     * identifier, and then this tx attempts to commit having written (or
     * written and deleted) a version for that identifier. Failure to treat this
     * case will cause problems during the merge since there will be an entry in
     * the global scope that was NOT visible to this transaction (which executed
     * against a distinct historical global scope). My take is the persistent
     * identifier assignment does not tend to have semantics (they are not
     * primary keys, but opaque identifiers) therefore we MUST NOT consider them
     * to be the same "object" and an unreconcilable write-write conflict MUST
     * be reported during validation. (Essentially, two transactions were handed
     * the same identifier for new objects.)
     * 
     * FIXME Think up sneaky test cases for this method and verify its operation
     * in some detail.
     */
    void mergeWithGlobalObjectIndex(Journal journal) {
        
        // Verify that this is a transaction scope object index.
        assert baseObjectIndex != null;
        
        final Iterator<Map.Entry<Integer, IObjectIndexEntry>> itr = objectIndex
                .entrySet().iterator();
        
        while( itr.hasNext() ) {
            
            Map.Entry<Integer, IObjectIndexEntry> mapEntry = itr.next();
            
            // The persistent identifier.
            final Integer id = mapEntry.getKey();
            
            // The value for that persistent identifier.
            final IObjectIndexEntry entry = mapEntry.getValue();
            
            if( entry.isDeleted() ) {

                /*
                 * IFF there was a pre-existing version in the global scope then
                 * we clear the 'currentVersionSlots' in the entry in the global
                 * scope and mark the index entry as dirty. The global scope
                 * will now recognized the persistent identifier as 'deleted'.
                 */
                
                if( entry.isPreExistingVersionOverwritten() ) {

                    /*
                     * Note: the same post-conditions could be satisified by
                     * getting the entry in the global scope, clearing its
                     * [currentVersionSlots] field, settting its
                     * [preExistingVersionSlots] field and marking the entry as
                     * dirty -- that may be more effective with a persistence
                     * capable implementation.
                     */
                    
                    journal.objectIndex.objectIndex.put(id,entry);
                    
                } else {
                    
                    /*
                     * The deleted version never existed in the global scope.
                     */
                    
                }

            } else {

                /*
                 * Copy the entry down onto the global scope.
                 */

                journal.objectIndex.objectIndex.put(id, entry);
                
            }
            
            /*
             * 
             * The slots allocated to the pre-existing version are retained in
             * the index entry for this transaction until the garbage collection
             * is run for the transaction. This is true regardless of whether
             * new version(s) were written in this transaction, if the
             * pre-existing version was simply deleted, or if the most recent
             * versions written by this transaction was finally deleted. If the
             * entry is holding the slots for a pre-existing version that was
             * overwritten then we MUST NOT remove it from the transaction's
             * object index. That information is required later to GC the
             * pre-existing versions.
             */
            
            if( ! entry.isPreExistingVersionOverwritten() ) {

                // Remove the index entry in the transaction scope.
                
                itr.remove();

            }

        }

    }

    /**
     * This implementation simply scans the object index. After a commit, the
     * only entries that we expect to find in the transaction's object index are
     * those where a pre-existing version was overwritten by the transaction. We
     * just deallocate the slots for those pre-existing versions.
     * 
     * @param allocationIndex
     *            The index on which slot allocations are maintained.
     * 
     * FIXME The transaction's object index SHOULD be deallocated on the journal
     * after garbage collection since it no longer holds any usable information.
     * 
     * FIXME Garbage collection probably MUST be atomic (it is Ok if it is both
     * incremental and atomic, but it needs a distinct commit point, it must be
     * restart safe, etc.).
     */

    void gc(ISlotAllocationIndex allocationIndex) {
        
        // Verify that this is a transaction scope object index.
        assert baseObjectIndex != null;
        
        final Iterator<Map.Entry<Integer, IObjectIndexEntry>> itr = objectIndex
                .entrySet().iterator();
        
        while( itr.hasNext() ) {
            
            Map.Entry<Integer, IObjectIndexEntry> mapEntry = itr.next();
            
//            // The persistent identifier.
//            final Integer id = mapEntry.getKey();
            
            // The value for that persistent identifier.
            final IObjectIndexEntry entry = mapEntry.getValue();
            
            // The slots on which the pre-existing version was written.
            ISlotAllocation preExistingVersionSlots = entry
                    .getPreExistingVersionSlots();

            // Deallocate those slots.
            allocationIndex.clear(preExistingVersionSlots);
            
            /*
             * Note: This removes the entry to avoid possible problems with
             * double-gc. However, this issue really needs to be resolved by an
             * ACID GC operation.
             */
            itr.remove();
                
        }

    }
    
}
