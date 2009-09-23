/*
 * Copyright 1999-2008 University of Chicago
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.nimbustools.ctxbroker.blackboard;

import org.nimbustools.ctxbroker.generated.gt4_0.description.IdentityProvides_Type;
import org.nimbustools.ctxbroker.generated.gt4_0.types.ContextualizationContext;
import org.nimbustools.ctxbroker.generated.gt4_0.types.MatchedRole_Type;
import org.nimbustools.ctxbroker.generated.gt4_0.types.Node_Type;
import org.nimbustools.ctxbroker.Identity;
import org.nimbustools.ctxbroker.ContextBrokerException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

/**
 * Each resource gets one blackboard. Setting this up to keep its own
 * Hashtable of all instances to leave room for persistence in the future.
 */
public class Blackboard {

    // -------------------------------------------------------------------------
    // STATIC VARIABLES
    // -------------------------------------------------------------------------

    private static final Log logger =
            LogFactory.getLog(Blackboard.class.getName());

    // The "database" of Blackboard objects
    // String ID --> Blackboard object
    private static Hashtable<String,Blackboard> all = new Hashtable<String, Blackboard>(8);

    private static final Node_Type[] NO_NODES_RESPONSE = new Node_Type[0];

    // -------------------------------------------------------------------------
    // INSTANCE VARIABLES
    // -------------------------------------------------------------------------

    private Identity[] allIdentityCache = null;


    // All nodes this blackboard knows about.
    // Integer workspaceID --> Node object
    private final Hashtable<Integer, Node> allNodes =
            new Hashtable<Integer, Node>(64);

    // All provided roles this blackboard knows about.
    // String roleName --> ProvidedRole object
    private final Hashtable<String, ProvidedRole> allProvidedRoles =
            new Hashtable<String, ProvidedRole>(16);

    // All data this blackboard knows about.
    // String roleName --> RequiredData object
    private final Hashtable<String, RequiredData> allRequiredDatas =
            new Hashtable<String, RequiredData>(16);

    // All RequiredRole objects this blackboard knows about.
    // No key to use for a hashtable because each is not only the
    // name but host and key requirements also.  See RequiredRole
    // equals/hashCode method.  No need for a set lock because set is
    // always accessed and mutated under this.dbLock.
    private final Set<RequiredRole> allRequiredRoles =
            new HashSet<RequiredRole>(16);

    private boolean needsRefresh = true;

    // One lock per blackboard.  Locking "up high" for now, optimize later
    private final Object dbLock = new Object();

    // Mainly for the future DB implementation (and logging)
    private final String id;

    // flips once and only once to true when all report OK
    private boolean allOK = false;

    // flips once and only once to true when a node reports an error
    private boolean oneErrorOccured = false;

    // stopping condition + for resource prop
    private int numNodes = 0;
    private int totalNodes = 0;


    // -------------------------------------------------------------------------
    // CONSTRUCTOR
    // -------------------------------------------------------------------------

    public Blackboard(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        this.id = id;
    }

    
    // -------------------------------------------------------------------------
    // FACTORY
    // -------------------------------------------------------------------------

    /**
     * Get or create a blackboard for a contextualization context.
     *
     * @param id may not be null
     * @return Blackboard
     */
    public synchronized static Blackboard createOrGetBlackboard(String id) {

        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }

