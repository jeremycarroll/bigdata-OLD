/**

Copyright (C) SYSTAP, LLC 2006-2011.  All rights reserved.

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
/*
 * Created on Aug 30, 2011
 */

package com.bigdata.bop.join;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.Logger;

import com.bigdata.bop.BOpContext;
import com.bigdata.bop.Constant;
import com.bigdata.bop.HTreeAnnotations;
import com.bigdata.bop.HashMapAnnotations;
import com.bigdata.bop.IBindingSet;
import com.bigdata.bop.IConstant;
import com.bigdata.bop.IConstraint;
import com.bigdata.bop.IVariable;
import com.bigdata.bop.IndexAnnotations;
import com.bigdata.bop.PipelineOp;
import com.bigdata.bop.engine.BOpStats;
import com.bigdata.btree.BytesUtil;
import com.bigdata.btree.Checkpoint;
import com.bigdata.btree.DefaultTupleSerializer;
import com.bigdata.btree.HTreeIndexMetadata;
import com.bigdata.btree.ITuple;
import com.bigdata.btree.ITupleIterator;
import com.bigdata.btree.ITupleSerializer;
import com.bigdata.btree.IndexMetadata;
import com.bigdata.btree.keys.ASCIIKeyBuilderFactory;
import com.bigdata.btree.keys.IKeyBuilder;
import com.bigdata.btree.raba.codec.FrontCodedRabaCoder;
import com.bigdata.btree.raba.codec.SimpleRabaCoder;
import com.bigdata.counters.CAT;
import com.bigdata.htree.HTree;
import com.bigdata.io.ByteArrayBuffer;
import com.bigdata.rawstore.Bytes;
import com.bigdata.rawstore.IRawStore;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.internal.IVCache;
import com.bigdata.rdf.internal.encoder.IBindingSetDecoder;
import com.bigdata.rdf.internal.encoder.IVBindingSetEncoderWithIVCache;
import com.bigdata.rdf.internal.impl.literal.XSDBooleanIV;
import com.bigdata.rdf.model.BigdataValue;
import com.bigdata.relation.accesspath.BufferClosedException;
import com.bigdata.relation.accesspath.IBuffer;
import com.bigdata.rwstore.sector.IMemoryManager;
import com.bigdata.rwstore.sector.MemStore;
import com.bigdata.striterator.Chunkerator;
import com.bigdata.striterator.Dechunkerator;
import com.bigdata.util.InnerCause;

import cutthecrap.utils.striterators.Expander;
import cutthecrap.utils.striterators.ICloseableIterator;
import cutthecrap.utils.striterators.IStriterator;
import cutthecrap.utils.striterators.Resolver;
import cutthecrap.utils.striterators.SingleValueIterator;
import cutthecrap.utils.striterators.Striterator;
import cutthecrap.utils.striterators.Visitor;

/**
 * Utility methods to support hash index builds and hash index joins using a
 * scalable native memory data structures.
 * 
 * <h2>Vectoring and IV encoding</h2>
 * 
 * In order to provide efficient encoding and persistence of solutions on the
 * {@link HTree}, this class is written directly to the RDF data model. Rather
 * than POJO serialization, solutions are encoded as logical {@link IV}[]s in a
 * manner very similar to how we represent the keys of the statement indices.
 * <p>
 * Since this encoding does not persist the {@link IV#getValue() cache}, a
 * separate mapping must be maintained from {@link IV} to {@link BigdataValue}
 * for those {@link IV}s which have a materialized {@link BigdataValue}.
 * 
 * TODO Do a 64-bit hash version which could be used for hash indices having
 * more than 500M distinct join variable combinations. Note that at 500M
 * distinct join variable combinations we have a 1 in 4 chance of a hash
 * collision. Whether or not that turns into a cost really depends on the
 * cardinality of the solutions per distinct combination of the join variables.
 * If there is only one solution per join variable combination, then those
 * collisions will cause basically no increase in the work to be done. However,
 * if there are 50,000 solutions per distinct combination of the join variables
 * then we would be better off using a 64-bit hash code.
 * 
 * FIXME Vector resolution of ivCache. Various methods use
 * {@link IVBindingSetEncoderWithIVCache#resolveCachedValues(IBindingSet)}
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id: HTreeHashJoinUtility.java 5568 2011-11-07 19:39:12Z thompsonbry
 */
public class HTreeHashJoinUtility implements IHashJoinUtility {

    static private final transient Logger log = Logger
            .getLogger(HTreeHashJoinUtility.class);

    /**
     * Note: If joinVars is an empty array, then the solutions will all hash to
     * ONE (1).
     */
    private static final int ONE = 1;
    
    /**
     * Return the hash code which will be used as the key given the ordered
     * as-bound values for the join variables.
     * 
     * @param joinVars
     *            The join variables.
     * @param bset
     *            The bindings whose as-bound hash code for the join variables
     *            will be computed.
     * @param ignoreUnboundVariables
     *            If a variable without a binding should be silently ignored.
     * 
     * @return The hash code.
     * 
     * @throws JoinVariableNotBoundException
     *             if there is no binding for a join variable.
     * 
     *             FIXME Does anything actually rely on the
     *             {@link JoinVariableNotBoundException}? It would seem that
     *             this exception could only be thrown if the joinvars[] was
     *             incorrectly formulated as it should only include
     *             "known bound" variables. (I think that this is related to
     *             incorrectly passing along empty solutions for named subquery
     *             hash joins.)
     */
    private static int hashCode(final IVariable<?>[] joinVars,
            final IBindingSet bset, final boolean ignoreUnboundVariables)
            throws JoinVariableNotBoundException {

        int h = ONE;

        for (IVariable<?> v : joinVars) {

            final IConstant<?> c = bset.get(v);

            if (c == null) {

                if(ignoreUnboundVariables)
                    continue;
                
                // Reject any solution which does not have a binding for a join
                // variable.
                throw new JoinVariableNotBoundException(v.getName());
                
            }
            
            // Works Ok.
            h = 31 * h + c.hashCode();
            
//          // Martyn's version.  Also works Ok.
            // @see http://burtleburtle.net/bob/hash/integer.html
//            
//            final int hc = c.hashCode();
//            h += ~(hc<<15);
//            h ^=  (hc>>10);
//            h +=  (hc<<3);
//            h ^=  (hc>>6);

        }
        
        if (log.isTraceEnabled())
            log.trace("hashCode=" + h + ", joinVars="
                    + Arrays.toString(joinVars) + " : " + bset);

        return h;

    }

    /**
     * <code>true</code> until the state is discarded by {@link #release()}.
     */
    private final AtomicBoolean open = new AtomicBoolean(true);
    
    /**
     * The operator whose annotations are used to initialize this object.
     * <p>
     * Note: This was added to support the DISTINCT FILTER in
     * {@link #outputSolutions(IBuffer)}.
     */
    private final PipelineOp op;
    
