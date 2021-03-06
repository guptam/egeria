/* SPDX-License-Identifier: Apache-2.0 */
package org.odpi.openmetadata.virtualdataconnector.igc.connectors.eventmapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.odpi.openmetadata.frameworks.connectors.ffdc.ConnectorCheckedException;

import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.*;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.PrimitiveDefCategory;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryeventmapper.OMRSRepositoryEventMapperBase;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.TypeErrorException;
import org.odpi.openmetadata.virtualdataconnector.igc.connectors.eventmapper.jackson.IGCKafkaEvent;
import org.odpi.openmetadata.virtualdataconnector.igc.connectors.repositoryconnector.jackson.IGCObject;
import org.odpi.openmetadata.virtualdataconnector.igc.connectors.repositoryconnector.jackson.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.odpi.openmetadata.virtualdataconnector.igc.connectors.repositoryconnector.IGCOMRSRepositoryConnector;

import java.util.*;


/**
 * IGCOMRSRepositoryEventMapper provides an implementation of a repository event mapper for the
 * IBM Governance Catalog (IGC).
 */

public class IGCOMRSRepositoryEventMapper extends OMRSRepositoryEventMapperBase {

    private static final Logger log = LoggerFactory.getLogger(IGCOMRSRepositoryEventMapper.class);
    private IGCOMRSRepositoryConnector igcomrsRepositoryConnector;
    private String originatorServerName;
    private String metadataCollectionId;
    private String originatorServerType;
    private String sourceName;
    private String originatorOrganizationName;

    /**
     * Default constructor
     */
    public IGCOMRSRepositoryEventMapper() {

    }

    /**
     * Indicates that the connector is completely configured and can begin processing.
     *
     * @throws ConnectorCheckedException - there is a problem within the connector.
     */
    @Override
    public void start() throws ConnectorCheckedException {
        super.start();
        this.igcomrsRepositoryConnector = (IGCOMRSRepositoryConnector) this.repositoryConnector;
        this.originatorServerName = this.localServerName;
        this.metadataCollectionId = igcomrsRepositoryConnector.getMetadataCollectionId();
        this.originatorServerType = "IGC";
        this.sourceName = "IGCOMRSRepositoryEventMapper";
        this.originatorOrganizationName = "ING";
        runConsumer();
    }


