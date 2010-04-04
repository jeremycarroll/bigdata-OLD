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
/*
 * Created on Oct 30, 2007
 */

package com.bigdata.rdf.inf;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.log4j.Logger;

import com.bigdata.rdf.lexicon.ITermIdFilter;
import com.bigdata.rdf.model.StatementEnum;
import com.bigdata.rdf.rules.InferenceEngine;
import com.bigdata.rdf.spo.ExplicitSPOFilter;
import com.bigdata.rdf.spo.ISPO;
import com.bigdata.rdf.spo.SPO;
import com.bigdata.rdf.spo.SPOKeyOrder;
import com.bigdata.rdf.spo.SPOKeyOrderProvider;
import com.bigdata.rdf.spo.SPORelation;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.IRawTripleStore;
import com.bigdata.relation.accesspath.IAccessPath;
import com.bigdata.striterator.ChunkedArrayIterator;
import com.bigdata.striterator.IChunkedIterator;
import com.bigdata.striterator.IChunkedOrderedIterator;
import com.bigdata.striterator.ICloseableIterator;
import com.bigdata.striterator.IKeyOrder;

import cutthecrap.utils.striterators.Filter;
import cutthecrap.utils.striterators.IFilter;
import cutthecrap.utils.striterators.Resolver;
import cutthecrap.utils.striterators.Striterator;

/**
 * Provides backward chaining for (x rdf:type rdfs:Resource).
 * <p>
 * Note: You only need to do this on read from a high level query language since
 * the rest of the RDFS rules will run correctly without the (x rdf:type
 * rdfs:Resource) entailments being present. Further, you only need to do this
 * when the {@link InferenceEngine} was instructed to NOT store the (x rdf:type
 * rdfs:Resource) entailments.
 * <p>
 * Note: This iterator will NOT generate an inferred (x rdf:type rdfs:Resource)
 * entailment iff there is an explicit statement (x rdf:type rdfs:Resource) in
 * the database.
 * 
 * @see InferenceEngine
 * @see InferenceEngine.Options
 *
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */ 
public class BackchainTypeResourceIterator implements IChunkedOrderedIterator<ISPO> {

    protected static final Logger log = Logger.getLogger(BackchainTypeResourceIterator.class);
    
    protected final static transient long NULL = IRawTripleStore.NULL;
    
    private final IChunkedOrderedIterator<ISPO> _src;
    private final Iterator<ISPO> src;
//    private final long s;
//    private final AbstractTripleStore db;
    private final long rdfType, rdfsResource;
    private final IKeyOrder<ISPO> keyOrder;
    private final AbstractTripleStore db;
    /**
     * The subject(s) whose (s rdf:type rdfs:Resource) entailments will be
     * visited.
     */
    private PushbackIterator<Long> resourceIds;
    
    /**
     * An iterator reading on the {@link SPOKeyOrder#POS} index. The predicate
     * is bound to <code>rdf:type</code> and the object is bound to
     * <code>rdfs:Resource</code>. If the subject was given to the ctor, then
     * it will also be bound. The iterator visits the term identifier for the
     * <em>subject</em> position.
     */
    private PushbackIterator<Long> posItr;
    
    private boolean sourceExhausted = false;
    
    private boolean open = true;

    /**
     * This is set each time by {@link #nextChunk()} and inspected by
     * {@link #nextChunk(IKeyOrder)} in order to decide whether the chunk needs
     * to be sorted.
     */
    private IKeyOrder<ISPO> chunkKeyOrder = null; 

    /**
     * The last {@link ISPO} visited by {@link #next()}.
     */
    private ISPO current = null;

