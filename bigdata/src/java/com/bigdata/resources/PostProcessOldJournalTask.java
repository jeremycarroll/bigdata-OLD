package com.bigdata.resources;

import java.io.File;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;

import com.bigdata.btree.BTree;
import com.bigdata.btree.BatchLookup;
import com.bigdata.btree.Counters;
import com.bigdata.btree.IIndex;
import com.bigdata.btree.ISplitHandler;
import com.bigdata.btree.ITuple;
import com.bigdata.btree.ITupleIterator;
import com.bigdata.btree.IndexMetadata;
import com.bigdata.btree.IndexSegment;
import com.bigdata.btree.AbstractKeyArrayIndexProcedure.ResultBuffer;
import com.bigdata.btree.BatchLookup.BatchLookupConstructor;
import com.bigdata.io.DataInputBuffer;
import com.bigdata.io.SerializerUtil;
import com.bigdata.journal.AbstractJournal;
import com.bigdata.journal.AbstractTask;
import com.bigdata.journal.IConcurrencyManager;
import com.bigdata.journal.ITx;
import com.bigdata.journal.TemporaryRawStore;
import com.bigdata.journal.Name2Addr.Entry;
import com.bigdata.journal.Name2Addr.EntrySerializer;
import com.bigdata.mdi.LocalPartitionMetadata;
import com.bigdata.mdi.MetadataIndex;
import com.bigdata.mdi.PartitionLocator;
import com.bigdata.rawstore.IRawStore;
import com.bigdata.service.DataService;
import com.bigdata.service.IDataService;
import com.bigdata.service.ILoadBalancerService;
import com.bigdata.service.MetadataService;
import com.bigdata.util.InnerCause;

