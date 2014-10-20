package io.lumify.core.model.ontology;

import com.google.common.collect.Lists;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.web.clientapi.model.PropertyType;
import org.apache.commons.io.IOUtils;
import org.securegraph.Authorizations;
import org.securegraph.TextIndexHint;
import org.securegraph.inmemory.InMemoryAuthorizations;
import org.securegraph.util.ConvertingIterable;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.ReaderDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.*;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.securegraph.util.IterableUtils.toList;

public class ReadOnlyInMemoryOntologyRepository extends OntologyRepositoryBase {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(ReadOnlyInMemoryOntologyRepository.class);
    private OWLOntologyLoaderConfiguration owlConfig = new OWLOntologyLoaderConfiguration();
    private Map<String, InMemoryConcept> conceptsCache = new HashMap<String, InMemoryConcept>();
    private Map<String, InMemoryOntologyProperty> propertiesCache = new HashMap<String, InMemoryOntologyProperty>();
    private Map<String, InMemoryRelationship> relationshipsCache = new HashMap<String, InMemoryRelationship>();
    private List<OwlData> fileCache = new ArrayList<OwlData>();

    public void init(Configuration config) throws Exception {
        clearCache();
        Authorizations authorizations = new InMemoryAuthorizations(VISIBILITY_STRING);
        owlConfig.setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        if (!isOntologyDefined()) {
            LOGGER.info("Base ontology not defined. Creating a new ontology.");
            defineOntology(config, authorizations);
        } else {
            LOGGER.info("Base ontology already defined.");
        }
    }

    @Override
    protected Concept importOntologyClass(OWLOntology o, OWLClass ontologyClass, File inDir, Authorizations authorizations) throws IOException {
        InMemoryConcept concept = (InMemoryConcept) super.importOntologyClass(o, ontologyClass, inDir, authorizations);
        conceptsCache.put(concept.getIRI(), concept);
        return concept;
    }

    @Override
    protected void setIconProperty(Concept concept, File inDir, String glyphIconFileName, String propertyKey, Authorizations authorizations) throws IOException {
        if (glyphIconFileName == null) {
            concept.setProperty(propertyKey, null, authorizations);
        } else {
            File iconFile = new File(inDir, glyphIconFileName);
            if (!iconFile.exists()) {
                throw new RuntimeException("Could not find icon file: " + iconFile.toString());
            }
            try {
                InputStream iconFileIn = new FileInputStream(iconFile);
                try {
                    concept.setProperty(propertyKey, IOUtils.toByteArray(iconFileIn), authorizations);
                } finally {
                    iconFileIn.close();
                }
            } catch (IOException ex) {
                throw new LumifyException("Failed to set glyph icon to " + iconFile, ex);
            }
        }
    }

    @Override
    protected void addEntityGlyphIconToEntityConcept(Concept entityConcept, byte[] rawImg) {
        entityConcept.setProperty(LumifyProperties.GLYPH_ICON.getPropertyName(), rawImg, null);
    }

