/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.ccil.cowan.tagsoup.HTMLSchema;
import org.dom4j.*;
import org.dom4j.io.DocumentSource;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.TransformerXMLReceiver;
import org.orbeon.oxf.pipeline.api.XMLReceiver;
import org.orbeon.oxf.processor.DebugProcessor;
import org.orbeon.oxf.resources.URLFactory;
import org.orbeon.oxf.util.*;
import org.orbeon.oxf.xforms.control.controls.XFormsOutputControl;
import org.orbeon.oxf.xforms.control.controls.XXFormsAttributeControl;
import org.orbeon.oxf.xforms.event.events.XFormsLinkErrorEvent;
import org.orbeon.oxf.xforms.model.DataModel;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xforms.xbl.Scope;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xml.*;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.oxf.xml.dom4j.LocationDocumentResult;
import org.orbeon.oxf.xml.dom4j.LocationDocumentSource;
import org.orbeon.saxon.Configuration;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.functions.FunctionLibrary;
import org.orbeon.saxon.om.*;
import org.orbeon.saxon.value.*;
import org.orbeon.saxon.value.StringValue;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.TransformerHandler;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

public class XFormsUtils {

    public static final IndentedLogger indentedLogger = XFormsContainingDocument.getIndentedLogger(XFormsServer.getLogger(), "utils");

    private static final int SRC_CONTENT_BUFFER_SIZE = NetUtils.COPY_BUFFER_SIZE / 2;

    public static String encodeXMLAsDOM(org.w3c.dom.Node node) {
        try {
            return encodeXML(TransformerUtils.domToDom4jDocument(node), XFormsProperties.isGZIPState(), XFormsProperties.getXFormsPassword(), false);
        } catch (TransformerException e) {
            throw new OXFException(e);
        }
    }

    public static String encodeXML(Document documentToEncode, boolean encodeLocationData) {
        return encodeXML(documentToEncode, XFormsProperties.isGZIPState(), XFormsProperties.getXFormsPassword(), encodeLocationData);
    }

    public static String encodeXML(Document document, boolean compress, String encryptionPassword, boolean location) {
        //        XFormsServer.logger.debug("XForms - encoding XML.");

        // Get SAXStore
        // TODO: This is not optimal since we create a second in-memory representation. Should stream instead.
        final SAXStore saxStore = new SAXStore();
        // NOTE: We don't encode XML comments and use only the ContentHandler interface
        final Source source = location ? new LocationDocumentSource(document) : new DocumentSource(document);
        TransformerUtils.sourceToSAX(source, saxStore);

        // Serialize SAXStore to bytes
        // TODO: This is not optimal since we create a third in-memory representation. Should stream instead.
        final byte[] bytes;
        try {
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            saxStore.writeExternal(new ObjectOutputStream(byteArrayOutputStream));
            bytes = byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new OXFException(e);
        }

        // Encode bytes
        return encodeBytes(bytes, compress, encryptionPassword);
    }

    public static String encodeBytes(byte[] bytesToEncode, boolean compress, String encryptionPassword) {
        // Compress if needed
        final byte[] gzipByteArray = compress ? XFormsCompressor.compressBytes(bytesToEncode) : null;

        // Encrypt if needed
        if (encryptionPassword != null) {
            // Perform encryption
            if (gzipByteArray == null) {
                // The data was not compressed above
                return "X1" + SecureUtils.encrypt(encryptionPassword, bytesToEncode);
            } else {
                // The data was compressed above
                return "X2" + SecureUtils.encrypt(encryptionPassword, gzipByteArray);
            }
        } else {
            // No encryption
            if (gzipByteArray == null) {
                // The data was not compressed above
                return "X3" + Base64.encode(bytesToEncode, false);
            } else {
                // The data was compressed above
                return "X4" + Base64.encode(gzipByteArray, false);
            }
        }
    }

    private static final HTMLSchema TAGSOUP_HTML_SCHEMA = new HTMLSchema();

    private static void htmlStringToResult(String value, LocationData locationData, Result result) {
        try {
            final XMLReader xmlReader = new org.ccil.cowan.tagsoup.Parser();
            xmlReader.setProperty(org.ccil.cowan.tagsoup.Parser.schemaProperty, TAGSOUP_HTML_SCHEMA);
            xmlReader.setFeature(org.ccil.cowan.tagsoup.Parser.ignoreBogonsFeature, true);
            final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
            identity.setResult(result);
            xmlReader.setContentHandler(identity);
            final InputSource inputSource = new InputSource();
            inputSource.setCharacterStream(new StringReader(value));
            xmlReader.parse(inputSource);
        } catch (Exception e) {
            throw new ValidationException("Cannot parse value as text/html for value: '" + value + "'", locationData);
        }
//			r.setFeature(Parser.CDATAElementsFeature, false);
//			r.setFeature(Parser.namespacesFeature, false);
//			r.setFeature(Parser.ignoreBogonsFeature, true);
//			r.setFeature(Parser.bogonsEmptyFeature, false);
//			r.setFeature(Parser.defaultAttributesFeature, false);
//			r.setFeature(Parser.translateColonsFeature, true);
//			r.setFeature(Parser.restartElementsFeature, false);
//			r.setFeature(Parser.ignorableWhitespaceFeature, true);
//			r.setProperty(Parser.scannerProperty, new PYXScanner());
//          r.setProperty(Parser.lexicalHandlerProperty, h);
    }


    public static org.w3c.dom.Document htmlStringToDocumentTagSoup(String value, LocationData locationData) {
        final org.w3c.dom.Document document = XMLUtils.createDocument();
        final DOMResult domResult = new DOMResult(document);
        htmlStringToResult(value, locationData, domResult);
        return document;
    }

    public static Document htmlStringToDom4jTagSoup(String value, LocationData locationData) {
        final LocationDocumentResult documentResult = new LocationDocumentResult();
        htmlStringToResult(value, locationData, documentResult);
        return documentResult.getDocument();
    }

