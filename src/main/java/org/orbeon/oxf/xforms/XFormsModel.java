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

import org.apache.log4j.Logger;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.OrbeonLocationException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.resources.URLFactory;
import org.orbeon.oxf.util.*;
import org.orbeon.oxf.xforms.analysis.ElementAnalysis;
import org.orbeon.oxf.xforms.analysis.model.Instance;
import org.orbeon.oxf.xforms.analysis.model.Model;
import org.orbeon.oxf.xforms.analysis.model.Submission;
import org.orbeon.oxf.xforms.control.Controls;
import org.orbeon.oxf.xforms.event.*;
import org.orbeon.oxf.xforms.event.events.*;
import org.orbeon.oxf.xforms.model.DataModel;
import org.orbeon.oxf.xforms.model.XFormsModelAction;
import org.orbeon.oxf.xforms.state.InstanceState;
import org.orbeon.oxf.xforms.submission.BaseSubmission;
import org.orbeon.oxf.xforms.submission.SubmissionUtils;
import org.orbeon.oxf.xforms.submission.XFormsModelSubmission;
import org.orbeon.oxf.xforms.xbl.Scope;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.om.*;
import org.orbeon.saxon.trans.XPathException;
import org.orbeon.saxon.value.Value;
import scala.Option;

import java.net.URL;
import java.util.*;

/**
 * Represents an XForms model.
 */
public class XFormsModel extends XFormsModelBase implements XFormsEventObserver, XFormsObjectResolver {

    public static final String LOGGING_CATEGORY = "model";
    public static final Logger logger = LoggerFactory.createLogger(XFormsModel.class);
    public final IndentedLogger indentedLogger;

    // Static representation of this model
    public final Model staticModel;

    // Model attributes
    private String effectiveId; // not final because can change if model within repeat iteration

    // Instances
    private final List<String> instanceIds;
    private final List<XFormsInstance> instances;
    private final Map<String, XFormsInstance> instancesMap;

    // Submissions and actions
    private final Map<String, XFormsModelSubmission> submissions;
    private final Map<String, XFormsModelAction> actions = new HashMap<String, XFormsModelAction>();

    // Context and variables
    private BindingContext defaultEvaluationContext;
    private Map<String, ValueRepresentation> topLevelVariables = new LinkedHashMap<String, ValueRepresentation>();

    // Binds
    private final XFormsModelBinds binds;
    private final boolean mustBindValidate;

    // Schema validation
    private XFormsModelSchemaValidator schemaValidator;
    private boolean hasSchema;

    // Container
    private final XBLContainer container;
    private final XFormsContextStack contextStack;    // context stack for evaluation, used by binds, submissions, event handlers

    // Containing document
    public final XFormsContainingDocument containingDocument;

    public XFormsContainingDocument containingDocument() {
        return containingDocument;
    }

    public XFormsModel(XBLContainer container, String effectiveId, Model staticModel) {

        // Remember static model
        this.staticModel = staticModel;

        // Set container
        this.container = container;
        this.containingDocument = container.getContainingDocument();

        this.indentedLogger = containingDocument.getIndentedLogger(LOGGING_CATEGORY);

        this.effectiveId = effectiveId;

        // Extract list of instances ids
        {
            final Collection<Instance> staticInstances = staticModel.instancesMap().values();
            if (staticInstances.isEmpty()) {
                // No instance in this model
                instanceIds = Collections.emptyList();
                instances = Collections.emptyList();
                instancesMap = Collections.emptyMap();
            } else {
                // At least one instance in this model
                instanceIds = new ArrayList<String>(staticInstances.size());
                for (final Instance instance : staticInstances)
                    instanceIds.add(instance.staticId());
                instances = Arrays.asList(new XFormsInstance[staticInstances.size()]);
                instancesMap = new HashMap<String, XFormsInstance>(staticInstances.size());
            }
        }

        // Get submissions
        {
            final List<Submission> staticSubmissions = staticModel.jSubmissions();
            if (staticSubmissions.isEmpty()) {
                // No submission in this model
                submissions = Collections.emptyMap();
            } else {
                // At least one submission in this model
                submissions = new HashMap<String, XFormsModelSubmission>();
                for (final Submission staticSubmission : staticSubmissions)
                    submissions.put(staticSubmission.staticId(), new XFormsModelSubmission(this.container, staticSubmission, this));
            }
        }

        // Get all event handlers
        for (final EventHandlerImpl staticEventHandler : staticModel.jEventHandlers()) {
            final ElementAnalysis staticParent = staticEventHandler.parent().get();

            final XFormsEventObserver parent;
            if (staticParent instanceof Submission) {
                parent = submissions.get(staticParent.staticId());
            } else {
                parent = XFormsModel.this;
            }

            actions.put(staticEventHandler.staticId(), new XFormsModelAction(parent, staticEventHandler));
        }

        // Create binds object
        binds = XFormsModelBinds.create(this);
        mustBindValidate = binds != null;

        // Create context stack
        this.contextStack = new XFormsContextStack(this);

        // Temporarily initialize the evaluation context to an empty context, so that handlers upon xforms-model-construct can work
        this.defaultEvaluationContext = XFormsContextStack.defaultContext(null, container, this);
    }

