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
 * Created on Jun 29, 2011
 */

package com.bigdata.rdf.internal;

/**
 * An interface declaring the one byte extension code for non-inline {@link IV}
 * s.
 * <p>
 * Note: Negative codes are used for extension types for which an extension
 * {@link IV} follows the extension byte. Positive codes are used for extension
 * types where the data immediately follows the extension byte.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public interface INonInlineExtensionCodes {
    
    /**
     * @see BlobIV
     */
    final byte BlobIV = 0;

    /**
     * @see URINamespaceIV
     */
    final byte URINamespaceIV = -1;

    /**
     * @see LiteralDatatypeIV
     */
    final byte LiteralDatatypeIV = -2;

}