    /**
     * Returns a suitably configured {@link BackchainTypeResourceIterator} -or-
     * <i>src</i> iff the <i>accessPath</i> does not require the
     * materialization of <code>(x rdf:type rdfs:Resource)</code> entailments.
     * 
     * @param _src
     *            The source iterator. {@link #nextChunk()} will sort statements
     *            into the {@link IKeyOrder} reported by this iterator (as long
     *            as the {@link IKeyOrder} is non-<code>null</code>).
     * @param accessPath
     *            The {@link IAccessPath} from which the <i>src</i> iterator
     *            was derived. Note that <i>src</i> is NOT necessarily
     *            equivalent to {@link IAccessPath#iterator()} since it MAY have
     *            been layered already to backchain other entailments, e.g.,
     *            <code>owl:sameAs</code>.
     * @param db
     *            The database from which we will read the distinct subject
     *            identifiers from its {@link SPORelation}. This parameter is
     *            used iff this is an all unbound triple pattern.
     * @param rdfType
     *            The term identifier that corresponds to rdf:Type for the
     *            database.
     * @param rdfsResource
     *            The term identifier that corresponds to rdf:Resource for the
     *            database.
     * 
     * @return The backchain iterator -or- the <i>src</i> iterator iff the
     *         <i>accessPath</i> does not require the materialization of
     *         <code>(x rdf:type rdfs:Resource)</code> entailments.
     */
    @SuppressWarnings("unchecked")
    static public IChunkedOrderedIterator<ISPO> newInstance(
            final IChunkedOrderedIterator<ISPO> _src,
            final IAccessPath<ISPO> accessPath, final AbstractTripleStore db,
            final long rdfType, final long rdfsResource) {
        
        if (accessPath == null)
            throw new IllegalArgumentException();
        
        final SPO spo = new SPO(accessPath.getPredicate());

        if (((spo.o == NULL || spo.o == rdfsResource) && 
             (spo.p == NULL || spo.p == rdfType)) == false) {
            
            /*
             * Backchain will not generate any statements.
             */

            return _src;
            
        }
        if (_src == null)
            throw new IllegalArgumentException();
        
        if (db == null)
            throw new IllegalArgumentException();
        
        /*
         * The subject(s) whose (s rdf:type rdfs:Resource) entailments will be
         * visited.
         */
        final PushbackIterator<Long> resourceIds;
        
        /*
         * An iterator reading on the {@link SPOKeyOrder#POS} index. The
         * predicate is bound to <code>rdf:type</code> and the object is bound
         * to <code>rdfs:Resource</code>. If the subject was given to the
         * ctor, then it will also be bound. The iterator visits the term
         * identifier for the <em>subject</em> position.
         */
        final PushbackIterator<Long> posItr;

        if (spo.s == NULL) {

            /*
             * Backchain will generate one statement for each distinct subject
             * or object in the store.
             * 
             * @todo This is Ok as long as you are forward chaining all of the
             * rules that put a predicate or an object into the subject position
             * since it will then have all resources. If you backward chain some
             * of those rules, e.g., rdf1, then you MUST change this to read on
             * the ids index and skip anything that is marked as a literal using
             * the low bit of the term identifier but you will overgenerate for
             * resources that are no longer in use by the KB (you could filter
             * for that).
             */

//            resourceIds = db.getSPORelation().distinctTermScan(SPOKeyOrder.getSubjectFirstKeyOrder(false));
            
            resourceIds = new PushbackIterator<Long>(new MergedOrderedIterator(//
                    db.getSPORelation().distinctTermScan(SPOKeyOrderProvider.getKeyOrderProvider(db.getNamespace()).getSubjectFirstKeyOrder(false)), //
                    db.getSPORelation().distinctTermScan(SPOKeyOrderProvider.getKeyOrderProvider(db.getNamespace()).getObjectFirstKeyOrder(false),
                            new ITermIdFilter() {
                                private static final long serialVersionUID = 1L;
                                public boolean isValid(long termId) {
                                    // filter out literals from the OSP scan.
                                    return !AbstractTripleStore
                                            .isLiteral(termId);
                                }
                            })));

            /*
             * Reading (? rdf:Type rdfs:Resource) using the POS index.
             */

            posItr = new PushbackIterator<Long>(new Striterator(db.getAccessPath(
                    NULL, rdfType, rdfsResource,
                            ExplicitSPOFilter.INSTANCE).iterator())
                    .addFilter(new Resolver() {
                        private static final long serialVersionUID = 1L;
                        @Override
                        protected Object resolve(Object obj) {
                            return Long.valueOf(((SPO) obj).s);
                        }
                    }));

        } else {

            /*
             * Backchain will generate exactly one statement: (s rdf:type
             * rdfs:Resource).
             */
/*
            resourceIds = new PushbackIterator<Long>(
                    new ClosableSingleItemIterator<Long>(spo.s));
*/
            /*
             * Reading a single point (s type resource), so this will actually
             * use the SPO index.
             */
/*
            posItr = new PushbackIterator<Long>(new Striterator(db
                    .getAccessPath(spo.s, rdfType, rdfsResource,
                            ExplicitSPOFilter.INSTANCE).iterator())
                    .addFilter(new Resolver() {
                        private static final long serialVersionUID = 1L;
                        @Override
                        protected Object resolve(Object obj) {
                            return Long.valueOf(((SPO) obj).s);
                        }
            }));
*/            
            return new BackchainSTypeResourceIterator
                ( _src, accessPath, db, rdfType, rdfsResource
                  );

        }
        
        /*
         * filters out (x rdf:Type rdfs:Resource) in case it is explicit in the
         * db so that we do not generate duplicates for explicit type resource
         * statement.
         */
        final Iterator<ISPO> src = new Striterator(_src).addFilter(new Filter(){

            private static final long serialVersionUID = 1L;

            protected boolean isValid(Object arg0) {

                final SPO o = (SPO) arg0;

                if (o.p == rdfType && o.o == rdfsResource) {
                    
                    return false;
                    
                }
                
                return true;
                
            }});
        
        return new BackchainTypeResourceIterator(db,_src, src, resourceIds,
                posItr, rdfType, rdfsResource);
        
    }
    