    @Override
    protected void storeOntologyFile(InputStream inputStream, IRI documentIRI) {
        try {
            byte[] inFileData = IOUtils.toByteArray(inputStream);
            fileCache.add(new OwlData(documentIRI.toString(), inFileData));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected List<OWLOntology> loadOntologyFiles(OWLOntologyManager m, OWLOntologyLoaderConfiguration config, IRI excludedIRI) throws Exception {
        List<OWLOntology> loadedOntologies = new ArrayList<OWLOntology>();
        for (OwlData owlData : fileCache) {
            IRI lumifyBaseOntologyIRI = IRI.create(owlData.iri);
            if (excludedIRI != null && excludedIRI.equals(lumifyBaseOntologyIRI)) {
                continue;
            }
            InputStream lumifyBaseOntologyIn = new ByteArrayInputStream(owlData.data);
            try {
                Reader lumifyBaseOntologyReader = new InputStreamReader(lumifyBaseOntologyIn);
                LOGGER.info("Loading existing ontology: %s", owlData.iri);
                OWLOntologyDocumentSource lumifyBaseOntologySource = new ReaderDocumentSource(lumifyBaseOntologyReader, lumifyBaseOntologyIRI);
                OWLOntology o = m.loadOntologyFromOntologyDocument(lumifyBaseOntologySource, config);
                loadedOntologies.add(o);
            } finally {
                lumifyBaseOntologyIn.close();
            }
        }
        return loadedOntologies;
    }

    @Override
    protected OntologyProperty addPropertyTo(
            Concept concept,
            String propertyIRI,
            String displayName,
            PropertyType dataType,
            Map<String, String> possibleValues,
            Collection<TextIndexHint> textIndexHints,
            boolean userVisible,
            boolean searchable,
            String displayType,
            String propertyGroup,
            Double boost) {
        checkNotNull(concept, "concept was null");
        InMemoryOntologyProperty property = getOrCreatePropertyType(propertyIRI, dataType, displayName, possibleValues, userVisible, searchable, displayType, propertyGroup, boost);
        concept.getProperties().add(property);
        checkNotNull(property, "Could not find property: " + propertyIRI);
        return property;
    }

    @Override
    protected void getOrCreateInverseOfRelationship(Relationship fromRelationship, Relationship inverseOfRelationship) {
        InMemoryRelationship fromRelationshipMem = (InMemoryRelationship) fromRelationship;
        InMemoryRelationship inverseOfRelationshipMem = (InMemoryRelationship) inverseOfRelationship;

        fromRelationshipMem.addInverseOf(inverseOfRelationshipMem);
        inverseOfRelationshipMem.addInverseOf(fromRelationshipMem);
    }

    private InMemoryOntologyProperty getOrCreatePropertyType(
            final String propertyName,
            final PropertyType dataType,
            final String displayName,
            Map<String, String> possibleValues,
            boolean userVisible,
            boolean searchable,
            String displayType,
            String propertyGroup,
            Double boost) {
        InMemoryOntologyProperty property = (InMemoryOntologyProperty) getProperty(propertyName);
        if (property == null) {
            property = new InMemoryOntologyProperty();
            property.setDataType(dataType);
            property.setUserVisible(userVisible);
            property.setSearchable(searchable);
            property.setTitle(propertyName);
            property.setBoost(boost);
            property.setDisplayType(displayType);
            property.setPropertyGroup(propertyGroup);
            if (displayName != null && !displayName.trim().isEmpty()) {
                property.setDisplayName(displayName);
            }
            property.setPossibleValues(possibleValues);
            propertiesCache.put(propertyName, property);
        }
        return property;
    }

    @Override
    public void clearCache() {
        // do nothing it's all in memory already.
    }

    @Override
    public Iterable<Relationship> getRelationships() {
        return new ConvertingIterable<InMemoryRelationship, Relationship>(relationshipsCache.values()) {
            @Override
            protected Relationship convert(InMemoryRelationship InMemRelationship) {
                return InMemRelationship;
            }
        };
    }

    @Override
    public Iterable<OntologyProperty> getProperties() {
        return new ConvertingIterable<InMemoryOntologyProperty, OntologyProperty>(propertiesCache.values()) {
            @Override
            protected OntologyProperty convert(InMemoryOntologyProperty ontologyProperty) {
                return ontologyProperty;
            }
        };
    }

    @Override
    public Iterable<Concept> getConcepts() {
        return new ConvertingIterable<InMemoryConcept, Concept>(conceptsCache.values()) {
            @Override
            protected Concept convert(InMemoryConcept concept) {
                return concept;
            }
        };
    }

    @Override
    public String getDisplayNameForLabel(String relationshipIRI) {
        InMemoryRelationship relationship = relationshipsCache.get(relationshipIRI);
        checkNotNull(relationship, "Could not find relationship " + relationshipIRI);
        return relationship.getDisplayName();
    }

    @Override
    public OntologyProperty getProperty(String propertyIRI) {
        return propertiesCache.get(propertyIRI);
    }

    @Override
    public Relationship getRelationshipByIRI(String relationshipIRI) {
        return relationshipsCache.get(relationshipIRI);
    }

    @Override
    public boolean hasRelationshipByIRI(String relationshipIRI) {
        return getRelationshipByIRI(relationshipIRI) != null;
    }

    @Override
    public Iterable<Concept> getConceptsWithProperties() {
        return new ConvertingIterable<InMemoryConcept, Concept>(conceptsCache.values()) {
            @Override
            protected Concept convert(InMemoryConcept concept) {
                return concept;
            }
        };
    }

    @Override
    public Concept getEntityConcept() {
        return conceptsCache.get(ReadOnlyInMemoryOntologyRepository.ENTITY_CONCEPT_IRI);
    }

    @Override
    public Concept getParentConcept(Concept concept) {
        for (String key : conceptsCache.keySet()) {
            if (key.equals(concept.getParentConceptIRI())) {
                return conceptsCache.get(key);
            }
        }
        return null;
    }

    @Override
    public Concept getConceptByIRI(String conceptIRI) {
        for (String key : conceptsCache.keySet()) {
            if (key.equals(conceptIRI)) {
                return conceptsCache.get(key);
            }
        }
        return null;
    }

    @Override
    public List<Concept> getConceptAndChildrenByIRI(String conceptIRI) {
        List<Concept> concepts = new ArrayList<Concept>();
        concepts.add(conceptsCache.get(conceptIRI));
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        try {
            List<OWLOntology> owlOntologyList = loadOntologyFiles(m, owlConfig, null);
            OWLClass owlClass = m.getOWLDataFactory().getOWLClass(IRI.create(conceptIRI));
            for (OWLClassExpression child : owlClass.getSubClasses(new HashSet<OWLOntology>(owlOntologyList))) {
                InMemoryConcept inMemoryConcept = conceptsCache.get(child.asOWLClass().getIRI().toString());
                concepts.add(inMemoryConcept);
            }
        } catch (Exception e) {
            throw new LumifyException("could not load ontology files");
        }


        return concepts;
    }

    @Override
    public List<Concept> getAllLeafNodesByConcept(Concept concept) {
        List<Concept> concepts = Lists.newArrayList(concept);
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();

        try {
            List<OWLOntology> owlOntologyList = loadOntologyFiles(m, owlConfig, null);
            OWLClass owlClass = m.getOWLDataFactory().getOWLClass(IRI.create(((InMemoryConcept) concept).getConceptIRI()));
            for (OWLClassExpression child : owlClass.getSubClasses(new HashSet<OWLOntology>(owlOntologyList))) {
                InMemoryConcept inMemoryConcept = conceptsCache.get(child.asOWLClass().getIRI().toString());
                concepts.add(inMemoryConcept);
            }
        } catch (Exception e) {
            throw new LumifyException("could not load ontology files");
        }

        return concepts;
    }

    @Override
    public Concept getOrCreateConcept(Concept parent, String conceptIRI, String displayName, File inDir) {
        InMemoryConcept concept = (InMemoryConcept) getConceptByIRI(conceptIRI);
        if (concept != null) {
            return concept;
        }
        if (parent == null) {
            concept = new InMemoryConcept(conceptIRI, null);
        } else {
            concept = new InMemoryConcept(conceptIRI, ((InMemoryConcept) parent).getConceptIRI());
        }
        concept.setProperty(LumifyProperties.TITLE.getPropertyName(), conceptIRI, null);
        concept.setProperty(LumifyProperties.DISPLAY_NAME.getPropertyName(), displayName, null);
        conceptsCache.put(conceptIRI, concept);

        return concept;
    }

    @Override
    public Relationship getOrCreateRelationshipType(Iterable<Concept> domainConcepts, Iterable<Concept> rangeConcepts, String relationshipIRI, String displayName) {
        Relationship relationship = getRelationshipByIRI(relationshipIRI);
        if (relationship != null) {
            return relationship;
        }

        List<String> domainConceptIris = toList(new ConvertingIterable<Concept, String>(domainConcepts) {
            @Override
            protected String convert(Concept o) {
                return o.getIRI();
            }
        });

        List<String> rangeConceptIris = toList(new ConvertingIterable<Concept, String>(rangeConcepts) {
            @Override
            protected String convert(Concept o) {
                return o.getIRI();
            }
        });

        InMemoryRelationship inMemRelationship = new InMemoryRelationship(relationshipIRI, displayName, domainConceptIris, rangeConceptIris);
        relationshipsCache.put(relationshipIRI, inMemRelationship);
        return inMemRelationship;
    }

    private static class OwlData {
        public final String iri;
        public final byte[] data;

        public OwlData(String iri, byte[] data) {
            this.iri = iri;
            this.data = data;
        }
    }
}