    // TODO: implement server-side plain text output with <br> insertion
//    public static void streamPlainText(final ContentHandler contentHandler, String value, LocationData locationData, final String xhtmlPrefix) {
//        // 1: Split string along 0x0a and remove 0x0d (?)
//        // 2: Output string parts, and between them, output <xhtml:br> element
//        try {
//            contentHandler.characters(filteredValue.toCharArray(), 0, filteredValue.length());
//        } catch (SAXException e) {
//            throw new OXFException(e);
//        }
//    }

    public static void streamHTMLFragment(XMLReceiver xmlReceiver, String value, LocationData locationData, String xhtmlPrefix) {
        if (StringUtils.isNotBlank(value)) { // don't parse blank values
            final org.w3c.dom.Document htmlDocument = htmlStringToDocumentTagSoup(value, locationData);

            // Stream fragment to the output
            if (htmlDocument != null) {
                TransformerUtils.sourceToSAX(new DOMSource(htmlDocument), new HTMLBodyXMLReceiver(xmlReceiver, xhtmlPrefix));
            }
        }
    }

    /**
     * Get the value of a child element known to have only static content.
     *
     * @param childElement          element to evaluate (xforms:label, etc.)
     * @param acceptHTML            whether the result may contain HTML
     * @param containsHTML          whether the result actually contains HTML (null allowed)
     * @return                      string containing the result of the evaluation, null if evaluation failed
     */
    public static String getStaticChildElementValue(final String prefix, final Element childElement, final boolean acceptHTML, final boolean[] containsHTML) {
        // Check that there is a current child element
        if (childElement == null)
            return null;

        // No HTML found by default
        if (containsHTML != null)
            containsHTML[0] = false;

        // Try to get inline value
        {
            final StringBuilder sb = new StringBuilder(20);

            // Visit the subtree and serialize

            // NOTE: It is a little funny to do our own serialization here, but the alternative is to build a DOM and
            // serialize it, which is not trivial because of the possible interleaved xforms:output's. Furthermore, we
            // perform a very simple serialization of elements and text to simple (X)HTML, not full-fledged HTML or XML
            // serialization.

            Dom4jUtils.visitSubtree(childElement, new LHHAElementVisitorListener(prefix, acceptHTML, containsHTML, sb, childElement));
            if (acceptHTML && containsHTML != null && !containsHTML[0]) {
                // We went through the subtree and did not find any HTML
                // If the caller supports the information, return a non-escaped string so we can optimize output later
                return XMLUtils.unescapeXMLMinimal(sb.toString());
            } else {
                // We found some HTML, just return it
                return sb.toString();
            }
        }
    }

    /**
     * Get the value of a child element by pushing the context of the child element on the binding stack first, then
     * calling getElementValue() and finally popping the binding context.
     *
     * @param container             current XFormsContainingDocument
     * @param sourceEffectiveId     source effective id for id resolution
     * @param scope                 XBL scope
     * @param childElement          element to evaluate (xforms:label, etc.)
     * @param acceptHTML            whether the result may contain HTML
     * @param containsHTML          whether the result actually contains HTML (null allowed)
     * @return                      string containing the result of the evaluation, null if evaluation failed
     */
    public static String getChildElementValue(final XBLContainer container, final String sourceEffectiveId,
                                              final Scope scope, final Element childElement, final boolean acceptHTML, boolean[] containsHTML) {

        // Check that there is a current child element
        if (childElement == null)
            return null;

        // Child element becomes the new binding
        final XFormsContextStack contextStack = container.getContextStack();
        contextStack.pushBinding(childElement, sourceEffectiveId, scope);
        final String result = getElementValue(container, contextStack, sourceEffectiveId, childElement, acceptHTML, containsHTML);
        contextStack.popBinding();
        return result;
    }