    /**
     * Create an iterator that will visit all statements in the source iterator
     * and also backchain any entailments of the form (x rdf:type rdfs:Resource)
     * which are valid for the given triple pattern.
     * 
     * @param src
     *            The source iterator. {@link #nextChunk()} will sort statements
     *            into the {@link IKeyOrder} reported by this iterator (as long
     *            as the {@link IKeyOrder} is non-<code>null</code>).
     * @param db
     *            The database from which we will read the distinct subject
     *            identifiers (iff this is an all unbound triple pattern).
     * @param rdfType
     *            The term identifier that corresponds to rdf:Type for the
     *            database.
     * @param rdfsResource
     *            The term identifier that corresponds to rdf:Resource for the
     *            database.
     * 
     * @see #newInstance(IChunkedOrderedIterator, IAccessPath,
     *      AbstractTripleStore, long, long)
     */
    @SuppressWarnings({ "unchecked", "serial" })
    private BackchainTypeResourceIterator(AbstractTripleStore db,//
    		IChunkedOrderedIterator<ISPO> _src,//
            Iterator<ISPO> src,//
            PushbackIterator<Long> resourceIds,//
            PushbackIterator<Long> posItr,//
            final long rdfType,//
            final long rdfsResource//
            ) {
        this.db=db;
        // the raw source - we pass close() through to this.
        this._src = _src;
        
        this.keyOrder = _src.getKeyOrder(); // MAY be null.
        
        // the source with (x type resource) filtered out.
        this.src = src;
        
        // 
        this.resourceIds = resourceIds;
        
        this.posItr = posItr;
        
        this.rdfType = rdfType;
        
        this.rdfsResource = rdfsResource;
                
    }

    public IKeyOrder<ISPO> getKeyOrder() {

        return keyOrder;
        
    }

    public void close() {

        if(!open) return;
        
        // release any resources here.
        
        open = false;

        _src.close();

        resourceIds.close();
        
        resourceIds = null;
        
        if (posItr != null) {

            posItr.close();
            
        }
        
    }

    public boolean hasNext() {
        
        if (!open) {

            // the iterator has been closed.
            
            return false;
            
        }

        if (!sourceExhausted) {

            if (src.hasNext()) {

                // still consuming the source iterator.

                return true;

            }

            // the source iterator is now exhausted.

            sourceExhausted = true;

            _src.close();

        }

        if (resourceIds.hasNext()) {

            // still consuming the subjects iterator.
            
            return true;
            
        }
        
        // the subjects iterator is also exhausted so we are done.
        
        return false;
        
    }