    // Evaluate all top-level variables
    public void resetAndEvaluateVariables() {

        // NOTE: This method is called during RRR and by submission processing. Need to do dependency handling.

        // Reset context to this model, including evaluating the model variables
        contextStack.resetBindingContext(this);

        // Remember context and variables
        defaultEvaluationContext = contextStack.getCurrentBindingContext();
    }

    // Return the value of the given model variable
    public SequenceIterator getVariable(String variableName) throws XPathException {
        return Value.asIterator(topLevelVariables.get(variableName));
    }

    public void updateEffectiveId(String effectiveId) {
        this.effectiveId = effectiveId;
    }

    public String getPrefixedId() {
        return staticModel.prefixedId();
    }

    public IndentedLogger getIndentedLogger() {
        return indentedLogger;
    }

    public XFormsContextStack getContextStack() {
        return contextStack;
    }

    public XBLContainer container() {
        return container;
    }

    public Model getStaticModel() {
        return staticModel;
    }

    /**
     * Get object with the id specified.
     */
    public XFormsObject getObjectByEffectiveId(String effectiveId) {

        // If prefixes or suffixes don't match, object can't be found here
        if (!container().getFullPrefix().equals(XFormsUtils.getEffectiveIdPrefix(effectiveId))
                || !XFormsUtils.getEffectiveIdSuffix(container().getEffectiveId()).equals(XFormsUtils.getEffectiveIdSuffix(effectiveId))) {
            return null;
        }

        // Find by static id
        return resolveObjectById(null, XFormsUtils.getStaticIdFromId(effectiveId), null);
    }

    /**
     * Resolve an object. This optionally depends on a source, and involves resolving whether the source is within a
     * repeat or a component.
     *
     * @param sourceEffectiveId  effective id of the source, or null
     * @param targetStaticId     static id of the target
     * @param contextItem        context item, or null (used for bind resolution only)
     * @return                   object, or null if not found
     */
    public XFormsObject resolveObjectById(String sourceEffectiveId, String targetStaticId, Item contextItem) {

        if (XFormsUtils.isEffectiveId(targetStaticId) || XFormsUtils.isAbsoluteId(targetStaticId))
            throw new OXFException("Target id must be static id: " + targetStaticId);

        // Check this id
        if (targetStaticId.equals(getId()))
            return this;

        // Search instances
        final XFormsInstance instance = instancesMap.get(targetStaticId);
        if (instance != null)
            return instance;

        // Search submissions
        if (submissions != null) {
            final XFormsModelSubmission resultSubmission = submissions.get(targetStaticId);
            if (resultSubmission != null)
                return resultSubmission;
        }

        // Search actions
        {
            final XFormsModelAction action = actions.get(targetStaticId);
            if (action != null)
                return action;
        }

        // Search binds
        if (binds != null) {
            final RuntimeBind bind = binds.resolveBind(targetStaticId, contextItem);
            if (bind != null)
                return bind;
        }

        return null;
    }

    /**
     * Return the default instance for this model, i.e. the first instance. Return null if there is
     * no instance in this model.
     *
     * @return  XFormsInstance or null
     */
    public XFormsInstance getDefaultInstance() {
        return ! instances.isEmpty() ? instances.get(0) : null;
    }

    /**
     * Return all XFormsInstance objects for this model, in the order they appear in the model.
     */
    public List<XFormsInstance> getInstances() {
        return instances;
    }

