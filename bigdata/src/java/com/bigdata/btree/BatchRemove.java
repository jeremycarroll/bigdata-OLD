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
 * Created on Feb 12, 2007
 */

package com.bigdata.btree;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Batch removal of one or more tuples, optionally returning their existing
 * values.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class BatchRemove extends AbstractKeyArrayIndexProcedure implements
        IBatchOperation, IParallelizableIndexProcedure {

    /**
     * 
     */
    private static final long serialVersionUID = -5332443478547654844L;

    private boolean returnOldValues;

    /**
     * True iff the old values stored under the keys will be returned by
     * {@link #apply(IIndex)}.
     */
    public boolean getReturnOldValues() {

        return returnOldValues;

    }

    /**
     * Factory for {@link BatchRemove} procedures.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class BatchRemoveConstructor extends
            AbstractIndexProcedureConstructor<BatchRemove> {

        /**
         * Singleton requests the return of the values that were removed from
         * the index by the operation.
         */
        public static final BatchRemoveConstructor RETURN_OLD_VALUES = new BatchRemoveConstructor(
                true);

        /**
         * Singleton does NOT request the return of the values that were removed
         * from the index by the operation.
         */
        public static final BatchRemoveConstructor RETURN_NO_VALUES = new BatchRemoveConstructor(
                false);

        private final boolean returnOldValues;

        private BatchRemoveConstructor(boolean returnOldValues) {

            this.returnOldValues = returnOldValues;

        }

        public BatchRemove newInstance(IDataSerializer keySer,
                IDataSerializer valSer, int fromIndex, int toIndex,
                byte[][] keys, byte[][] vals) {

            if(vals != null) throw new IllegalArgumentException("vals should be null");

            return new BatchRemove(keySer,valSer,fromIndex, toIndex, keys, returnOldValues);

        }

    }

    /**
     * De-serialization ctor.
     * 
     */
    public BatchRemove() {

    }

    /**
     * Batch remove operation.
     * 
     * @param keys
     *            A series of keys paired to values. Each key is an variable
     *            length unsigned byte[]. The keys MUST be presented in sorted
     *            order.
     * @param returnOldValues
     *            When <code>true</code> the old values for those keys will be
     *            returned by {@link #apply(IIndex)}.
     * 
     * @see BatchRemoveConstructor
     */
    protected BatchRemove(IDataSerializer keySer, IDataSerializer valSer,
            int fromIndex, int toIndex, byte[][] keys, boolean returnOldValues) {

        super(keySer, valSer, fromIndex, toIndex, keys, null/* vals */);

        this.returnOldValues = returnOldValues;

    }

    /**
     * Applies the operation.
     * 
     * @param ndx
     * 
     * @return The old values as a {@link ResultBuffer} iff they were requested.
     */
    public Object apply(IIndex ndx) {

        final int n = getKeyCount();

        final byte[][] ret = returnOldValues ? new byte[n][] : null;

        int i = 0;

        while (i < n) {

            final byte[] val = (byte[]) ndx.remove(getKey(i));

            if (returnOldValues) {

                ret[i] = val;

            }

            i++;

        }

        if (returnOldValues) {

            return new ResultBuffer(n, ret, ndx.getIndexMetadata()
                    .getValueSerializer());

        }

        return null;

    }

    @Override
    protected void readMetadata(ObjectInput in) throws IOException, ClassNotFoundException {

        super.readMetadata(in);

        returnOldValues = in.readBoolean();

    }

    @Override
    protected void writeMetadata(ObjectOutput out) throws IOException {

        super.writeMetadata(out);

        out.writeBoolean(returnOldValues);

    }

}