    /**
     * Visits all {@link SPO}s visited by the source iterator and then begins
     * to backchain ( x rdf:type: rdfs:Resource ) statements.
     * <p>
     * The "backchain" scans two iterators: an {@link IChunkedOrderedIterator}
     * on <code>( ? rdf:type
     * rdfs:Resource )</code> that reads on the database
     * (this tells us whether we have an explicit
     * <code>(x rdf:type rdfs:Resource)</code> in the database for a given
     * subject) and iterator that reads on the term identifiers for the distinct
     * resources in the database (this bounds the #of backchained statements
     * that we will emit).
     * <p>
     * For each value visited by the {@link #resourceIds} iterator we examine
     * the statement iterator. If the next value that would be visited by the
     * statement iterator is an explicit statement for the current subject, then
     * we emit the explicit statement. Otherwise we emit an inferred statement.
     */
    public ISPO next() {

        if (!hasNext()) {

            throw new NoSuchElementException();
            
        }

        if (src.hasNext()) {

            return current = src.next();
            
        } else if(resourceIds.hasNext()) {

            /*
             * Examine resourceIds and posItr.
             */
            
            // resourceIds is the source for _inferences_
            final Long s1 = resourceIds.next();
            
            if(posItr.hasNext()) {
                
                // posItr is the source for _explicit_ statements.
                final Long s2 = posItr.next();
                
                final int cmp = s1.compareTo(s2);
                
                if (cmp < 0) {

                    /*
                     * Consuming from [resourceIds] (the term identifier ordered
                     * LT the next term identifier from [posItr]).
                     * 
                     * There is NOT an explicit statement from [posItr], so emit
                     * as an inference and pushback on [posItr].
                     */
                    
                    current = new SPO(s1, rdfType, rdfsResource,
                            StatementEnum.Inferred);

                    posItr.pushback();
                    
                } else {
                 
                    /*
                     * Consuming from [posItr].
                     * 
                     * There is an explicit statement for the current term
                     * identifer from [resourceIds].
                     */
                    
                    if (cmp != 0) {
                        
                        /*
                         * Since [resourceIds] and [posItr] are NOT visiting the
                         * same term identifier, we pushback on [resourceIds].
                         * 
                         * Note: When they DO visit the same term identifier
                         * then we only emit the explicit statement and we
                         * consume (rather than pushback) from [resourceIds].
                         */
                        
                        resourceIds.pushback();
                        
                    }
                    
                    current = new SPO(s2, rdfType, rdfsResource,
                            StatementEnum.Explicit);

                }
                
            } else {
                
                /*
                 * [posItr] is exhausted so just emit inferences based on
                 * [resourceIds].
                 */
                
                current = new SPO(s1, rdfType, rdfsResource,
                        StatementEnum.Inferred);
            
            }

            return current;

        } else {
            
            /*
             * Finish off the [posItr]. Anything from this source is an explicit (?
             * type resource) statement.
             */
            
            assert posItr.hasNext();
            
            return new SPO(posItr.next(), rdfType, rdfsResource,
                    StatementEnum.Explicit);
            
        }
        
    }

    /**
     * Note: This method preserves the {@link IKeyOrder} of the source iterator
     * iff it is reported by {@link #getKeyOrder()}. Otherwise chunks read from
     * the source iterator will be in whatever order that iterator is using
     * while chunks containing backchained entailments will be in
     * {@link SPOKeyOrder#POS} order.
     * <p>
     * Note: In order to ensure that a consistent ordering is always used within
     * a chunk the backchained entailments will always begin on a chunk
     * boundary.
     */
    public ISPO[] nextChunk() {

        final int chunkSize = 10000;
        
        if (!hasNext())
            throw new NoSuchElementException();
        
        if(!sourceExhausted) {
            
            /*
             * Return a chunk from the source iterator.
             * 
             * Note: The chunk will be in the order used by the source iterator.
             * If the source iterator does not report that order then
             * [chunkKeyOrder] will be null.
             */
            
            chunkKeyOrder = keyOrder;

            ISPO[] s = new ISPO[chunkSize];

            int n = 0;
            
            while(src.hasNext() && n < chunkSize ) {
                
                s[n++] = src.next();
                
            }
            
            ISPO[] stmts = new ISPO[n];
            
            // copy so that stmts[] is dense.
            System.arraycopy(s, 0, stmts, 0, n);
            
            return stmts;
            
        }

        /*
         * Create a "chunk" of entailments.
         * 
         * Note: This chunk will be in natural POS order since that is the index
         * that we scan to decide whether or not there was an explicit ( x
         * rdf:type rdfs:Resource ) while we consume the [subjects] in termId
         * order.
         */
        
        long[] s = new long[chunkSize];
        
        int n = 0;
        
        while(resourceIds.hasNext() && n < chunkSize ) {
            
            s[n++] = resourceIds.next();
            
        }
        
        SPO[] stmts = new SPO[n];
        
        for(int i=0; i<n; i++) {
            
            stmts[i] = new SPO(s[i], rdfType, rdfsResource,
                    StatementEnum.Inferred);
            
        }
                
        if (keyOrder != null && keyOrder != SPOKeyOrderProvider.getKeyOrderProvider(db.getNamespace()).getPredicateFirstKeyOrder(false)) {

            /*
             * Sort into the same order as the source iterator.
             * 
             * Note: We have to sort explicitly since we are scanning the POS
             * index
             */

            Arrays.sort(stmts, 0, stmts.length, keyOrder.getComparator());

        }

        /*
         * The chunk will be in POS order since that is how we are scanning the
         * indices.
         */
        
        chunkKeyOrder = SPOKeyOrderProvider.getKeyOrderProvider(db.getNamespace()).getPredicateFirstKeyOrder(false);
        
        return stmts;
        
    }

