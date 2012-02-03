/**

Copyright (C) SYSTAP, LLC 2006-2010.  All rights reserved.

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

package com.bigdata.rdf.internal;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.bigdata.btree.keys.KeyBuilder;
import com.bigdata.rdf.lexicon.LexiconRelation;
import com.bigdata.rdf.model.BigdataLiteral;
import com.bigdata.rdf.model.BigdataValueFactory;

/** Implementation for inline <code>xsd:integer</code>. */
public class XSDDecimalIV<V extends BigdataLiteral> extends
        AbstractLiteralIV<V, BigDecimal> {
    
    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    private final BigDecimal value;
    private transient int byteLength;

    public IV<V, BigDecimal> clone(final boolean clearCache) {

        final XSDDecimalIV<V> tmp = new XSDDecimalIV<V>(value);

        // propagate transient state if available.
        tmp.byteLength = byteLength;

        if (!clearCache) {

            tmp.setValue(getValueCache());
            
        }
        
        return tmp;

    }

    public XSDDecimalIV(final BigDecimal value) {
        
        super(DTE.XSDDecimal);

        if (value == null)
            throw new IllegalArgumentException();
        
        this.value = value;
        
    }

    final public BigDecimal getInlineValue() {

        return value;
        
    }

    @SuppressWarnings("unchecked")
    public V asValue(final LexiconRelation lex) {
		V v = getValueCache();
		if (v == null) {
			final BigdataValueFactory f = lex.getValueFactory();
			v = (V) f.createLiteral(value.toPlainString(),//
					f.createURI(DTE.XSDDecimal.getDatatype()));
			v.setIV(this);
			setValue(v);
		}
		return v;
    }

    @Override
    final public long longValue() {
        return value.longValue();
    }

    @Override
    public boolean booleanValue() {
        return value.equals(BigDecimal.ZERO) ? false : true;
    }

    @Override
    public byte byteValue() {
        return value.byteValue();
    }

    @Override
    public double doubleValue() {
        return value.doubleValue();
    }

    @Override
    public float floatValue() {
        return value.floatValue();
    }

    @Override
    public int intValue() {
        return value.intValue();
    }

    @Override
    public short shortValue() {
        return value.shortValue();
    }
    
    /**
     * Use toPlainString to avoid expression with exponent value that 
     * would imply xsd:double rather than xsd:decimal
     */
    @Override
    public String stringValue() {
        return value.toPlainString();
    }

    @Override
    public BigDecimal decimalValue() {
        return value;
    }

    @Override
    public BigInteger integerValue() {
        return value.toBigInteger();
    }

    public boolean equals(final Object o) {
        if (this == o)
            return true;
        if (o instanceof XSDDecimalIV<?>) {
            return this.value.equals(((XSDDecimalIV<?>) o).value);
        }
        return false;
    }

    /**
     * Return the hash code of the {@link BigDecimal}.
     */
    public int hashCode() {
        return value.hashCode();
    }

    public int byteLength() {
        
        if (byteLength == 0) {

            /*
             * Cache the byteLength if not yet set.
             */
            byteLength = 1 /* flags */ + KeyBuilder.byteLength(value);

        }

        return byteLength;
        
    }
        
    @Override
    protected int _compareTo(IV o) {
        
        return value.compareTo(((XSDDecimalIV) o).value);
        
    }
}