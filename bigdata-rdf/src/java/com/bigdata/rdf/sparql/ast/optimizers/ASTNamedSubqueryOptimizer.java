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

package com.bigdata.rdf.sparql.ast.optimizers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bigdata.bop.BOp;
import com.bigdata.bop.BOpUtility;
import com.bigdata.bop.IBindingSet;
import com.bigdata.bop.IVariable;
import com.bigdata.rdf.sparql.ast.AST2BOpContext;
import com.bigdata.rdf.sparql.ast.ASTUtil;
import com.bigdata.rdf.sparql.ast.IGroupMemberNode;
import com.bigdata.rdf.sparql.ast.IGroupNode;
import com.bigdata.rdf.sparql.ast.IQueryNode;
import com.bigdata.rdf.sparql.ast.NamedSubqueriesNode;
import com.bigdata.rdf.sparql.ast.NamedSubqueryInclude;
import com.bigdata.rdf.sparql.ast.NamedSubqueryRoot;
import com.bigdata.rdf.sparql.ast.QueryRoot;
import com.bigdata.rdf.sparql.ast.SubqueryBase;
import com.bigdata.rdf.sparql.ast.SubqueryRoot;
import com.bigdata.rdf.sparql.ast.VarNode;

import cutthecrap.utils.striterators.Striterator;