    /**
     * Return the XFormsInstance with given id, null if not found.
     */
    public XFormsInstance getInstance(String instanceStaticId) {
        return instancesMap.get(instanceStaticId);
    }

    /**
     * Return the XFormsInstance object containing the given node.
     */
    public XFormsInstance getInstanceForNode(NodeInfo nodeInfo) {

        final DocumentInfo documentInfo = nodeInfo.getDocumentRoot();

        // NOTE: We shouldn't even be called if the parent control is not relevant.
        if (container.isRelevant()) {
            for (final XFormsInstance currentInstance: instances) {
                if (currentInstance.documentInfo().isSameNodeInfo(documentInfo))
                    return currentInstance;
            }
        }

        return null;
    }

    /**
     * Set an instance. The id of the instance must exist in the model.
     */
    public void indexInstance(XFormsInstance instance) {
        final String instanceId = instance.instance().staticId();
        final int instancePosition = instanceIds.indexOf(instanceId);
        instances.set(instancePosition, instance);
        instancesMap.put(instanceId, instance);
    }

    public String getId() {
        return staticModel.staticId();
    }

    public String getEffectiveId() {
        return effectiveId;
    }

    public Scope scope() {
        return staticModel.scope();
    }

    public Scope getResolutionScope() {
        return container.getPartAnalysis().scopeForPrefixedId(getPrefixedId());
    }

    public LocationData getLocationData() {
        return staticModel.locationData();
    }

    public XFormsModelBinds getBinds() {
        return binds;
    }

    private void loadSchemasIfNeeded() {
        if (schemaValidator == null) {
            final Element modelElement = staticModel.element();
            schemaValidator = new XFormsModelSchemaValidator(modelElement, indentedLogger);
            schemaValidator.loadSchemas(containingDocument);

            hasSchema = schemaValidator.hasSchema();
        }
    }

    public boolean hasSchema() {
        return hasSchema;
    }

    public XFormsModelSchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    public String[] getSchemaURIs() {
        if (hasSchema) {
            return schemaValidator.getSchemaURIs();
        } else {
            return null;
        }
    }

    /**
     * Restore the state of the model when the model object was just recreated.
     */
    public void restoreState() {
        // Ensure schema are loaded
        loadSchemasIfNeeded();

        // Refresh binds, but do not recalculate (only evaluate "computed expression binds")
        deferredActionContext.rebuild = true;
        deferredActionContext.revalidate = true;

        doRebuild();
        if (binds != null)
            binds.applyComputedExpressionBinds();
        doRevalidate();
    }

    /**
     * Restore all the instances serialized as children of the given container element.
     */
    public void restoreInstances() {

        // Find serialized instances from context
        final List<InstanceState> instanceStates = Controls.restoringInstancesJava();

        // Get instances from dynamic state first
        if (instanceStates != null) {
            for (final InstanceState state : instanceStates) {
                // Check that the instance belongs to this model
                if (effectiveId.equals(state.modelEffectiveId())) {
                    // NOTE: Here instance must contain document
                    XFormsInstance.restoreInstanceFromState(this, state, INSTANCE_LOADER);
                    indentedLogger.logDebug("restore", "restoring instance from dynamic state", "model effective id", effectiveId, "instance effective id", state.effectiveId());
                }
            }
        }

        // Then get missing instances from static state if necessary
        // This can happen if the instance is not replaced, readonly and inline
        for (final Instance instance : container().getPartAnalysis().getInstances(getPrefixedId()))
            if (instancesMap.get(instance.staticId()) == null)
                setInlineInstance(instance);
    }

