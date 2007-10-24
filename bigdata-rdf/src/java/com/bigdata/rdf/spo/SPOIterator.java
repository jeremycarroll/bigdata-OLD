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
 * Created on Oct 24, 2007
 */

package com.bigdata.rdf.spo;

import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import com.bigdata.btree.IEntryIterator;
import com.bigdata.rawstore.Bytes;
import com.bigdata.rdf.store.IAccessPath;
import com.bigdata.rdf.util.KeyOrder;
import com.bigdata.util.concurrent.DaemonThreadFactory;

/**
 * Iterator visits {@link SPO}s reading from a statement index. The iterator
 * optionally supports asynchronous read ahead and may be used to efficiently
 * obtain the top N statements in index order.
 * 
 * @todo write tests.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class SPOIterator implements ISPOIterator {

    static protected final Logger log = Logger.getLogger(SPOIterator.class);
    
    /**
     * The maximum #of statements that will be buffered by the iterator.
     */
    public static final transient int MAXIMUM_CAPACITY = 100 * Bytes.kilobyte32;
    
    private boolean open = true;
    
    /**
     * The object that encapsulates access to, and operations on, the statement
     * index that will be used by this iterator.
     */
    private final IAccessPath accessPath;
    
    /**
     * The maximum #of statements to read from the index -or- ZERO (0) if all
     * statements will be read.
     */
    private final int limit;
    
    /**
     * The actual capacity of the buffer (never zero).
     */
    private final int capacity;
    
    /**
     * The #of statements that have been read <strong>from the source</strong>.
     */
    private int numReadFromSource;

    /**
     * The #of statements that have been read by the caller using
     * {@link #next()}.
     */
    private int numReadByCaller;
    
    /**
     * Identifies the statement index that is being traversed.
     */
    private final KeyOrder keyOrder;
    
    /**
     * A buffer holding {@link SPO}s that have not been visited. Statements
     * that have been visited are taken from the buffer, making room for new
     * statements which can be filled in asynchronously by the {@link Reader}.
     */
    private ArrayBlockingQueue<SPO> buffer;
    
    /**
     * The source iterator reading on the selected statement index.
     */
    private IEntryIterator src;
    
    /**
     * The executor service for the {@link Reader} (iff the {@link Reader} runs
     * asynchronously).
     */
    private ExecutorService readService;
    
    /**
     * Set to true iff an asynchronous {@link Reader} is used AND there is
     * nothing more to be read.
     */
    private AtomicBoolean readerDone = new AtomicBoolean(false);

    /**
     * The minimum desirable chunk size for {@link #nextChunk()}.
     */
    final int MIN_CHUNK_SIZE = 50000;
    
    /**
     * If NO results show up within this timeout then {@link #nextChunk()} will
     * throw a {@link RuntimeException} to abort the reader - the probably cause
     * is a network outage.
     */
    final long TIMEOUT = 3000;
    
    /**
     * Create an {@link SPOIterator} that buffers an iterator reading from one
     * of the statement indices.
     * 
     * @param accessPath
     *            The access path to be used by the iterator (including the from
     *            and to keys formed from a triple pattern).
     * @param limit
     *            The maximum #of statements that will be read from the index
     *            -or- ZERO (0) to read all statements.
     * @param capacity
     *            The maximum #of statements that will be buffered. When ZERO
     *            (0) the iterator will range count the access path fully buffer
     *            if there are less than {@link #MAXIMUM_CAPACITY} statements
     *            selected by the triple pattern.
     *            <p>
     *            Note: fully buffering the iterator is extremely important if
     *            you want to #reset
     * 
     * @param async
     *            When true, asynchronous read-ahead will be used to refill the
     *            buffer as it becomes depleted. When false, read-ahead will be
     *            synchronous (this is useful when you want to read at most N
     *            statements from the index).
     */
    public SPOIterator(IAccessPath accessPath, int limit, int capacity, boolean async) {

        assert accessPath != null;
        
        assert limit >= 0;
        
        assert capacity >= 0;
        
        this.limit = limit;
        
        if(limit != 0) {
            
            capacity = limit;
            
        }
        
        if(capacity == 0) {

            /*
             * Range count the index and fully buffer the statements.
             */
            
            capacity = accessPath.rangeCount();
            
        }
        
        if (capacity > MAXIMUM_CAPACITY) {

            /*
             * If the capacity would exceed the maximum then we limit
             * the capacity to the maximum.
             */
            
            capacity = MAXIMUM_CAPACITY;

        }

        this.capacity = capacity;
        
        this.accessPath = accessPath;

        this.keyOrder = accessPath.getKeyOrder();
        
        this.src = accessPath.rangeQuery();
        
        this.buffer = new ArrayBlockingQueue<SPO>(capacity);

        if(async) {
            
            readService = Executors.newSingleThreadExecutor(DaemonThreadFactory
                    .defaultThreadFactory());

            readService.submit(new Reader());
            
        } else {
            
            /*
             * Pre-fill the buffer.
             */
            
            readService = null;
            
            fillBuffer();
            
        }
        
    }

    /**
     * Reads from the statement index, filling the buffer.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    private class Reader implements Callable<Object> {

        public Object call() throws Exception {
       
            while (src.hasNext()) {

                if(limit != 0 && numReadFromSource == limit) {
                    
                    // We have read all that we are going to read.

                    readerDone.set(true);
                    
                    return false;
                    
                }

                Object val = src.next();

                numReadFromSource++;
                
                SPO spo = new SPO(keyOrder, src.getKey(), val);

                try {

                    /*
                     * Note: This will block if the buffer is at capacity.
                     */
                    
                    buffer.put(spo);

                } catch (InterruptedException ex) {

                    throw new RuntimeException(ex);

                }

            }

            // Nothing left to read.
            
            readerDone.set(true);

            return null;
            
        }
               
    }
    
    /**
     * (Re-)fills the buffer up to its capacity or the exhaustion of the source
     * iterator.
     * 
     * @return false if the buffer is still empty.
     */
    private boolean fillBuffer() {
        
        log.info("(Re-)filling buffer: remainingCapacity="
                + buffer.remainingCapacity());
        
        while (src.hasNext() && buffer.remainingCapacity() > 0) {

            if(limit != 0 && numReadFromSource == limit) {
                
                // We have read all that we are going to read.

                log.info("Reached limit="+limit);

                return false;
                
            }
            
            Object val = src.next();

            numReadFromSource++;
            
            SPO spo = new SPO(keyOrder, src.getKey(), val);

            try {

                buffer.put(spo);

            } catch (InterruptedException ex) {

                throw new RuntimeException(ex);

            }

        }

        log.info("(Re-)filled buffer: size=" + buffer.size()
                + ", remainingCapacity=" + buffer.remainingCapacity());

        // false if the buffer is still empty.
            
        return ! buffer.isEmpty();
        
    }
    
    public boolean hasNext() {
        
        if(!open) return false;
        
        if (buffer.isEmpty() && !fillBuffer()) {

            return false;
            
        }

        return true;
        
    }

    public SPO next() {

        assertOpen();
        
        if (!hasNext()) {

            throw new NoSuchElementException();
        
        }
        
        final SPO spo;
        
        try {
            
            spo = buffer.take();
            
        } catch(InterruptedException ex) {
            
            throw new RuntimeException(ex);
            
        }
        
        numReadByCaller++;
        
        return spo;
        
    }

    /**
     * Returns a chunk whose size is the #of statements currently in the buffer.
     * <p>
     * Note: When asynchronous reads are used, the buffer will be transparently
     * refilled and should be ready for a next chunk by the time you are done
     * with this one.
     */
    public SPO[] nextChunk() {
        
        assertOpen();

        awaitReader();
        
        // there are at least this many in the buffer.
        
        final int n = buffer.size();
        
        // allocate the array.
        
        SPO[] stmts = new SPO[ n ];
        
        for(int i=0; i<n; i++) {
            
            stmts[ i ] = next();
            
        }
        
        return stmts;
        
    }

    /**
     * Await some data from the reader (returns immediately if we are not using
     * an asynchronous reader). If there is some data available immediately,
     * this will continue to wait until at least {@link #MIN_CHUNK_SIZE}
     * statements are available from the {@link Reader} -or- until the reader
     * signals that it is {@link #readerDone done}. This helps to keep up the
     * chunk size and hence the efficiency of batch operations when we might
     * otherwise get into a race with the {@link Reader}.
     */
    private void awaitReader() {
        
        if( readService == null) {
            
            // We are not using an asynchronous reader.
            
            return;
            
        }

        final long begin = System.currentTimeMillis();
        
        /*
         * Wait for at least N records to show up.
         */
        
        final int N = capacity < MIN_CHUNK_SIZE ? capacity : MIN_CHUNK_SIZE;
        
        while(buffer.size()<N && ! readerDone.get()) {
            
            try {
                
                Thread.sleep(100);
                
            } catch (InterruptedException ex) {
                
                throw new RuntimeException(ex);
                
            }
            
            long elapsed = System.currentTimeMillis() - begin;
            
            if (elapsed > TIMEOUT && buffer.isEmpty()) {

                throw new RuntimeException("Timeout after " + elapsed + "ms");
                
            }
            
        }
        
    }
    
    /**
     * 
     * FIXME Once the problems with traversal with concurrent modification of
     * the BTree have been resolved this method should be modified such that
     * this class will buffer no more than some maximum #of statements and
     * {@link #remove()} should be implemented.
     * <p>
     * Note: Traversal with concurrent modification MUST declare semantics that
     * isolate the reader from the writer in order for clients to be able to
     * read ahead and buffer results from the iterator. Otherwise the buffered
     * statements would have to be discarded in there was a concurrent
     * modification somewhere in the future visitation of the iterator.
     * <p>
     * The one simple case is removal of the current statement, especially when
     * that statement has already been buffered.
     * 
     * @throws UnsupportedOperationException
     */
    public void remove() {
        
        assertOpen();

        throw new UnsupportedOperationException();
        
    }
    
    public void close() {
        
        if(!open) {
            
            // Already closed.
            
            return;
            
        }
        
        log.info("Closing iterator");
        
        open = false;
        
        if(readService!=null) {
            
            // immediate shutdown.
            
            readService.shutdownNow();
            
            try {

                readService.awaitTermination(500, TimeUnit.MILLISECONDS);
                
            } catch (InterruptedException e) {
                
                log.warn("Read service did not terminate: "+e);
                
            }
            
            readService = null;
            
        }
        
        // discard buffer.
        
        buffer.clear();
        
        buffer = null;
        
        // discard the source iterator.
        
        src = null;
        
    }
    
    private final void assertOpen() {
        
        if (!open)
            throw new IllegalStateException();
        
    }
    
}