/**
 * Class identifies the join variables for each instance in which a named
 * subquery solution set is incorporated into the query plan.
 *
 * @see NamedSubqueryRoot
 * @see NamedSubqueryInclude
 *
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ASTNamedSubqueryOptimizer implements IASTOptimizer {

//    private static final Logger log = Logger
//            .getLogger(ASTNamedSubqueryOptimizer.class);
    
    /**
     *
     * @throws RuntimeException
     *             if there is an {@link NamedSubqueryInclude} for a named
     *             solution set which is not generated by the query.
     * @throws RuntimeException
     *             if there is an {@link NamedSubqueryRoot} for a named solution
     *             set which is not consumed by the query.
     * @throws RuntimeException
     *             if there is more than one {@link NamedSubqueryRoot} for a
     *             given named solution set.
     */
    @Override
    public IQueryNode optimize(AST2BOpContext context, IQueryNode queryNode,
            /*DatasetNode dataset,*/ IBindingSet[] bindingSet) {

        final QueryRoot queryRoot = (QueryRoot) queryNode;

        /*
         * Rewrite subqueryRoot objects as named subquery.
         * 
         * FIXME We should do this if there are no join variables for the SPARQL
         * 1.1 subquery. That will prevent multiple evaluations of the SPARQL
         * 1.1 subquery since the named subquery is run once before the main
         * WHERE clause.
         */
        if (false)
            rewriteSparql11Subqueries(queryRoot);

        /*
         * Order the named subqueries in order to support nested includes
         */
        orderNamedSubqueries(queryRoot, queryRoot.getNamedSubqueries());

        final NamedSubqueriesNode namedSubqueries = queryRoot.getNamedSubqueries();

        if (namedSubqueries == null || namedSubqueries.isEmpty()) {

            // NOP.
            return queryRoot;

        }

        // The set of all INCLUDEs in the query.
        final NamedSubqueryInclude[] allIncludes = findAllIncludes(queryRoot);

        // Verify that a named subquery exists for each INCLUDE.
        assertNamedSubqueryForEachInclude(namedSubqueries, allIncludes);

        /*
         * Verify that each named subquery is consumed by at least one include
         * somewhere in the WHERE clause of the query.
         */
        assertEachNamedSubqueryIsUsed(namedSubqueries, allIncludes);

        /*
         * Figure out the join variables for each INCLUDE.
         */
        assignJoinVars(queryRoot, namedSubqueries, allIncludes);

        return queryRoot;

    }

    /**
     * Return all {@link NamedSubqueryInclude}s which appear in the WHERE clause
     * of the main query.
     */
    private NamedSubqueryInclude[] findAllIncludes(final QueryRoot queryRoot) {

        final Striterator itr = new Striterator(
                BOpUtility.postOrderIterator((BOp) queryRoot.getWhereClause()));

        itr.addTypeFilter(NamedSubqueryInclude.class);

        final List<NamedSubqueryInclude> list = new LinkedList<NamedSubqueryInclude>();

        while (itr.hasNext()) {

            list.add((NamedSubqueryInclude) itr.next());

        }

        final Striterator itr2 = new Striterator(
                BOpUtility.postOrderIterator((BOp) queryRoot.getWhereClause()));

        itr2.addTypeFilter(SubqueryRoot.class);


        while (itr2.hasNext()) {

            list.addAll(findSubqueryIncludes((SubqueryRoot) itr2.next()));

        }

        if (queryRoot.getNamedSubqueries() != null) {

            for(NamedSubqueryRoot root:queryRoot.getNamedSubqueries()){

                list.addAll(findSubqueryIncludes(root));

            }

        }

        return list.toArray(new NamedSubqueryInclude[] {});

    }

    private List<NamedSubqueryInclude> findSubqueryIncludes(final SubqueryBase queryRoot){
        final Striterator itr = new Striterator(
                BOpUtility.postOrderIterator((BOp) queryRoot.getWhereClause()));

        itr.addTypeFilter(NamedSubqueryInclude.class);

        final List<NamedSubqueryInclude> list = new LinkedList<NamedSubqueryInclude>();

        while (itr.hasNext()) {

            list.add((NamedSubqueryInclude) itr.next());

        }

        final Striterator itr2 = new Striterator(
                BOpUtility.postOrderIterator((BOp) queryRoot.getWhereClause()));

        itr2.addTypeFilter(SubqueryRoot.class);


        while (itr2.hasNext()) {

            list.addAll(findSubqueryIncludes((SubqueryRoot) itr2.next()));

        }

        return list;

    }

    /**
     * Verify that a named subquery exists for each INCLUDE.
     *
     * @param namedSubqueries
     * @param allIncludes
     */
    private void assertNamedSubqueryForEachInclude(
            final NamedSubqueriesNode namedSubqueries,
            final NamedSubqueryInclude[] allIncludes) {

        for (NamedSubqueryInclude anInclude : allIncludes) {

            final String namedSet = anInclude.getName();

            if (namedSet == null || namedSet.trim().length() == 0)
                throw new RuntimeException(
                        "Missing or illegal name for include.");

            boolean found = false;

            for (NamedSubqueryRoot aNamedSubquery : namedSubqueries) {

                if (aNamedSubquery.getName().equals(namedSet)) {
                    found = true;
                    break;
                }

            }

            if (!found)
                throw new RuntimeException(
                        "No subquery produces that solution set: " + namedSet);

        }

    }

    /**
     * Verify that each named subquery is consumed by at least one include
     * somewhere in the WHERE clause of the query.
     *
     * @param namedSubqueries
     * @param allIncludes
     */
    private void assertEachNamedSubqueryIsUsed(
            final NamedSubqueriesNode namedSubqueries,
            final NamedSubqueryInclude[] allIncludes) {

        // The set of all named solution sets produced by this query.
        final Set<String> namedSets = new LinkedHashSet<String>();

        for (NamedSubqueryRoot aNamedSubquery : namedSubqueries) {

            final String namedSet = aNamedSubquery.getName();

            if (!namedSets.add(namedSet)) {

                throw new RuntimeException("NamedSet declared more than once: "
                        + namedSet);

            }

            if (namedSet == null || namedSet.trim().length() == 0)
                throw new RuntimeException(
                        "Missing or illegal name for named subquery.");

            final List<NamedSubqueryInclude> includes = new LinkedList<NamedSubqueryInclude>();

            for (NamedSubqueryInclude anInclude : allIncludes) {

                if (namedSet.equals(anInclude.getName())) {

                    includes.add(anInclude);

                }

            }

            if (includes.isEmpty()) {
                throw new RuntimeException(
                        "Named subquery results are not used by this query: "
                                + namedSet);
            }

        }

    }

    /**
     * Figure out the join variables for each INCLUDE. If the join variables
     * were already assigned to a {@link NamedSubqueryInclude}, then we just
     * make sure that the {@link NamedSubqueryRoot} will produce a suitable hash
     * index. If an INCLUDE does not have its join variables pre-assigned, then
     * we do a static analysis of the query and figure out which shared
     * variables MUST be bound. The set of shared variables is assigned as the
     * join variables. Again, we verify that a suitable hash index will be
     * produced for that INCLUDE.
     * <p>
     * Note: If the join variables were not pre-assigned (by a query hint) and
     * no join variables are identified by a static analysis then a full N x M
     * cross product of the solutions must be tested and filtered for those
     * solutions which join. This is a lot of effort when compared with a hash
     * join. Having the right join variables is very important for performance.
     *
     * @param namedSubqueries
     * @param allIncludes
     */
    private void assignJoinVars(//
            final QueryRoot queryRoot,
            final NamedSubqueriesNode namedSubqueries,
            final NamedSubqueryInclude[] allIncludes) {

        for (NamedSubqueryRoot aNamedSubquery : namedSubqueries) {

            final String namedSet = aNamedSubquery.getName();

            // Collect each INCLUDE for this named subquery.
            final List<NamedSubqueryInclude> includes = new LinkedList<NamedSubqueryInclude>();
            {

                for (NamedSubqueryInclude anInclude : allIncludes) {

                    if (namedSet.equals(anInclude.getName())) {

                        includes.add(anInclude);

                    }

                }

            }

            /*
             * Collect each distinct joinvar[] combination for those includes.
             *
             * Note: Since having the distinct joinvar[] combinations is
             * important, we sort each joinvar[] to ensure that they have a
             * common order.
             */
            final Set<JoinVars> distinctJoinVarsSet = new LinkedHashSet<JoinVars>();

            for (NamedSubqueryInclude anInclude : includes) {

                @SuppressWarnings("rawtypes")
                final IVariable[] joinvars;

                if (anInclude.getJoinVars() == null) {

                    /*
                     * Since no query hint was used, then figure out the join
                     * variables using a static analysis of the query.
                     */

                    joinvars = staticAnalysis(queryRoot, aNamedSubquery,
                            anInclude);

                    // Sort.
                    Arrays.sort(joinvars);

                    // Set those join variables on the include.
                    anInclude.setJoinVars(ASTUtil.convert(joinvars));

                } else {

                    // Get the user specified join variables.
                    joinvars = ASTUtil.convert(anInclude.getJoinVars());

                    // Sort.
                    Arrays.sort(joinvars);

                    // Set them back on the include in sorted order.
                    anInclude.setJoinVars(ASTUtil.convert(joinvars));

                }

                distinctJoinVarsSet.add(new JoinVars(joinvars));

            }

            /*
             * Figure out the join variables for each place in the query where
             * the named result set is included and annotate the include
             * operator to specify the join variables for that include.
             */

            final int nhashIndices = distinctJoinVarsSet.size();

            if (nhashIndices > 1) {

                /*
                 * Since there is more than one set of join variables required
                 * by the INCLUDEs, we use the largest subset of the join
                 * variables defined across all of the includes.
                 */

                // First, collect all join variables.
                final Set<IVariable<?>> sharedVariables = new LinkedHashSet<IVariable<?>>();

                for (JoinVars joinVars : distinctJoinVarsSet) {

                    sharedVariables.addAll(joinVars.vars());

                }
                
                // Now, retain only those variables in scope for each include.
                for (JoinVars joinVars : distinctJoinVarsSet) {

                    sharedVariables.retainAll(joinVars.vars());

                }

                /*
                 * The join variables which are shared across all contexts in
                 * which this named solution set is joined back into the query.
                 */
                final VarNode[] sharedJoinVars = ASTUtil
                        .convert(sharedVariables.toArray(new IVariable[] {}));

                // Set the shared join variables on the named subquery.
                aNamedSubquery.setJoinVars(sharedJoinVars);

                for (NamedSubqueryInclude anInclude : includes) {

                    // Set the shared join variables on each subquery include.
                    anInclude.setJoinVars(sharedJoinVars);

                }

            } else {

                /*
                 * Since there is just one set of join variables we will use
                 * that.
                 */

                final JoinVars joinVars = distinctJoinVarsSet.iterator().next();

                aNamedSubquery.setJoinVars(ASTUtil.convert(joinVars.toArray()));

            }

        }

    }

    private void rewriteSparql11Subqueries(final QueryRoot queryRoot){

        final Striterator itr2 = new Striterator(
                BOpUtility.postOrderIterator((BOp) queryRoot.getWhereClause()));

        itr2.addTypeFilter(SubqueryRoot.class);

        final List<SubqueryRoot> subqueries  = new ArrayList<SubqueryRoot>();

        while (itr2.hasNext()) {

            subqueries.add((SubqueryRoot)itr2.next());

        }
        
        if (queryRoot.getNamedSubqueries() == null) {
        
            queryRoot.setNamedSubqueries(new NamedSubqueriesNode());
            
        }

        for (SubqueryRoot root : subqueries) {

            final IGroupNode<IGroupMemberNode> parent = root.getParent();

            parent.removeChild(root);

            final String newName = UUID.randomUUID().toString();

            final NamedSubqueryInclude include = new NamedSubqueryInclude(
                    newName);

            parent.addChild(include);

            final NamedSubqueryRoot nsr = new NamedSubqueryRoot(
                    root.getQueryType(), newName);

            nsr.setConstruct(root.getConstruct());
            nsr.setGroupBy(root.getGroupBy());
            nsr.setHaving(root.getHaving());
            nsr.setOrderBy(root.getOrderBy());
            nsr.setProjection(root.getProjection());
            nsr.setSlice(root.getSlice());
            nsr.setWhereClause(root.getWhereClause());

            queryRoot.getNamedSubqueries().add(nsr);

        }

    }

    /**
     * Order the named subqueries based on nested includes
     */
    private void orderNamedSubqueries(final QueryRoot queryRoot,
            final NamedSubqueriesNode namedSubqueries) {
        
        if (namedSubqueries != null) {

            /*
             * List of named solutions on which each named subquery depends.
             */
            final Map<NamedSubqueryRoot, List<String>> subqueryToIncludes = new HashMap<NamedSubqueryRoot, List<String>>();

            final Map<String, NamedSubqueryRoot> nameToSubquery = new HashMap<String, NamedSubqueryRoot>();

            for (NamedSubqueryRoot aNamedSubquery : namedSubqueries) {

                nameToSubquery.put(aNamedSubquery.getName(), aNamedSubquery);

            }

            for (NamedSubqueryRoot aNamedSubquery : namedSubqueries) {

                final List<String> includes = new ArrayList<String>();

                subqueryToIncludes.put(aNamedSubquery, includes);

                for (NamedSubqueryInclude include : findSubqueryIncludes(aNamedSubquery)) {

                    includes.add(include.getName());

                }

                // Set the DEPENDS_ON annotation.
                aNamedSubquery.setProperty(
                        NamedSubqueryRoot.Annotations.DEPENDS_ON,
                        includes.toArray(new String[0]));
                
            }
            
            final Set<String> processed = new HashSet<String>();

            final NamedSubqueriesNode newNode = new NamedSubqueriesNode();

            Iterator<Map.Entry<NamedSubqueryRoot, List<String>>> iter = subqueryToIncludes
                    .entrySet().iterator();

            while (iter.hasNext()) {
                final Map.Entry<NamedSubqueryRoot, List<String>> entry = iter
                        .next();
                final NamedSubqueryRoot namedSubquery = entry.getKey();
                if (entry.getValue().size() == 0) {
                    newNode.add(namedSubquery);
                    processed.add(namedSubquery.getName());
                    iter.remove();
                }
            }

            while (subqueryToIncludes.size() > 0) {
                iter = subqueryToIncludes.entrySet().iterator();
                while (iter.hasNext()) {
                    boolean ok = true;
                    final Map.Entry<NamedSubqueryRoot, List<String>> entry = iter
                            .next();
                    for (String dep : entry.getValue()) {
                        if (!processed.contains(dep)) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        newNode.add(entry.getKey());
                        processed.add(entry.getKey().getName());
                        iter.remove();
                    }
                }
            }
            queryRoot.setNamedSubqueries(newNode);
        }
    }

    /**
     * Identify the join variables for the specified INCLUDE for the position
     * within the query in which it appears.
     *
     * @param queryRoot
     * @param aNamedSubquery
     * @param anInclude
     * @return
     *
     *         FIXME This code must figure out which variables "must" be bound
     *         by both the the subquery and context in which the INCLUDE appears
     *         and return just those variables. [It is currently returning an
     *         empty {@link IVariable}[]. While a hash join using an empty array
     *         of join variables will always produce the correct solutions, it
     *         is not very efficient.]
     */
    @SuppressWarnings("rawtypes")
    private IVariable[] staticAnalysis(final QueryRoot queryRoot,
            final NamedSubqueryRoot aNamedSubquery,
            final NamedSubqueryInclude anInclude) {

        final Set<IVariable<?>> boundBySubquery = aNamedSubquery
                .getDefinatelyProducedBindings();
        
        final Set<IVariable<?>> incomingBindings = anInclude.getParentJoinGroup()
                .getIncomingBindings(new LinkedHashSet<IVariable<?>>());

        /*
         * This is only those variables which are bound on entry into the group
         * in which the INCLUDE appears *and* which are "must" bound variables
         * projected by the subquery.
         */
        boundBySubquery.retainAll(incomingBindings);

        // Convert to an array.
        final IVariable[] vars = boundBySubquery
                .toArray(new IVariable[boundBySubquery.size()]);

        // Put the variable[] into a consistent, predictable order.
        Arrays.sort(vars);

        return vars;

    }

    /**
     * Wrapper class used to inflict Arrays.equals() rather than Object.equals()
     * when an array is used in a Collection.
     */
    private static class JoinVars {

        private final Set<IVariable<?>> vars;

        private final int hashCode;

        public Set<IVariable<?>> vars() {
            
            return Collections.unmodifiableSet(vars);
            
        }
        
        public IVariable<?>[] toArray() {
            
            return vars.toArray(new IVariable[vars.size()]);
            
        }
        
        public JoinVars(final IVariable<?>[] vars) {

            this.vars = new LinkedHashSet<IVariable<?>>();

            for (int i = 0; i < vars.length; i++) {

                this.vars.add(vars[i]);

            }

            this.hashCode = Arrays.hashCode(vars);

        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o)
                return true;
            if (!(o instanceof JoinVars))
                return false;
            final JoinVars t = (JoinVars) o;
            return vars.equals(t.vars);
//            return Arrays.equals(vars, t.vars);
        }

    }

}