    /**
     * This basically controls the vectoring of the hash join.
     * 
     * TODO parameter from operator annotations. Note that 10k tends to put too
     * much heap pressure on the system if the source chunks happen to be
     * smallish. 1000k or 100 is probably the right value until we improve
     * vectoring of the query engine.
     */
    private final int chunkSize = 1000;//ChunkedWrappedIterator.DEFAULT_CHUNK_SIZE;

    /**
     * Utility class for compactly and efficiently encoding and decoding
     * {@link IBindingSet}s.
     */
    private final IVBindingSetEncoderWithIVCache encoder;
    
    /**
     * The type of join to be performed.
     */
    private final JoinTypeEnum joinType;
    
    /**
     * <code>true</code> iff the join is OPTIONAL.
     */
    private final boolean optional;
    
    /**
     * <code>true</code> iff this is a DISTINCT filter.
     */
    private final boolean filter;
    
//    /**
//     * The operator which was used to construct the {@link IHashJoinUtility}
//     * state.
//     * <p>
//     * Note: This is NOT necessarily the operator which is currently executing.
//     * Hash indices are often built by one operator and then consumed by
//     * other(s).
//     */
//    private final PipelineOp op;
    
    /**
     * @see HashJoinAnnotations#ASK_VAR
     */
    private final IVariable<?> askVar;
    
    /**
     * The join variables.
     */
    private final IVariable<?>[] joinVars;

    /**
     * The variables to be retained (optional, all variables are retained if
     * not specified).
     */
    private final IVariable<?>[] selectVars;

    /**
     * The variables to be projected into a join group. When non-
     * <code>null</code> variables that are NOT in this array are NOT flowed
     * into the join group.
     * 
     * @see <a href="https://sourceforge.net/apps/trac/bigdata/ticket/668" >
     *      JoinGroup optimizations </a>
     */
    private final IVariable<?>[] projectedInVars;

    /**
     * The join constraints (optional).
     */
    private final IConstraint[] constraints;

    /**
     * The backing {@link IRawStore}.
     */
    private final IRawStore store;
    
    /**
     * The hash index. The keys are int32 hash codes built from the join
     * variables. The values are an {@link IV}[], similar to the encoding in
     * the statement indices. The mapping from the index positions in the
     * {@link IV}s to the variables is managed by the {@link #encoder}.
     */
    private final AtomicReference<HTree> rightSolutions = new AtomicReference<HTree>();

    /**
     * The set of distinct source solutions which joined. This set is maintained
     * iff the join is optional and is <code>null</code> otherwise.
     */
    private final AtomicReference<HTree> joinSet = new AtomicReference<HTree>();
    
    /**
     * The maximum #of (left,right) solution joins that will be considered
     * before failing the join. This is used IFF there are no join variables.
     * 
     * FIXME Annotation and query hint for this. Probably on
     * {@link HashJoinAnnotations}.
     */
    private final long noJoinVarsLimit = HashJoinAnnotations.DEFAULT_NO_JOIN_VARS_LIMIT;
    
    /**
     * The #of left solutions considered for a join.
     */
    private final CAT nleftConsidered = new CAT();

    /**
     * The #of right solutions considered for a join.
     */
    private final CAT nrightConsidered = new CAT();

    /**
     * The #of solution pairs considered for a join.
     */
    private final CAT nJoinsConsidered = new CAT();
    
    /**
     * The hash index.
     */
    private HTree getRightSolutions() {
        
        return rightSolutions.get();
        
    }
    
    /**
     * The set of distinct source solutions which joined. This set is
     * maintained iff the join is optional and is <code>null</code>
     * otherwise.
     */
    private HTree getJoinSet() {

        return joinSet.get();
        
    }
    
    /**
     * Human readable representation of the {@link IHashJoinUtility} metadata
     * (but not the solutions themselves).
     */
    @Override
    public String toString() {

        final StringBuilder sb = new StringBuilder();
        
        sb.append(getClass().getSimpleName());
        
        sb.append("{open=" + open);
        sb.append(",joinType="+joinType);
        sb.append(",chunkSize=" + chunkSize);
//        sb.append(",optional=" + optional);
//        sb.append(",filter=" + filter);
        if (askVar != null)
            sb.append(",askVar=" + askVar);
        sb.append(",joinVars=" + Arrays.toString(joinVars));
        if (projectedInVars != null)
            sb.append(",projectedInVars=" + Arrays.toString(projectedInVars));
        if (selectVars != null)
            sb.append(",selectVars=" + Arrays.toString(selectVars));
        if (constraints != null)
            sb.append(",constraints=" + Arrays.toString(constraints));
        sb.append(",size=" + getRightSolutionCount());
        sb.append(",considered(left=" + nleftConsidered + ",right="
                + nrightConsidered + ",joins=" + nJoinsConsidered + ")");
        if (joinSet.get() != null)
            sb.append(",joinSetSize=" + getJoinSetSize());
//        sb.append(",encoder="+encoder);
        sb.append("}");
        
        return sb.toString();
        
    }

    @Override
    public boolean isEmpty() {
        
        return getRightSolutionCount() == 0;
        
    }
    
    @Override
    public long getRightSolutionCount() {

        final HTree htree = getRightSolutions();

        if (htree != null) {

            return htree.getEntryCount();

        }

        return 0L;
        
    }

    private long getJoinSetSize() {

        final HTree htree = getJoinSet();

        if (htree != null) {

            return htree.getEntryCount();

        }

        return 0L;
        
    }

    @Override
    public JoinTypeEnum getJoinType() {
        
        return joinType;
        
    }

    @Override
    public IVariable<?> getAskVar() {
        
        return askVar;
        
    }

    @Override
    public IVariable<?>[] getJoinVars() {

        return joinVars;
        
    }

    @Override
    public IVariable<?>[] getSelectVars() {
        
        return selectVars;
        
    }

    @Override
    public IConstraint[] getConstraints() {
        
        return constraints;
        
    }
    
    /**
     * Setup the {@link IndexMetadata} for {@link #rightSolutions} or
     * {@link #joinSet}.
     */
    static private HTreeIndexMetadata getIndexMetadata(final PipelineOp op) {

		final HTreeIndexMetadata metadata = new HTreeIndexMetadata(
				UUID.randomUUID());

        final int addressBits = op.getProperty(HTreeAnnotations.ADDRESS_BITS,
                HTreeAnnotations.DEFAULT_ADDRESS_BITS);

//        final int branchingFactor = 2 ^ addressBits;
        
        final int ratio = 32; // TODO Config/tune.
        
        metadata.setAddressBits(addressBits);

        metadata.setRawRecords(op.getProperty(//
                HTreeAnnotations.RAW_RECORDS,
                HTreeAnnotations.DEFAULT_RAW_RECORDS));

        metadata.setMaxRecLen(op.getProperty(//
                HTreeAnnotations.MAX_RECLEN,
                HTreeAnnotations.DEFAULT_MAX_RECLEN));

        metadata.setWriteRetentionQueueCapacity(op.getProperty(
                IndexAnnotations.WRITE_RETENTION_QUEUE_CAPACITY,
                IndexAnnotations.DEFAULT_WRITE_RETENTION_QUEUE_CAPACITY));

        metadata.setKeyLen(Bytes.SIZEOF_INT); // int32 hash code keys.

        @SuppressWarnings("rawtypes")
        final ITupleSerializer<?, ?> tupleSer = new DefaultTupleSerializer(
                new ASCIIKeyBuilderFactory(Bytes.SIZEOF_INT),
                new FrontCodedRabaCoder(ratio),// keys : TODO Optimize for int32!
                new SimpleRabaCoder() // vals
        );

        metadata.setTupleSerializer(tupleSer);
        
        return metadata;

    }
    
