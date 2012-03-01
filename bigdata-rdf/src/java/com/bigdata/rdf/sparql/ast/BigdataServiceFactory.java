/**

Copyright (C) SYSTAP, LLC 2006-2012.  All rights reserved.

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
 * Created on Feb 29, 2012
 */

package com.bigdata.rdf.sparql.ast;

import java.util.Map;

import org.openrdf.model.URI;

import com.bigdata.rdf.store.AbstractTripleStore;

/**
 * Factory for service calls against bigdata aware services.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id: BigdataServiceFactory.java 6053 2012-02-29 18:47:54Z
 *          thompsonbry $
 */
public interface BigdataServiceFactory extends ServiceFactory {

    @Override
    BigdataServiceCall create(final AbstractTripleStore store,
            final IGroupNode<IGroupMemberNode> groupNode, final URI serviceURI,
            final String exprImage, final Map<String, String> prefixDecls);

}