    public void performDefaultAction(XFormsEvent event) {
        final String eventName = event.name();
        if (XFormsEvents.XFORMS_MODEL_CONSTRUCT.equals(eventName)) {
            // 4.2.1 The xforms-model-construct Event
            // Bubbles: Yes / Cancelable: No / Context Info: None
            final XFormsModelConstructEvent modelConstructEvent = (XFormsModelConstructEvent) event;
            doModelConstruct(modelConstructEvent.rrr());
        } else if (XFormsEvents.XXFORMS_READY.equals(eventName)) {
            // This is called after xforms-ready events have been dispatched to all models
            doAfterReady();
        } else if (XFormsEvents.XFORMS_MODEL_CONSTRUCT_DONE.equals(eventName)) {
            // 4.2.2 The xforms-model-construct-done Event
            // Bubbles: Yes / Cancelable: No / Context Info: None
            // TODO: implicit lazy instance construction
        } else if (XFormsEvents.XFORMS_REBUILD.equals(eventName)) {
            // 4.3.7 The xforms-rebuild Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doRebuild();
        } else if (XFormsEvents.XFORMS_RECALCULATE.equals(eventName)) {
            // 4.3.6 The xforms-recalculate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            final XFormsRecalculateEvent recalculateEvent = (XFormsRecalculateEvent) event;
            doRecalculate(recalculateEvent.applyDefaults());
        } else if (XFormsEvents.XFORMS_REVALIDATE.equals(eventName)) {
            // 4.3.5 The xforms-revalidate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doRevalidate();
        } else if (XFormsEvents.XFORMS_REFRESH.equals(eventName)) {
            // 4.3.4 The xforms-refresh Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doRefresh();
        } else if (XFormsEvents.XFORMS_RESET.equals(eventName)) {
            // 4.3.8 The xforms-reset Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doReset();
        } else if (XFormsEvents.XFORMS_LINK_EXCEPTION.equals(eventName)) {
            // 4.5.2 The xforms-link-exception Event
            // Bubbles: Yes / Cancelable: No / Context Info: The URI that failed to load (xsd:anyURI)
            // The default action for this event results in the following: Fatal error.

            final XFormsLinkExceptionEvent exceptionEvent = (XFormsLinkExceptionEvent) event;
            final Throwable throwable = exceptionEvent.throwable();
            if (throwable instanceof RuntimeException)
                throw (RuntimeException) throwable;
            else
                throw new ValidationException("Received fatal error event: " + eventName, throwable, getLocationData());
        } else if (XFormsEvents.XXFORMS_XPATH_ERROR.equals(eventName)) {
            // Custom event for XPath errors
            // NOTE: We don't like this event very much as it is dispatched in the middle of rebuild/recalculate/revalidate,
            // and event handlers for this have to be careful. It might be better to dispatch it *after* RRR.

            final XXFormsXPathErrorEvent ev = (XXFormsXPathErrorEvent) event;
            XFormsError.handleNonFatalXPathError(container(), ev.throwable());
        } else if (XFormsEvents.XXFORMS_BINDING_ERROR.equals(eventName)) {
            // Custom event for binding errors
            // NOTE: We don't like this event very much as it is dispatched in the middle of rebuild/recalculate/revalidate,
            // and event handlers for this have to be careful. It might be better to dispatch it *after* RRR.

            final XXFormsBindingErrorEvent ev = (XXFormsBindingErrorEvent) event;
            XFormsError.handleNonFatalSetvalueError(this, ev.locationData(), ev.reason());
        } else if (XFormsEvents.XXFORMS_ACTION_ERROR.equals(eventName)) {
            final XXFormsActionErrorEvent ev = (XXFormsActionErrorEvent) event;
            XFormsError.handleNonFatalActionError(this, ev.throwable());
        }
    }

    private void doReset() {
        // TODO
        // "The instance data is reset to the tree structure and values it had immediately
        // after having processed the xforms-ready event."

        // "Then, the events xforms-rebuild, xforms-recalculate, xforms-revalidate and
        // xforms-refresh are dispatched to the model element in sequence."
        Dispatch.dispatchEvent(new XFormsRebuildEvent(XFormsModel.this));
        Dispatch.dispatchEvent(new XFormsRecalculateEvent(XFormsModel.this));
        Dispatch.dispatchEvent(new XFormsRevalidateEvent(XFormsModel.this));
        Dispatch.dispatchEvent(new XFormsRefreshEvent(XFormsModel.this));
    }

    private void doAfterReady() {
    }