    /**
     * 
     * @param mmgr
     *            The IMemoryManager which will back the named solution set.
     * @param op
     *            The operator whose annotation will inform construction the
     *            hash index. The {@link HTreeAnnotations} may be specified for
     *            this operator and will control the initialization of the
     *            various {@link HTree} instances.
     * @param joinType
     *            The type of join to be performed.
     * 
     * @see HTreeHashJoinAnnotations
     */
    public HTreeHashJoinUtility(final IMemoryManager mmgr, final PipelineOp op,
            final JoinTypeEnum joinType) {

        if (mmgr == null)
            throw new IllegalArgumentException();

        if (op == null)
            throw new IllegalArgumentException();

        if(joinType == null)
            throw new IllegalArgumentException();
        
        this.op = op;
        this.joinType = joinType;
        this.optional = joinType == JoinTypeEnum.Optional;
        this.filter = joinType == JoinTypeEnum.Filter;

        // Optional variable used for (NOT) EXISTS.
        this.askVar = (IVariable<?>) op
                .getProperty(HashJoinAnnotations.ASK_VAR);

        // The join variables (required).
        this.joinVars = (IVariable<?>[]) op
                .getRequiredProperty(HashJoinAnnotations.JOIN_VARS);

        // The projected variables (optional and equal to the join variables iff
        // this is a DISTINCT filter).
        this.selectVars = filter ? joinVars : (IVariable<?>[]) op
                .getProperty(JoinAnnotations.SELECT);

        /*
         * The variables that are projected IN to the join group.
         */
        this.projectedInVars = (IVariable<?>[]) op
                .getProperty(HashJoinAnnotations.PROJECT_IN_VARS);

        /*
         * This wraps an efficient raw store interface around a child memory
         * manager created from the IMemoryManager which will back the named
         * solution set.
         */
        store = new MemStore(mmgr.createAllocationContext());

        // Setup the encoder.  The ivCache will be backed by the memory manager.
        this.encoder = new IVBindingSetEncoderWithIVCache(store, filter, op);

        /*
         * Note: This is not necessary. We will encounter the join variables in
         * the solutions are they are processed and they will automatically
         * become part of the schema maintained by the encoder.
         */
//        // Initialize the schema with the join variables.
//        encoder.updateSchema(joinVars);

        // The join constraints (optional).
        this.constraints = (IConstraint[]) op
                .getProperty(JoinAnnotations.CONSTRAINTS);
        
        // Will support incremental eviction and persistence.
        rightSolutions.set(HTree.create(store, getIndexMetadata(op)));

        switch (joinType) {
        case Optional:
        case Exists:
        case Minus:
            // The join set is used to handle optionals.
            joinSet.set(HTree.create(store, getIndexMetadata(op)));
            break;
        }

    }

    /**
     * The backing {@link IRawStore}.
     */
    public IRawStore getStore() {
    
        return store;
        
    }
    
    /**
     * {@inheritDoc}
     * <p>
     * This implementation checkpoints the {@link HTree} instance(s) used to
     * buffer the source solutions ({@link #rightSolutions} and {@link #ivCache}
     * ) and then re-load the them in a read-only mode from their checkpoint(s).
     * This exposes a view of the {@link HTree} which is safe for concurrent
     * readers.
     */
    public void saveSolutionSet() {

        if (!open.get())
            throw new IllegalStateException();

        checkpointHTree(rightSolutions);

        encoder.saveSolutionSet();

        /*
         * Note: DO NOT checkpoint the joinSet here. That index is not even
         * written upon until we begin to evaluate the joins, which happens
         * after we checkpoint the source solutions.
         */

    }

//    /**
//     * Checkpoint the join set (used to buffer the optional solutions).
//     * <p>
//     * Note: Since we always output the solutions which did not join from a
//     * single thread as part of last pass evaluation there is no need to
//     * checkpoint the {@link #joinSet}.
//     */
//    public void checkpointJoinSet() {
//
//        if (!open.get())
//            throw new IllegalStateException();
//
//        checkpointHTree(joinSet);
//
//    }

    private void checkpointHTree(final AtomicReference<HTree> ref) {

        final HTree tmp = ref.get();

        if (tmp != null) {

            // Checkpoint the HTree.
            final Checkpoint checkpoint = tmp.writeCheckpoint2();

            final HTree readOnly = HTree.load(store,
                    checkpoint.getCheckpointAddr(), true/* readOnly */);

            // Get a read-only view of the HTree.
            if (!ref.compareAndSet(tmp/* expect */, readOnly)) {

                throw new IllegalStateException();

            }

        }

    }

    @Override
    public void release() {

        if (open.compareAndSet(true/* expect */, false/* update */)) {
            // Already closed.
            return;
        }

        encoder.release();
        
        HTree tmp = rightSolutions.getAndSet(null/* newValue */);

        if (tmp != null) {

            tmp.close();

        }

        tmp = joinSet.getAndSet(null/* newValue */);
        
        if (tmp != null) {

            tmp.close();

        }

        store.close();

    }

    @Override
    public long acceptSolutions(final ICloseableIterator<IBindingSet[]> itr,
            final BOpStats stats) {

        if (itr == null)
            throw new IllegalArgumentException();
        
        if (stats == null)
            throw new IllegalArgumentException();

        try {

            long naccepted = 0L;

            final HTree htree = getRightSolutions();

            final IKeyBuilder keyBuilder = htree.getIndexMetadata()
                    .getKeyBuilder();

            /*
             * Rechunk in order to have a nice fat vector size for ordered
             * inserts.
             * 
             * TODO This should probably be eliminated in favor of the existing
             * chunk size. That allows us to control the vectoring directly from
             * the pipeline annotations for the query engine. If 1000 (the
             * current [chunkSize] hard wired into this class) makes a
             * significant difference over 100 (the current default pipeline
             * chunk capacity) then we should simply override the default chunk
             * capacity for the htree hash join operators (i.e., analytic
             * operators always imply a larger default chunk capacity, as could
             * operators running on a cluster). This change should be verified
             * against the GOVTRACK dataset and also by using BSBM with JVM and
             * HTree hash joins and measuring the change in the performance
             * delta when the HTree hash join vector size is changed.
             * 
             * @see https://sourceforge.net/apps/trac/bigdata/ticket/483
             * (Eliminate unnecessary dechunking and rechunking)
             */

            final ICloseableIterator<IBindingSet[]> it = new Chunkerator<IBindingSet>(
                    new Dechunkerator<IBindingSet>(itr), chunkSize,
                    IBindingSet.class);
            
            try {
                
                final AtomicInteger vectorSize = new AtomicInteger();
                
                while (it.hasNext()) {
    
                    // Vector a chunk of solutions.
                    final BS[] a = vector(it.next(), joinVars,
                            null/* selectVars */,
                            false/* ignoreUnboundVariables */, vectorSize);
    
                    final int n = vectorSize.get();
    
                    stats.chunksIn.increment();
                    stats.unitsIn.add(a.length);
    
                    // Insert solutions into HTree in key order.
                    for (int i = 0; i < n; i++) {
    
                        final BS tmp = a[i];
    
                        // Encode the key.
                        final byte[] key = keyBuilder.reset().append(tmp.hashCode)
                                .getKey();
    
                        // Encode the solution.
                        final byte[] val = encoder.encodeSolution(tmp.bset);
    
                        // Insert binding set under hash code for that key.
                        htree.insert(key, val);
    
                    }
    
                    naccepted += a.length;
    
                    // Vectored update of the IV Cache.
    //                encoder.updateIVCache(cache);
                    encoder.flush();
    
                }

            } finally {
            
                it.close();
                
            }

            return naccepted;

        } catch(Throwable t) {
            throw launderThrowable(t);
        }

    }

