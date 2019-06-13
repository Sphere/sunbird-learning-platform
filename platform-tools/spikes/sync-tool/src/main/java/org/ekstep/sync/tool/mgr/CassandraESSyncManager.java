/**
 * @author: Rhea Fernandes
 * @created: 13th May 2019
 */
package org.ekstep.sync.tool.mgr;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.ekstep.common.Platform;
import org.ekstep.common.dto.Response;
import org.ekstep.common.exception.ClientException;
import org.ekstep.common.exception.ServerException;
import org.ekstep.common.mgr.ConvertToGraphNode;
import org.ekstep.common.util.RequestValidatorUtil;
import org.ekstep.graph.dac.model.Node;
import org.ekstep.graph.model.node.DefinitionDTO;
import org.ekstep.graph.service.common.DACConfigurationConstants;
import org.ekstep.learning.hierarchy.store.HierarchyStore;
import org.ekstep.learning.util.ControllerUtil;
import org.ekstep.sync.tool.util.ElasticSearchConnector;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CassandraESSyncManager {

    private ControllerUtil util = new ControllerUtil();
    private ObjectMapper mapper = new ObjectMapper();
    private String graphId;
    private final String objectType = "Content";
    private final String nodeType = "DATA_NODE";
    Map<String, Object> definition = getDefinition();


    private HierarchyStore hierarchyStore = new HierarchyStore();
    private ElasticSearchConnector searchConnector = new ElasticSearchConnector();
    private static final String COLLECTION_MIMETYPE = "application/vnd.ekstep.content-collection";
    private static String graphPassportKey = Platform.config.getString(DACConfigurationConstants.PASSPORT_KEY_BASE_PROPERTY);


    @PostConstruct
    private void init() throws Exception {
    }

    public void syncAllIds(String graphId, List<String> resourceIds, List<String> bookmarkIds) {
        if(CollectionUtils.isNotEmpty(resourceIds)) {
            if(CollectionUtils.size(resourceIds) > 1) {
                if(CollectionUtils.isNotEmpty(bookmarkIds))
                    System.out.println("Bookmark Id's shouldn't be provided for Multiple textbooks");
                resourceIds.forEach(textbook->{
                    Boolean flag = syncByBookmarkId(graphId, textbook, null, false);
                        System.out.println("Textbook id : " + textbook + " Sync status : " + flag);
                });
            } else
                resourceIds.forEach(textbook->{
                    Boolean flag = syncByBookmarkId(graphId, textbook, bookmarkIds, false);
                    System.out.println("Textbook id : " + textbook + " Sync status : " + flag);
                });        }
    }

    public void syncLeafNodesCountByIds(String graphId, List<String> resourceIds) {
        if(CollectionUtils.isNotEmpty(resourceIds)) {
            resourceIds.forEach(collectionId->{
                Boolean flag = syncByBookmarkId(graphId, collectionId, null, true);
                System.out.println("Collection id : " + collectionId + " Sync status : " + flag);
            });
        }
    }

    public Boolean syncByBookmarkId(String graphId, String resourceId, List<String> bookmarkIds, boolean refreshLeafNodeCount) {
        this.graphId = RequestValidatorUtil.isEmptyOrNull(graphId) ? "domain" : graphId;
        try {
            Map<String, Object> hierarchy = getTextbookHierarchy(resourceId);
            if (MapUtils.isNotEmpty(hierarchy)) {
                if(refreshLeafNodeCount) {
                    updateLeafNodeCount(hierarchy, resourceId);
                }
                List<Map<String, Object>> units = getUnitsMetadata(hierarchy, bookmarkIds);
                return updateElasticSearch(units, bookmarkIds, resourceId);
            } else {
                System.out.println(resourceId + " is not a type of Collection or it is not live.");
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
            return false;
        }
    }

    private void updateLeafNodeCount(Map<String, Object> hierarchy, String resourceId) throws Exception {
            //Update Collection leafNodesCount in the hierarchy
            int collectionLeafNodesCount = getLeafNodesCount(hierarchy, 0);
            hierarchy.put("leafNodesCount", collectionLeafNodesCount);
            // Update RootNode in Neo4j
            updateTextBookNode(resourceId, collectionLeafNodesCount);

            //Update leafNodesCount of children in the hierarchy
            updateLeafNodesCountInHierarchyMetadata((List<Map<String, Object>>) hierarchy.get("children"));

            //Update cassandra with updatedHierarchy
            hierarchyStore.saveOrUpdateHierarchy(resourceId, hierarchy);
    }

    private Boolean updateElasticSearch(List<Map<String, Object>> units, List<String> bookmarkIds, String resourceId) {
        if (CollectionUtils.isNotEmpty(units)) {
            List<String> syncedUnits = getSyncedUnitIds(units);
            List<String> failedUnits = getFailedUnitIds(units, bookmarkIds);
            Map<String, Object> esDocs = getESDocuments(units);
            if (MapUtils.isNotEmpty(esDocs)) {
                pushToElastic(esDocs);
                printMessages("success", syncedUnits, resourceId);
            }
            if (CollectionUtils.isNotEmpty(failedUnits)) {
                printMessages("failed", failedUnits, resourceId);
                return false;
            }
        }
        return true;
    }


    public Map<String, Object> getTextbookHierarchy(String resourceId) throws Exception {
        Map<String, Object> hierarchy;
        if (RequestValidatorUtil.isEmptyOrNull(resourceId))
            throw new ClientException("BLANK_IDENTIFIER", "Identifier is blank.");
        hierarchy = hierarchyStore.getHierarchy(resourceId);
        return hierarchy;
    }


    public List<Map<String, Object>> getUnitsMetadata(Map<String, Object> hierarchy, List<String> bookmarkIds) {
        Boolean flag = false;
        List<Map<String, Object>> unitsMetadata = new ArrayList<>();
        if(CollectionUtils.isEmpty(bookmarkIds))
            flag = true;
        List<Map<String, Object>> children = (List<Map<String, Object>>)hierarchy.get("children");
        getUnitsToBeSynced(unitsMetadata, children, bookmarkIds, flag);
        return unitsMetadata;
    }

    private void getUnitsToBeSynced(List<Map<String, Object>> unitsMetadata, List<Map<String, Object>> children, List<String> bookmarkIds, Boolean flag) {
        if (CollectionUtils.isNotEmpty(children)) {
            children.forEach(child -> {
                if (child.containsKey("visibility") && StringUtils.equalsIgnoreCase((String) child.get("visibility"), "parent")) {
                		Map<String, Object> childData = refactorUnit(child);
                		if (flag)
                        unitsMetadata.add(childData);
                    else if (bookmarkIds.contains(child.get("identifier")))
                        unitsMetadata.add(childData);
                    if (child.containsKey("children")) {
                        List<Map<String,Object>> newChildren = mapper.convertValue(child.get("children"), new TypeReference<List<Map<String, Object>>>(){});
                        getUnitsToBeSynced(unitsMetadata, newChildren , bookmarkIds, flag);
                    }
                }
            });
        }
    }

    private Map<String, Object> refactorUnit(Map<String, Object> child) {
    		Map<String, Object> childData = new HashMap<>();
        childData.putAll(child);
        List<String> relationshipProperties = Platform.config.hasPath("content.relationship.properties") ?
                Arrays.asList(Platform.config.getString("content.relationship.properties").split(",")) : Collections.emptyList();
        for(String property : relationshipProperties) {
        		if(childData.containsKey(property)) {
        			List<Map<String, Object>> nextLevelNodes = (List<Map<String, Object>>) childData.get(property);
        	        List<String> finalPropertyList = new ArrayList<>();
        			if (CollectionUtils.isNotEmpty(nextLevelNodes)) {
        				finalPropertyList = nextLevelNodes.stream().map(nextLevelNode -> {
        					String identifier = (String)nextLevelNode.get("identifier");
        					return identifier;
        				}).collect(Collectors.toList());
        			}
        			childData.remove(property);
        			childData.put(property, finalPropertyList);
        		}
        }
        return childData;
	}

    private List<String> getFailedUnitIds(List<Map<String, Object>> units, List<String> bookmarkIds) {
        List<String> failedUnits = new ArrayList<>();
        if(CollectionUtils.isNotEmpty(bookmarkIds)) {
        	    if (units.size() == bookmarkIds.size())
                return failedUnits;
        	    failedUnits.addAll(bookmarkIds);
            units.forEach(unit -> {
                if (bookmarkIds.contains(unit.get("identifier")))
                		failedUnits.remove(unit.get("identifier"));
            });
        }
        return failedUnits;
    }
    private List<String> getSyncedUnitIds(List<Map<String, Object>> units){
    		List<String> syncedUnits = new ArrayList<>();
    		units.forEach(unit -> {
    			syncedUnits.add((String)unit.get("identifier"));
    		});
    		return syncedUnits;
    }

    private Map<String,Object> getTBMetaData(String textBookId) throws Exception {
        Node node = util.getNode(graphId, textBookId);
        if (RequestValidatorUtil.isEmptyOrNull(node))
            throw new ClientException("RESOURCE_NOT_FOUND", "Enter a Valid Textbook id");
        String status = (String) node.getMetadata().get("status");
        if (StringUtils.isNotEmpty(status) && (!StringUtils.equalsIgnoreCase(status,"live")))
            throw new ClientException("RESOURCE_NOT_FOUND", "Text book must be live");
        Map<String,Object> metadata = node.getMetadata();
        metadata.put("identifier",node.getIdentifier());
        metadata.put("nodeUniqueId",node.getId());
        return metadata;
    }

    private Map<String, Object> getESDocuments(List<Map<String, Object>> units) {
        List<String> indexablePropslist;
        
        Map<String, Object> esDocument = new HashMap<>();
        List<String> objectTypeList = Platform.config.hasPath("restrict.metadata.objectTypes") ?
                Arrays.asList(Platform.config.getString("restrict.metadata.objectTypes").split(",")) : Collections.emptyList();
        if (objectTypeList.contains(objectType)) {
            indexablePropslist = getIndexableProperties(definition);
            units.forEach(unit -> {
                String identifier = (String) unit.get("identifier");
                if (CollectionUtils.isNotEmpty(indexablePropslist))
                    filterIndexableProps(unit, indexablePropslist);
                putAdditionalFields(unit, identifier);
                esDocument.put( identifier , unit);
            });
        }else {
        		units.forEach(unit -> {
                String identifier = (String) unit.get("identifier");
                putAdditionalFields(unit, identifier);
                esDocument.put( identifier , unit);
            });
        }
        return esDocument;
    }

    private void putAdditionalFields(Map<String, Object> unit, String identifier) {
        unit.put("graph_id", graphId);
        unit.put("identifier", identifier);
        unit.put("objectType", objectType);
        unit.put("nodeType", nodeType);
    }

    private Map<String, Object> getDefinition() {
    		this.graphId = RequestValidatorUtil.isEmptyOrNull(graphId) ? "domain" : graphId;
        DefinitionDTO definition = util.getDefinition(graphId, objectType);
        if (RequestValidatorUtil.isEmptyOrNull(definition)) {
            throw new ServerException("ERR_DEFINITION_NOT_FOUND", "No Definition found for " + objectType);
        }
        return mapper.convertValue(definition, new TypeReference<Map<String, Object>>() {
        });
    }

    //Return a list of all failed units
    public void pushToElastic(Map<String, Object> esDocument) {
        try {
            searchConnector.bulkImport(esDocument);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getLocalizedMessage());
        }
    }

    private List<String> getIndexableProperties(Map<String, Object> definition) {
        List<String> propsList = new ArrayList<>();
        List<Map<String, Object>> properties = (List<Map<String, Object>>) definition.get("properties");
        for (Map<String, Object> property : properties) {
            if ((Boolean) property.get("indexed")) {
                propsList.add((String) property.get("propertyName"));
            }
        }
        return propsList;
    }

    private static void filterIndexableProps(Map<String, Object> documentMap, final List<String> indexablePropsList) {
        documentMap.keySet().removeIf(propKey -> !indexablePropsList.contains(propKey));
    }

    private void printMessages(String status, List<String> bookmarkIds, String id) {
        switch (status) {
            case "failed": {
                System.out.println("The units " + bookmarkIds + " of textbook with " + id + " failed. Check if valid unit.");
                break;
            }
            case "success": {
                System.out.println("The units " + bookmarkIds + " of textbook with " + id + " success");
                break;
            }
        }

    }

    private void updateLeafNodesCountInHierarchyMetadata(List<Map<String, Object>> children) {
        if(CollectionUtils.isNotEmpty(children)) {
            for(Map<String, Object> child : children) {
                if(StringUtils.equalsIgnoreCase("Parent",
                        (String)child.get("visibility"))){
                    //set child metadata -- leafNodesCount
                    child.put("leafNodesCount", getLeafNodesCount(child, 0));
                    updateLeafNodesCountInHierarchyMetadata((List<Map<String,Object>>)child.get("children"));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Integer getLeafNodesCount(Map<String, Object> data, int leafCount) {
        List<Object> children = (List<Object>) data.get("children");
        if (null != children && !children.isEmpty()) {
            for (Object child : children) {
                Map<String, Object> childMap = (Map<String, Object>) child;
                int lc = 0;
                lc = getLeafNodesCount(childMap, lc);
                leafCount = leafCount + lc;
            }
        } else {
            if (!COLLECTION_MIMETYPE.equals(data.get("mimeType")))
                leafCount++;
        }
        return leafCount;
    }


    private void updateTextBookNode(String id, int collectionLeafNodesCount) throws Exception {
        DefinitionDTO definition =util.getDefinition("domain", "Content");
        Node node = util.getNode("domain", id);
        Map<String, Object> map = new HashMap<String, Object>() {{
            put("leafNodesCount", collectionLeafNodesCount);
            put("versionKey", graphPassportKey);
        }};
        Node domainObj = ConvertToGraphNode.convertToGraphNode(map, definition, null);
        domainObj.setGraphId("domain");
        domainObj.setObjectType("Content");
        domainObj.setIdentifier(id);
        Response response = util.updateNode(domainObj);
        if(util.checkError(response))
            throw new ServerException("Error while updating RootNode" , response.getParams().getErrmsg() + " :: " + response.getResult());
    }
}