    private void doModelConstruct(boolean rrr) {
        final Element modelElement = staticModel.element();

        // 1. All XML Schema loaded (throws xforms-link-exception)

        try {
            loadSchemasIfNeeded();
        } catch (Exception e) {
            final String schemaAttribute = modelElement.attributeValue(XFormsConstants.SCHEMA_QNAME);
            Dispatch.dispatchEvent(new XFormsLinkExceptionEvent(XFormsModel.this, schemaAttribute, e));
        }

        // 2. Create XPath data model from instance (inline or external) (throws xforms-link-exception)
        //    Instance may not be specified.

        {
            // Build initial instance documents

            // Iterate through all instances
            int instancePosition = 0;
            for (final Instance instance : staticModel.instancesMap().values()) {
                // Skip processing in case somebody has already set this particular instance
                // FIXME: can this ever happen?
                if (instances.get(instancePosition++) == null) {
                    // Load instance. This might throw an exception event (and therefore a Java exception) in case of fatal problem.
                    loadInitialInstance(instance);
                }
            }
        }

        // 3. P3P (N/A)

        // 4. Instance data is constructed. Evaluate binds:
        //    a. Evaluate nodeset
        //    b. Apply model item properties on nodes
        //    c. Throws xforms-binding-exception if the node has already model item property with same name
        // TODO: a, b, c

        // 5. xforms-rebuild, xforms-recalculate, xforms-revalidate
        deferredActionContext.markStructuralChange();

        if (rrr) {
            doRebuild();
            doRecalculate(false);
            doRevalidate();
        }
    }

    private void loadInitialInstance(Instance instance) {

        indentedLogger.startHandleOperation("load", "loading instance", "instance id", instance.staticId());
        {
            if (instance.useExternalContent()) {
                // Load from @src or @resource
                loadInitialExternalInstanceFromCacheIfNeeded(instance);
            } else if (instance.useInlineContent()) {
                // Load from inline content
                try {
                    setInlineInstance(instance);
                } catch (Exception e) {
                    final LocationData extendedLocationData = new ExtendedLocationData(instance.locationData(), "processing XForms instance", instance.element());
                    final Throwable throwable = new ValidationException("Error extracting or setting inline instance", extendedLocationData);
                    Dispatch.dispatchEvent(new XFormsLinkExceptionEvent(XFormsModel.this, null, throwable));
                }
            } else {
                // Everything missing
                final LocationData extendedLocationData = new ExtendedLocationData(instance.locationData(), "processing XForms instance", instance.element());
                final Throwable throwable = new ValidationException("Required @src attribute, @resource attribute, or inline content for instance: " + instance.staticId(), extendedLocationData);
                Dispatch.dispatchEvent(new XFormsLinkExceptionEvent(XFormsModel.this, "", throwable));
            }
        }
        indentedLogger.endHandleOperation();
    }

    private void setInlineInstance(Instance instance) {
        // Extract document
        final DocumentInfo instanceDocument = instance.inlineContent();

        // Set instance and associated information if everything went well
        // NOTE: No XInclude supported to read instances with @src for now
        indexInstance(XFormsInstance.apply(this, instance, instanceDocument));
    }

    private String resolveInstanceURL(Instance instance) {
        return XFormsUtils.resolveServiceURL(
            containingDocument,
            instance.element(),
            instance.instanceSource().get(),
            ExternalContext.Response.REWRITE_MODE_ABSOLUTE);
    }

    private void loadInitialExternalInstanceFromCacheIfNeeded(Instance instance) {
        final String instanceResource = instance.instanceSource().get();
        try {
            if (instance.cache() && ! ProcessorImpl.isProcessorInputScheme(instanceResource)) {
                // Instance 1) has cache hint and 2) is not input:*, so it can be cached
                // NOTE: We don't allow sharing for input:* URLs as the data will likely differ per request

                // TODO: This doesn't handle optimized submissions.

                // NOTE: No XInclude supported to read instances with @src for now
                final InstanceCaching caching = InstanceCaching.fromInstance(instance, resolveInstanceURL(instance), null);
                final DocumentInfo documentInfo =
                    XFormsServerSharedInstancesCache.findContentOrLoad(
                            indentedLogger,
                            instance,
                            caching,
                            instance.readonly(),
                            INSTANCE_LOADER);

                indexInstance(
                    new XFormsInstance(
                        this,
                        instance,
                        Option.<InstanceCaching>apply(caching),
                        documentInfo,
                        instance.readonly(),
                        false,
                        true));
            } else {
                // Instance cannot be cached

                // NOTE: Optimizing with include() for servlets has limitations, in particular
                // the proper split between servlet path and path info is not done.

                // TODO: Temporary. Use XFormsModelSubmission to load instances instead
                if (!NetUtils.urlHasProtocol(instanceResource) && containingDocument.getContainerType().equals("portlet"))
                    throw new UnsupportedOperationException("<xf:instance src=\"\"> with relative path within a portlet");

                // Use full resolved resource URL
                // - absolute URL, e.g. http://example.org/instance.xml
                // - absolute path relative to server root, e.g. /orbeon/foo/bar/instance.xml
                loadNonCachedExternalInstance(instance);
            }
        } catch (Exception e) {
            final ValidationException validationException
                = OrbeonLocationException.wrapException(e, new ExtendedLocationData(instance.locationData(), "reading external instance", instance.element()));
            Dispatch.dispatchEvent(new XFormsLinkExceptionEvent(XFormsModel.this, instanceResource, validationException));
        }
    }