        Blackboard bb = all.get(id);
        if (bb == null) {
            bb = new Blackboard(id);
            all.put(id, bb);
            return bb;
        } else {
            return bb;
        }
    }


    // -------------------------------------------------------------------------
    // NEW CONTEXT (RESOURCE PROP STUFF)
    // -------------------------------------------------------------------------

    public ContextualizationContext newContext(boolean noMoreInjections) {

        final ContextualizationContext context = new ContextualizationContext();

        context.setNoMoreInjections(noMoreInjections);

        synchronized (this.dbLock) {

            context.setAllOK(this.allOK);
            context.setErrorPresent(this.oneErrorOccured);

            context.setComplete(this.isComplete());

            final MatchedRole_Type[] matchedRoles =
                    new MatchedRole_Type[this.allRequiredRoles.size()];

            int idx = 0;
            for (Object allRequiredRole : this.allRequiredRoles) {

                final RequiredRole role = (RequiredRole) allRequiredRole;
                final MatchedRole_Type matchedRole = new MatchedRole_Type();

                matchedRole.setName(role.getName());
                matchedRole.setNumFilledProviders(role.getFilledNum());
                matchedRole.setNumProvidersInContext(role.getProviderNum());

                matchedRoles[idx] = matchedRole;
                idx++;
            }
            
            context.setMatchedRole(matchedRoles);
        }
        return context;
    }

    boolean isComplete() {
        synchronized (this.dbLock) {
            boolean complete;
            if (this.needsRefresh()) {
                this.refresh();
                complete = !this.needsRefresh();
            } else {
                complete = true;
            }

            // maybe we haven't heard from all the agents yet
            if (this.totalNodes <= 0 ||
                    this.totalNodes != this.numNodes) {
                complete = false;
            }

            return complete;
        }
    }

    
    // -------------------------------------------------------------------------
    // MATCHING
    // -------------------------------------------------------------------------

    // assumed under lock
    private void refreshNowNeeded() {
        this.needsRefresh = true;
    }

    // assumed under lock
    private void refreshNotNeeded() {
        this.needsRefresh = false;
    }

    private boolean needsRefresh() {
        return this.needsRefresh;
    }

    // assumed under lock
    private void refresh() {

        if (!this.needsRefresh()) {
            return;
        }

        logger.debug("Beginning refresh of blackboard '" + this.id + "'");

        StringBuffer tracebuf = null;
        if (logger.isDebugEnabled()) {
            tracebuf = new StringBuffer();
            tracebuf.append("\n==========================================\n");
        }

        boolean stillNeedsRefresh = false;

        // look for provided roles not matched yet with required roles

        for (RequiredRole requiredRole : this.allRequiredRoles) {

            if (tracebuf != null) {
                tracebuf.append("Required role: ")
                        .append(requiredRole)
                        .append("\n");
            }

            // delete old list and start over:
            requiredRole.clearProviders();

            final ProvidedRole providedRole = this.allProvidedRoles.get(requiredRole.getName());

            if (providedRole == null) {
                if (tracebuf != null) {
                    tracebuf.append("  - Found no providers\n");
                }
                stillNeedsRefresh = true;
                continue;
            }

            int count = 0;
            final Iterator<Identity> iter2 = providedRole.getProviders();
            while (iter2.hasNext()) {
                requiredRole.addProvider(iter2.next());
                count += 1;
            }

            if (tracebuf != null) {
                tracebuf.append("  - Added ")
                        .append(count)
                        .append(" providers\n");
            }
        }


        // check the current node count against the expected node count

        if (this.totalNodes > 0 &&
                this.totalNodes == this.numNodes) {

            ArrayList<Identity> allIdentities =
                    new ArrayList<Identity>();
            Enumeration<Node> nodes = this.allNodes.elements();
            while (nodes.hasMoreElements()) {
                Node node = nodes.nextElement();
                Enumeration<Identity> ids = node.getIdentities();
                while (ids.hasMoreElements()) {
                    allIdentities.add(ids.nextElement());
                }
            }

            this.allIdentityCache =
                    allIdentities.toArray(
                            new Identity[allIdentities.size()]);

            if (tracebuf != null) {
                tracebuf.append("  - Instantiated, number of identities: ")
                        .append(this.allIdentityCache.length)
                        .append("\n");
            }

        } else {

            if (this.allIdentityCache != null) {

                this.allIdentityCache = null;
                if (tracebuf != null) {
                    tracebuf.append("  - Invalidated because of missing ")
                            .append("IP or hostname\n");
                }

            } else {

                if (tracebuf != null) {
                    tracebuf.append("  - Remains invalid because of ")
                            .append("missing nodes\n");
                }
            }

            stillNeedsRefresh = true;

        }


        String tail = "\nFurther refresh";
        if (stillNeedsRefresh) {
            this.refreshNowNeeded();
        } else {
            this.refreshNotNeeded();
            tail += " not";
        }
        tail += " needed currently.\n";
        
        if (tracebuf != null) {
            logger.trace("\n\nRefreshed blackboard '" + this.id + "'" +
                             tracebuf.toString() + tail);
        } else {
            logger.debug("Refreshed blackboard '" + this.id + "'" + tail);
        }
    }


    // -------------------------------------------------------------------------
    // DATA ADDITIONS
    // -------------------------------------------------------------------------

    public void injectData(String dataName, String value)
            throws ContextBrokerException {

        if (dataName == null || dataName.trim().length() == 0) {
            // does not happen when object is created via XML (which is usual)
            throw new ContextBrokerException("Empty data element name (?)");
        }

        synchronized (this.dbLock) {
            this._newData(dataName, value);
        }
    }
    
    // -------------------------------------------------------------------------
    // NODE ADDITIONS
    // -------------------------------------------------------------------------

    /**
     * Creates new Node, treats provides and requires documents in the
     * empty, provided interpretation.
     *
     * @param workspaceID workspace ID
     * @param identities identity objects filled by factory/service.
     *        What 'is' already based on creation request or initialization.
     *        Once passed to this method, caller must discard pointers
     *        (avoids needing to clone it).
     * @param totalNodesFromAgent total number of nodes reported by ctx agent
     * @throws ContextBrokerException illegalities
     */
    public void addWorkspace(Integer workspaceID,
                             Identity[] identities,
                             Boolean allIdentitiesRequired,
                             RequiredRole[] requiredRoles,
                             DataPair[] requiredData,
                             ProvidedRoleDescription[] providedRoles,
                             int totalNodesFromAgent)
            throws ContextBrokerException {

        if (workspaceID == null) {
            throw new IllegalArgumentException("workspaceID cannot be null");
        }
        if (identities == null || identities.length == 0) {
            throw new IllegalArgumentException("'real' identities cannot be " +
                        "null or empty, contextualization is not possible. " +
                        "If a workspace has no NICs, requires and provides " +
                        "documents should not have been given.  If they " +
                        "existed, the factory/service should have rejected " +
                        "the request. " +
                        "However this happened it is a programming error.");
        }

        synchronized (this.dbLock) {

            if (this.totalNodes > 0) {
                if (this.totalNodes != totalNodesFromAgent) {
                    throw new ContextBrokerException("Context '" +
                        this.id + "' has received a conflicting " +
                        "total node count.  Was previously " + this.totalNodes +
                        "but has received a cluster definition with a total " +
                        "of " + totalNodesFromAgent);
                }
            } else {
                this.totalNodes = totalNodesFromAgent;
            }

            this.numNodes += 1;

            if (this.numNodes > this.totalNodes) {
                throw new ContextBrokerException("Context '" +
                        this.id + "' has heard from a new agent which " +
                        "makes the total node count exceed the theoretical" +
                        "maximum from the cluster definitions (" +
                        this.totalNodes + ").");
            }

            // invalidate cache if it existed
            this.allIdentityCache = null;

            Node node = this.allNodes.get(workspaceID);
            if (node != null) {
                throw new ContextBrokerException("Blackboard has " +
                        "already added node with ID #" + workspaceID);
            }

            String[] requiredDataNames = null;
            if (requiredData != null && requiredData.length > 0) {

                // set up names of data this node needs

                requiredDataNames = new String[requiredData.length];
                for (int i = 0; i < requiredData.length; i++) {
                    requiredDataNames[i] = requiredData[i].getName();
                }

                // If the contextualization definition included a value for
                // the data already, register it into the known-data store.
                // _intakeData also creates new RequiredData objects for any
                // newly seen data name (no matter if the value is present
                // or not).
                _intakeData(requiredData);
            }

            node = new Node(workspaceID, requiredDataNames);
            for (Identity identity : identities) {
                node.addIdentity(identity);
            }

            if (providedRoles != null && providedRoles.length > 0) {
                this.handleProvidesRoles(node, providedRoles);
            }

            handleNewRequires(node, allIdentitiesRequired, requiredRoles);

            this.allNodes.put(workspaceID, node);
            this.refreshNowNeeded();
        }
    }

    // no args are null and roles.length > 0
    private void handleProvidesRoles(Node node,
                                     ProvidedRoleDescription[] roles)
            throws ContextBrokerException {

        for (ProvidedRoleDescription roleDesc : roles) {

            final String roleName = roleDesc.getRoleName();

            // we are still under this.dbLock lock, check then act here is ok
            ProvidedRole role =
                    this.allProvidedRoles.get(roleName);
            if (role == null) {
                role = new ProvidedRole(roleName);
                this.allProvidedRoles.put(roleName, role);
            }

            final String iface = roleDesc.getIface();
            if (iface != null) {

                // only this specific interface provides the role

                final Identity identity = node.getParticularIdentity(iface);
                if (identity == null) {
                    throw new ContextBrokerException("There is an " +
                            "interface specification ('" + iface + "') in " +
                            "the provides section for role '" + roleName +
                            "' that does not have matching identity element " +
                            "with that interface name.  Cannot " +
                            "contextualize #" + node.getId());
                }
                role.addProvider(identity);

            } else {

                // each identity provides this role

                final Enumeration<Identity> identities = node.getIdentities();
                while (identities.hasMoreElements()) {
                    role.addProvider(identities.nextElement());
                }
            }
        }
    }

    // no args are null
    private void handleNewRequires(Node node, boolean allIdentitiesRequired,
                                   RequiredRole[] requiredRoles)
            throws ContextBrokerException {

        final int workspaceID = node.getId();

        node.setAllIdentitiesRequired(allIdentitiesRequired);

        if (requiredRoles != null && requiredRoles.length > 0) {
            
            this.handleRequiredRoles(node, requiredRoles);

        } else if (logger.isTraceEnabled()) {
            
            logger.trace("Requires section for #" + workspaceID + " has " +
                    "no role-required elements." +
                    "  Allowing, perhaps the only thing required by " +
                    "this node is the contextualization context's " +
                    "all-identity list and/or just data elements.");
        }
    }

    // no args are null and datas.length > 0, always call under this.dbLock
    private void _intakeData(DataPair[] datas)
            throws ContextBrokerException {

        for (DataPair data : datas) {
            final String dataName = data.getName();
            this._newData(dataName, data.getValue());
        }
    }

    private void _newData(String name, String value)
            throws ContextBrokerException {

        if (name == null) {
            throw new IllegalArgumentException("name may not be null");
        }

        // we are under this.dbLock lock, check then act here is ok
        RequiredData data = this.allRequiredDatas.get(name);
        
        if (data == null) {
            data = new RequiredData(name);
            this.allRequiredDatas.put(name, data);
        }

        if (value != null) {
            data.addNewValue(value);
        }
    }

    private boolean isOneDataValuePresent(String dataname) {
        final RequiredData data =
                this.allRequiredDatas.get(dataname);
        return data != null && data.numValues() > 0;
    }

    // returns List of Requires_TypeData
    private List<DataPair> getDataValues(String dataname) {
        final RequiredData data =
                this.allRequiredDatas.get(dataname);
        if (data != null) {
            return data.getDataList();
        } else {
            return null;
        }
    }

    // no args are null and roles.length > 0
    private void handleRequiredRoles(Node node,
                                     RequiredRole[] roles)
            throws ContextBrokerException {


        for (RequiredRole role : roles) {

            // We expect a lot of duplicates, HashSet does not add if exists
            // in list already.
            boolean didNotExist = this.allRequiredRoles.add(role);
            if (didNotExist && logger.isTraceEnabled()) {
                logger.trace("Found new RequiredRole for blackboard '" +
                        this.id + "': " + role + " -- cardinality now " +
                        this.allRequiredRoles.size());
            }

            // Copy reference to node specific list.
            // There is a possibility client provided requires schema with
            // multiple duplicate elements for some reason.  Just silently
            // allowing that (i.e. with server side log message and not
            // exception client will see).

            if (!didNotExist) {
                // It existed already.  We need to add the exact object to the
                // node's required role's, not the duplicate we created above.
                boolean found = false;
                for (RequiredRole aRole : this.allRequiredRoles) {
                    if (aRole.equals(role)) {
                        role = aRole;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new ContextBrokerException("Set contained a " +
                            "required role already but we cannot find the " +
                            "object in the set's iterator (?).  Role: " + role);
                }
            }

            didNotExist = node.addRequiredRole(role);
            if (!didNotExist) {
                logger.warn("Client provided requires document with " +
                        "duplicate required role elements.  That is not " +
                        "expected but ignoring and handling it. Required " +
                        "role that trigged this: " + role + ".  Node #" +
                        node.getId());
            }
        }
    }


    // -------------------------------------------------------------------------
    // NODE RETRIEVAL/UPDATES
    // -------------------------------------------------------------------------

    // (no service or client updates, VM assertions allowed)
    
    public NodeManifest retrieve(Integer workspaceID)

                throws ContextBrokerException {

        if (workspaceID == null) {
            throw new IllegalArgumentException("workspaceID cannot be null");
        }
        
        final Node node = this.allNodes.get(workspaceID);
        if (node == null) {
            throw new ContextBrokerException("Blackboard is not aware " +
                    "of node with ID #" + workspaceID);
        }

        synchronized(this.dbLock) {

            this.refresh();


            // Check if identities are available

            final List<Identity> identities;
            final boolean allIdentities = node.isAllIdentitiesRequired();
            if (allIdentities) {

                if (this.allIdentityCache == null) {

                    return null;

                } else {
                    identities = Arrays.asList(this.allIdentityCache);
                }
            } else {
                identities = new ArrayList<Identity>();
            }

            if (this.numNodes != this.totalNodes) {
                return null;
            }


            // Check if all data is available.  At least one value of each
            // data requirement constitutes "available"

            final ArrayList<DataPair> data = new ArrayList<DataPair>();

            for (String reqData : node.getRequiredDataNames()) {
                if (this.isOneDataValuePresent(reqData)) {
                    data.addAll(this.getDataValues(reqData));
                } else {

                    if (logger.isTraceEnabled()) {
                        logger.trace("Not constructing node manifest because " +
                                "suppressIncomplete is true, and a required " +
                                "data item for this node (#" +
                                node.getId() + ") is not present: ");
                    }

                    return null; // *** EARLY RETURN ***
                }
            }

            final ArrayList<RoleIdentityPair> roles = new ArrayList<RoleIdentityPair>();

            final Iterator<RequiredRole> iter = node.getRequiredRoles();
            while (iter.hasNext()) {
                final RequiredRole aRole = iter.next();
                for (Identity provider : aRole.getProviders()) {
                    roles.add(new RoleIdentityPair(aRole.getName(), provider));

                    if (!allIdentities) {
                        identities.add(provider);
                    }
                }
            }

            return new NodeManifest(identities, data, roles);
        }
    }


    // -------------------------------------------------------------------------
    // NODE STATUS
    // -------------------------------------------------------------------------

    public void okExit(Integer workspaceID)
            throws ContextBrokerException {

        final Node node = this.allNodes.get(workspaceID);
        if (node == null) {
            throw new ContextBrokerException("unknown workspace #" + workspaceID);
        }
        synchronized (this.dbLock) {
            CtxResult result = node.getCtxResult();

            if (result.okOccurred || result.errorOccurred) {
                throw new ContextBrokerException("already received " +
                        "exiting report from workspace #" + workspaceID);
            }

            result.okOccurred = true;

            // check if all are now OK
            for (Enumeration<Node> e = this.allNodes.elements(); e.hasMoreElements();) {
                final Node aNode = e.nextElement();
                if (!aNode.getCtxResult().okOccurred) {
                    return;
                }
            }
            this.allOK = true;
        }
    }

    public void errorExit(Integer workspaceID,
                          short exitCode,
                          String errorMessage)
            throws ContextBrokerException {

        final Node node = this.allNodes.get(workspaceID);
        if (node == null) {
            throw new ContextBrokerException("unknown workspace #" + workspaceID);
        }
        synchronized (this.dbLock) {
            CtxResult result = node.getCtxResult();

            if (result.okOccurred || result.errorOccurred) {
                throw new ContextBrokerException("already received " +
                        "exiting report from workspace #" + workspaceID);
            }

            result.errorOccurred = true;
            result.errorCode = exitCode;
            result.errorMessage = errorMessage;

            this.oneErrorOccured = true;
        }
    }
    

    public Node_Type[] identities(boolean allNodess, String host, String ip) {

        final List<Node> nodeList;
        if (allNodess) {
            nodeList = new ArrayList<Node>(this.allNodes.size());
        } else {
            nodeList = new ArrayList<Node>(1);
        }

        synchronized (this.dbLock) {

            if (allNodess) {
                for (Enumeration<Node> e = this.allNodes.elements(); e.hasMoreElements();) {
                    nodeList.add(e.nextElement());
                }
            } else {
                final Node node = this.findNode(host, ip);
                if (node != null) {
                    nodeList.add(node);
                }
            }
            
            return getNodeResponse(nodeList);
        }
    }

    private static Node_Type[] getNodeResponse(List<Node> nodeList) {
        if (nodeList.isEmpty()) {
            return NO_NODES_RESPONSE;
        }
        final List<Node_Type> resultList = new ArrayList<Node_Type>(nodeList.size());
        for (final Node node : nodeList) {
            if (node != null) {
                resultList.add(getOneNodeResponse(node));
            }
        }
        return resultList.toArray(
                        new Node_Type[resultList.size()]);
    }

    private static Node_Type getOneNodeResponse(Node node) {

        final Node_Type xmlNode = new Node_Type();

        /* identities */
        final List<IdentityProvides_Type> xmlIdentsList = new ArrayList<IdentityProvides_Type>(3);
        for (Enumeration e = node.getIdentities(); e.hasMoreElements();) {
            final Identity ident = (Identity) e.nextElement();
            if (ident != null) {
                xmlIdentsList.add(idToXML(ident));
            }
        }
        final IdentityProvides_Type[] xmlIdents =
                xmlIdentsList.toArray(
                        new IdentityProvides_Type[xmlIdentsList.size()]);

        xmlNode.setIdentity(xmlIdents);

        /* status */
        final CtxResult status = node.getCtxResult();
        if (status.okOccurred) {
            xmlNode.setExited(true);
            xmlNode.setOk(true);
        } else if (status.errorOccurred) {
            xmlNode.setExited(true);
            xmlNode.setErrorCode(status.errorCode);
            xmlNode.setErrorMessage(status.errorMessage);
        } else {
            xmlNode.setExited(false);
        }

        return xmlNode;
    }

    private static IdentityProvides_Type idToXML(Identity ident) {
        final IdentityProvides_Type xml = new IdentityProvides_Type();
        xml.set_interface(ident.getIface());
        xml.setHostname(ident.getHostname());
        xml.setIp(ident.getIp());
        xml.setPubkey(ident.getPubkey());
        return xml;
    }

    private Node findNode(String host, String ip) {

        if (host == null && ip == null) {
            return null;
        }

        for (Enumeration<Node> e = this.allNodes.elements(); e.hasMoreElements();) {

            final Node node = e.nextElement();

            for (Enumeration e2 = node.getIdentities(); e2.hasMoreElements();) {
                final Identity ident = (Identity) e2.nextElement();

                if (host != null) {
                    if (host.equals(ident.getHostname())) {
                        return node;
                    }
                } else {
                    if (ip.equals(ident.getIp())) {
                        return node;
                    }
                }
            }
        }

        return null;
    }


}