    @Override
    public long filterSolutions(final ICloseableIterator<IBindingSet[]> itr,
            final BOpStats stats, final IBuffer<IBindingSet> sink) {

        if (itr == null)
            throw new IllegalArgumentException();
        
        if (stats == null)
            throw new IllegalArgumentException();

        try {
        
        long naccepted = 0L;
        
        final HTree htree = getRightSolutions();

        final IKeyBuilder keyBuilder = htree.getIndexMetadata().getKeyBuilder();

        /*
         * Rechunk in order to have a nice fat vector size for ordered inserts.
         */
        final Iterator<IBindingSet[]> it = new Chunkerator<IBindingSet>(
                new Dechunkerator<IBindingSet>(itr), chunkSize,
                IBindingSet.class);

        final AtomicInteger vectorSize = new AtomicInteger();
        while (it.hasNext()) {

            final BS[] a = vector(it.next(), joinVars, selectVars,
                    true/* ignoreUnboundVariables */, vectorSize);
  
            final int n = vectorSize.get();

            stats.chunksIn.increment();
            stats.unitsIn.add(a.length);

            for (int i = 0; i < n; i++) {

                final BS tmp = a[i];
                
                // Encode the key.
                final byte[] key = keyBuilder.reset().append(tmp.hashCode)
                        .getKey();

                /*
                 * Encode the solution. Do not update the cache since we are
                 * only encoding so we can probe the hash index.
                 */
                final byte[] val = encoder
                        .encodeSolution(tmp.bset, false/* updateCache */);

                /*
                 * Search the hash index for a match.
                 */
                boolean found = false;
                
                final ITupleIterator<?> titr = htree.lookupAll(key);

                while(titr.hasNext()) {
                
                    final ITuple<?> t = titr.next();
                    
                    final ByteArrayBuffer tb = t.getValueBuffer();

                    if (0 == BytesUtil.compareBytesWithLenAndOffset(
                            0/* aoff */, val.length/* alen */, val,//
                            0/* boff */, tb.limit()/* blen */, tb.array()/* b */
                    )) {

                        found = true;

                        break;
                        
                    }
                    
                }

                if (!found) {
                
                    // Add to the hash index.
                    htree.insert(key, val);

                    // Write onto the sink.
                    sink.add(tmp.bset);

                    naccepted++;
                    
                }
                
            }

            // Note: The IV=>Value cache is NOT maintained for DISTINCT.
//            encoder.flush();
//            updateIVCache(cache, ivCache.get());

        }
        
        return naccepted;

        } catch(Throwable t) {
            throw launderThrowable(t);
        }

    }
    
    /**
     * Decode a solution from an encoded {@link IV}[].
     * <p>
     * Note: The {@link IVCache} associated are NOT resolved by this method. The
     * resolution step is relatively expensive since it must do lookups in
     * persistence capable data structures. The caller MUST use
     * {@link IBindingSetDecoder#resolveCachedValues(IBindingSet)} to resolve
     * the {@link IVCache} associations once they decide that the decoded
     * solution can join.
     * <p>
     * Note: This instance method is required by the MERGE JOIN logic which
     * associates the schema with the first {@link IHashJoinUtility} instance.
     * 
     * @param t
     *            A tuple whose value is an encoded {@link IV}[].
     * 
     * @return The decoded {@link IBindingSet}.
     */
    private IBindingSet decodeSolution(final ITuple<?> t) {
        
        final ByteArrayBuffer b = t.getValueBuffer();
        
        return encoder
                .decodeSolution(b.array(), 0, b.limit(), false/* resolveCachedValues */);
        
    }

    /**
     * Glue class for hash code and binding set used when the hash code is for
     * just the join variables rather than the entire binding set.
     */
    private static class BS implements Comparable<BS> {

        final private int hashCode;

        final private IBindingSet bset;

        BS(final int hashCode, final IBindingSet bset) {
            this.hashCode = hashCode;
            this.bset = bset;
        }

        @Override
        public int compareTo(final BS o) {
            if (this.hashCode < o.hashCode)
                return -1;
            if (this.hashCode > o.hashCode)
                return 1;
            return 0;
        }
        
        public String toString() {
            return getClass().getName() + "{hashCode=" + hashCode + ",bset="
                    + bset + "}";
        }
        
    }
    
    /**
     * Glue class for hash code and encoded binding set used when we already
     * have the binding set encoded.
     */
    private static class BS2 implements Comparable<BS2> {

        final private int hashCode;

        final private byte[] value;

        BS2(final int hashCode, final byte[] value) {
            this.hashCode = hashCode;
            this.value = value;
        }

        @Override
        public int compareTo(final BS2 o) {
            if (this.hashCode < o.hashCode)
                return -1;
            if (this.hashCode > o.hashCode)
                return 1;
            return 0;
        }
        
        public String toString() {
            return getClass().getName() + "{hashCode=" + hashCode + ",value="
                    + BytesUtil.toString(value) + "}";
        }
        
    }
    
    @Override
    public void hashJoin(//
            final ICloseableIterator<IBindingSet> leftItr,//
            final IBuffer<IBindingSet> outputBuffer//
            ) {

        hashJoin2(leftItr, outputBuffer, constraints);
        
    }