    private final InstanceLoader INSTANCE_LOADER = new InstanceLoader();

    private class InstanceLoader implements XFormsServerSharedInstancesCache.Loader {
        public DocumentInfo load(String resolvedURL, boolean handleXInclude) {
            return SubmissionUtils.readTinyTree(XFormsModel.this, resolvedURL, handleXInclude);
        }
    }

    /*
     * Load an external instance using an absolute URL.
     */
    private void loadNonCachedExternalInstance(Instance instance) {

        final String absoluteURLString = resolveInstanceURL(instance);

        // Connect using external protocol

        final Object instanceDocument;// Document or DocumentInfo
        if (containingDocument.getURIResolver() == null) {
            // Connect directly if there is no resolver or if the instance is globally cached
            // NOTE: If there is no resolver, URLs of the form input:* are not allowed

            assert ! ProcessorImpl.isProcessorInputScheme(absoluteURLString);

            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("load", "getting document from URI", "URI", absoluteURLString);

            final URL absoluteResolvedURL = URLFactory.createURL(absoluteURLString);

            final scala.collection.immutable.Map<String, String[]> headers =
                Connection.jBuildConnectionHeaders(absoluteResolvedURL.getProtocol(), instance.credentialsOrNull(), null,
                        XFormsProperties.getForwardSubmissionHeaders(containingDocument), indentedLogger);

            final ConnectionResult connectionResult = Connection.jApply(
                "GET", absoluteResolvedURL, instance.credentialsOrNull(), null,
                headers, true, BaseSubmission.isLogBody(), indentedLogger).connect(true);

            try {
                // Handle connection errors
                if (connectionResult.statusCode() != 200) {
                    throw new OXFException("Got invalid return code while loading instance: " + absoluteURLString + ", " + connectionResult.statusCode());
                }

                // TODO: Handle validating and XInclude!

                // Read result as XML
                // TODO: use submission code
                if (!instance.readonly()) {
                    instanceDocument = TransformerUtils.readDom4j(connectionResult.getResponseInputStream(), connectionResult.resourceURI(), false, true);
                } else {
                    instanceDocument = TransformerUtils.readTinyTree(XPathCache.getGlobalConfiguration(),
                            connectionResult.getResponseInputStream(), connectionResult.resourceURI(), false, true);
                }
            } finally {
                // Clean-up
                connectionResult.close();
            }

        } else {
            // Optimized case that uses the provided resolver
            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("load", "getting document from resolver", "URI", absoluteURLString);

            // TODO: Handle validating and handleXInclude!

            if (!instance.readonly()) {
                instanceDocument = containingDocument.getURIResolver().readAsDom4j(
                        absoluteURLString, instance.credentialsOrNull(),
                        XFormsProperties.getForwardSubmissionHeaders(containingDocument));
            } else {
                instanceDocument = containingDocument.getURIResolver().readAsTinyTree(XPathCache.getGlobalConfiguration(),
                        absoluteURLString, instance.credentialsOrNull(),
                        XFormsProperties.getForwardSubmissionHeaders(containingDocument));
            }
        }

        // Set instance and associated information if everything went well
        // NOTE: No XInclude supported to read instances with @src for now
        final DocumentInfo documentInfo = XFormsInstance.createDocumentInfo(instanceDocument, instance.exposeXPathTypes());
        indexInstance(XFormsInstance.apply(this, instance, documentInfo));
    }

    public void performTargetAction(XFormsEvent event) {
        // NOP
    }

    private boolean hasInstancesAndBinds() {
        return ! instances.isEmpty() && binds != null;
    }