    /**
     * Get the value of an element by trying single-node binding, value attribute, linking attribute, and inline value
     * (including nested XHTML and xforms:output elements).
     *
     * This may return an HTML string if HTML is accepted and found, or a plain string otherwise.
     *
     * @param container             current XBLContainer
     * @param contextStack          context stack for XPath evaluation
     * @param sourceEffectiveId     source effective id for id resolution
     * @param childElement          element to evaluate (xforms:label, etc.)
     * @param acceptHTML            whether the result may contain HTML
     * @param containsHTML          whether the result actually contains HTML (null allowed)
     * @return                      string containing the result of the evaluation, null if evaluation failed (see comments)
     */
    public static String getElementValue(final XBLContainer container,
                                         final XFormsContextStack contextStack, final String sourceEffectiveId,
                                         final Element childElement, final boolean acceptHTML, final boolean[] containsHTML) {

        // No HTML found by default
        if (containsHTML != null)
            containsHTML[0] = false;

        final BindingContext currentBindingContext = contextStack.getCurrentBindingContext();

        // "the order of precedence is: single node binding attributes, linking attributes, inline text."

        // Try to get single node binding
        {
            final boolean hasSingleNodeBinding = currentBindingContext.isNewBind();
            if (hasSingleNodeBinding) {
                final Item boundItem = currentBindingContext.getSingleItem();
                final String tempResult = DataModel.getValue(boundItem);
                if (tempResult != null) {
                    return (acceptHTML && containsHTML == null) ? XMLUtils.escapeXMLMinimal(tempResult) : tempResult;
                } else {
                    // There is a single-node binding but it doesn't point to an acceptable item
                    return null;
                }
            }
        }

        // Try to get value attribute
        // NOTE: This is an extension attribute not standard in XForms 1.0 or 1.1
        {
            final String valueAttribute = childElement.attributeValue(XFormsConstants.VALUE_QNAME);
            final boolean hasValueAttribute = valueAttribute != null;
            if (hasValueAttribute) {
                final List<Item> currentNodeset = currentBindingContext.getNodeset();
                if (currentNodeset != null && currentNodeset.size() > 0) {
                    String tempResult;
                    try {
                            tempResult = XPathCache.evaluateAsString(
                                currentNodeset, currentBindingContext.getPosition(),
                                valueAttribute, container.getNamespaceMappings(childElement),
                                contextStack.getCurrentVariables(), XFormsContainingDocument.getFunctionLibrary(),
                                contextStack.getFunctionContext(sourceEffectiveId), null,
                                (LocationData) childElement.getData());
                    } catch (Exception e) {
                        XFormsError.handleNonFatalXPathError(container.getContainingDocument(), e);
                        tempResult = "";
                    } finally {
                        contextStack.returnFunctionContext();
                    }

                    return (acceptHTML && containsHTML == null) ? XMLUtils.escapeXMLMinimal(tempResult) : tempResult;
                } else {
                    // There is a value attribute but the evaluation context is empty
                    return null;
                }
            }
        }

        // Try to get linking attribute
        // NOTE: This is deprecated in XForms 1.1
        {
            final String srcAttributeValue = childElement.attributeValue(XFormsConstants.SRC_QNAME);
            final boolean hasSrcAttribute = srcAttributeValue != null;
            if (hasSrcAttribute) {
                try {
                    // NOTE: This should probably be cached, but on the other hand almost nobody uses @src
                    final String tempResult  = retrieveSrcValue(srcAttributeValue);
                    if (containsHTML != null)
                        containsHTML[0] = false; // NOTE: we could support HTML if the media type returned is text/html
                    return (acceptHTML && containsHTML == null) ? XMLUtils.escapeXMLMinimal(tempResult) : tempResult;
                } catch (IOException e) {
                    // Dispatch xforms-link-error to model
                    final XFormsModel currentModel = currentBindingContext.model();
                    // NOTE: xforms-link-error is no longer in XForms 1.1 starting 2009-03-10
                    currentModel.getXBLContainer(null).dispatchEvent(new XFormsLinkErrorEvent(container.getContainingDocument(), currentModel, srcAttributeValue, childElement, e));
                    // Exception when dereferencing the linking attribute
                    return null;
                }
            }
        }

        // Try to get inline value
        {
            final StringBuilder sb = new StringBuilder(20);

            // Visit the subtree and serialize

            // NOTE: It is a little funny to do our own serialization here, but the alternative is to build a DOM and
            // serialize it, which is not trivial because of the possible interleaved xforms:output's. Furthermore, we
            // perform a very simple serialization of elements and text to simple (X)HTML, not full-fledged HTML or XML
            // serialization.
            Dom4jUtils.visitSubtree(childElement, new LHHAElementVisitorListener(container, contextStack,
                    sourceEffectiveId, acceptHTML, containsHTML, sb, childElement));
            if (acceptHTML && containsHTML != null && !containsHTML[0]) {
                // We went through the subtree and did not find any HTML
                // If the caller supports the information, return a non-escaped string so we can optimize output later
                return XMLUtils.unescapeXMLMinimal(sb.toString());
            } else {
                // We found some HTML, just return it
                return sb.toString();
            }
        }
    }

    /**
     * Compare two objects, handling null values as well.
     *
     * @param value1    first value or null
     * @param value2    second value or null
     * @return          whether the values are identical or both null
     */
    public static boolean compareStrings(Object value1, Object value2) {
        return (value1 == null && value2 == null) || (value1 != null && value2 != null && value1.equals(value2));
    }

    /**
     * Compare two collections, handling null values as well.
     *
     * @param value1    first value or null
     * @param value2    second value or null
     * @return          whether the values are identical or both null
     */
    public static boolean compareCollections(Collection value1, Collection value2) {
        // Add quick check on size, which AbstractList e.g. doesn't do
        return (value1 == null && value2 == null) || (value1 != null && value2 != null && value1.size() == value2.size() && value1.equals(value2));
    }

    /**
     * Compare two maps, handling null values as well.
     *
     * @param value1    first value or null
     * @param value2    second value or null
     * @return          whether the values are identical or both null
     */
    public static boolean compareMaps(Map value1, Map value2) {
        return (value1 == null && value2 == null) || (value1 != null && value2 != null && value1.equals(value2));
    }

    public static ValueRepresentation convertJavaObjectToSaxonObject(Object object) {
        final ValueRepresentation valueRepresentation;
        if (object instanceof ValueRepresentation) {
            // Native Saxon variable value
            valueRepresentation = (ValueRepresentation) object;
        } else if (object instanceof String) {
            valueRepresentation = new StringValue((String) object);
        } else if (object instanceof Boolean) {
            valueRepresentation = BooleanValue.get((Boolean) object);
        } else if (object instanceof Integer) {
            valueRepresentation = new Int64Value((Integer) object);
        } else if (object instanceof Float) {
            valueRepresentation = new FloatValue((Float) object);
        } else if (object instanceof Double) {
            valueRepresentation = new DoubleValue((Double) object);
        } else if (object instanceof URI) {
            valueRepresentation = new AnyURIValue(object.toString());
        } else {
            throw new OXFException("Invalid variable type: " + object.getClass());
        }
        return valueRepresentation;
    }

    public static org.w3c.dom.Document decodeXMLAsDOM(String encodedXML) {
        try {
            return TransformerUtils.dom4jToDomDocument(XFormsUtils.decodeXML(encodedXML));
        } catch (TransformerException e) {
            throw new OXFException(e);
        }
    }

    public static Document decodeXML(String encodedXML) {
        return decodeXML(encodedXML, XFormsProperties.getXFormsPassword());
    }

    public static Document decodeXML(String encodedXML, String encryptionPassword) {

        final byte[] bytes = decodeBytes(encodedXML, encryptionPassword);

        // Deserialize bytes to SAXStore
        // TODO: This is not optimal
        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        final SAXStore saxStore;
        try {
            saxStore = new SAXStore(new ObjectInputStream(byteArrayInputStream));
        } catch (IOException e) {
            throw new OXFException(e);
        }

        // Deserialize SAXStore to dom4j document
        // TODO: This is not optimal
        final TransformerXMLReceiver identity = TransformerUtils.getIdentityTransformerHandler();
        final LocationDocumentResult result = new LocationDocumentResult();
        identity.setResult(result);
        try {
            saxStore.replay(identity);
        } catch (SAXException e) {
            throw new OXFException(e);
        }
        return result.getDocument();
    }