    @Override
    public void hashJoin2(//
            final ICloseableIterator<IBindingSet> leftItr,//
            final IBuffer<IBindingSet> outputBuffer,//
            final IConstraint[] constraints//
            ) {

        try {

            final HTree rightSolutions = this.getRightSolutions();

            if (log.isInfoEnabled()) {
                log.info("rightSolutions: #nnodes="
                        + rightSolutions.getNodeCount() + ",#leaves="
                        + rightSolutions.getLeafCount() + ",#entries="
                        + rightSolutions.getEntryCount());
            }
            
            final IKeyBuilder keyBuilder = rightSolutions.getIndexMetadata()
                    .getKeyBuilder();

            final Iterator<IBindingSet[]> it = new Chunkerator<IBindingSet>(
                    leftItr, chunkSize, IBindingSet.class);

            // true iff there are no join variables.
            final boolean noJoinVars = joinVars.length == 0;
            
            final AtomicInteger vectorSize = new AtomicInteger();
            while (it.hasNext()) {

                final BS[] a = vector(it.next(), joinVars,
                        null/* selectVars */,
                        false/* ignoreUnboundVariables */, vectorSize);
                
                final int n = vectorSize.get();

                nleftConsidered.add(n);
                
                int fromIndex = 0;

                while (fromIndex < n) {

                    /*
                     * Figure out how many left solutions in the current chunk
                     * have the same hash code. We will use the same iterator
                     * over the right solutions for that hash code against the
                     * HTree.
                     */
                    
                    // The next hash code to be processed.
                    final int hashCode = a[fromIndex].hashCode;

                    // scan for the first hash code which is different.
                    int toIndex = n; // assume upper bound.
                    for (int i = fromIndex + 1; i < n; i++) {
                        if (a[i].hashCode != hashCode) {
                            toIndex = i;
                            break;
                        }
                    }
                    // #of left solutions having the same hash code.
                    final int bucketSize = toIndex - fromIndex;

                    if (log.isTraceEnabled())
                        log.trace("hashCode=" + hashCode + ": #left="
                                + bucketSize + ", firstLeft=" + a[fromIndex]);

                    /*
                     * Note: all source solutions in [fromIndex:toIndex) have
                     * the same hash code. They will be vectored together.
                     */
                    // All solutions which join for that collision bucket
                    final LinkedList<BS2> joined;
                    switch (joinType) {
                    case Optional:
                    case Exists:
                    case Minus:
                        joined = new LinkedList<BS2>();
                        break;
                    default:
                        joined = null;
                        break;
                    }
                    // #of solutions which join for that collision bucket.
                    int njoined = 0;
                    // #of solutions which did not join for that collision bucket.
                    int nrejected = 0;
                    {

                        final byte[] key = keyBuilder.reset().append(hashCode).getKey();
                        
                        // visit all source solutions having the same hash code
                        final ITupleIterator<?> titr = rightSolutions
                                .lookupAll(key);

                        long sameHashCodeCount = 0;
                        
                        while (titr.hasNext()) {

                            sameHashCodeCount++;
                            
                            final ITuple<?> t = titr.next();

                            /*
                             * Note: The map entries must be the full source
                             * binding set, not just the join variables, even
                             * though the key and equality in the key is defined
                             * in terms of just the join variables.
                             * 
                             * Note: Solutions which have the same hash code but
                             * whose bindings are inconsistent will be rejected
                             * by bind() below.
                             */
                            final IBindingSet rightSolution = decodeSolution(t);

                            nrightConsidered.increment();

                            for (int i = fromIndex; i < toIndex; i++) {

                                final IBindingSet leftSolution = a[i].bset;
                                
                                // Join.
                                final IBindingSet outSolution = BOpContext
                                        .bind(leftSolution, rightSolution,
                                                constraints,
                                                selectVars, joinType == JoinTypeEnum.Minus);

                                nJoinsConsidered.increment();

                                if (noJoinVars
                                        && nJoinsConsidered.get() == noJoinVarsLimit) {

                                    if (nleftConsidered.get() > 1
                                            && nrightConsidered.get() > 1) {

                                        throw new UnconstrainedJoinException();

                                    }

                                }

                                if (outSolution == null) {
                                    
                                    nrejected++;
                                    
                                    if (log.isTraceEnabled())
                                        log.trace("Does not join"//
                                                +": hashCode="+ hashCode//
                                                + ", sameHashCodeCount="+ sameHashCodeCount//
                                                + ", #left=" + bucketSize//
                                                + ", #joined=" + njoined//
                                                + ", #rejected=" + nrejected//
                                                + ", left=" + leftSolution//
                                                + ", right=" + rightSolution//
                                                );

                                } else {

                                    njoined++;

                                    if (log.isDebugEnabled())
                                        log.debug("JOIN"//
                                            + ": hashCode=" + hashCode//
                                            + ", sameHashCodeCount="+ sameHashCodeCount//
                                            + ", #left="+ bucketSize//
                                            + ", #joined=" + njoined//
                                            + ", #rejected=" + nrejected//
                                            + ", solution=" + outSolution//
                                            );
                                
                                }

                                switch(joinType) {
                                case Normal:
                                case Optional: {
                                    if (outSolution == null) {
                                        // Join failed.
                                        continue;
                                    }

                                    // Resolve against ivCache.
                                    encoder.resolveCachedValues(outSolution);
                                    
                                    // Output this solution.
                                    outputBuffer.add(outSolution);

                                    if (optional) {
                                        // Accumulate solutions to vector into
                                        // the joinSet.
                                        joined.add(new BS2(rightSolution
                                                .hashCode(), t.getValue()));
                                    }
                                    
                                    break;
                                }
                                case Exists: {
                                    /*
                                     * The right solution is output iff there is
                                     * at least one left solution which joins
                                     * with that right solution. Each right
                                     * solution is output at most one time. This
                                     * amounts to outputting the joinSet after
                                     * we have run the entire join. As long as
                                     * the joinSet does not allow duplicates it
                                     * will be contain the solutions that we
                                     * want.
                                     */
                                    if (outSolution != null) {
                                        // Accumulate solutions to vector into
                                        // the joinSet.
                                        joined.add(new BS2(rightSolution
                                                .hashCode(), t.getValue()));
                                    }
                                    break;
                                }
                                case Minus: {
                                    /*
                                     * The right solution is output iff there
                                     * does not exist any left solution which
                                     * joins with that right solution. This
                                     * basically an optional join where the
                                     * solutions which join are not output.
                                     */
                                    if (outSolution != null) {
                                        // Accumulate solutions to vector into
                                        // the joinSet.
                                        joined.add(new BS2(rightSolution
                                                .hashCode(), t.getValue()));
                                    }
                                    break;
                                }
                                default:
                                    throw new AssertionError();
                                }

                            } // next left in the same bucket.

                        } // next rightSolution with the same hash code.

                        if (joined != null && !joined.isEmpty()) {
                            /*
                             * Vector the inserts into the [joinSet].
                             */
                            final BS2[] a2 = joined.toArray(new BS2[njoined]);
                            Arrays.sort(a2, 0, njoined);
                            for (int i = 0; i < njoined; i++) {
                                final BS2 tmp = a2[i];
                                saveInJoinSet(tmp.hashCode, tmp.value);
                            }
                        }

                    } // end block of leftSolutions having the same hash code.

                    fromIndex = toIndex;
                    
                } // next slice of source solutions with the same hash code.

            } // while(itr.hasNext()

        } catch(Throwable t) {

            throw launderThrowable(t);
            
        } finally {

            leftItr.close();

        }

    } // handleJoin