/**
 * Examine the named indices defined on the journal identified by the
 * <i>lastCommitTime</i> and, for each named index registered on that journal,
 * determines which of the following conditions applies and then schedules any
 * necessary tasks based on that decision:
 * <ul>
 * <li>Build a new {@link IndexSegment} for an existing index partition - this
 * is essentially a compacting merge (build).</li>
 * <li>Split an index partition into N index partitions (overflow).</li>
 * <li>Join N index partitions into a single index partition (underflow).</li>
 * <li>Move an index partition to another data service (redistribution). The
 * decision here is made on the basis of (a) underutilized nodes elsewhere; and
 * (b) overutilization of this node.</li>
 * <li>Nothing. This option is selected when (a) synchronous overflow
 * processing choose to copy the index entries from the old journal onto the new
 * journal (this is cheaper when the index has not absorbed many writes); and
 * (b) the index partition is not identified as the source for a move.</li>
 * </ul>
 * Each task has two phases
 * <ol>
 * <li>historical read from the lastCommitTime of the old journal</li>
 * <li>unisolated task performing an atomic update of the index partition view
 * and the metadata index</li>
 * </ol>
 * <p>
 * Processing is divided into three stages:
 * <dl>
 * <dt>{@link #chooseTasks()}</dt>
 * <dd>This stage examines the named indices and decides what action (if any)
 * will be applied to each index partition.</dd>
 * <dt>{@link #runTasks()}</dt>
 * <dd>This stage reads on the historical state of the named index partitions,
 * building, splitting, joining, or moving their data as appropriate.</dd>
 * <dt>{@link #atomicUpdates()}</dt>
 * <dd>This stage runs tasks using {@link ITx#UNISOLATED} operations on the
 * live journal to make atomic updates to the index partition definitions and to
 * the {@link MetadataIndex} and/or a remote data service where necessary</dd>
 * </dl>
 * <p>
 * Note: This task is invoked after an
 * {@link ResourceManager#overflow(boolean, boolean)}. It is run on the
 * {@link ResourceManager}'s {@link ExecutorService} so that its execution is
 * asynchronous with respect to the {@link IConcurrencyManager}. While it does
 * not require any locks for some of its processing stages, this task relies on
 * the {@link ResourceManager#overflowAllowed} flag to disallow additional
 * overflow operations until it has completed. The various actions taken by this
 * task are submitted as submits tasks to the {@link IConcurrencyManager} so
 * that they will obtain the appropriate locks as necessary on the named
 * indices.
 * 
 * @todo write alternative to the "build" task (which is basically a full
 *       compacting merge) that builds an index segment from the buffered writes
 *       on the journal and updates the view. This would let the #of index
 *       segments grow before performing a full compacting merge on the index
 *       partition. The advantage is that we only need to consider the buffered
 *       writes for the index partition vs a full key range scan of the fused
 *       view for the index partition. This lighter weight operation will copy
 *       any deleted index entries in the btree write buffer as of the
 *       lastCommitState of the old journal, but it will typically copy less
 *       data than was buffered since overwrites will reduce to a single index
 *       entry.
 *       <P>
 *       Think of a pithy name, such as "compact" vs "build" or "build" vs
 *       "buffer". The distinction could also be "full compacting merge" vs
 *       "compacting merge" but "merge" sounds too close to "join" for me and
 *       those labels are too long.
 * 
 * @todo consider side-effects of post-processing tasks (build, split, join, or
 *       move ) on a distributed index rebuild operation. It is possible that
 *       the new index partitions may have been defined (but not yet registered
 *       in the metadata index) and that new index resources (on journals or
 *       index segment files) may have been defined. However, none of these
 *       operations should produce incoherent results so it should be possible
 *       to restore a coherent state of the metadata index by picking and
 *       choosing carefully. The biggest danger is choosing a new index
 *       partition which does not yet have all of its state on hand. There may
 *       need to be some flag to note when the index partition goes live so that
 *       we never select it for the rebuild until it is complete.
 * 
 * @todo if an index partition is moved (or split or joined) while an active
 *       transaction has a write set for that index partition on a data service
 *       then we may need to move (or split and move) the transaction write set
 *       before it can be validated.
 * 
 * @todo its possible to play "catch up" on a "hot for write" index by copying
 *       behind the commit point on the live journal to the target journal in
 *       order to minimize the duration of the unisolated operation that handles
 *       the atomic cut over of the index to its new host. it might also be
 *       possible to duplicate the writes on the new host while they continue to
 *       be absorbed on the old host, but it would seem to be difficult to get
 *       the conditions just right for that.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class PostProcessOldJournalTask implements Callable<Object> {

    protected static final Logger log = Logger.getLogger(PostProcessOldJournalTask.class);
    
    /**
     * True iff the {@link #log} level is DEBUG or less.
     */
    final protected static boolean DEBUG = log.getEffectiveLevel().toInt() <= Level.DEBUG
            .toInt();

    /**
     * True iff the {@link #log} level is INFO or less.
     */
    final protected static boolean INFO = log.getEffectiveLevel().toInt() <= Level.INFO
            .toInt();

    /**
     * 
     */
    private final ResourceManager resourceManager;

    private final long lastCommitTime;

    /**
     * The names of any index partitions that were copied onto the new journal during
     * synchronous overflow processing.
     */
    private final Set<String> copied; 
    
    private IRawStore tmpStore;

    /** Aggregated counters for the named indices. */ 
    private final Counters totalCounters;
    
    /** Raw score computed for those aggregated counters. */
    private final double totalRawStore;
    
    /** Individual counters for the named indices. */
    private final Map<String/*name*/,Counters> indexCounters;

    /**
     * Scores computed for each named index in order by ascending score
     * (increased activity).
     */
    private final Score[] scores;
    /**
     * Random access to the index {@link Score}s.
     */
    private final Map<String,Score> scoreMap;
    
    /**
     * This is populated with each index partition that has been "used",
     * starting with those that were copied during synchronous overflow
     * processing. This allows us to detect when a possible join candidate can
     * not in fact be joined because we have already applied another task to its
     * rightSibling.
     */
    private final Set<String> used = new HashSet<String>();

    /**
     * Return <code>true</code> if the named index partition has already been
     * "used" by assigning it to partitipate in some build, split, join, or move
     * operation.
     * 
     * @param name
     *            The name of the index partition.
     */
    protected boolean isUsed(String name) {
        
        if (name == null)
            throw new IllegalArgumentException();

        return used.contains(name);
        
    }
    
    /**
     * This method is invoked each time an index partition is "used" by
     * assigning it to partitipate in some build, split, join, or move
     * operation.
     * 
     * @param name
     *            The name of the index partition.
     * 
     * @throws IllegalStateException
     *             if the index partition was already used by some other
     *             operation.
     */
    protected void markUsed(String name) {
        
        if (name == null)
            throw new IllegalArgumentException();
        
        if(used.contains(name)) {
            
            throw new IllegalStateException("Already used: "+name);
            
        }
        
        used.add(name);
        
    }
    
    /**
     * Helper class assigns a raw and a normalized score to each index based on
     * its per-index {@link Counters} and on the global (non-restart safe)
     * {@link Counters} for the data service during the life cycle of the last
     * journal.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    private static class Score implements Comparable<Score>{

        /** The name of the index partition. */
        public final String name;
        /** The counters collected for that index partition. */
        public final Counters counters;
        /** The raw score computed for that index partition. */
        public final double rawScore;
        /** The normalized score computed for that index partition. */
        public final double score;
        /** The rank in [0:#scored].  This is an index into the Scores[]. */
        public int rank = -1;
        /** The normalized double precision rank in [0.0:1.0]. */
        public double drank = -1d;
        
        public String toString() {
            
            return "Score{name=" + name + ", rawScore=" + rawScore + ", score="
                    + score + ", rank=" + rank + ", drank=" + drank + "}";
            
        }
        
        public Score(String name,Counters counters, double totalRawScore) {
            
            assert name != null;
            
            assert counters != null;
            
            this.name = name;
            
            this.counters = counters;
            
            rawScore = counters.computeRawScore();
            
            score = Counters.normalize( rawScore , totalRawScore );
            
        }

        /**
         * Places elements into order by ascending {@link #rawScore}. The
         * {@link #name} is used to break any ties.
         */
        public int compareTo(Score arg0) {
            
            if(rawScore < arg0.rawScore) {
                
                return -1;
                
            } else if(rawScore> arg0.rawScore ) {
                
                return 1;
                
            }
            
            return name.compareTo(arg0.name);
            
        }
        
    }
    
    /**
     * Return <code>true</code> if the named index partition is "warm" for
     * {@link ITx#UNISOLATED} and/or {@link ITx#READ_COMMITTED} operations.
     * <p>
     * Note: This method informs the selection of index partitions that will be
     * moved to another {@link IDataService}. The preference is always to move
     * an index partition that is "warm" rather than "hot" or "cold". Moving a
     * "hot" index partition causes more latency since more writes will have
     * been buffered and unisolated access to the index partition will be
     * suspended during the atomic part of the move operation. Likewise, "cold"
     * index partitions are not consuming any resources other than disk space
     * for their history, and the history of an index is not moved when the
     * index partition is moved.
     * <p>
     * Since the history of an index partition is not moved when the index
     * partition is moved, the determination of cold, warm or hot is made in
     * terms of the resources consumed by {@link ITx#READ_COMMITTED} and
     * {@link ITx#UNISOLATED} access to named index partitions. If an index
     * partition is hot for historical read, then your only choices are to shed
     * other index partitions from the data service, to read from a failover
     * data service having the same index partition, or possibly to increase the
     * replication count for the index partition.
     * 
     * @param name
     *            The name of an index partition.
     * 
     * @return The index {@link Score} -or- <code>null</code> iff the index
     *         was not touched for read-committed or unisolated operations.
     */
    public Score getScore(String name) {
        
        Score score = scoreMap.get(name);
        
        if(score == null) {
            
            /*
             * Index was not touched for read-committed or unisolated
             * operations.
             */

            log.debug("Index is cold: "+name);
            
            return null;
            
        }
        
        log.debug("Index score: "+score);
        
        return score;
        
    }
    
    /**
     * 
     * @param resourceManager
     * @param lastCommitTime
     *            The lastCommitTime on the old journal.
     * @param copied 
     *            The names of any index partitions that were copied onto the
     *            new journal during synchronous overflow processing.
     * @param totalCounters
     *            The total {@link Counters} reported for all unisolated and
     *            read-committed index views on the old journal.
     * @param indexCounters
     *            The per-index {@link Counters} for the unisolated and
     *            read-committed index views on the old journal.
     */
    public PostProcessOldJournalTask(ResourceManager resourceManager,
            long lastCommitTime, Set<String> copied, Counters totalCounters,
            Map<String/* name */, Counters> indexCounters) {

        if (resourceManager == null)
            throw new IllegalArgumentException();

        if( lastCommitTime <= 0)
            throw new IllegalArgumentException();

        if( copied == null ) 
            throw new IllegalArgumentException();
        
        if (totalCounters == null)
            throw new IllegalArgumentException();

        if (indexCounters == null)
            throw new IllegalArgumentException();

        this.resourceManager = resourceManager;

        this.lastCommitTime = lastCommitTime;

        this.copied = copied;

        /*
         * Note: An index that has been copied because its write set on the old
         * journal was small should not undergo an incremental build. It could
         * be used in a full build if its view has too much history. Likewise we
         * can still use it in a join operation. So we do not add the copied
         * indices to the "used" set.
         */ 
//        // copied indices are used and should not be processed further.
//        used.addAll(copied);
        
        this.totalCounters = totalCounters;
        
        this.indexCounters = indexCounters;

        final int nscores = indexCounters.size();
        
        this.scores = new Score[nscores];

        this.scoreMap = new HashMap<String/*name*/,Score>(nscores);

        this.totalRawStore = totalCounters.computeRawScore();

        if (nscores > 0) {

            final Iterator<Map.Entry<String, Counters>> itr = indexCounters
                    .entrySet().iterator();

            int i = 0;

            while (itr.hasNext()) {

                final Map.Entry<String, Counters> entry = itr.next();

                final String name = entry.getKey();

                final Counters counters = entry.getValue();

                scores[i] = new Score(name, counters, totalRawStore);

                i++;

            }

            // sort into ascending order (inceasing activity).
            Arrays.sort(scores);
            
            for (i = 0; i < scores.length; i++) {
                
                scores[i].rank = i;
                
                scores[i].drank = ((double)i)/scores.length;
                
                scoreMap.put(scores[i].name, scores[i]);
                
            }
            
            log.debug("The most active index was: "
                            + scores[scores.length - 1]);

            log.debug("The least active index was: " + scores[0]);

        }

    }

    /**
     * Scans the registered named indices and decides which ones (if any) are
     * undercapacity and should be joined.
     * <p>
     * If the rightSibling of an undercapacity index partition is also local
     * then a {@link JoinIndexPartitionTask} is created to join those index
     * partitions and both index partitions will be marked as "used".
     * <p>
     * If the rightSibling of an undercapacity index partition is remote, then a
     * {@link MoveIndexPartitionTask} is created to move the undercapacity index
     * partition to the remove data service and the undercapacity index partition
     * will be marked as "used".
     */
    protected List<AbstractTask> chooseIndexPartitionJoins() {

        // list of tasks that we create (if any).
        final List<AbstractTask> tasks = new LinkedList<AbstractTask>();

        log.info("begin: lastCommitTime=" + lastCommitTime);

        /*
         * Map of the under capacity index partitions for each scale-out index
         * having index partitions on the journal. Keys are the name of the
         * scale-out index. The values are B+Trees. There is an entry for each
         * scale-out index having index partitions on the journal.
         * 
         * Each B+Tree is sort of like a metadata index - its keys are the
         * leftSeparator of the index partition but its values are the
         * LocalPartitionMetadata records for the index partitions found on the
         * journal.
         * 
         * This map is populated during the scan of the named indices with index
         * partitions that are "undercapacity"
         */
        final Map<String, BTree> undercapacityIndexPartitions = new HashMap<String, BTree>();
        {

            // counters : must sum to ndone as post-condition.
            int ndone = 0; // for each named index we process
            int nskip = 0; // nothing.
            int njoin = 0; // join task _candidate_.
            int nignored = 0; // #of index partitions that are NOT join candidates.

            // the old journal.
            final AbstractJournal oldJournal = resourceManager
                    .getJournal(lastCommitTime);
            
            final ITupleIterator itr = oldJournal.getName2Addr().rangeIterator(
                    null, null);

            assert used.isEmpty() : "There are "+used.size()+" used index partitions";
            
            while (itr.hasNext()) {

                final ITuple tuple = itr.next();

                final Entry entry = EntrySerializer.INSTANCE
                        .deserialize(new DataInputBuffer(tuple.getValue()));

                // the name of an index to consider.
                final String name = entry.name;

                /*
                 * Open the historical view of that index at that time (not just
                 * the mutable BTree but the full view).
                 */
                final IIndex view = resourceManager.getIndex(name,
                        -lastCommitTime);

                if (view == null) {

                    throw new AssertionError(
                            "Index not found? : name=" + name
                                    + ", lastCommitTime=" + lastCommitTime);

                }

                // index metadata for that index partition.
                final IndexMetadata indexMetadata = view.getIndexMetadata();

                // handler decides when and where to split an index partition.
                final ISplitHandler splitHandler = indexMetadata
                        .getSplitHandler();

                // index partition metadata
                final LocalPartitionMetadata pmd = indexMetadata
                        .getPartitionMetadata();

                if (INFO)
                    log.info("Considering join: name=" + name + ", entryCount="
                            + view.rangeCount(null, null) + ", pmd=" + pmd);
                
                if (splitHandler != null
                        && pmd.getRightSeparatorKey() != null
                        && splitHandler.shouldJoin(view)) {

                    /*
                     * Add to the set of index partitions that are candidates
                     * for join operations.
                     * 
                     * Note: joins are only considered when the rightSibling of
                     * an index partition exists. The last index partition has
                     * [rightSeparatorKey == null] and there is no rightSibling
                     * for that index partition.
                     * 
                     * Note: If we decide to NOT join this with another local
                     * partition then we MUST do an index segment build in order
                     * to release the dependency on the old journal. This is
                     * handled below when we consider the JOIN candidates that
                     * were discovered in this loop.
                     */

                    final String scaleOutIndexName = indexMetadata.getName();

                    BTree tmp = undercapacityIndexPartitions
                            .get(scaleOutIndexName);

                    if (tmp == null) {

                        tmp = BTree.create(tmpStore, new IndexMetadata(UUID
                                .randomUUID()));

                        undercapacityIndexPartitions
                                .put(scaleOutIndexName, tmp);

                    }

                    tmp.insert(pmd.getLeftSeparatorKey(), pmd);

                    log.info("join candidate: " + name);

                    njoin++;

                } else {
                    
                    nignored++;
                    
                }

                ndone++;

            } // itr.hasNext()

            // verify counters.
            assert ndone == nskip + njoin + nignored : "ndone=" + ndone
                    + ", nskip=" + nskip + ", njoin=" + njoin + ", nignored="
                    + nignored;

        }

        /*
         * Consider the JOIN candidates. These are underutilized index
         * partitions on this data service. They are grouped by the scale-out
         * index to which they belong.
         * 
         * In each case we will lookup the rightSibling of the underutilized
         * index partition using its rightSeparatorKey and the metadata index
         * for that scale-out index.
         * 
         * If the rightSibling is local, then we will join those siblings.
         * 
         * If the rightSibling is remote, then we will move the index partition
         * to the remote data service.
         * 
         * Note: If we decide neither to join the index partition NOR to move
         * the index partition to another data service then we MUST add an index
         * build task for that index partition in order to release the history
         * dependency on the old journal.
         */
        {

            /*
             * This iterator visits one entry per scale-out index on this data
             * service having an underutilized index partition on this data
             * service.
             */
            final Iterator<Map.Entry<String, BTree>> itr = undercapacityIndexPartitions
                    .entrySet().iterator();

            int ndone = 0;
            int njoin = 0; // do index partition join on local service.
            int nmove = 0; // move to another service for index partition join.

            assert used.isEmpty() : "There are "+used.size()+" used index partitions";

            final UUID sourceDataService = resourceManager.getDataServiceUUID();

            while (itr.hasNext()) {

                final Map.Entry<String, BTree> entry = itr.next();

                // the name of the scale-out index.
                final String scaleOutIndexName = entry.getKey();

                log.info("Considering join candidates: " + scaleOutIndexName);

                // keys := leftSeparator; value := LocalPartitionMetadata
                final BTree tmp = entry.getValue();

                // #of underutilized index partitions for that scale-out index.
                final int ncandidates = tmp.getEntryCount();
                
                assert ncandidates > 0 : "Expecting at least one candidate";
                
                final ITupleIterator titr = tmp.entryIterator();

                /*
                 * Setup a BatchLookup query designed to locate the rightSibling
                 * of each underutilized index partition for the current
                 * scale-out index.
                 * 
                 * Note: This approach makes it impossible to join the last
                 * index partition when it is underutilized since it does not,
                 * by definition, have a rightSibling. However, the last index
                 * partition always has an open key range and is far more likely
                 * than any other index partition to recieve new writes.
                 */

                log.info("Formulating rightSiblings query=" + scaleOutIndexName
                        + ", #underutilized=" + ncandidates);
                
                final byte[][] keys = new byte[ncandidates][];
                
                final LocalPartitionMetadata[] underUtilizedPartitions = new LocalPartitionMetadata[ncandidates];
                
                int i = 0;
                
                while (titr.hasNext()) {

                    ITuple tuple = titr.next();

                    LocalPartitionMetadata pmd = (LocalPartitionMetadata) SerializerUtil
                            .deserialize(tuple.getValue());

                    underUtilizedPartitions[i] = pmd;

                    /*
                     * Note: the right separator key is also the key under which
                     * we will find the rightSibling.
                     * 
                     */
                    
                    if (pmd.getRightSeparatorKey() == null) {

                        throw new AssertionError(
                                "The last index partition may not be a join candidate: name="
                                        + scaleOutIndexName + ", " + pmd);

                    }
                    
                    keys[i] = pmd.getRightSeparatorKey();
                    
                    i++;
                    
                } // next underutilized index partition.

                log.info("Looking for rightSiblings: name=" + scaleOutIndexName
                        + ", #underutilized=" + ncandidates);

                /*
                 * Submit a single batch request to identify rightSiblings for
                 * all of the undercapacity index partitions for this scale-out
                 * index.
                 * 
                 * Note: default key/val serializers are used.
                 */
                final BatchLookup op = BatchLookupConstructor.INSTANCE
                        .newInstance(0/* fromIndex */, ncandidates/* toIndex */,
                                keys, null/*vals*/);
                final ResultBuffer resultBuffer;
                try {
                    resultBuffer = (ResultBuffer) resourceManager
                            .getMetadataService()
                            .submit(
                                    -lastCommitTime,
                                    MetadataService
                                            .getMetadataIndexName(scaleOutIndexName),
                                    op);
                } catch (Exception e) {
                    
                    log.error("Could not locate rightSiblings: index="
                            + scaleOutIndexName, e);

                    continue;
                    
                }

                for (i = 0; i < ncandidates; i++) {

                    final LocalPartitionMetadata pmd = underUtilizedPartitions[i];
                    
                    final PartitionLocator rightSiblingLocator = (PartitionLocator) SerializerUtil
                            .deserialize(resultBuffer.getResult(i));

                    final UUID targetDataServiceUUID = rightSiblingLocator.getDataServices()[0];

                    if (sourceDataService.equals(targetDataServiceUUID)) {

                        /*
                         * JOIN underutilized index partition with its local
                         * rightSibling.
                         * 
                         * Note: This is only joining two index partitions at a
                         * time. It's possible to do more than that if it
                         * happens that N > 2 underutilized sibling index
                         * partitions are on the same data service, but that is
                         * a relatively unlikley combination of events.
                         */
                        
                        final String[] resources = new String[2];
                        
                        resources[0] = DataService
                                .getIndexPartitionName(scaleOutIndexName,
                                        underUtilizedPartitions[i].getPartitionId());
                        
                        resources[1] = DataService.getIndexPartitionName(
                                scaleOutIndexName, rightSiblingLocator.getPartitionId());

                        log.info("Will JOIN: " + Arrays.toString(resources));
                        
                        final AbstractTask task = new JoinIndexPartitionTask(
                                    resourceManager, lastCommitTime, resources);

                        // add to set of tasks to be run.
                        tasks.add(task);
                        
                        markUsed(resources[0]);

                        markUsed(resources[1]);
                        
                        njoin++;

                    } else {
                        
                        /*
                         * MOVE underutilized index partition to data service
                         * hosting the right sibling.
                         */
                        
                        final String sourceIndexName = DataService
                                .getIndexPartitionName(scaleOutIndexName, pmd
                                        .getPartitionId());

                        final AbstractTask task = new MoveIndexPartitionTask(
                                resourceManager, lastCommitTime,
                                sourceIndexName, targetDataServiceUUID);

                        tasks.add(task);
                        
                        markUsed(sourceIndexName);
                        
                        nmove++;
                        
                    }

                    ndone++;
                    
                }
                
            } // next scale-out index with underutilized index partition(s).

            assert ndone == njoin + nmove;
            
        } // consider JOIN candidates.

        return tasks;
        
    }
    
    @SuppressWarnings("unchecked")
    private final List<AbstractTask> EMPTY_LIST = Collections.EMPTY_LIST; 
    
    protected List<AbstractTask> chooseIndexPartitionMoves() {
        
        /*
         * Figure out if this data service is considered to be highly utilized.
         * We will consider moves IFF this is a highly utilized service.
         * 
         * Note: We consult the load balancer service on this since it is able
         * to put the load of this service into perspective by also considering
         * the load on the other services in the federation.
         * 
         * Note: This is robust to failure of the load balancer service. When it
         * is not available we simply do not consider index partition moves.
         */

        // lookup the load balancer service.
        final ILoadBalancerService loadBalancerService;
        
        try {

            loadBalancerService = resourceManager.getLoadBalancerService();

        } catch (Exception ex) {

            log.warn("Could not discover the load balancer service", ex);

            return EMPTY_LIST;
            
        }
        
        if (loadBalancerService == null) {

            log.warn("Could not discover the load balancer service");

            return EMPTY_LIST;
            
        }

        // inquire if this service is highly utilized.
        final boolean highlyUtilizedService;
        try {

            final UUID serviceUUID = resourceManager.getDataServiceUUID();
            
            highlyUtilizedService = loadBalancerService
                    .isHighlyUtilizedDataService(serviceUUID);

        } catch (Exception ex) {

            log.warn("Could not determine if this data service is highly utilized");
            
            return EMPTY_LIST;
            
        }
        
        /*
         * The minimum #of active index partitions on a data service. We will
         * consider moving index partitions iff this threshold is exceeeded.
         */
        final int minActiveIndexPartitions = resourceManager.minimumActiveIndexPartitions;

        /*
         * The #of active index partitions on this data service.
         */
        final int nactive = scores.length;

        if (!highlyUtilizedService || nactive <= minActiveIndexPartitions) {

            log.info("Preconditions for move not satisified: highlyUtilized="
                    + highlyUtilizedService + ", nactive=" + nactive
                    + ", minActive=" + minActiveIndexPartitions);
            
            return EMPTY_LIST;
            
        }

        /*
         * Note: We make sure that we do not move all the index partitions to
         * the same target by moving at most M index partitions per
         * under-utilized data service recommended to us.
         */
        final int maxMovesPerTarget = resourceManager.maximumMovesPerTarget;

        /*
         * Obtain some data service UUIDs onto which we will try and offload
         * some index partitions iff this data service is deemed to be highly
         * utilized.
         */
        final UUID[] underUtilizedDataServiceUUIDs;
        try {

            // request under utilized data services (RMI).

            final UUID excludeUUID = resourceManager.getDataServiceUUID();
            
            underUtilizedDataServiceUUIDs = resourceManager
                    .getLoadBalancerService()
                    .getUnderUtilizedDataServices(//
                            0, // minCount - no lower bound.
                            0, // maxCount - no upper bound.
                            excludeUUID // exclude this data service.
                            );

        } catch (Exception ex) {

            log.warn("Could not obtain target service UUIDs: ", ex);

            return EMPTY_LIST;

        }

        /*
         * The maximum #of index partition moves that we will attempt. This will
         * be zero if there are no under-utilized services onto which we can
         * move an index partition. Also, it will be zero unless there is a
         * surplus of active index partitions on this data service.
         */
        final int maxMoves;
        {
            
            final int nactiveSurplus = nactive - minActiveIndexPartitions;
            
            assert nactiveSurplus > 0;
            
            assert underUtilizedDataServiceUUIDs != null;
            
            final int maxTargetMoves = maxMovesPerTarget
                    * underUtilizedDataServiceUUIDs.length;
            
            maxMoves = Math.min(nactiveSurplus, maxTargetMoves);
            
        }

        /*
         * Move candidates.
         * 
         * Note: We make sure that we don't move all the hot/warm index
         * partitions by choosing the index partitions to be moved from the
         * middle of the range. However, note that "cold" index partitions are
         * NOT assigned scores, so there has been either an unisolated or
         * read-committed operation on any index partition that was assigned a
         * score.
         * 
         * Note: This could lead to a failure to recommend a move if all the
         * "warm" index partitions happen to require a split or join. However,
         * this is unlikely since the "warm" index partitions change size, if
         * not slowly, then at least not as rapidly as the hot index partitions.
         * Eventually a lot of splits will lead to some index partition not
         * being split during an overflow and then we can move it.
         * 
         * @todo could adjust the bounds here based on how important it is to
         * begin moving index partitions off of this data service.
         */

        log.info("Considering index partition moves: #targetServices="
                + underUtilizedDataServiceUUIDs.length + ", maxMovesPerTarget="
                + maxMovesPerTarget + ", maxMoves=" + maxMoves + ", nactive="
                + nactive);
        
        int nmove = 0;
        
        final List<AbstractTask> tasks = new ArrayList<AbstractTask>(maxMoves);

        for (int i = 0; i < scores.length && nmove < maxMoves; i++) {

            final Score score = scores[i];
                        
            final String name = score.name;
            
            if(isUsed(name)) continue;

            log.info("Considering move candidate: "+score);
            
            if (score.drank > .3 && score.drank < .8) {

                /*
                 * Move this index partition to an under-utilized data service.
                 */

                // choose target using round robin among candidates.
                final UUID targetDataServiceUUID = underUtilizedDataServiceUUIDs[nmove
                        % underUtilizedDataServiceUUIDs.length];

                log.info("Will move "+name+" to dataService="+targetDataServiceUUID);
                
                final AbstractTask task = new MoveIndexPartitionTask(
                        resourceManager, lastCommitTime, name,
                        targetDataServiceUUID);

                tasks.add(task);

                markUsed(name);

                nmove++;

            }

        }
        
        log.info("Will move "+nmove+" index partitions based on utilization.");

        return tasks;

    }
    
    /**
     * Examine each named index on the old journal and decide what, if anything,
     * to do with that index. These indices are key range partitions of named
     * scale-out indices. The {@link LocalPartitionMetadata} describes the key
     * range partition and identifies the historical resources required to
     * present a coherent view of that index partition.
     * <p>
     * 
     * <h2> Build </h2>
     * 
     * A build is performed when there are buffered writes, when the buffered
     * writes were not simply copied onto the new journal during the atomic
     * overflow operation, and when the index partition is neither overcapacity
     * (split) nor undercapacity (joined). Also, we do not do a build if the
     * index partitions will be moved.
     * 
     * <h2> Split </h2>
     * 
     * A split is considered when an index partition appears to be overcapacity.
     * The split operation will inspect the index partition in more detail when
     * it runs. If the index partition does not, in fact, have sufficient
     * capacity to warrant a split then a build will be performed instead (the
     * build is treated more or less as a one-to-one split, but we do not assign
     * a new partition identifier). An index partition which is WAY overcapacity
     * can be split into more than 2 new index partitions.
     * 
     * <h2> Join </h2>
     * 
     * A join is considered when an index partition is undercapacity. Joins
     * require both an undercapacity index partition and its rightSibling. Since
     * the rightSeparatorKey for an index partition is also the key under which
     * the rightSibling would be found, we use the rightSeparatorKey to lookup
     * the rightSibling of an index partition in the {@link MetadataIndex}. If
     * that rightSibling is local (same {@link ResourceManager}) then we will
     * JOIN the index partitions. Otherwise we will MOVE the undercapacity index
     * partition to the {@link IDataService} on which its rightSibling was
     * found.
     * 
     * <h2> Move </h2>
     * 
     * We move index partitions around in order to make the use of CPU, RAM and
     * DISK resources more even across the federation and prevent hosts or data
     * services from being either under- or over-utilized. Index partition moves
     * are necessary when a scale-out index is relatively new in to distribute
     * the index over more than a single data service. Likewise, index partition
     * moves are important when a host is overloaded, especially when it is
     * approaching resource exhaustion. However, index partition moves DO NOT
     * release DISK space on a host since only the current state of the index
     * partition is moved, not its historical states (which are on a mixture of
     * journals and index segments).
     * <p>
     * We can choose which index partitions to move fairly liberally. Cold index
     * partitions are not consuming any CPU/RAM/IO resources and moving them to
     * another host will not effect the utilization of either the source or the
     * target host. Moving an index partition which is "hot for write" can
     * impose a noticable latency because the "hot for write" partition will
     * have absorbed more writes on the journal while we are moving the data
     * from the old view and we will need to move those writes as well. When we
     * move those writes the index will be unavailable for write until it
     * appears on the target data service. Therefore we generally choose to move
     * "warm" index partitions since it will introduce less latency when we
     * temporarily suspend writes on the index partition.
     * <p>
     * Indices typically have many commit points, and any one of them could
     * become "hot for read". However, moving an index partition is not going to
     * reduce the load on the old node for historical reads since we only move
     * the current state of the index, not its history. Nodes that are hot for
     * historical reads spots should be handled by increasing its replication
     * count and reading from the secondary data services. Note that
     * {@link ITx#READ_COMMITTED} and {@link ITx#UNISOLATED} both count against
     * the "live" index - read-committed reads always track the most recent
     * state of the index partition and would be moved if the index partition
     * was moved.
     * <p>
     * Bottom line: if a node is hot for historical read then increase the
     * replication count and read from failover services. If a node is hot for
     * read-committed and unisolated operations then move one or more of the
     * warm read-committed/unisolated index partitions to a node with less
     * utilization.
     * <p>
     * Index partitions that get a lot of action are NOT candidates for moves
     * unless the node itself is either overutilized, about to exhaust its DISK,
     * or other nodes are at very low utilization. We always prefer to move the
     * "warm" index partitions instead.
     * 
     * <h2> DISK exhaustion </h2>
     * 
     * Running out of DISK space causes an urgent condition and can lead to
     * failure or all services on the same host. Therefore, when a host is near
     * to exhausting its DISK space it (a) MUST notify the
     * {@link ILoadBalancerService}; (b) temporary files SHOULD be purged; it
     * MAY choose to shed indices that are "hot for write" since that will slow
     * down the rate at which the disk space is consumed; and (d) the resource
     * manager MAY aggressively release old resources, even at the expense of
     * forcing transactions to abort.
     * 
     * FIXME codify the suggestions when nearing DISK exhaustion into code.
     */
    protected List<AbstractTask> chooseTasks() throws Exception {

        log.info("begin: lastCommitTime=" + lastCommitTime);

        // the old journal.
        final AbstractJournal oldJournal = resourceManager
                .getJournal(lastCommitTime);

        // tasks to be created (capacity of the array is estimated).
        final List<AbstractTask> tasks = new ArrayList<AbstractTask>(
                (int) oldJournal.getName2Addr().rangeCount(null, null));

        /*
         * First, identify any index partitions that have underflowed and will
         * either be joined or moved. When an index partition is joined its
         * rightSibling is also processed. This is why we identify the index
         * partition joins first - so that we can avoid attempts to process the
         * rightSibling now that we know it is being used by the join operation.
         */
        tasks.addAll(chooseIndexPartitionJoins());

        /*
         * Identify index partitions that will be moved based on utilization.
         * 
         * We only identify index partitions when this data service is highly
         * utilized and when there is at least one underutilized data service
         * available.
         */
        tasks.addAll(chooseIndexPartitionMoves());
        
        /*
         * Review all index partitions on the old journal as of the last commit
         * time and verify for each one that we have either already assigned a
         * post-processing task to handle it, that we assign one now, or that no
         * post-processing is required (this last case occurs when the view
         * state was copied onto the new journal).
         */
        {

            // counters : must sum to ndone as post-condition.
            int ndone = 0; // for each named index we process
            int nskip = 0; // nothing.
            int nbuild = 0; // build task.
            int nsplit = 0; // split task.

            final ITupleIterator itr = oldJournal.getName2Addr().rangeIterator(
                    null, null);

            while (itr.hasNext()) {

                final ITuple tuple = itr.next();

                final Entry entry = EntrySerializer.INSTANCE
                        .deserialize(new DataInputBuffer(tuple.getValue()));

                // the name of an index to consider.
                final String name = entry.name;

                /*
                 * The index partition has already been handled.
                 */
                if(isUsed(name)) {
                    
                    log.info("Skipping name="+name+" - already used.");
                    
                    nskip++;

                    ndone++;
                    
                    continue;
                    
                }

                /*
                 * Open the historical view of that index at that time (not just
                 * the mutable BTree but the full view).
                 */
                final IIndex view = resourceManager.getIndex(name,
                        -lastCommitTime);

                if (view == null) {

                    throw new AssertionError(
                            "Index not found? : name" + name
                                    + ", lastCommitTime=" + lastCommitTime);

                }

                // index metadata for that index partition.
                final IndexMetadata indexMetadata = view.getIndexMetadata();

                // handler decides when and where to split an index partition.
                final ISplitHandler splitHandler = indexMetadata
                        .getSplitHandler();

                if (splitHandler != null && splitHandler.shouldSplit(view)) {

                    /*
                     * Do an index split task.
                     */

                    final AbstractTask task = new SplitIndexPartitionTask(
                            resourceManager,//
                            lastCommitTime,//
                            name//
                            );

                    // add to set of tasks to be run.
                    tasks.add(task);

                    markUsed(name);
                    
                    log.info("index split: " + name);

                    nsplit++;

                } else if (copied.contains(name)) {

                    /*
                     * The write set from the old journal was already copied to
                     * the new journal so we do not need to do a build.
                     */

                    markUsed(name);

                    log.info("index was copied: " + name);

                    nskip++;

                } else {

                    /*
                     * Just do an index build task.
                     * 
                     * @todo could do an incremental build if the amount of
                     * history in the view is not too great.
                     * 
                     * @todo mark incremental build segments differently in the
                     * file system since that might be important information
                     * when doing a distributed index rebuild. This mark
                     * probably needs to be driven through the index segment
                     * build API so that it shows up in the view history and in
                     * the index segment checkpoint record.
                     */

                    // the file to be generated.
                    final File outFile = resourceManager
                            .getIndexSegmentFile(indexMetadata);

                    final AbstractTask task = new BuildIndexSegmentTask(
                            resourceManager, lastCommitTime,
                            name, outFile);

                    log.info("index build: " + name);

                    // add to set of tasks to be run.
                    tasks.add(task);

                    markUsed(name);

                    nbuild++;

                }

                ndone++;

            } // itr.hasNext()

            // verify counters.
            assert ndone == nskip + nbuild + nsplit : "ndone=" + ndone
                    + ", nskip=" + nskip + ", nbuild=" + nbuild + ", nsplit="
                    + nsplit;

            // verify all indices were handled in one way or another.
            assert ndone == used.size() : "ndone="+ndone+", but #used="+used.size()+" : "+used.toString();
            
        }

        log.info("end");

        return tasks;

    }

    /**
     * Note: This task is interrupted by {@link OverflowManager#shutdownNow()}.
     * Therefore is tests {@link Thread#isInterrupted()} and returns immediately
     * if it has been interrupted.
     * 
     * @return The return value is always null.
     * 
     * @throws Exception
     *             This implementation does not throw anything since there is no
     *             one to catch the exception. Instead it logs exceptions at a
     *             high log level.
     */
    public Object call() throws Exception {

        /*
         * Mark the purpose of the thread using the same variable name as the
         * AbstractTask.
         */
        MDC.put("taskname", "overflowService");
        
        if (resourceManager.overflowAllowed.get()) {

            // overflow must be disabled while we run this task.
            throw new AssertionError();

        }

        final long begin = System.currentTimeMillis();
        
        try {

            log.warn("begin");

            if(Thread.currentThread().isInterrupted()) return null;
            
            tmpStore = new TemporaryRawStore();
            
            if(INFO) {
                
                // The pre-condition views.
            
                if(Thread.currentThread().isInterrupted()) return null;
                
                log.info("\npre-condition views: overflowCounter="
                        + resourceManager.overflowCounter.get() + "\n"
                        + resourceManager.listIndexPartitions(-lastCommitTime));
                
            }
            
            if(Thread.currentThread().isInterrupted()) return null;
            
            List<AbstractTask> tasks = chooseTasks();
            
            if(Thread.currentThread().isInterrupted()) return null;
            
            // runTasks()
            {
                log.info("begin : will run "+tasks.size()+" update tasks");

                /*
                 * Submit all tasks, awaiting their completion.
                 * 
                 * Note: The update tasks should be relatively fast, excepting possibly
                 * moves of a hot index partition since there could be a lot of buffered
                 * writes.
                 * 
                 * @todo config param for timeout.
                 */
                final List<Future<Object>> futures = resourceManager
                        .getConcurrencyManager().invokeAll(tasks, 60*1, TimeUnit.SECONDS);

                if(Thread.currentThread().isInterrupted()) return null;
                
                // verify that all tasks completed successfully.
                for (Future<Object> f : futures) {

                    /*
                     * Note: An error here, like an error in the build/split/join/move
                     * tasks above, MAY be ignored. The consequences are exactly like
                     * those above. The index partition will remain coherent and valid
                     * but its view will continue to have a dependency on the old
                     * journal until a post-processing task for that index partition
                     * succeeds.
                     */
                    try {

                        /*
                         * Note: Don't wait long - we already gave the tasks a chance to
                         * complete up above.
                         * 
                         * @todo Verify that no problems can arise if we allow
                         * post-processing to complete while update tasks are still
                         * executing. Ideally the atomic update operations will either
                         * succeed fully or fail, and failure will mean that the view
                         * was not changed.
                         */
                        
                        if(Thread.currentThread().isInterrupted()) return null;
                        
                        f.get(100,TimeUnit.MILLISECONDS);
                        
                    } catch(Throwable t) {
                
                        if(isNormalShutdown(t)) { 
                            
                            log.warn("Normal shutdown? : "+t);
                            
                        } else {
                        
                            log.error("Update task failed", t);
                            
                        }
                        
                        continue;
                        
                    }

                }

                log.info("end");

            }
            
            if(Thread.currentThread().isInterrupted()) return null;
            
            if(!resourceManager.isRunning()) {
                
                log.warn("Resource manager no longer running.");
                
                return null;
                
            }
            
            if(INFO) {
                
                // The post-condition views.
            
                if(Thread.currentThread().isInterrupted()) return null;
                
                log.info("\npost-condition views: overflowCounter="
                        + resourceManager.overflowCounter.get() + "\n"
                        + resourceManager.listIndexPartitions(ITx.UNISOLATED));
                
            }

            /*
             * Note: At this point we have the history as of the lastCommitTime
             * entirely contained in index segments. Also, since we constained
             * the resource manager to refuse another overflow until we have
             * handle the old journal, all new writes are on the live index.
             */
            
            final long overflowCounter = resourceManager.overflowCounter
                    .incrementAndGet();

            final long elapsed = System.currentTimeMillis()-begin;
            
            log.info("warn: overflowCounter=" + overflowCounter+", elapsed="+elapsed+"ms");
            
            return null;
            
        } catch(Throwable t) {
            
            /*
             * Note: This task is run normally from a Thread by the
             * ResourceManager so no one checks the Future for the task.
             * Therefore it is very important to log any errors here since
             * otherwise they will not be noticed.
             * 
             * At the same time, the resource manager can be shutdown at any
             * time. Asynchrous shutdown will provoke an exception here, but
             * those exceptions do not indicate a problem.
             */
            
            resourceManager.overflowFailedCounter.incrementAndGet();
            
            if(isNormalShutdown(t)) { 
                
                log.warn("Normal shutdown? : "+t);
                
            } else {
                
                log.error(t/*msg*/, t/*stack trace*/);
                
            }

            throw new RuntimeException( t );
            
        } finally {

            if (tmpStore != null) {

                try {
                    tmpStore.closeAndDelete();
                } catch (Throwable t) {
                    log.warn(t.getMessage(), t);
                }

            }

            // enable overflow again as a post-condition.
            if (!resourceManager.overflowAllowed.compareAndSet(false/*expect*/, true/*set*/)) {

                throw new AssertionError();

            }

        }

    }

    /**
     * These are all good indicators that the data service was shutdown.
     */
    protected boolean isNormalShutdown(Throwable t) {
       
        if(Thread.currentThread().isInterrupted()) return true;
        
        if (!resourceManager.isRunning()
                || !resourceManager.getConcurrencyManager()
                        .isOpen()
                || InnerCause.isInnerCause(t,
                        InterruptedException.class)
                || InnerCause.isInnerCause(t,
                        ClosedByInterruptException.class)
                || InnerCause.isInnerCause(t,
                        ClosedChannelException.class)
                || InnerCause.isInnerCause(t,
                        AsynchronousCloseException.class)) {
            
            return true;
            
        }
        
        return false;
            
    }
    
}