    /**
     * Set properties for the kafka consumer.
     *
     * @return kafka consumer object
     */
    private Consumer<Long, String> createConsumer() {
        final Properties props = new Properties();
        String address = this.connectionBean.getEndpoint().getAddress().split("/")[0];
        String topicName = this.connectionBean.getEndpoint().getAddress().split("/")[1];
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, address);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka_IGC_Consumer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());//LongDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        final Consumer<Long, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topicName));
        return consumer;
    }

    /**
     * Start a new thread to read IGC kafka events.
     */
    public void runConsumer() {
        new Thread(new ConsumerThread()).start();
    }


    private class ConsumerThread implements Runnable {

        /**
         * Read the Kafka events originating from IGC.
         */
        @Override
        public void run() {
            log.info("Started IGC Eventmapper");
            IGCKafkaEvent igcKafkaEvent;
            final Consumer<Long, String> consumer = createConsumer();
            ConsumerRecords<Long, String> records;
            while (true) {
                try {
                    records = consumer.poll(100);
                    for (ConsumerRecord<Long, String> record : records) {
                        ObjectMapper mapper = new ObjectMapper();
                        igcKafkaEvent = mapper.readValue(record.value(), IGCKafkaEvent.class);
                        log.info("Consumer Record");
                        log.info(record.value());
                        if (igcKafkaEvent.getEventType().equals("IMAM_SHARE_EVENT"))
                            processIMAM(igcKafkaEvent);
                        else
                            processAsset(igcKafkaEvent);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Propagate the importation of databases into IGC through the OMRS.
     *
     * @param igcKafkaEvent A Kafka event originating from IGC
     * @throws TypeErrorException
     */
    private void processIMAM(IGCKafkaEvent igcKafkaEvent) throws TypeErrorException {
        IGCObject igcTable = igcomrsRepositoryConnector.genericIGCQuery(igcKafkaEvent.getDatacollectionRID());
        IGCObject igcDatabase = igcomrsRepositoryConnector.genericIGCQuery(igcTable.getContext().get(1).getId());
        IGCObject igcSchema = igcomrsRepositoryConnector.genericIGCQuery(igcTable.getContext().get(2).getId());

        IGCObject igcEndpoint = igcomrsRepositoryConnector.genericIGCQuery(igcTable.getContext().get(0).getId());
        ;

        createEntity(igcDatabase, "Database", false);
        createEntity(igcEndpoint, "Endpoint", false);
        createEntity(igcSchema, "DeployedDatabaseSchema", false);
        createEntity(igcTable, "RelationalTable", false);

        createEntity(igcSchema, "RelationalDBSchemaType", true);
        createEntity(igcTable, "RelationalTableType", true);

        String relationalDBSchemaTypeID = "RelationalDBSchemaType.rid." + igcSchema.getId();
        String relationalTableTypeID = "RelationalTableType.rid." + igcTable.getId();

        createRelationship(
                "DataContentForDataSet",
                igcDatabase.getId(),
                "Database",
                igcSchema.getId(),
                "DeployedDatabaseSchema");

        createRelationship(
                "AssetSchemaType",
                igcSchema.getId(),
                "DeployedDatabaseSchema",
                relationalDBSchemaTypeID,
                "RelationalDBSchemaType");

        createRelationship(
                "AttributeForSchema",
                relationalDBSchemaTypeID,
                "RelationalDBSchemaType",
                igcTable.getId(),
                "RelationalTable");

        createRelationship(
                "SchemaAttributeType",
                igcTable.getId(),
                "RelationalTable",
                relationalTableTypeID,
                "RelationalTableType");


        IGCObject igcConnection;
        //Propagate Connection/Connection type creations and relationships
        for (Item connection : igcDatabase.getDataConnections().getItems()) {
            igcConnection = igcomrsRepositoryConnector.genericIGCQuery(connection.getId());
            IGCObject igcConnectorType = igcomrsRepositoryConnector.genericIGCQuery(igcConnection.getDataConnectors().getId());
            createEntity(igcConnection, "Connection", false);
            createEntity(igcConnectorType, "ConnectorType", false);

            createRelationship(
                    "ConnectionConnectorType",
                    igcConnection.getId(),
                    "Connection",
                    igcConnectorType.getId(),
                    "ConnectorType");

            createRelationship(
                    "ConnectionEndpoint",
                    igcEndpoint.getId(),
                    "Endpoint",
                    igcConnection.getId(),
                    "Connection");
        }

        IGCObject igcColumn;
        String relationalColumnTypeID;
        //Propagate RelationalColumns and relationships
        for (Item column : igcTable.getDatabaseColumns().getItems()) {
            igcColumn = igcomrsRepositoryConnector.genericIGCQuery(column.getId());

            createEntity(igcColumn, "RelationalColumn", false);

            createEntity(igcColumn, "RelationalColumnType", true);
            relationalColumnTypeID = "RelationalColumnType.rid." + igcColumn.getId();

            createRelationship(
                    "SchemaAttributeType",
                    igcTable.getId(),
                    "RelationalColumn",
                    relationalColumnTypeID,
                    "RelationalColumnType");

            createRelationship(
                    "AttributeForSchema",
                    igcTable.getId(),
                    "RelationalColumn",
                    relationalTableTypeID,
                    "RelationalTableType");
        }
    }

    /**
     * Process individual IGC Kafka events and publish them through OMRS.
     *
     * @param igcKafkaEvent a Kafka event originating from IGC.
     */
    private void processAsset(IGCKafkaEvent igcKafkaEvent) {
        try {
            IGCObject igcObject = igcomrsRepositoryConnector.genericIGCQuery(igcKafkaEvent.getASSETRID());
            switch (igcKafkaEvent.getASSETTYPE()) {
                case "Database Column":
                    if (igcKafkaEvent.getACTION().equals("ASSIGNED_RELATIONSHIP") || igcKafkaEvent.getACTION().equals("MODIFY")) { //Relationship between a technical term and a glossary term.
                        if (igcObject.getAssignedToTerms() != null) {

                            String glossaryTermID = igcObject.getAssignedToTerms().getItems().get(0).getId();

                            createRelationship(
                                    "SemanticAssignment",
                                    igcObject.getId(),
                                    "RelationalColumn",
                                    glossaryTermID,
                                    "GlossaryTerm");
                        }
                    }
                    break;
                case "Term":
                    if (igcKafkaEvent.getACTION().equals("CREATE")) { //Creation of a glossary term.
                        createEntity(igcObject, "GlossaryTerm", false);
                        if (igcKafkaEvent.getACTION().equals("CREATE")) { //Creation of a  glossary category and assignment to its glossary category.
                            createEntity(igcObject, "GlossaryCategory", false);
                            createRelationship(
                                    "TermCategorization",
                                    igcObject.getContext().get(0).getId(),
                                    "GlossaryCategory",
                                    igcObject.getId(),
                                    "GlossaryTerm");
                        }
                    }
                    break;
                case "Category":
                    if (igcKafkaEvent.getACTION().equals("CREATE")) { //Creation of a  glossary category.
                        createEntity(igcObject, "GlossaryCategory", false);
                    }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createEntity(IGCObject igcObject, String typeName, boolean avoidDuplicate) throws TypeErrorException {

        String username = igcObject.getCreatedBy();
        Map<String, InstancePropertyValue> properties = new HashMap<>();

        PrimitivePropertyValue qualifiedName = new PrimitivePropertyValue();
        qualifiedName.setPrimitiveValue(typeName + ".qualifiedName." + igcObject.getId());
        qualifiedName.setPrimitiveDefCategory(PrimitiveDefCategory.OM_PRIMITIVE_TYPE_STRING);
        properties.put("qualifiedName", qualifiedName);

        PrimitivePropertyValue displayname = new PrimitivePropertyValue();
        displayname.setPrimitiveValue(igcObject.getName());
        displayname.setPrimitiveDefCategory(PrimitiveDefCategory.OM_PRIMITIVE_TYPE_STRING);
        properties.put("displayName", displayname);

        PrimitivePropertyValue name = new PrimitivePropertyValue();
        name.setPrimitiveValue(igcObject.getName());
        name.setPrimitiveDefCategory(PrimitiveDefCategory.OM_PRIMITIVE_TYPE_STRING);
        properties.put("name", name);

        PrimitivePropertyValue summary = new PrimitivePropertyValue();
        summary.setPrimitiveValue(igcObject.getShortDescription());
        summary.setPrimitiveDefCategory(PrimitiveDefCategory.OM_PRIMITIVE_TYPE_STRING);
        properties.put("summary", summary);

        PrimitivePropertyValue description = new PrimitivePropertyValue();
        description.setPrimitiveValue(igcObject.getLongDescription());
        description.setPrimitiveDefCategory(PrimitiveDefCategory.OM_PRIMITIVE_TYPE_STRING);
        properties.put("description", description);

        InstanceProperties instanceProperties = new InstanceProperties();
        instanceProperties.setInstanceProperties(properties);

        EntityDetail entityDetail = this.repositoryHelper.getNewEntity(
                sourceName,
                metadataCollectionId,
                InstanceProvenanceType.LOCAL_COHORT,
                username,
                typeName,
                instanceProperties,
                null
        );

        entityDetail.setStatus(InstanceStatus.ACTIVE);

        if (avoidDuplicate)
            entityDetail.setGUID(typeName + ".rid." + igcObject.getId());
        else
            entityDetail.setGUID(igcObject.getId());
        this.repositoryEventProcessor.processNewEntityEvent(
                sourceName,
                metadataCollectionId,
                originatorServerName,
                originatorServerType,
                originatorOrganizationName,
                entityDetail

        );

        log.info("Created " + typeName + "." + igcObject.getId());
    }

    /**
     * Set relationship between two entities.
     *
     * @param relationshipType name of the type
     * @param entityId1        String unique identifier
     * @param entityType1      name of the type
     * @param entityId2        String unique identifier
     * @param entityType2      name of the type
     * @throws TypeErrorException The type name is not recognized as a relationship type.
     */
    private void createRelationship(String relationshipType, String entityId1, String entityType1, String entityId2, String entityType2) throws TypeErrorException {

        Relationship relationship = this.repositoryHelper.getNewRelationship(
                sourceName,
                metadataCollectionId,
                InstanceProvenanceType.LOCAL_COHORT,
                null,
                relationshipType,
                null
        );

        EntityProxy entityProxyOne = newEntityProxy(entityType1);
        EntityProxy entityProxyTwo = newEntityProxy(entityType2);

        entityProxyOne.setGUID(entityId1);
        entityProxyTwo.setGUID(entityId2);

        relationship.setEntityOneProxy(entityProxyOne);
        relationship.setEntityTwoProxy(entityProxyTwo);

        this.repositoryEventProcessor.processNewRelationshipEvent(
                sourceName,
                metadataCollectionId,
                originatorServerName,
                originatorServerType,
                originatorOrganizationName,
                relationship
        );

        log.info("Created relationship " + relationshipType + "(" + entityType1 + ", " + entityType2 + ")");
        log.info("Created relationship between " + entityId1 + " and " + entityId2);
    }


    /**
     * Return a filled out entity.
     *
     * @param typeName name of the type
     * @return an entity that is filled out
     * @throws TypeErrorException the type name is not recognized as an entity type
     */
    private EntityProxy newEntityProxy(String typeName) throws TypeErrorException {
        EntityProxy entityProxy = this.repositoryHelper.getNewEntityProxy(
                sourceName,
                metadataCollectionId,
                InstanceProvenanceType.LOCAL_COHORT,
                null,
                typeName,
                null,
                null
        );
        entityProxy.setStatus(InstanceStatus.ACTIVE);
        return entityProxy;
    }

}