    /**
     * Vector a chunk of solutions.
     * 
     * @param leftSolutions
     *            The solutions.
     * @param joinVars
     *            The variables on which the hash code will be computed.
     * @param selectVars
     *            When non-<code>null</code>, all other variables are dropped.
     *            (This is used when we are modeling a DISTINCT solutions filter
     *            since we need to drop anything which is not part of the
     *            DISTINCT variables list.)
     * @param ignoreUnboundVariables
     *            When <code>true</code>, an unbound variable will not cause a
     *            {@link JoinVariableNotBoundException} to be thrown.
     * @param vectorSize
     *            The vector size (set by side-effect).
     * 
     * @return The vectored chunk of solutions ordered by hash code.
     */
    private BS[] vector(final IBindingSet[] leftSolutions,
            final IVariable<?>[] joinVars,
            final IVariable<?>[] selectVars,
            final boolean ignoreUnboundVariables,
            final AtomicInteger vectorSize) {

        final BS[] a = new BS[leftSolutions.length];

        int n = 0; // The #of non-dropped source solutions.

        for (int i = 0; i < a.length; i++) {

            /*
             * Note: If this is a DISINCT FILTER, then we need to drop the
             * variables which are not being considered immediately. Those
             * variables MUST NOT participate in the computed hash code.
             */

            final IBindingSet bset = selectVars == null ? leftSolutions[i]
                    : leftSolutions[i].copy(selectVars);

            // Compute hash code from bindings on the join vars.
            int hashCode = ONE;
            try {

                hashCode = HTreeHashJoinUtility.hashCode(joinVars,
                        bset, ignoreUnboundVariables);
                
            } catch (JoinVariableNotBoundException ex) {

                if (!optional) {// Drop solution
                
                    if (log.isTraceEnabled())
                        log.trace(ex);
                    
                    continue;
                    
                }
                
            }
            
            a[n++] = new BS(hashCode, bset);
            
        }

        /*
         * Sort by the computed hash code. This not only orders the accesses
         * into the HTree but it also allows us to handle all source solutions
         * which have the same hash code with a single scan of the appropriate
         * collision bucket in the HTree.
         */
        Arrays.sort(a, 0, n);
        
        // Indicate the actual vector size to the caller via a side-effect.
        vectorSize.set(n);
        
        return a;
        
    }

    /**
     * Add to 2nd hash tree of all solutions which join.
     * <p>
     * Note: the hash key is based on the entire solution (not just the join
     * variables). The values are the full encoded {@link IBindingSet}.
     */
    private void saveInJoinSet(final int joinSetHashCode, final byte[] val) {

        final HTree joinSet = this.getJoinSet();

        if (true) {
            
            /*
             * Do not insert if there is already an entry for that solution in
             * the join set.
             * 
             * Note: EXISTS depends on this to have the correct cardinality. If
             * EXISTS allows duplicate solutions into the join set then having
             * multiple left solutions which satisfy the EXISTS filter will
             * cause multiple copies of the right solution to be output! If you
             * change the joinSet to allow duplicates, then it MUST NOT allow
             * them for EXISTS!
             */

            final IKeyBuilder keyBuilder = joinSet.getIndexMetadata()
                    .getKeyBuilder();

            final byte[] key = keyBuilder.reset().append(joinSetHashCode)
                    .getKey();

            // visit all joinSet solutions having the same hash code
            final ITupleIterator<?> xitr = joinSet.lookupAll(key);

            while (xitr.hasNext()) {

                final ITuple<?> xt = xitr.next();

                final ByteArrayBuffer b = xt.getValueBuffer();

                if (0 == BytesUtil.compareBytesWithLenAndOffset(0/* aoff */,
                        val.length/* alen */, val/* a */, 0/* boff */,
                        b.limit()/* blen */, b.array())) {

                    return;

                }

            }
            
        }

        joinSet.insert(joinSetHashCode, val);

    }
    
    @Override
    public void outputOptionals(final IBuffer<IBindingSet> outputBuffer) {

        try {

            @SuppressWarnings({ "rawtypes", "unchecked" })
            final Constant f = askVar == null ? null : new Constant(
                    XSDBooleanIV.FALSE);

            if (log.isInfoEnabled()) {
                final HTree htree = this.getRightSolutions();
                log.info("rightSolutions: #nnodes=" + htree.getNodeCount()
                        + ",#leaves=" + htree.getLeafCount() + ",#entries="
                        + htree.getEntryCount());

                final HTree joinSet = this.getJoinSet();
                log.info("joinSet: #nnodes=" + joinSet.getNodeCount()
                        + ",#leaves=" + joinSet.getLeafCount() + ",#entries="
                        + joinSet.getEntryCount());
            }

            final HTree joinSet = getJoinSet();

            final IKeyBuilder keyBuilder = joinSet.getIndexMetadata()
                    .getKeyBuilder();

            // Visit all source solutions.
            final ITupleIterator<?> sitr = getRightSolutions().rangeIterator();

            while (sitr.hasNext()) {

                final ITuple<?> t = sitr.next();

                final ByteArrayBuffer tb = t.getValueBuffer();

                /*
                 * Note: This MUST be treated as effectively immutable since we
                 * may have to output multiple solutions for each rightSolution.
                 * Those output solutions MUST NOT side-effect [rightSolutions].
                 */
                final IBindingSet rightSolution = decodeSolution(t);

                // The hash code is based on the entire solution for the
                // joinSet.
                final int hashCode = rightSolution.hashCode();

                final byte[] key = keyBuilder.reset().append(hashCode).getKey();

                // Probe the join set for this source solution.
                final ITupleIterator<?> jitr = joinSet.lookupAll(key);

                boolean found = false;
                while (jitr.hasNext()) {

                    // Note: Compare full solutions, not just the hash code!

                    final ITuple<?> xt = jitr.next();

                    final ByteArrayBuffer xb = xt.getValueBuffer();

                    if (0 == BytesUtil.compareBytesWithLenAndOffset(
                            0/* aoff */, tb.limit()/* alen */,
                            tb.array()/* a */, 0/* boff */,
                            xb.limit()/* blen */, xb.array())) {

                        found = true;

                        break;

                    }

                }

                if (!found) {

                    /*
                     * Since the source solution is not in the join set, output
                     * it as an optional solution.
                     */

                    IBindingSet bs = rightSolution;

                    if (selectVars != null) {

                        // Drop variables which are not projected.
                        bs = bs.copy(selectVars);

                    }

                    encoder.resolveCachedValues(bs);

                    if (f != null) {

                        if (bs == rightSolution)
                            bs = rightSolution.clone();

                        bs.set(askVar, f);

                    }

                    outputBuffer.add(bs);

                }

            }

        } catch (Throwable t) {

            throw launderThrowable(t);
        }
        
    } // outputOptionals.

