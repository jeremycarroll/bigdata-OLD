package com.bigdata.rdf.model;

import java.util.UUID;

import javax.xml.datatype.XMLGregorianCalendar;

import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.Value;

/**
 * Class provides a document-scoped context for generating blank node
 * identifiers (IDs). This class is intended for use to generate blank node
 * identifiers (IDs) for a source document within a shared prefix. When that
 * prefix is mapped onto the term2id index, all blank nodes for the same
 * source document will tend to be mapped onto the same index partition
 * (this is more efficient since the ordered writes are more local). The
 * prefix itself is a {@link UUID}, which is how we ensure that blank nodes
 * generated for different sources are understood as distinct blank nodes by
 * the database (this is required).
 * <p>
 * Note: All {@link BigdataValue} instances are in fact created by the
 * delegate instances created by this class and by the delegate will appear
 * to have been created by the same factory. This is intentional - it makes
 * the instances reusable by the base factory.
 * <p>
 * Note: {@link #nextID()} is NOT thread-safe, but different documents that
 * are being passed concurrently will have a distinct instance of this
 * factory and processing a single document is generally single-threaded.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class BNodeContextFactory implements BigdataValueFactory {

    /**
     * Provides the globally unique context for the generated blank node
     * identifiers.
     */
    private final UUID contextUuid = UUID.randomUUID();

    /**
     * The delegate factory.
     */
    private final BigdataValueFactory valueFactory;

    BNodeContextFactory(final BigdataValueFactory valueFactory) {

        this.valueFactory = valueFactory;

    }

    /**
     * A globally unique blank node identifier (ID) created with prefix
     * shared by all blank nodes whose identifers are generated by this
     * class.
     */
    protected String nextID() {

        return "_" + contextUuid + nextID++;

    }

    private int nextID;

    public BigdataBNodeImpl createBNode() {
        return valueFactory.createBNode(nextID());
    }

    /*
     * Delegate pattern.
     */

    public BigdataValue asValue(Value v) {
        return valueFactory.asValue(v);
    }

    public BigdataBNodeImpl createBNode(String id) {
        return valueFactory.createBNode(id);
    }

    public BigdataLiteralImpl createLiteral(boolean arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataLiteralImpl createLiteral(byte arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataLiteralImpl createLiteral(double arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataLiteralImpl createLiteral(float arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataLiteralImpl createLiteral(int arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataLiteralImpl createLiteral(long arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataLiteralImpl createLiteral(short arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataLiteralImpl createLiteral(String label, String language) {
        return valueFactory.createLiteral(label, language);
    }

    public BigdataLiteralImpl createLiteral(String label, URI datatype) {
        return valueFactory.createLiteral(label, datatype);
    }

    public BigdataLiteralImpl createLiteral(String label) {
        return valueFactory.createLiteral(label);
    }

    public BigdataLiteralImpl createLiteral(XMLGregorianCalendar arg0) {
        return valueFactory.createLiteral(arg0);
    }

    public BigdataStatementImpl createStatement(Resource s, URI p, Value o) {
        return valueFactory.createStatement(s, p, o);
    }

    public BigdataStatementImpl createStatement(Resource s, URI p, Value o,
            Resource c) {
        return valueFactory.createStatement(s, p, o, c);
    }

    public BigdataStatementImpl createStatement(Resource s, URI p, Value o,
            Resource c, StatementEnum type) {
        return valueFactory.createStatement(s, p, o, c, type);
    }

    public BigdataURIImpl createURI(String namespace, String localName) {
        return valueFactory.createURI(namespace, localName);
    }

    public BigdataURIImpl createURI(String uriString) {
        return valueFactory.createURI(uriString);
    }

    public BigdataValueSerializer<BigdataValue> getValueSerializer() {
        return valueFactory.getValueSerializer();
    }

    /**
     * Recursive contexts are not available (should not be necessary,
     * right?)
     */
    public BigdataValueFactory newBNodeContext() {
        throw new UnsupportedOperationException();
    }

    public BigdataResource asValue(Resource v) {
        return valueFactory.asValue(v);
    }

    public BigdataURI asValue(URI v) {
        return valueFactory.asValue(v);
    }

    public BigdataLiteral asValue(Literal v) {
        return valueFactory.asValue(v);
    }

    public BigdataBNode asValue(BNode v) {
        return valueFactory.asValue(v);
    }

}