    public static byte[] decodeBytes(String encoded, String encryptionPassword) {
        // Get raw text
        byte[] resultBytes;
        {
            final String prefix = encoded.substring(0, 2);
            final String encodedString = encoded.substring(2);

            final byte[] resultBytes1;
            final byte[] gzipByteArray;
            if (prefix.equals("X1")) {
                // Encryption + uncompressed
                resultBytes1 = SecureUtils.decrypt(encryptionPassword, encodedString);
                gzipByteArray = null;
            } else if (prefix.equals("X2")) {
                // Encryption + compressed
                resultBytes1 = null;
                gzipByteArray = SecureUtils.decrypt(encryptionPassword, encodedString);
            } else if (prefix.equals("X3")) {
                // No encryption + uncompressed
                resultBytes1 = Base64.decode(encodedString);
                gzipByteArray = null;
            } else if (prefix.equals("X4")) {
                // No encryption + compressed
                resultBytes1 = null;
                gzipByteArray = Base64.decode(encodedString);
            } else {
                throw new OXFException("Invalid prefix for encoded string: " + prefix);
            }

            // Decompress if needed
            if (gzipByteArray != null) {
                resultBytes = XFormsCompressor.uncompressBytes(gzipByteArray);
            } else {
                resultBytes = resultBytes1;
            }
        }
        return resultBytes;
    }

    public static String retrieveSrcValue(String src) throws IOException {

        // Handle HHRI
        src = NetUtils.encodeHRRI(src, true);

        final URL url = URLFactory.createURL(src);

        // Load file into buffer
        // TODO: this is wrong, must use regular URL resolution methods
        final InputStreamReader reader = new InputStreamReader(url.openStream());
        try {
            final StringBuffer value = new StringBuffer();
            final char[] buff = new char[SRC_CONTENT_BUFFER_SIZE];
            int c;
            while ((c = reader.read(buff, 0, SRC_CONTENT_BUFFER_SIZE - 1)) != -1) value.append(buff, 0, c);
            return value.toString();
        } finally {
            reader.close();
        }
    }

    /**
     * Resolve a render URL including xml:base resolution.
     *
     * @param containingDocument    current document
     * @param currentElement        element used for xml:base resolution
     * @param url                   URL to resolve
     * @param skipRewrite           whether to skip the actual URL rewriting step
     * @return                      resolved URL
     */
    public static String resolveRenderURL(XFormsContainingDocument containingDocument, Element currentElement, String url, boolean skipRewrite) {
        final URI resolvedURI = resolveXMLBase(containingDocument, currentElement, url);

        final String resolvedURIStringNoPortletFragment = uriToStringNoFragment(containingDocument, resolvedURI);

        return skipRewrite ? resolvedURIStringNoPortletFragment :
                NetUtils.getExternalContext().getResponse().rewriteRenderURL(resolvedURIStringNoPortletFragment, null, null);
    }

    public static String resolveActionURL(XFormsContainingDocument containingDocument, Element currentElement, String url, boolean skipRewrite) {
        final URI resolvedURI = resolveXMLBase(containingDocument, currentElement, url);

        final String resolvedURIStringNoPortletFragment = uriToStringNoFragment(containingDocument, resolvedURI);

        return skipRewrite ? resolvedURIStringNoPortletFragment :
                NetUtils.getExternalContext().getResponse().rewriteActionURL(resolvedURIStringNoPortletFragment, null, null);
    }

    private static String uriToStringNoFragment(XFormsContainingDocument containingDocument, URI resolvedURI) {
        if (containingDocument.isPortletContainer() && resolvedURI.getFragment() != null) {
            // XForms page was loaded from a portlet and there is a fragment, remove it
            try {
                return new URI(resolvedURI.getScheme(), resolvedURI.getAuthority(), resolvedURI.getPath(), resolvedURI.getQuery(), null).toString();
            } catch (URISyntaxException e) {
                throw new OXFException(e);
            }
        } else {
            return resolvedURI.toString();
        }
    }

    /**
     * Resolve a resource URL including xml:base resolution.
     *
     *
     * @param containingDocument    current document
     * @param element               element used to start resolution (if null, no resolution takes place)
     * @param url                   URL to resolve
     * @param rewriteMode           rewrite mode (see ExternalContext.Response)
     * @return                      resolved URL
     */
    public static String resolveResourceURL(XFormsContainingDocument containingDocument, Element element, String url, int rewriteMode) {

        final URI resolvedURI = resolveXMLBase(containingDocument, element, url);

        return NetUtils.getExternalContext().getResponse().rewriteResourceURL(resolvedURI.toString(), rewriteMode);
    }

    /**
     * Resolve a resource URL including xml:base resolution.
     *
     * @param containingDocument    current document
     * @param element               element used to start resolution (if null, no resolution takes place)
     * @param url                   URL to resolve
     * @param rewriteMode           rewrite mode (see ExternalContext.Response)
     * @return                      resolved URL
     */
    public static String resolveServiceURL(XFormsContainingDocument containingDocument, Element element, String url, int rewriteMode) {

        final URI resolvedURI = resolveXMLBase(containingDocument, element, url);

        return NetUtils.getExternalContext().rewriteServiceURL(resolvedURI.toString(), rewriteMode);
    }