    @SuppressWarnings("unchecked")
    @Override
    public ICloseableIterator<IBindingSet> indexScan() {

        final HTree rightSolutions = getRightSolutions();

        if (log.isInfoEnabled()) {
            log.info("rightSolutions: #nnodes="
                    + rightSolutions.getNodeCount() + ",#leaves="
                    + rightSolutions.getLeafCount() + ",#entries="
                    + rightSolutions.getEntryCount());
        }

        // source.
        final ITupleIterator<?> solutionsIterator = rightSolutions
                .rangeIterator();

        IStriterator itr = new Striterator(solutionsIterator);

        /**
         * Add resolution step.
         */
        itr = itr.addFilter(new Resolver(){

            private static final long serialVersionUID = 1L;

            @Override
            protected Object resolve(Object obj) {
                
                final ITuple<?> t = ((ITuple<?>) obj);
                
                // Decode the solution.
                IBindingSet bset = decodeSolution(t);

//                if (selectVars != null) {
//
//                    // Drop variables which are not projected.
//                    bset = bset.copy(selectVars);
//
//                }

                // Resolve ivCache associations.
                encoder.resolveCachedValues(bset);

                return bset;
                
            }
            
        });
        
        return (ICloseableIterator<IBindingSet>) itr;
        
    }

    /**
     * DISTINCT solutions filter for
     * {@link HTreeHashJoinUtility#outputSolutions(IBuffer)}
     * 
     * @see <a href="https://sourceforge.net/apps/trac/bigdata/ticket/668" >
     *      JoinGroup optimizations </a>
     */
    private class HTreeDistinctFilter implements IDistinctFilter {

        /**
         * The variables used to impose a distinct constraint.
         */
        private final IVariable<?>[] vars;

        private final HTreeHashJoinUtility state;

        public HTreeDistinctFilter(final IVariable<?>[] vars, final PipelineOp op) {

            this.vars = vars;

            this.state = new HTreeHashJoinUtility(
                    ((MemStore) store).getMemoryManager(), op,
                    JoinTypeEnum.Filter);

        }
        
        @Override
        public IVariable<?>[] getProjectedVars() {

            return vars;
            
        }

        @Override
        public IBindingSet accept(final IBindingSet bset) {
            // FIXME Auto-generated method stub
            throw new UnsupportedOperationException();
        }

        @Override
        public long filterSolutions(ICloseableIterator<IBindingSet[]> itr,
                BOpStats stats, IBuffer<IBindingSet> sink) {
            // FIXME Auto-generated method stub
            throw new UnsupportedOperationException();
        }
        
        @Override
        public void release() {

            state.release();
            
        }

    }
    
    @Override
    public void outputSolutions(final IBuffer<IBindingSet> out) {

        try {

            /*
             * FIXME Set this to enable "DISTINCT" on the solutions flowing into the
             * join group.
             * 
             * Note: This should be set by the HashIndexOp (or passed in through the
             * interface).
             * 
             * @see <a href="https://sourceforge.net/apps/trac/bigdata/ticket/668" >
             * JoinGroup optimizations </a>
             */
            final boolean distinct = false;
            
            /*
             * FIXME Replace with an HTreeDistinctFilter and integrate to NOT
             * flow duplicate solutions into the sub-group. The HTree
             * filterSolutions() method needs to be vectored to be efficient.
             * Therefore, this outputSolutions() method needs to be rewritten to
             * be vectored as well. It is efficient in reading the solutions
             * from the HTree, and the solutions are in the "natural" order of
             * the HTree for the join vars. This order SHOULD be pretty
             * efficient for the DISTINCT solutions set as well, but note that
             * joinVars:=projectedInVars. To maximize the corrleation, both the
             * joinVars[] and the projectedInVars[] should be sorted so the
             * variables in the solutions will be correllated and any variables
             * that are NOT in the projectedInVars should appear towards the end
             * of the joinVars where they will cause the least perturbation in
             * this scan + filter.
             */
            final IDistinctFilter distinctFilter;
            
            if (distinct && projectedInVars != null && projectedInVars.length > 0) {

                /*
                 * Note: We are single threaded here so we can use a lower
                 * concurrencyLevel value.
                 * 
                 * Note: If necessary, this could be replaced with JVMHashIndex so
                 * we get the #of occurrences of each distinct combination of
                 * bindings that is projected into the sub-group/-query.
                 */
                final int concurrencyLevel = 1;//ConcurrentHashMapAnnotations.DEFAULT_CONCURRENCY_LEVEL;

                distinctFilter = new JVMDistinctFilter(projectedInVars, //
                        op.getProperty(HashMapAnnotations.INITIAL_CAPACITY,
                                HashMapAnnotations.DEFAULT_INITIAL_CAPACITY),//
                        op.getProperty(HashMapAnnotations.LOAD_FACTOR,
                                HashMapAnnotations.DEFAULT_LOAD_FACTOR),//
                                concurrencyLevel
                );
                
            } else {
             
                distinctFilter = null;
                
            }
            
           final HTree rightSolutions = getRightSolutions();

            if (log.isInfoEnabled()) {
                log.info("rightSolutions: #nnodes="
                        + rightSolutions.getNodeCount() + ",#leaves="
                        + rightSolutions.getLeafCount() + ",#entries="
                        + rightSolutions.getEntryCount());
            }

            // source.
            final ITupleIterator<?> solutionsIterator = rightSolutions
                    .rangeIterator();

            while (solutionsIterator.hasNext()) {

                final ITuple<?> t = solutionsIterator.next();

                IBindingSet bset = decodeSolution(t);

                if (distinctFilter != null) {

                    /*
                     * Note: The DISTINCT filter is based on the variables
                     * that are projected INTO the child join group.
                     * However, those are NOT always the same as the
                     * variables that are projected OUT of the child join
                     * group, so we need to
                     */

                    if ((bset = distinctFilter.accept(bset)) == null) {

                        // Drop duplicate solutions.
                        continue;

                    }

                } else if (selectVars != null) {

                    /*
                     * FIXME We should be using projectedInVars here since
                     * outputSolutions() is used to stream solutions into
                     * the child join group (at least for some kinds of
                     * joins, but there might be exceptions for joining with
                     * a named solution set).
                     */

                    // Drop variables which are not projected.
                    bset = bset.copy(selectVars);

                }

                encoder.resolveCachedValues(bset);

                out.add(bset);

            }

        } catch (Throwable t) {

            throw launderThrowable(t);

        }

    } // outputSolutions

    @Override
    public void outputJoinSet(final IBuffer<IBindingSet> out) {

        try {

            @SuppressWarnings({ "rawtypes", "unchecked" })
            final Constant t = askVar == null ? null : new Constant(
                    XSDBooleanIV.TRUE);
            
            final HTree joinSet = getJoinSet();

            if (log.isInfoEnabled()) {
                log.info("joinSet: #nnodes="
                        + joinSet.getNodeCount() + ",#leaves="
                        + joinSet.getLeafCount() + ",#entries="
                        + joinSet.getEntryCount());
            }

            // source.
            final ITupleIterator<?> solutionsIterator = joinSet
                    .rangeIterator();

            while (solutionsIterator.hasNext()) {

                IBindingSet bset = decodeSolution(solutionsIterator.next());

                if (selectVars != null) {

                    // Drop variables which are not projected.
                    bset = bset.copy(selectVars);

                }

                if (t != null) {

                    if (selectVars == null)
                        bset = bset.clone();

                    bset.set(askVar, t);

                }

                encoder.resolveCachedValues(bset);

                out.add(bset);

            }

        } catch (Throwable t) {

            throw launderThrowable(t);

        }

    } // outputJoinSet