    public void doRebuild() {

        // Rebuild bind tree only if needed
        if (deferredActionContext.rebuild) {
            // Re-evaluate top-level variables if needed
            resetAndEvaluateVariables();

            if (hasInstancesAndBinds()) {
                // NOTE: contextStack.resetBindingContext(this) called in evaluateVariables()
                binds.rebuild();

                // Controls may have @bind or xxf:bind() references, so we need to mark them as dirty. Will need dependencies for controls to fix this.
                // TODO: Handle XPathDependencies
                container().requireRefresh();
            }

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            deferredActionContext.rebuild = false;
        }

        // Notify dependencies
        containingDocument.getXPathDependencies().rebuildDone(staticModel);
    }

    public void doRecalculate(boolean applyDefaults) {

        // Recalculate only if needed
        if (deferredActionContext.recalculate) {

            final boolean hasVariables = ! staticModel.jVariablesSeq().isEmpty();

            // Re-evaluate top-level variables if needed
            if (hasInstancesAndBinds() || hasVariables)
                resetAndEvaluateVariables();

            if (hasInstancesAndBinds()) {
                // Apply calculate binds
                binds.applyCalculateBinds(applyDefaults);
            }

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            deferredActionContext.recalculate = false;
        }

        // Notify dependencies
        containingDocument.getXPathDependencies().recalculateDone(staticModel);
    }


    public void doRevalidate() {

        Set<String> invalidInstances = null;

        // Validate only if needed, including checking the flags, because if validation state is clean, validation
        // being idempotent, revalidating is not needed.
        if (deferredActionContext.revalidate) {
            final boolean mustRevalidate = ! instances.isEmpty() && (mustBindValidate || hasSchema);
            if (mustRevalidate) {
                if (indentedLogger.isDebugEnabled())
                    indentedLogger.startHandleOperation("validation", "performing revalidate", "model id", getEffectiveId());

                // Clear schema validation state
                // NOTE: This could possibly be moved to rebuild(), but we must be careful about the presence of a schema
                for (final XFormsInstance instance: instances) {
                    // Only clear instances that are impacted by xf:bind/(@ref|@nodeset), assuming we were able to figure out the dependencies
                    // The reason is that clearing this state can take quite some time
                    final boolean instanceMightBeSchemaValidated = hasSchema && instance.isSchemaValidation();
                    if (instanceMightBeSchemaValidated) {
                        DataModel.visitElementJava(instance.rootElement(), new DataModel.NodeVisitor() {
                            public void visit(NodeInfo nodeInfo) {
                                InstanceData.clearSchemaState(nodeInfo);
                            }
                        });
                    }
                }

                // Run validation
                invalidInstances = new LinkedHashSet<String>();

                // Validate using schemas if needed
                if (hasSchema) {
                    // Apply schemas to all instances
                    for (final XFormsInstance instance : instances) {
                        // Currently we don't support validating read-only instances
                        if (instance.isSchemaValidation()) {
                            if (!schemaValidator.validateInstance(instance)) {
                                // Remember that instance is invalid
                                invalidInstances.add(instance.getEffectiveId());
                            }
                        }
                    }
                }

                // Validate using binds if needed
                if (mustBindValidate)
                    binds.applyValidationBinds(invalidInstances);

                if (indentedLogger.isDebugEnabled())
                    indentedLogger.endHandleOperation();
            }

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            deferredActionContext.revalidate = false;
        }

        // Notify dependencies
        containingDocument.getXPathDependencies().revalidateDone(staticModel);

        if (invalidInstances != null) {
            // Gather events to dispatch, at most one per instance, and only if validity has changed
            // NOTE: It is possible, with binds and the use of xxf:instance(), that some instances in
            // invalidInstances do not belong to this model. Those instances won't get events with the dispatching
            // algorithm below.
            List<XFormsEvent> eventsToDispatch = new ArrayList<XFormsEvent>();
            for (final XFormsInstance instance : instances) {

                final boolean previouslyValid = instance.valid();
                final boolean currentlyValid  = ! invalidInstances.contains(instance.getEffectiveId());

                if (previouslyValid != currentlyValid) {
                    instance.valid_$eq(currentlyValid);
                    eventsToDispatch.add(currentlyValid ? new XXFormsValidEvent(instance) : new XXFormsInvalidEvent(instance));
                }
            }

            // Dispatch all events
            for (final XFormsEvent event : eventsToDispatch)
                Dispatch.dispatchEvent(event);
        }
    }