    /**
     * Resolve attribute value templates (AVTs).
     *
     * @param contextItems       context items
     * @param contextPosition    context position
     * @param variableToValueMap variables
     * @param functionLibrary    XPath function library to use
     * @param functionContext    context object to pass to the XForms function
     * @param namespaceMapping   namespace mappings
     * @param locationData       LocationData for error reporting
     * @param attributeValue     attribute value
     * @return                   resolved attribute value
     */
    public static String resolveAttributeValueTemplates(List<Item> contextItems, int contextPosition, Map<String, ValueRepresentation> variableToValueMap,
                                                        FunctionLibrary functionLibrary, XPathCache.FunctionContext functionContext,
                                                        NamespaceMapping namespaceMapping, LocationData locationData, String attributeValue) {

        if (attributeValue == null)
            return null;

        return XPathCache.evaluateAsAvt(contextItems, contextPosition, attributeValue, namespaceMapping,
                variableToValueMap, functionLibrary, functionContext, null, locationData);
    }

    /**
     * Resolve attribute value templates (AVTs).
     *
     * @param xpathContext      current XPath context
     * @param contextNode       context node for evaluation
     * @param attributeValue    attribute value
     * @return                  resolved attribute value
     */
    public static String resolveAttributeValueTemplates(XPathCache.XPathContext xpathContext, NodeInfo contextNode, String attributeValue) {

        if (attributeValue == null)
            return null;

        return XPathCache.evaluateAsAvt(xpathContext, contextNode, attributeValue);
    }

    /**
     * Resolve a URI string against an element, taking into account ancestor xml:base attributes for
     * the resolution, and using the document's request URL as a base.
     *
     * @param containingDocument    current document
     * @param element   element used to start resolution (if null, no resolution takes place)
     * @param uri       URI to resolve
     * @return          resolved URI
     */
    public static URI resolveXMLBase(XFormsContainingDocument containingDocument, Element element, String uri) {
        return resolveXMLBase(element, containingDocument.getRequestPath(), uri);
    }

    /**
     * Resolve a URI string against an element, taking into account ancestor xml:base attributes for
     * the resolution.
     *
     * @param element   element used to start resolution (if null, no resolution takes place)
     * @param baseURI   optional base URI
     * @param uri       URI to resolve
     * @return          resolved URI
     */
    public static URI resolveXMLBase(Element element, String baseURI, String uri) {
        try {
            // Allow for null Element
            if (element == null)
                return new URI(uri);

            final List<String> xmlBaseValues = new ArrayList<String>();

            // Collect xml:base values
            Element currentElement = element;
            do {
                final String xmlBaseAttribute = currentElement.attributeValue(XMLConstants.XML_BASE_QNAME);
                if (xmlBaseAttribute != null)
                    xmlBaseValues.add(xmlBaseAttribute);
                currentElement = currentElement.getParent();
            } while(currentElement != null);

            // Append base if needed
            if (baseURI != null)
                xmlBaseValues.add(baseURI);

            // Go from root to leaf
            Collections.reverse(xmlBaseValues);
            xmlBaseValues.add(uri);

            // Resolve paths from root to leaf
            URI result = null;
            for (final String currentXMLBase: xmlBaseValues) {
                final URI currentXMLBaseURI = new URI(currentXMLBase);
                result = (result == null) ? currentXMLBaseURI : result.resolve(currentXMLBaseURI);
            }
            return result;
        } catch (URISyntaxException e) {
            throw new ValidationException("Error while resolving URI: " + uri, e, (element != null) ? (LocationData) element.getData() : null);
        }
    }

    /**
     * Resolve f:url-norewrite attributes on this element, taking into account ancestor f:url-norewrite attributes for
     * the resolution.
     *
     * @param element   element used to start resolution
     * @return          true if rewriting is turned off, false otherwise
     */
    public static boolean resolveUrlNorewrite(Element element) {
        Element currentElement = element;
        do {
            final String urlNorewriteAttribute = currentElement.attributeValue(XMLConstants.FORMATTING_URL_NOREWRITE_QNAME);
            // Return the first ancestor value found
            if (urlNorewriteAttribute != null)
                return "true".equals(urlNorewriteAttribute);
            currentElement = currentElement.getParent();
        } while(currentElement != null);

        // Default is to rewrite
        return false;
    }

    /**
     * Log a message and Document.
     *
     * @param debugMessage  the message to display
     * @param document      the Document to display
     */
    public static void logDebugDocument(String debugMessage, Document document) {
        DebugProcessor.logger.info(debugMessage + ":\n" + Dom4jUtils.domToString(document));
    }

    /**
     * Prefix an id with the container namespace if needed. If the id is null, return null.
     *
     * @param containingDocument    current ContainingDocument
     * @param id                    id to prefix
     * @return                      prefixed id or null
     */
    public static String namespaceId(XFormsContainingDocument containingDocument, CharSequence id) {
        if (id == null)
            return null;
        else
            return containingDocument.getContainerNamespace() + id;
    }

    /**
     * Remove the container namespace prefix if possible. If the id is null, return null.
     *
     * @param containingDocument    current ContainingDocument
     * @param id                    id to de-prefix
     * @return                      de-prefixed id if possible or null
     */
    public static String deNamespaceId(XFormsContainingDocument containingDocument, String id) {
        if (id == null)
            return null;

        final String containerNamespace = containingDocument.getContainerNamespace();
        if (containerNamespace.length() > 0 && id.startsWith(containerNamespace))
            return id.substring(containerNamespace.length());
        else
            return id;
    }

    /**
     * Return LocationData for a given node, null if not found.
     *
     * @param node  node containing the LocationData
     * @return      LocationData or null
     */
    public static LocationData getNodeLocationData(Node node) {
        final Object data;
        {
            if (node instanceof Element)
                data = ((Element) node).getData();
            else if (node instanceof Attribute)
                data = ((Attribute) node).getData();
            else
                data = null;
            // TODO: other node types
        }
        if (data == null)
            return null;
        else if (data instanceof LocationData)
            return (LocationData) data;
        else if (data instanceof InstanceData)
            return ((InstanceData) data).getLocationData();

        return null;
    }

