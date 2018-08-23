package be.ugent.rml;

import be.ugent.rml.functions.*;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.term.Literal;
import be.ugent.rml.term.NamedNode;
import be.ugent.rml.term.Term;
import be.ugent.rml.termgenerator.BlankNodeGenerator;
import be.ugent.rml.termgenerator.LiteralGenerator;
import be.ugent.rml.termgenerator.NamedNodeGenerator;
import be.ugent.rml.termgenerator.TermGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MappingFactory {
    private final FunctionLoader functionLoader;
    private MappingInfo subjectMappingInfo;
    private List<MappingInfo> graphMappingInfos;
    private Term triplesMap;
    private QuadStore store;
    private List<PredicateObjectGraphMapping> predicateObjectGraphMappings;
    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    public MappingFactory(FunctionLoader functionLoader) {
        this.functionLoader = functionLoader;
    }

    public Mapping createMapping(Term triplesMap, QuadStore store) throws IOException {
        this.triplesMap = triplesMap;
        this.store = store;
        this.subjectMappingInfo = null;
        this.predicateObjectGraphMappings = new ArrayList<>();
        this.graphMappingInfos = null;

        parseSubjectMap();
        parsePredicateObjectMaps();
        graphMappingInfos = parseGraphMapsAndShortcuts(subjectMappingInfo.getTerm());


        //return the mapping
        return new Mapping(subjectMappingInfo, predicateObjectGraphMappings, graphMappingInfos);
    }

    private void parseSubjectMap() throws IOException {
        if (this.subjectMappingInfo == null) {
            TermGenerator generator;
            List<Term> subjectmaps = Utils.getObjectsFromQuads(store.getQuads(triplesMap, new NamedNode(NAMESPACES.RR + "subjectMap"), null));

            if (!subjectmaps.isEmpty()) {
                if (subjectmaps.size() > 1) {
                    logger.warn(triplesMap + " has " + subjectmaps.size() + "Subject Maps. You can only have one. A random one is taken.");
                }

                Term subjectmap = subjectmaps.get(0);
                List<Term> functionValues =  Utils.getObjectsFromQuads(store.getQuads(subjectmap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));

                if (functionValues.isEmpty()) {
                    List<Term> termTypes = Utils.getObjectsFromQuads(store.getQuads(subjectmap, new NamedNode(NAMESPACES.RR  + "termType"), null));

                    //checking if we are dealing with a Blank Node as subject
                    if (!termTypes.isEmpty() && termTypes.get(0).equals(new NamedNode(NAMESPACES.RR  + "BlankNode"))) {
                        String template = getGenericTemplate(subjectmap);

                        if (template != null) {
                            generator = new BlankNodeGenerator(ApplyTemplateFunctionFactory.generate(template, true));
                        } else {
                            generator = new BlankNodeGenerator();
                        }
                    } else {
                        //we are not dealing with a Blank Node, so we create the template
                        generator = new NamedNodeGenerator(ApplyTemplateFunctionFactory.generate(getGenericTemplate(subjectmap), true));
                    }
                } else {
                    DynamicFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));

                    generator = new NamedNodeGenerator(functionExecutor);
                }

                this.subjectMappingInfo = new MappingInfo(subjectmap, generator);

                //get classes
                List<Term> classes = Utils.getObjectsFromQuads(store.getQuads(subjectmap, new NamedNode(NAMESPACES.RR  + "class"), null));

                //we create predicateobjects for the classes
                for (Term c: classes) {
                    // Don't put in graph for rr:class, subject is already put in graph, otherwise double export
                    NamedNodeGenerator predicateGenerator = new NamedNodeGenerator(ApplyTemplateFunctionFactory.generateWithConstantValue(NAMESPACES.RDF + "type"));
                    NamedNodeGenerator objectGenerator = new NamedNodeGenerator(ApplyTemplateFunctionFactory.generateWithConstantValue(c.getValue()));
                    predicateObjectGraphMappings.add(new PredicateObjectGraphMapping(
                            new MappingInfo(subjectmap, predicateGenerator),
                            new MappingInfo(subjectmap, objectGenerator),
                            null));
                }
            } else {
                throw new Error(triplesMap + " has no Subject Map. Each Triples Map should have exactly one Subject Map.");
            }
        }
    }

    private void parsePredicateObjectMaps() throws IOException {
        List<Term> predicateobjectmaps = Utils.getObjectsFromQuads(store.getQuads(triplesMap, new NamedNode(NAMESPACES.RR + "predicateObjectMap"), null));

        for (Term pom : predicateobjectmaps) {
            List<MappingInfo> predicateMappingInfos = parsePredicateMapsAndShortcuts(pom);
            List<MappingInfo> graphMappingInfos = parseGraphMapsAndShortcuts(pom);

            parseObjectMapsAndShortcutsAndGeneratePOGGenerators(pom, predicateMappingInfos, graphMappingInfos);
        }
    }

    private void parseObjectMapsAndShortcutsAndGeneratePOGGenerators(Term termMap, List<MappingInfo> predicateMappingInfos, List<MappingInfo> graphMappingInfos) throws IOException {
        parseObjectMapsAndShortcutsWithCallback(termMap, oMappingInfo -> {
            predicateMappingInfos.forEach(pMappingInfo -> {
                if (graphMappingInfos.isEmpty()) {
                    predicateObjectGraphMappings.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, null));
                } else {
                    graphMappingInfos.forEach(gMappingInfo -> {
                        predicateObjectGraphMappings.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, gMappingInfo));
                    });
                }
            });
        }, (parentTriplesMap, joinConditionFunctions) -> {
            predicateMappingInfos.forEach(pMappingInfo -> {
                List<PredicateObjectGraphMapping> pos = getPredicateObjectGraphMappingFromMultipleGraphMappingInfos(pMappingInfo, null, graphMappingInfos);

                pos.forEach(pogMappingInfo -> {
                    pogMappingInfo.setParentTriplesMap(parentTriplesMap);

                    joinConditionFunctions.forEach(jcf -> {
                        pogMappingInfo.addJoinCondition(jcf);
                    });

                    predicateObjectGraphMappings.add(pogMappingInfo);
                });
            });
        });
    }

    private void parseObjectMapsAndShortcutsWithCallback(Term termMap, Consumer<MappingInfo> objectMapCallback, BiConsumer<Term, List<JoinConditionFunction>> refObjectMapCallback) throws IOException {
        List<Term> objectmaps = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "objectMap"), null));

        for (Term objectmap : objectmaps) {
            List<Term> functionValues = Utils.getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));
            Term termType = getTermType(objectmap);

            List<Term> datatypes = Utils.getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RR + "datatype"), null));
            List<Term> languages = Utils.getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RR + "language"), null));

            if (functionValues.isEmpty()) {
                String genericTemplate = getGenericTemplate(objectmap);

                if (genericTemplate != null) {
                    StaticFunctionExecutor fn = ApplyTemplateFunctionFactory.generate(genericTemplate, termType);
                    TermGenerator oGen;

                    if (termType.equals(new NamedNode(NAMESPACES.RR + "Literal"))) {
                        //check if we need to apply a datatype to the object
                        if (!datatypes.isEmpty()) {
                            oGen = new LiteralGenerator(fn, datatypes.get(0));
                            //check if we need to apply a language to the object
                        } else if (!languages.isEmpty()) {
                            oGen = new LiteralGenerator(fn, languages.get(0).getValue());
                        } else {
                            oGen = new LiteralGenerator(fn);
                        }
                    } else {
                        oGen = new NamedNodeGenerator(fn);
                    }

                    objectMapCallback.accept(new MappingInfo(objectmap, oGen));
                } else {
                    //look for parenttriplesmap
                    List<Term> parentTriplesMaps = Utils.getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RR + "parentTriplesMap"), null));

                    if (! parentTriplesMaps.isEmpty()) {
                        if (parentTriplesMaps.size() > 1) {
                            logger.warn(triplesMap + " has " + parentTriplesMaps.size() + " Parent Triples Maps. You can only have one. A random one is taken.");
                        }

                        Term parentTriplesMap = parentTriplesMaps.get(0);

                        List<Term> joinConditions = Utils.getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RR + "joinCondition"), null));
                        ArrayList<JoinConditionFunction> joinConditionFunctions = new ArrayList<>();

                        for (Term joinCondition : joinConditions) {

                            List<String> parents = Utils.getLiteralObjectsFromQuads(store.getQuads(joinCondition, new NamedNode(NAMESPACES.RR + "parent"), null));
                            List<String> childs = Utils.getLiteralObjectsFromQuads(store.getQuads(joinCondition, new NamedNode(NAMESPACES.RR + "child"), null));

                            if (parents.isEmpty()) {
                                throw new Error("One of the join conditions of " + triplesMap + " is missing rr:parent.");
                            } else if (childs.isEmpty()) {
                                throw new Error("One of the join conditions of " + triplesMap + " is missing rr:child.");
                            } else {
                                FunctionModel equal = functionLoader.getFunction(new NamedNode("http://example.com/idlab/function/equal"));
                                Map<String, Object[]> parameters = new HashMap<>();

                                Template parent = new Template();
                                parent.addElement(new TemplateElement(parents.get(0), TEMPLATETYPE.VARIABLE));
                                List<Template> parentsList = new ArrayList<>();
                                parentsList.add(parent);
                                Object[] detailsParent = {"parent", parentsList};
                                parameters.put("http://users.ugent.be/~bjdmeest/function/grel.ttl#valueParameter", detailsParent);

                                Template child = new Template();
                                child.addElement(new TemplateElement(childs.get(0), TEMPLATETYPE.VARIABLE));
                                List<Template> childsList = new ArrayList<>();
                                childsList.add(child);
                                Object[] detailsChild = {"child", childsList};
                                parameters.put("http://users.ugent.be/~bjdmeest/function/grel.ttl#valueParameter2", detailsChild);

                                joinConditionFunctions.add(new JoinConditionFunction(equal, parameters));
                            }
                        }

                        refObjectMapCallback.accept(parentTriplesMap, joinConditionFunctions);
                    }
                }
            } else {
                DynamicFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));
                TermGenerator gen;

                //TODO is literal the default?
                if (termType == null || termType.equals( new NamedNode(NAMESPACES.RR + "Literal"))) {
                    //check if we need to apply a datatype to the object
                    if (!datatypes.isEmpty()) {
                        gen = new LiteralGenerator(functionExecutor, datatypes.get(0));
                        //check if we need to apply a language to the object
                    } else if (!languages.isEmpty()) {
                        gen = new LiteralGenerator(functionExecutor, languages.get(0).getValue());
                    } else {
                        gen = new LiteralGenerator(functionExecutor);
                    }
                } else {
                    gen = new NamedNodeGenerator(functionExecutor);
                }

                objectMapCallback.accept(new MappingInfo(objectmap, gen));
            }
        }

        //dealing with rr:object
        List<Term> objectsConstants = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "object"), null));

        for (Term o : objectsConstants) {
            TermGenerator gen;
            StaticFunctionExecutor fn = ApplyTemplateFunctionFactory.generateWithConstantValue(o.getValue());

            if (o instanceof Literal) {
                gen = new LiteralGenerator(fn);
            } else {
                gen = new NamedNodeGenerator(fn);
            }

            objectMapCallback.accept(new MappingInfo(termMap, gen));
        }
    }

    private List<MappingInfo> parseGraphMapsAndShortcuts(Term termMap) throws IOException {
        ArrayList<MappingInfo> graphMappingInfos = new ArrayList<>();

        List<Term> graphMaps = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "graphMap"), null));

        for (Term graphMap : graphMaps) {
            List<Term> functionValues = Utils.getObjectsFromQuads(store.getQuads(graphMap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));
            List<Term> termTypes = Utils.getObjectsFromQuads(store.getQuads(graphMap, new NamedNode(NAMESPACES.RR + "termType"), null));
            Term termType = null;

            if (!termTypes.isEmpty()) {
                termType = termTypes.get(0);
            }

            if (functionValues.isEmpty()) {
                String genericTemplate = getGenericTemplate(graphMap);

                if (termType == null || termType.equals(new NamedNode(NAMESPACES.RR + "IRI"))) {
                    graphMappingInfos.add(new MappingInfo(termMap,
                            new NamedNodeGenerator(ApplyTemplateFunctionFactory.generate(genericTemplate, true))));
                } else {
                    if (genericTemplate == null) {
                        graphMappingInfos.add(new MappingInfo(termMap, new BlankNodeGenerator()));
                    } else {
                        graphMappingInfos.add(new MappingInfo(termMap,
                                new BlankNodeGenerator(ApplyTemplateFunctionFactory.generate(genericTemplate, true))));
                    }
                }
            } else {
                DynamicFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));

                if (termType == null || termType.equals(new NamedNode(NAMESPACES.RR + "IRI"))) {
                    graphMappingInfos.add(new MappingInfo(termMap, new NamedNodeGenerator(functionExecutor)));
                } else {
                    graphMappingInfos.add(new MappingInfo(termMap, new BlankNodeGenerator(functionExecutor)));
                }
            }
        }

        List<Term> graphShortcuts = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "graph"), null));

        for (Term graph : graphShortcuts) {
            String gStr = graph.getValue();
            graphMappingInfos.add(new MappingInfo(termMap,
                    new NamedNodeGenerator(ApplyTemplateFunctionFactory.generateWithConstantValue(gStr))));
        }

        return graphMappingInfos;
    }

    private List<MappingInfo> parsePredicateMapsAndShortcuts(Term termMap) throws IOException {
        ArrayList<MappingInfo> predicateMappingInfos = new ArrayList<>();

        List<Term> predicateMaps = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "predicateMap"), null));

        for (Term predicateMap : predicateMaps) {
            List<Term> functionValues = Utils.getObjectsFromQuads(store.getQuads(predicateMap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));

            if (functionValues.isEmpty()) {
                String genericTemplate = getGenericTemplate(predicateMap);

                predicateMappingInfos.add(new MappingInfo(predicateMap,
                        new NamedNodeGenerator(ApplyTemplateFunctionFactory.generate(genericTemplate, true))));
            } else {
                DynamicFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));

                predicateMappingInfos.add(new MappingInfo(predicateMap, new NamedNodeGenerator(functionExecutor)));
            }
        }

        List<Term> predicateShortcuts = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "predicate"), null));

        for (Term predicate : predicateShortcuts) {
            String pStr = predicate.getValue();
            predicateMappingInfos.add(new MappingInfo(termMap, new NamedNodeGenerator(ApplyTemplateFunctionFactory.generateWithConstantValue(pStr))));
        }

        return predicateMappingInfos;
    }

    private DynamicFunctionExecutor parseFunctionTermMap(Term functionValue) throws IOException {
        List<Term> functionPOMs = Utils.getObjectsFromQuads(store.getQuads(functionValue, new NamedNode(NAMESPACES.RR + "predicateObjectMap"), null));
        ArrayList<ParameterValuePair> params = new ArrayList<>();

        for (Term pom : functionPOMs) {
            List<MappingInfo> pMappingInfos = parsePredicateMapsAndShortcuts(pom);
            List<MappingInfo> oMappingInfos = parseObjectMapsAndShortcuts(pom);

            List<TermGenerator> pGenerators = new ArrayList<>();
            pMappingInfos.forEach(mappingInfo -> { pGenerators.add(mappingInfo.getTermGenerator()); });

            List<TermGenerator> oGenerators = new ArrayList<>();
            oMappingInfos.forEach(mappingInfo -> { oGenerators.add(mappingInfo.getTermGenerator()); });

            params.add(new ParameterValuePair(pGenerators, oGenerators));
        }

        return new DynamicFunctionExecutor(params, functionLoader);
    }

    private List<MappingInfo> parseObjectMapsAndShortcuts(Term pom) throws IOException {
        List<MappingInfo> mappingInfos  = new ArrayList<>();

        parseObjectMapsAndShortcutsWithCallback(pom, mappingInfo -> {
            mappingInfos.add(mappingInfo);
        }, (term, joinConditionFunctions) -> {});

        return mappingInfos;
    }

    /**
     * This method parses reference, template, and constant of a given Term Map and return a generic template.
     **/
    private String getGenericTemplate(Term termMap) {
        List<Term> references = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML + "reference"), null));
        List<Term> templates = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "template"), null));
        List<Term> constants = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RR + "constant"), null));
        String genericTemplate = null;

        if (!references.isEmpty()) {
            genericTemplate = "{" + references.get(0).getValue() + "}";
        } else if (!templates.isEmpty()) {
            genericTemplate = templates.get(0).getValue();
        } else if (!constants.isEmpty()) {
            genericTemplate = constants.get(0).getValue();
            genericTemplate = genericTemplate.replaceAll("\\{", "\\\\{").replaceAll("}", "\\\\}");
        }

        return genericTemplate;
    }

    /**
     * This method returns the TermType of a given Term Map.
     * If no Term Type is found, a default Term Type is return based on the R2RML specification.
     **/
    private Term getTermType(Term map) {
        List<Term> references = Utils.getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RML + "reference"), null));
        List<Term> templates = Utils.getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RR  + "template"), null));
        List<Term> constants = Utils.getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RR  + "constant"), null));
        List<Term> termTypes = Utils.getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RR  + "termType"), null));

        Term termType = null;

        if (!termTypes.isEmpty()) {
            termType = termTypes.get(0);
        } else {
            if (!references.isEmpty()) {
                termType = new NamedNode(NAMESPACES.RR + "Literal");
            } else if (!templates.isEmpty()) {
                termType = new NamedNode(NAMESPACES.RR + "IRI");
            } else if (!constants.isEmpty()) {
                termType = new NamedNode(NAMESPACES.RR + "Literal");
            }
        }

        return termType;
    }

    private List<PredicateObjectGraphMapping> getPredicateObjectGraphMappingFromMultipleGraphMappingInfos(MappingInfo pMappingInfo, MappingInfo oMappingInfo, List<MappingInfo> gMappingInfos) {
        ArrayList<PredicateObjectGraphMapping> list = new ArrayList<>();

        gMappingInfos.forEach(gMappingInfo -> {
            list.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, gMappingInfo));
        });

        if (gMappingInfos.isEmpty()) {
            list.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, null));
        }

        return list;
    }

}