    private void doRefresh() {
        // This is called in response to dispatching xforms-refresh to this model, whether using the xf:refresh
        // action or by dispatching the event by hand.

        // NOTE: If the refresh flag is not set, we do not call synchronizeAndRefresh() because that would only have the
        // side effect of performing RRR on models, but  but not update the UI, which wouldn't make sense for xforms-refresh.
        // This said, is unlikely (impossible?) that the RRR flags would be set but not the refresh flag.
        if (containingDocument.getControls().isRequireRefresh()) {
            container().synchronizeAndRefresh();
        }
    }

    private final DeferredActionContext deferredActionContext = new DeferredActionContext();

    public class DeferredActionContext {
        public boolean rebuild;
        public boolean recalculate;
        public boolean revalidate;

        public void markStructuralChange() {

            // "XForms Actions that change the tree structure of instance data result in setting all four deferred update
            // flags to true for the model over which they operate"

            rebuild = true;
            recalculate = true;
            revalidate = true;

            container().requireRefresh();
        }

        public void markValueChange(boolean isCalculate) {

            // "XForms Actions that change only the value of an instance node results in setting the flags for
            // recalculate, revalidate, and refresh to true and making no change to the flag for rebuild".

            if (!isCalculate) {
                // Only set recalculate when we are not currently performing a recalculate (avoid infinite loop)
                recalculate = true;
            }

            revalidate = true;

            container().requireRefresh();
        }
    }

    public DeferredActionContext getDeferredActionContext() {
        return deferredActionContext;
    }

    public void markValueChange(NodeInfo nodeInfo, boolean isCalculate) {
        // Set the flags
        deferredActionContext.markValueChange(isCalculate);

        // Notify dependencies of the change
        if (nodeInfo != null)
            containingDocument.getXPathDependencies().markValueChanged(this, nodeInfo);
    }

//    public void markMipChange(NodeInfo nodeInfo) {
//        // Notify dependencies of the change
//        if (nodeInfo != null)
//            containingDocument.getXPathDependencies().markMipChanged(this, nodeInfo);
//    }

    public void markStructuralChange(XFormsInstance instance) {
        // Set the flags
        deferredActionContext.markStructuralChange();

        // Notify dependencies of the change
        containingDocument.getXPathDependencies().markStructuralChange(this, instance);
    }

    public void startOutermostActionHandler() {
        // NOP now that deferredActionContext is always created
    }

    public boolean needRebuildRecalculateRevalidate() {
        return deferredActionContext.rebuild || deferredActionContext.recalculate || deferredActionContext.revalidate;
    }

    public void rebuildRecalculateRevalidateIfNeeded() {
        // Process deferred behavior
        final DeferredActionContext currentDeferredActionContext = deferredActionContext;
        // NOTE: We used to clear deferredActionContext , but this caused events to be dispatched in a different
        // order. So we are now leaving the flag as is, and waiting until they clear themselves.

        if (currentDeferredActionContext.rebuild) {
            containingDocument.startOutermostActionHandler();
            Dispatch.dispatchEvent(new XFormsRebuildEvent(this));
            containingDocument.endOutermostActionHandler();
        }
        if (currentDeferredActionContext.recalculate) {
            containingDocument.startOutermostActionHandler();
            Dispatch.dispatchEvent(new XFormsRecalculateEvent(this));
            containingDocument.endOutermostActionHandler();
        }
        if (currentDeferredActionContext.revalidate) {
            containingDocument.startOutermostActionHandler();
            Dispatch.dispatchEvent(new XFormsRevalidateEvent(this));
            containingDocument.endOutermostActionHandler();
        }
    }

    public XFormsEventObserver parentEventObserver() {
        // There is no point for events to propagate beyond the model
        // NOTE: This could change in the future once models are more integrated in the components hierarchy
        return null;
    }

    public BindingContext getDefaultEvaluationContext() {
        return defaultEvaluationContext;
    }

    public Map<String, ValueRepresentation> getTopLevelVariables() {
        return topLevelVariables;
    }

    public void setTopLevelVariables(Map<String, ValueRepresentation> topLevelVariables) {
        this.topLevelVariables = topLevelVariables;
    }

    // Don't allow any external events
    public boolean allowExternalEvent(String eventName) {
        return false;
    }
}