    public ISPO[] nextChunk(IKeyOrder<ISPO> keyOrder) {
        
        if (keyOrder == null)
            throw new IllegalArgumentException();

        final ISPO[] stmts = nextChunk();
        
        if (chunkKeyOrder != keyOrder) {

            // sort into the required order.

            Arrays.sort(stmts, 0, stmts.length, keyOrder.getComparator());

        }

        return stmts;
        
    }

    /**
     * Note: You can not "remove" the backchained entailments. If the last
     * statement visited by {@link #next()} is "explicit" then the request is
     * delegated to the source iterator.
     */
    public void remove() {

        if (!open)
            throw new IllegalStateException();
        
        if (current == null)
            throw new IllegalStateException();
        
        if(current.isExplicit()) {
            
            /*
             * Delegate the request to the source iterator.
             */
            
            src.remove();
            
        }
        
        current = null;
        
    }
    
    /**
     * Reads on two iterators visiting elements in some natural order and visits
     * their order preserving merge (no duplicates).
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     * @param <T>
     */
    private static class MergedOrderedIterator<T extends Comparable<T>>
            implements IChunkedIterator<T> {
       
        private final IChunkedIterator<T> src1;
        private final IChunkedIterator<T> src2;
        
        public MergedOrderedIterator(IChunkedIterator<T> src1,
                IChunkedIterator<T> src2) {

            this.src1 = src1;
            
            this.src2 = src2;
            
        }
        
        public void close() {
            
            src1.close();
            
            src2.close();
            
        }

        /**
         * Note: Not implemented since not used above and this class is private.
         */
        public T[] nextChunk() {
            throw new UnsupportedOperationException();
        }

        public boolean hasNext() {

            return tmp1 != null || tmp2 != null || src1.hasNext()
                    || src2.hasNext();
            
        }
        
        private T tmp1;
        private T tmp2;
        
        public T next() {

            if(!hasNext()) throw new NoSuchElementException();
            
            if (tmp1 == null && src1.hasNext()) {

                tmp1 = src1.next();

            }
 
            if (tmp2 == null && src2.hasNext()) {

                tmp2 = src2.next();

            }
            
            if (tmp1 == null) {

                // src1 is exhausted so deliver from src2.
                final T tmp = tmp2;

                tmp2 = null;

                return tmp;

            }
            
            if (tmp2 == null) {

                // src2 is exhausted so deliver from src1.
                final T tmp = tmp1;

                tmp1 = null;

                return tmp;

            }

            final int cmp = tmp1.compareTo(tmp2);

            if (cmp == 0) {

                final T tmp = tmp1;

                tmp1 = tmp2 = null;

                return tmp;

            } else if (cmp < 0) {

                final T tmp = tmp1;

                tmp1 = null;

                return tmp;

            } else {

                final T tmp = tmp2;

                tmp2 = null;

                return tmp;

            }
            
        }

        public void remove() {

            throw new UnsupportedOperationException();
            
        }

    }

    /**
     * Filterator style construct that allows push back of a single visited
     * element.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     * @param <E>
     */
    public static class PushbackFilter<E> implements IFilter {

        /**
         * 
         */
        private static final long serialVersionUID = -8010263934867149205L;

        @SuppressWarnings("unchecked")
        public PushbackIterator<E> filter(Iterator src) {

            return new PushbackIterator<E>((Iterator<E>) src);

        }

    }