    /**
     * Return the underlying Node from the given NodeInfo, possibly converting it to a Dom4j Node. Changes to the returned Node may or may not 
     * reflect on the original, depending on its type.
     *
     * @param nodeInfo      NodeInfo to process
     * @return              Node
     */
    public static Node getNodeFromNodeInfoConvert(NodeInfo nodeInfo) {
        if (nodeInfo instanceof NodeWrapper)
            return (Node) ((NodeWrapper) nodeInfo).getUnderlyingNode();
        else
            return TransformerUtils.tinyTreeToDom4j2((nodeInfo.getParent() instanceof DocumentInfo) ? nodeInfo.getParent() : nodeInfo);
    }

    /**
     * Return the underlying Node from the given NodeInfo if possible. If not, throw an exception with the given error
     * message.
     *
     * @param nodeInfo      NodeInfo to process
     * @param errorMessage  error message to throw
     * @return              Node if found
     */
    public static Node getNodeFromNodeInfo(NodeInfo nodeInfo, String errorMessage) {
        if (!(nodeInfo instanceof NodeWrapper))
            throw new OXFException(errorMessage);

        return (Node) ((NodeWrapper) nodeInfo).getUnderlyingNode();
    }

    /**
     * Return whether the given node has at least one child element.
     *
     * @param nodeInfo  NodeInfo to look at
     * @return          true iff NodeInfo has at least one child element
     */
    public static boolean hasChildrenElements(NodeInfo nodeInfo) {
        final AxisIterator i = nodeInfo.iterateAxis(Axis.CHILD);
        i.next();
        while (i.current() != null) {
            final Item current = i.current();
            if (current instanceof NodeInfo) {
                final NodeInfo currentNodeInfo = (NodeInfo) current;
                if (currentNodeInfo.getNodeKind() == org.w3c.dom.Document.ELEMENT_NODE) {
                    return true;
                }
            }
            i.next();
        }
        return false;
    }

    /**
     * Return whether the given string contains a well-formed XPath 2.0 expression.
     *
     * @param xpathString       string to check
     * @param namespaceMapping  in-scope namespaces
     * @return                  true iif the given string contains well-formed XPath 2.0
     */
    public static boolean isXPath2Expression(Configuration configuration, String xpathString, NamespaceMapping namespaceMapping) {
        // Empty string is never well-formed XPath
        if (xpathString.trim().length() == 0)
            return false;

        try {
            XPathCache.checkXPathExpression(configuration, xpathString, namespaceMapping, XFormsContainingDocument.getFunctionLibrary());
        } catch (Exception e) {
            // Ideally we would like the parser to not throw as this is time-consuming, but not sure ho.w to achieve that
            return false;
        }

        return true;
    }
    
    private static String[] voidElementsNames = {
        // HTML 5: http://www.w3.org/TR/html5/syntax.html#void-elements
        "area", "base", "br", "col", "command", "embed", "hr", "img", "input", "keygen", "link", "meta", "param", "source", "track", "wbr",
        // Legacy
        "basefont", "frame", "isindex"
    };
    private static final Set<String> voidElements = new HashSet<String>(Arrays.asList(voidElementsNames));
    
    public static boolean isVoidElement(String elementName) {
        return voidElements.contains(elementName);
    }

    private static class LHHAElementVisitorListener implements Dom4jUtils.VisitorListener {
        private final String prefix;
        private final XBLContainer container;
        private final XFormsContextStack contextStack;
        private final String sourceEffectiveId;
        private final boolean acceptHTML;
        private final boolean[] containsHTML;
        private final StringBuilder sb;
        private final Element childElement;
        private final boolean hostLanguageAVTs;

        // Constructor for "static" case, i.e. when we know the child element cannot have dynamic content
        public LHHAElementVisitorListener(String prefix, boolean acceptHTML, boolean[] containsHTML, StringBuilder sb, Element childElement) {
            this.prefix = prefix;
            this.container = null;
            this.contextStack = null;
            this.sourceEffectiveId = null;
            this.acceptHTML = acceptHTML;
            this.containsHTML = containsHTML;
            this.sb = sb;
            this.childElement = childElement;
            this.hostLanguageAVTs = false;
        }

        // Constructor for "dynamic" case, i.e. when we know the child element can have dynamic content
        public LHHAElementVisitorListener(XBLContainer container, XFormsContextStack contextStack,
                                          String sourceEffectiveId, boolean acceptHTML, boolean[] containsHTML, StringBuilder sb, Element childElement) {
            this.prefix = container.getFullPrefix();
            this.container = container;
            this.contextStack = contextStack;
            this.sourceEffectiveId = sourceEffectiveId;
            this.acceptHTML = acceptHTML;
            this.containsHTML = containsHTML;
            this.sb = sb;
            this.childElement = childElement;
            this.hostLanguageAVTs = XFormsProperties.isHostLanguageAVTs();
        }

        private boolean lastIsStart = false;

