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
 * Created on May 25, 2011
 */

package com.bigdata.io.compression;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Unicode compression / decompression api.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public interface IUnicodeCompressor {

    /**
     * Encode a Unicode character sequence.  The run length of the encoded
     * sequence is NOT marked in the output.
     * 
     * @param in
     *            The Unicode data.
     * @param out
     *            Where to write the encoded data.
     */
    public void encode(CharSequence in, OutputStream out);

    /**
     * Decode a Unicode character sequence. The run length of the encoded
     * sequence is NOT marked in the input, so the caller must provide a view
     * consisting of exactly the bytes to be decoded.
     * 
     * @param in
     *            The encoded data.
     * @param out
     *            The decoded data (Unicode characters) are appended to this
     *            object.
     */
    public void decode(InputStream in, Appendable out);

}