    /**
     * Implementation class for {@link PushbackFilter}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     * @param <E>
     */
    public static class PushbackIterator<E> implements Iterator<E>,
            ICloseableIterator<E> {

        private final Iterator<E> src;

        /**
         * The most recent element visited by the iterator.
         */
        private E current;
        
        /**
         * When non-<code>null</code>, this element was pushed back and
         * is the next element to be visited.
         */
        private E buffer;

        public PushbackIterator(Iterator<E> src) {

            if (src == null)
                throw new IllegalArgumentException();

            this.src = src;

        }

        public boolean hasNext() {

            return buffer != null || src.hasNext();

        }

        public E next() {

            if (!hasNext())
                throw new NoSuchElementException();

            final E tmp;

            if (buffer != null) {

                tmp = buffer;

                buffer = null;

            } else {

                tmp = src.next();

            }

            current = tmp;
            
            return tmp;

        }

        /**
         * Push the value onto the internal buffer. It will be returned by the
         * next call to {@link #next()}.
         * 
         * @param value
         *            The value.
         * 
         * @throws IllegalStateException
         *             if there is already a value pushed back.
         */
        public void pushback() {

            if (buffer != null)
                throw new IllegalStateException();
            
            // pushback the last visited element.
            buffer = current;
            
        }
        
        public void remove() {

            throw new UnsupportedOperationException();

        }

        public void close() {

            if(src instanceof ICloseableIterator) {

                ((ICloseableIterator<E>)src).close();
                
            }
            
        }

    }
    
    private static class BackchainSTypeResourceIterator 
        implements IChunkedOrderedIterator<ISPO> {

        private final IChunkedOrderedIterator<ISPO> _src;
        private final IAccessPath<ISPO> accessPath;
        private final AbstractTripleStore db;
        private final long rdfType;
        private final long rdfsResource;
        private final long s;
        private IChunkedOrderedIterator<ISPO> appender;
        private boolean canRemove;
        
        public BackchainSTypeResourceIterator(
            final IChunkedOrderedIterator<ISPO> _src,
            final IAccessPath<ISPO> accessPath, final AbstractTripleStore db,
            final long rdfType, final long rdfsResource) {
            this._src = _src;
            this.accessPath = accessPath;
            this.db = db;
            this.rdfType = rdfType;
            this.rdfsResource = rdfsResource;
            this.s = (Long) accessPath.getPredicate().get(0).get();
            SPO spo = new SPO(s, rdfType, rdfsResource, StatementEnum.Inferred);
            this.appender = new ChunkedArrayIterator<ISPO>
                ( 1, new SPO[] { spo },SPOKeyOrderProvider.getKeyOrderProvider(db.getNamespace()).getSubjectFirstKeyOrder(false)
                  );
        }
        
        private void testSPO(ISPO spo) {
            // do not need to append if we see it in the data
            if (spo.s() == s && spo.p() == rdfType && spo.o() == rdfsResource) {
                appender = null;
            }
        }
        
        public boolean hasNext() {
            return _src.hasNext() || (appender != null && appender.hasNext()); 
        }

        public IKeyOrder<ISPO> getKeyOrder() {
            return _src.getKeyOrder();
        }

        public ISPO[] nextChunk(IKeyOrder<ISPO> keyOrder) {
            if (_src.hasNext()) {
                ISPO[] chunk = _src.nextChunk(keyOrder);
                for (ISPO spo : chunk) {
                    testSPO(spo);
                }
                canRemove = true;
                return chunk;
            } else if (appender != null) {
                canRemove = false;
                return appender.nextChunk(keyOrder);
            }
            return null;
        }

        public ISPO next() {
            if (_src.hasNext()) {
                ISPO spo = _src.next();
                testSPO(spo);
                canRemove = true;
                return spo;
            } else if (appender != null) {
                canRemove = false;
                return appender.next();
            }
            return null;
        }

        public ISPO[] nextChunk() {
            if (_src.hasNext()) {
                ISPO[] chunk = _src.nextChunk();
                for (ISPO spo : chunk) {
                    testSPO(spo);
                }
                canRemove = true;
                return chunk;
            } else if (appender != null) {
                canRemove = false;
                return appender.nextChunk();
            }
            return null;
        }

        public void remove() {
            if (canRemove) {
                _src.remove();
            }
        }

        public void close() {
            _src.close();
        }
        
    }

}