        public void startElement(Element element) {
            if (element.getQName().equals(XFormsConstants.XFORMS_OUTPUT_QNAME)) {
                // This is an xforms:output nested among other markup

                final XFormsOutputControl outputControl = new XFormsOutputControl(container, null, element, null, null) {
                    // Override this as super.getContextStack() gets the containingDocument's stack, and here we need whatever is the current stack
                    // Probably need to modify super.getContextStack() at some point to NOT use the containingDocument's stack
                    @Override
                    public XFormsContextStack getContextStack() {
                        return LHHAElementVisitorListener.this.contextStack;
                    }

                    @Override
                    public String getEffectiveId() {
                        // Return given source effective id, so we have a source effective id for resolution of index(), etc.
                        return sourceEffectiveId;
                    }
                };
                contextStack.pushBinding(element, sourceEffectiveId, outputControl.getChildElementScope(element));
                {
                    outputControl.setBindingContext(contextStack.getCurrentBindingContext());
                    outputControl.evaluate();
                }
                contextStack.popBinding();

                if (outputControl.isRelevant()) {
                    if (acceptHTML) {
                        if ("text/html".equals(outputControl.getMediatype())) {
                            if (containsHTML != null)
                                containsHTML[0] = true; // this indicates for sure that there is some nested HTML
                            sb.append(outputControl.getExternalValue());
                        } else {
                            // Mediatype is not HTML so we don't escape
                            sb.append(XMLUtils.escapeXMLMinimal(outputControl.getExternalValue()));
                        }
                    } else {
                        if ("text/html".equals(outputControl.getMediatype())) {
                            // HTML is not allowed here, better tell the user
                            throw new OXFException("HTML not allowed within element: " + childElement.getName());
                        } else {
                            // Mediatype is not HTML so we don't escape
                            sb.append(outputControl.getExternalValue());
                        }
                    }
                }
            } else {
                // This is a regular element, just serialize the start tag to no namespace

                // If HTML is not allowed here, better tell the user
                if (!acceptHTML)
                    throw new OXFException("Nested XHTML or XForms not allowed within element: " + childElement.getName());

                if (containsHTML != null)
                    containsHTML[0] = true;// this indicates for sure that there is some nested HTML

                sb.append('<');
                sb.append(element.getName());
                final List attributes = element.attributes();
                if (attributes.size() > 0) {
                    for (Object attribute: attributes) {
                        final Attribute currentAttribute = (Attribute) attribute;

                        final String currentAttributeName = currentAttribute.getName();
                        final String currentAttributeValue = currentAttribute.getValue();

                        final String resolvedValue;
                        if (hostLanguageAVTs && maybeAVT(currentAttributeValue)) {
                            // This is an AVT, use attribute control to produce the output
                            final XXFormsAttributeControl attributeControl
                                    = new XXFormsAttributeControl(container, element, currentAttributeName, currentAttributeValue, element.getName());

                            contextStack.pushBinding(element, sourceEffectiveId, attributeControl.getChildElementScope(element));
                            {
                                attributeControl.setBindingContext(contextStack.getCurrentBindingContext());
                                attributeControl.evaluate();
                            }
                            contextStack.popBinding();

                            resolvedValue = attributeControl.getExternalValue();
                        } else if (currentAttributeName.equals("id")) {
                            // This is an id, prefix if needed
                            resolvedValue = prefix + currentAttributeValue;
                        } else {
                            // Simply use control value
                            resolvedValue = currentAttributeValue;
                        }

                        // Only consider attributes in no namespace
                        if ("".equals(currentAttribute.getNamespaceURI())) {
                            sb.append(' ');
                            sb.append(currentAttributeName);
                            sb.append("=\"");
                            if (resolvedValue != null)
                                sb.append(XMLUtils.escapeXMLMinimal(resolvedValue));
                            sb.append('"');
                        }
                    }
                }
                sb.append('>');
                lastIsStart = true;
            }
        }

        public void endElement(Element element) {
            final String elementName = element.getName();
            if ((!lastIsStart || !isVoidElement(elementName)) && !element.getQName().equals(XFormsConstants.XFORMS_OUTPUT_QNAME)) {
                // This is a regular element, just serialize the end tag to no namespace
                // UNLESS the element was just opened. This means we output <br>, not <br></br>, etc.
                sb.append("</");
                sb.append(elementName);
                sb.append('>');
            }
            lastIsStart = false;
        }

        public void text(Text text) {
            sb.append(acceptHTML ? XMLUtils.escapeXMLMinimal(text.getStringValue()) : text.getStringValue());
            lastIsStart = false;
        }
    }

    public static String escapeJavaScript(String value) {
        return StringUtils.replace(StringUtils.replace(StringUtils.replace(value, "\\", "\\\\"), "\"", "\\\""), "\n", "\\n");
    }

    public static boolean maybeAVT(String attributeValue) {
        return attributeValue.indexOf('{') != -1;
    }