    /**
     * {@inheritDoc}
     * <p>
     * Note: For the {@link HTree}, the entries are in key order. Those keys are
     * hash codes computed from the solutions using the join variables. Since
     * the keys are hash codes and not the join variable bindings, each hash
     * code identifies a collision bucket from the perspective of the merge join
     * algorithm. Of course, from the perspective of the {@link HTree} those
     * solutions are just consequective tuples readily identified using
     * {@link HTree#lookupAll(int)}.
     * 
     * FIXME Either always project everything or raise [select] into a parameter
     * for this method. We DO NOT want to only project whatever was projected by
     * the first source.
     */
    @Override
    public void mergeJoin(
			//
			final IHashJoinUtility[] others,
			final IBuffer<IBindingSet> outputBuffer,
			final IConstraint[] constraints, final boolean optional) {

        try {
        
		/*
		 * Validate arguments.
		 */

		if (others == null)
			throw new IllegalArgumentException();

		if (others.length == 0)
			throw new IllegalArgumentException();

		if (outputBuffer == null)
			throw new IllegalArgumentException();
		
		final HTreeHashJoinUtility[] all = new HTreeHashJoinUtility[others.length + 1];
		{
			all[0] = this;
			for (int i = 0; i < others.length; i++) {
				final HTreeHashJoinUtility o = (HTreeHashJoinUtility) others[i];
				if (o == null)
					throw new IllegalArgumentException();
                if (!Arrays.equals(joinVars, o.joinVars)) {
					// Must have the same join variables.
					throw new IllegalArgumentException();
				}
				all[i + 1] = o;
			}

		}

		if (isEmpty()) {
			// NOP
			return;
		}

		/*
		 * Combine constraints for each source with the given constraints.
		 */
		final IConstraint[] c = JVMHashJoinUtility.combineConstraints(
				constraints, all);

		/*
		 * MERGE JOIN
		 * 
		 * We follow the iterator on the first source. For each hash code which
		 * it visits, we synchronize iterators against the remaining sources. If
		 * the join is optional, then the iterator will be null for a source
		 * which does not have that hash code. Otherwise false is returned if
		 * any source lacks tuples for the current hash code.
		 */
		long njoined = 0, nrejected = 0;
		{

            // if optional then if there are no solutions don't try and
            // expand further, we need a place-holder object
            final Object NULL_VALUE = "NULL";

            final int nsources = all.length;
            
            final ITuple<?>[] set = new ITuple<?>[nsources + 1];

            // Visit everything in the first source.
			final Striterator sols0 = new Striterator(all[0].getRightSolutions()
					.rangeIterator());
			{

			    sols0.addFilter(new Visitor() {
                    private static final long serialVersionUID = 1L;
                    /**
                     * Set the tuple for the first source each time it advances.
                     * 
                     * @param obj
                     *            The tuple.
                     */
                    @Override
                    protected void visit(final Object obj) {
                        set[0] = (ITuple<?>) obj;
                    }

                });

                // now add in Expanders and Visitors for each remaining source.
                for (int i = 1; i < nsources; i++) {
                    // Final variables used inside inner classes.
                    final int slot = i;
                    final HTree thisTree = all[slot].getRightSolutions();

					sols0.addFilter(new Expander() {
	                    private static final long serialVersionUID = 1L;
                        /**
                         * Expansion pattern gives solutions for source @ slot.
                         * 
                         * @param obj
                         *            The tuple in set[slot-1].
                         */
						@Override
						protected Iterator<?> expand(final Object obj) {
							if (obj == NULL_VALUE) {
								assert optional;
								return new SingleValueIterator(NULL_VALUE);
							}
							// Sync itr for this source to key for prior src.
							final byte[] key2 = ((ITuple<?>) obj).getKey();
							final ITupleIterator<?> ret = thisTree.lookupAll(key2);
							if (optional && !ret.hasNext()) {
                                /*
                                 * Nothing for that key from this source. Return
                                 * a single marker value so we can proceed to
                                 * the remaining sources rather than halting.
                                 */
								return new SingleValueIterator(NULL_VALUE);
							} else {
                                /*
                                 * Iterator visiting solutions from this source
                                 * for the current key in the prior source.
                                 */
								return ret;
							}
						}

					});

					sols0.addFilter(new Visitor() {
	                    private static final long serialVersionUID = 1L;
                        /**
                         * Assign tuple to set[slot].
                         * 
                         * Note: If [obj==NULL_VALUE] then no solutions for that
                         * slot.
                         */
						@Override
						protected void visit(final Object obj) {
							set[slot] = (ITuple<?>) (obj == NULL_VALUE ? null : obj);
						}

					});
			    }
			} // end of striterator setup.

            /*
             * This will visit after all expansions. That means that we will
             * observe the cross product of the solutions from the remaining
             * sources having the same hash for each from the first source.
             * 
             * Each time we visit something, set[] is the tuple[] which
             * describes a specific set of solutions from that cross product.
             * 
             * TODO Lift out the decodeSolution() for all slots into the
             * expander pattern.
             */
			while (sols0.hasNext()) {
				sols0.next();
				IBindingSet in = all[0].decodeSolution(set[0]);
				// FIXME apply constraint to source[0] (JVM version also).
				for (int i = 1; i < set.length; i++) {

					// See if the solutions join.
                    final IBindingSet left = in;
					if (set[i] != null) {
						final IBindingSet right = all[i].decodeSolution(set[i]); 
						in = BOpContext.bind(//
								left,// 
								right,// 
								c,// TODO constraint[][]
								null//
								);
					}

					if (in == null) {
//					    if(optional) {
//					        in = left;
//					        continue;
//					    }
						// Join failed.
						break;
					}

				}
				// Accept this binding set.
				if (in != null) {
					if (log.isDebugEnabled())
						log.debug("Output solution: " + in);
                    encoder.resolveCachedValues(in);
					outputBuffer.add(in);
				}

//				// now clear set!
//				for (int i = 1; i < set.length; i++) {
//					set[i] = null;
//				}
				
			}

		}
		
        } catch(Throwable t) {
            throw launderThrowable(t);
        }

	}    

    /**
     * Adds metadata about the {@link IHashJoinUtility} state to the stack
     * trace.
     * 
     * @param t
     *            The thrown error.
     * 
     * @return The laundered exception.
     * 
     * @throws Exception
     * 
     * @see http://sourceforge.net/apps/trac/bigdata/ticket/508 (LIMIT causes
     *      hash join utility to log errors)
     */
    private RuntimeException launderThrowable(final Throwable t) {

        final String msg = "cause=" + t + ", state=" + toString();

        if (!InnerCause.isInnerCause(t, InterruptedException.class)
                && !InnerCause.isInnerCause(t, BufferClosedException.class)) {

            /*
             * Some sort of unexpected exception.
             */
            
            log.error(msg, t);

        }

        return new RuntimeException(msg, t);

    }

}
