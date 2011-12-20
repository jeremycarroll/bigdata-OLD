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
 * Created on Oct 29, 2011
 */

package com.bigdata.bop.solutions;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import com.bigdata.bop.BOp;
import com.bigdata.bop.BOpContext;
import com.bigdata.bop.IBindingSet;
import com.bigdata.bop.IVariable;
import com.bigdata.bop.NV;
import com.bigdata.bop.PipelineOp;
import com.bigdata.bop.engine.BOpStats;
import com.bigdata.relation.accesspath.IAsynchronousIterator;
import com.bigdata.relation.accesspath.IBlockingBuffer;

/**
 * Operator drops the identified variables from the solutions
 * 
 * @see ProjectionOp
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class DropOp extends PipelineOp {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    public interface Annotations extends PipelineOp.Annotations {

        /**
         * An {@link IVariable}[] identifying the variables to be DROPPED in the
         * {@link IBindingSet}s written out by the operator.
         */
        String DROP_VARS = DropOp.class.getName() + ".dropVars";
        
    }
    
    /**
     * @param op
     */
    public DropOp(DropOp op) {
        super(op);
    }

    /**
     * @param args
     * @param annotations
     */
    public DropOp(BOp[] args, Map<String, Object> annotations) {
        
        super(args, annotations);
        
        final IVariable<?>[] dropVars = (IVariable<?>[]) getRequiredProperty(Annotations.DROP_VARS);

        if (dropVars == null)
            throw new IllegalArgumentException();

        if (dropVars.length == 0)
            throw new IllegalArgumentException();
        
    }

    public DropOp(final BOp[] args, NV... annotations) {

        this(args, NV.asMap(annotations));
        
    }

//    /**
//     * @see Annotations#DROP
//     */
//    public IVariable<?>[] getVariables() {
//
//        return (IVariable<?>[]) getRequiredProperty(Annotations.DROP);
//        
//    }

    public FutureTask<Void> eval(final BOpContext<IBindingSet> context) {

        return new FutureTask<Void>(new ChunkTask(this, context));
        
    }

    /**
     * Task executing on the node.
     */
    static private class ChunkTask implements Callable<Void> {

        private final BOpContext<IBindingSet> context;

        /**
         * The projected variables.
         */
        private final IVariable<?>[] vars;

        ChunkTask(final DropOp op, final BOpContext<IBindingSet> context) {

            this.context = context;

            this.vars = (IVariable<?>[]) op
                    .getRequiredProperty(Annotations.DROP_VARS);

        }

        public Void call() throws Exception {

            final BOpStats stats = context.getStats();

            final IAsynchronousIterator<IBindingSet[]> itr = context
                    .getSource();

            final IBlockingBuffer<IBindingSet[]> sink = context.getSink();

            try {

                while (itr.hasNext()) {

                    final IBindingSet[] a = itr.next();

                    stats.chunksIn.increment();
                    stats.unitsIn.add(a.length);

                    for (int i = 0; i < a.length; i++) {

                        a[i] = a[i].clone();

                        for (IVariable<?> var : vars) {

                            a[i].clear(var);

                        }

                    }
                    
                    sink.add(a);

                }

                sink.flush();

                // done.
                return null;

            } finally {

                sink.close();

            }

        }

    }

}