    /**
     * Return the prefix of an effective id, e.g. "" or "foo$bar$". The prefix returned does end with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              prefix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdPrefix(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return effectiveId.substring(0, prefixIndex + 1);
        } else {
            return "";
        }
    }

    /**
     * Return the prefix of an effective id, e.g. "" or "foo$bar". The prefix returned does NOT end with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              prefix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdPrefixNoSeparator(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return effectiveId.substring(0, prefixIndex);
        } else {
            return "";
        }
    }

    /**
     * Return whether the effective id has a suffix.
     *
     * @param effectiveId   effective id to check
     * @return              true iif the effective id has a suffix
     */
    public static boolean hasEffectiveIdSuffix(String effectiveId) {
        return (effectiveId != null) && effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) != -1;
    }

    /**
     * Return the suffix of an effective id, e.g. "" or "2-5-1". The suffix returned does not start with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              suffix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdSuffix(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            return effectiveId.substring(suffixIndex + 1);
        } else {
            return "";
        }
    }

    /**
     * Return the suffix of an effective id, e.g. "" or ".2-5-1". The suffix returned starts with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              suffix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdSuffixWithSeparator(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            return effectiveId.substring(suffixIndex);
        } else {
            return "";
        }
    }

    /**
     * Return an effective id's prefixed id, i.e. the effective id without its suffix, e.g.:
     *
     * o foo$bar$my-input.1-2 => foo$bar$my-input
     *
     * @param effectiveId   effective id to check
     * @return              effective id without its suffix, null if effectiveId was null
     */
    public static String getPrefixedId(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            return effectiveId.substring(0, suffixIndex);
        } else {
            return effectiveId;
        }
    }

    /**
     * Return an effective id without its prefix, e.g.:
     *
     * o foo$bar$my-input => my-input
     * o foo$bar$my-input.1-2 => my-input.1-2
     *
     * @param effectiveId   effective id to check
     * @return              effective id without its prefix, null if effectiveId was null
     */
    public static String getEffectiveIdNoPrefix(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return effectiveId.substring(prefixIndex + 1);
        } else {
            return effectiveId;
        }
    }

    /**
     * Return the parts of an effective id prefix, e.g. for foo$bar$my-input return new String[] { "foo", "bar" }
     *
     * @param effectiveId   effective id to check
     * @return              array of parts, empty array if no parts, null if effectiveId was null
     */
    public static String[] getEffectiveIdPrefixParts(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return StringUtils.split(effectiveId.substring(0, prefixIndex), XFormsConstants.COMPONENT_SEPARATOR);
        } else {
            return new String[0];
        }
    }

    /**
     * Given a repeat control's effective id, compute the effective id of an iteration.
     *
     * @param repeatEffectiveId     repeat control effective id
     * @param iterationIndex        repeat iteration
     * @return                      repeat iteration effective id
     */
    public static String getIterationEffectiveId(String repeatEffectiveId, int iterationIndex) {
        final String parentSuffix = XFormsUtils.getEffectiveIdSuffixWithSeparator(repeatEffectiveId);
        final String iterationPrefixedId = getPrefixedId(repeatEffectiveId) + "~iteration";
        if (parentSuffix.equals("")) {
            // E.g. foobar => foobar~iteration.3
            return iterationPrefixedId + XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1 + iterationIndex;
        } else {
            // E.g. foobar.3-7 => foobar~iteration.3-7-2
            return iterationPrefixedId + parentSuffix + XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_2 + iterationIndex;
        }
    }

    /**
     * Return the parts of an effective id suffix, e.g. for $foo$bar.3-1-5 return new Integer[] { 3, 1, 5 }
     *
     * @param effectiveId   effective id to check
     * @return              array of parts, empty array if no parts, null if effectiveId was null
     */
    public static int[] getEffectiveIdSuffixParts(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            final String[] stringResult = StringUtils.split(effectiveId.substring(suffixIndex + 1), XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_2);
            final int[] result = new int[stringResult.length];
            for (int i = 0; i < stringResult.length; i++) {
                final String currentString = stringResult[i];
                result[i] = Integer.parseInt(currentString);
            }
            return result;
        } else {
            return new int[0];
        }
    }

    /**
     * Compute an effective id based on an existing effective id and a static id. E.g.:
     *
     *  foo$bar.1-2 and myStaticId => foo$myStaticId.1-2
     *
     * @param baseEffectiveId   base effective id
     * @param staticId          static id
     * @return                  effective id
     */
    public static String getRelatedEffectiveId(String baseEffectiveId, String staticId) {
        final String prefix = getEffectiveIdPrefix(baseEffectiveId);
        final String suffix; {
            final int suffixIndex = baseEffectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
            suffix = (suffixIndex == -1) ? "" : baseEffectiveId.substring(suffixIndex);
        }
        return prefix + staticId + suffix;
    }

    /**
     * Return the static id associated with the given id, removing suffix and prefix if present.
     *
     *  foo$bar.1-2 => bar
     *
     * @param anyId id to check
     * @return      static id, or null if anyId was null
     */
    public static String getStaticIdFromId(String anyId) {
        return getPrefixedId(getEffectiveIdNoPrefix(anyId));
    }

    /**
     * Append a new string to an effective id.
     *
     *   foo$bar.1-2 and -my-ending => foo$bar-my-ending.1-2
     *
     * @param effectiveId   base effective id
     * @param ending        new ending
     * @return              effective id
     */
    public static String appendToEffectiveId(String effectiveId, String ending) {
        final String prefixedId = getPrefixedId(effectiveId);
        return prefixedId + ending + getEffectiveIdSuffixWithSeparator(effectiveId);
    }

    /**
     * Check if an id is a static id, i.e. if it does not contain component/hierarchy separators.
     *
     * @param staticId  static id to check
     * @return          true if the id is a static id
     */
    public static boolean isStaticId(String staticId) {
        return staticId.indexOf(XFormsConstants.COMPONENT_SEPARATOR) == -1 && staticId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) == -1;
    }

    /**
     * Convert an absolute id to an effective id.
     */
    public static String absoluteIdToEffectiveId(String absoluteId) {
        assert absoluteId.startsWith("/");

        return absoluteId.substring(1).replace('/', '$');
    }

    public static boolean isTopLevelId(String id) {
        // NOTE: Top-level id if static id == prefixed id
        return id.equals(XFormsUtils.getStaticIdFromId(id));
    }

    /**
     * Return the id of the enclosing HTML <form> element.
     *
     * @param containingDocument    containing document
     * @return                      id, possibly namespaced
     */
    public static String getFormId(XFormsContainingDocument containingDocument) {
        return namespaceId(containingDocument, "xforms-form");
    }

    public static boolean compareItems(Item item1, Item item2) {
        if (item1 == null && item2 == null) {
            return true;
        }
        if (item1 == null || item2 == null) {
            return false;
        }

        if (item1 instanceof StringValue) {
            // Saxon doesn't allow equals() on StringValue because it requires a collation and equals() might throw
            if (item2 instanceof StringValue) {
                final StringValue currentStringValue = (StringValue) item1;
                if (currentStringValue.codepointEquals((StringValue) item2)) {
                    return true;
                }
            }
        } else if (item1 instanceof NumericValue) {
            // Saxon doesn't allow equals() between numeric and non-numeric values
            if (item2 instanceof NumericValue) {
                final NumericValue currentNumericValue = (NumericValue) item1;
                if (currentNumericValue.equals((NumericValue) item2)) {
                    return true;
                }
            }
        } else if (item1 instanceof AtomicValue) {
            if (item2 instanceof AtomicValue) {
                final AtomicValue currentAtomicValue = (AtomicValue) item1;
                if (currentAtomicValue.equals((AtomicValue) item2)) {
                    return true;
                }
            }
        } else {
            if (item1.equals(item2)) {// equals() is the same as isSameNodeInfo() for NodeInfo, and compares the values for values
                return true;
            }
        }
        return false;
    }

    /**
     * Get an element's id.
     *
     * @param element   element to check
     * @return          id or null
     */
    public static String getElementId(Element element) {
        return element.attributeValue(XFormsConstants.ID_QNAME);
    }
}
