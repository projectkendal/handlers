package kendal.processor;

import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Scope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import kendal.api.AstHelper;
import kendal.api.KendalHandler;
import kendal.api.exceptions.KendalException;
import kendal.api.exceptions.KendalRuntimeException;
import kendal.api.impl.AstHelperImpl;
import kendal.api.inheritance.Inherit;
import kendal.model.ForestBuilder;
import kendal.model.Node;
import kendal.utils.ForestUtils;
import kendal.utils.KendalMessager;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static kendal.utils.AnnotationUtils.isAnnotationType;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class KendalProcessor extends AbstractProcessor {

    private Context context;
    private Trees trees;
    private ForestBuilder forestBuilder;
    private KendalMessager messager;
    private AstHelper astHelper;
    private boolean firstRound;
    private TreeMaker treeMaker;
    private Set<Node> firstRoundNodes;
    private JavacProcessingEnvironment procEnv;


    @Override
    public void init(ProcessingEnvironment processingEnv) {
        procEnv = (JavacProcessingEnvironment) processingEnv;
        trees = Trees.instance(processingEnv);
        messager = new KendalMessager(processingEnv.getMessager());
        forestBuilder = new ForestBuilder(trees, messager);
        firstRound = true;
        super.init(processingEnv);
    }

    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        context = procEnv.getContext();
        astHelper = new AstHelperImpl(context);
        treeMaker = TreeMaker.instance(context);
        long startTime = System.currentTimeMillis();
        messager.printMessage(Diagnostic.Kind.NOTE, "Processor run!");
        if (roundEnv.processingOver()) return false;
        Set<Node> forest = forestBuilder.buildForest(roundEnv.getRootElements());

        if(firstRound) {
            handleAnnotationInheritance(forest);
            try {
                // force next processing round
                JavaFileObject fileObject = processingEnv.getFiler().createSourceFile("KendalGreatestFrameworkInTheWorld", null);
                Writer writer = fileObject.openWriter();
                writer.close();
                firstRoundNodes = forest;
                firstRound = false;
            } catch (IOException e) {
                throw new KendalRuntimeException(e.getMessage());
            }
        } else {
            if(firstRoundNodes != null) {
                // handle nodes found on the first round, which we could not handle then because of annotation extending processing
                forest.addAll(firstRoundNodes);
                firstRoundNodes = null;
            }
            Set<KendalHandler> handlers = getHandlersFromSPI();
            displayRegisteredHandlers(handlers);
            executeHandlers(getHandlerAnnotationsMap(handlers, forest), forest);
        }

        messager.printElapsedTime("Processor", startTime);
        return false;
    }

    private void handleAnnotationInheritance(Set<Node> forest) {
        Map<String, Node<JCClassDecl>> annotationsDeclMap = getAnnotationsDeclMap(forest);
        handleInherit(forest, annotationsDeclMap);
        handleAttribute(forest, annotationsDeclMap);
        handleAttrReference(forest);
    }

    private void handleInherit(Set<Node> forest, Map<String, Node<JCClassDecl>> annotationsDeclMap) {
        Set<Node> inherits = getAnnotationsOfType(forest, Inherit.class);
        Set<JCClassDecl> handledAnnotations = new HashSet<>();
        inherits.forEach(node -> handleInheritingAnnotation((JCClassDecl) node.getParent().getObject(),
                handledAnnotations, annotationsDeclMap));
    }

    private void handleInheritingAnnotation(JCClassDecl annotationDecl, Set<JCClassDecl> handledNodes, Map<String, Node<JCClassDecl>> annotationsDeclMap) {
        if(handledNodes.contains(annotationDecl)) {
            return;
        }
        JCAnnotation inheritAnnotation = getJCAnnotation(annotationDecl, Inherit.class);
        if(inheritAnnotation != null) {
            String inheritedAnnotationName = ((JCIdent) ((JCAnnotation) ((JCAssign) inheritAnnotation.args.head).rhs).annotationType).sym.getQualifiedName().toString();

            Map<String, JCTree> inheritedDefs = new HashMap<>();
            if(annotationsDeclMap.containsKey(inheritedAnnotationName)) {
                // inherited annotation is part of the compilation
                Node<JCClassDecl> inheritedAnnotation = annotationsDeclMap.get(inheritedAnnotationName);
                handleInheritingAnnotation(inheritedAnnotation.getObject(), handledNodes, annotationsDeclMap);
                inheritedDefs = inheritedAnnotation.getObject().defs.stream()
                        .collect(Collectors.toMap(h -> ((JCMethodDecl) h).name.toString(), Function.identity()));
            } else {
                // inherited annotation has to be retrieved through ClassSymbol
                ClassSymbol inheritedAnnotationSymbol = (ClassSymbol) ((JCIdent) ((JCAnnotation) ((JCAssign) inheritAnnotation.args.head).rhs).annotationType).sym;
                for (Scope.Entry entry = inheritedAnnotationSymbol.members().elems; entry != null; entry = entry.sibling) {
                    if(entry.sym instanceof Symbol.MethodSymbol) {
                        JCMethodDecl methodDecl = treeMaker.MethodDef((Symbol.MethodSymbol) entry.sym, null);
                        Attribute defaultValue = ((Symbol.MethodSymbol) entry.sym).defaultValue;
                        methodDecl.defaultValue = defaultValue == null ? null : treeMaker.Literal(defaultValue.getValue());
                        inheritedDefs.put(entry.sym.name.toString(), methodDecl);
                    }
                }
            }

            // override default values
            for (JCExpression arg : ((JCAnnotation) ((JCAssign) inheritAnnotation.args.head).rhs).args) {
                JCTree def = inheritedDefs.get(((JCIdent) ((JCAssign) arg).lhs).name.toString());
                if(def instanceof JCMethodDecl) {
                    ((JCMethodDecl) def).defaultValue = ((JCAssign) arg).rhs;
                }
            }

            annotationDecl.defs = annotationDecl.defs.appendList(astHelper.getAstUtils().toJCList(new ArrayList<>(inheritedDefs.values())));
            // store inherited annotation class in metadata field
            inheritAnnotation.args = com.sun.tools.javac.util.List.of(
                    treeMaker.Assign(
                            treeMaker.Ident(astHelper.getAstUtils().nameFromString("inheritedAnnotationClass")),
                            treeMaker.ClassLiteral(((JCIdent) ((JCAnnotation) ((JCAssign) inheritAnnotation.args.head).rhs).annotationType).sym.type)));
            handledNodes.add(annotationDecl);
        }
    }

    private void handleAttribute(Set<Node> forest, Map<String, Node<JCClassDecl>> annotationsDeclMap) {
        annotationsDeclMap.forEach((name, jcClassDeclNode) -> {
            List<JCAnnotation> attributes = new ArrayList<>();

            jcClassDeclNode.getChildren().forEach(child -> {
                if(child.is(JCAnnotation.class)) {
                    if(child.getObject().type.tsym.getQualifiedName().contentEquals(kendal.api.inheritance.Attribute.class.getName())) {
                        attributes.add((JCAnnotation) child.getObject());
                    }

                    if(child.getObject().type.tsym.getQualifiedName().contentEquals(kendal.api.inheritance.Attribute.List.class.getName())) {
                        ((JCNewArray) ((JCAnnotation) child.getObject()).args.head).elems.forEach(elem -> {
                            attributes.add((JCAnnotation) elem);
                        });
                    }
                }
            });

            if(attributes.isEmpty()) {
                return; // no attributes to apply, so we are free
            }

            List<Node<JCAnnotation>> annotationNodes = new ArrayList<>();
            ForestUtils.traverse(forest, node -> {
                if(node.is(JCAnnotation.class) && node.getObject().type.tsym.getQualifiedName().contentEquals(name)) {
                    annotationNodes.add(node);
                }
            });
            annotationNodes.forEach(node -> node.getObject().args = node.getObject().args
                    .appendList(astHelper.getAstUtils().toJCList(attributes.stream().map(attr -> {
                JCIdent attrIdent = attr.args
                        .stream()
                        .filter(arg -> ((JCIdent) ((JCAssign) arg).lhs).name.contentEquals("name"))
                        .map(arg -> ((JCLiteral) ((JCAssign) arg).rhs))
                        .map(literal -> treeMaker.Ident(astHelper.getAstUtils().nameFromString((String) literal.value)))
                        .findFirst().orElse(null);

                JCExpression attrValue = attr.args
                        .stream()
                        .filter(arg -> ((JCIdent) ((JCAssign) arg).lhs).name.contentEquals("value"))
                        .findFirst()
                        .map(arg -> ((JCAssign) arg).rhs)
                        .orElse(null);
                return treeMaker.Assign(attrIdent, attrValue);
            }).collect(Collectors.toList()))));

            attributes.forEach(attr -> {
                attr.args = astHelper.getAstUtils().toJCList(attr.args.stream()
                        .filter(arg -> !((JCIdent) ((JCAssign) arg).lhs).name.contentEquals("value"))
                        .collect(Collectors.toList()));
            });
        });
    }

    private void handleAttrReference(Set<Node> forest) {

    }

    private JCAnnotation getJCAnnotation(JCClassDecl classDecl, Class<? extends Annotation> annotationClass) {
        return classDecl.mods.annotations.stream()
                .filter(ann -> ann.type.tsym.getQualifiedName().contentEquals(annotationClass.getName()))
                .findFirst()
                .orElse(null);
    }

    private Set<Node> getAnnotationsOfType(Set<Node> forest, Class annotation) {
        Set<Node> result = new HashSet<>();
        ForestUtils.traverse(forest, node -> {
            if(node.is(JCAnnotation.class) && node.getObject().type.tsym.getQualifiedName().contentEquals(annotation.getName())) {
                result.add(node);
            }
        });
        return result;
    }

    private Map<String, Node<JCClassDecl>> getAnnotationsDeclMap(Set<Node> forest) {
        Map<String, Node<JCClassDecl>> result = new HashMap<>();
        ForestUtils.traverse(forest, node -> {
            if(isAnnotationType(node)) {
                result.put(((JCClassDecl) node.getObject()).sym.type.tsym.getQualifiedName().toString(), node);
            }
        });
        return result;
    }

    private Map<KendalHandler, Set<Node>> getHandlerAnnotationsMap(Set<KendalHandler> handlers, Set<Node> forest) {
        long startTime = System.currentTimeMillis();
        Map<KendalHandler, Set<Node>> handlersWithNodes = handlers.stream()
                .collect(Collectors.toMap(Function.identity(), h -> new HashSet<>()));

        // annotation FQN -> Handler
        // Handler may be null, if annotation is not handled by any handler
        Map<String, KendalHandler> annotationToHandler = handlers.stream()
                .filter(kendalHandler -> kendalHandler.getHandledAnnotationType() != null)
                .collect(Collectors.toMap(h -> h.getHandledAnnotationType().getName(), Function.identity()));

        Map<String, Node<JCClassDecl>> annotationsDeclMap = getAnnotationsDeclMap(forest);

        ForestUtils.traverse(forest, node -> {
            if(node.is(JCAnnotation.class)) {
                assignNodeToHandler(node, annotationToHandler, handlersWithNodes, annotationsDeclMap);
            }
        });

        messager.printElapsedTime("Annotations' scanner", startTime);
        return handlersWithNodes;
    }

    private void assignNodeToHandler(Node<JCAnnotation> node, Map<String, KendalHandler> annotationToHandler,
                                     Map<KendalHandler, Set<Node>> handlersWithNodes, Map<String, Node<JCClassDecl>> annotationsDeclMap) {
        String fqn = node.getObject().type.tsym.getQualifiedName().toString();
        if(annotationToHandler.containsKey(fqn)) {
            // handler for this annotation type is already cached. Possibly null
            KendalHandler handler = annotationToHandler.get(fqn);
            if(handler != null) {
                handlersWithNodes.get(handler).add(node);
            }
        } else {
            // try to inherit handler
            Inherit inherit = node.getObject().type.tsym.getAnnotation(Inherit.class);
            resolveInherit(fqn, inherit, annotationToHandler, annotationsDeclMap);
            KendalHandler handler = annotationToHandler.get(fqn);
            if(handler != null) {
                handlersWithNodes.get(handler).add(node);
            }
        }
    }

    private void resolveInherit(String fqn, Inherit inherit, Map<String, KendalHandler> annotationToHandler, Map<String, Node<JCClassDecl>> annotationsDeclMap) {
        if(inherit == null) {
            // we don't inherit, so insert null handler
            annotationToHandler.put(fqn, null);
            return;
        }
        try {
            inherit.inheritedAnnotationClass();
        } catch(MirroredTypeException e) {
            Type.ClassType inheritedAnnotationType = (Type.ClassType) e.getTypeMirror();
            String inheritedFqn = inheritedAnnotationType.tsym.getQualifiedName().toString();

            if(annotationToHandler.containsKey(inheritedFqn)) {
                annotationToHandler.put(fqn, annotationToHandler.get(inheritedFqn));
            } else {
                resolveInherit(inheritedFqn, inheritedAnnotationType.tsym.getAnnotation(Inherit.class), annotationToHandler, annotationsDeclMap);
                annotationToHandler.put(fqn, annotationToHandler.get(inheritedFqn));
            }
            return;
        }
        throw new KendalRuntimeException("Inherited annotation type mirror could not be resolved for " + fqn); // should never happen, but let's throw just in case
    }

    private Set<KendalHandler> getHandlersFromSPI() {
        return StreamSupport
                .stream(ServiceLoader.load(KendalHandler.class, KendalProcessor.class.getClassLoader()).spliterator(), false)
                .collect(Collectors.toSet());
    }

    private void displayRegisteredHandlers(Set<KendalHandler> handlers) {
        messager.printMessage(Diagnostic.Kind.NOTE, "### Handlers' registration ###");
        handlers.forEach(handler -> {
            String target = handler.getHandledAnnotationType() != null ? handler.getHandledAnnotationType().toString() : "all nodes";
            messager.printMessage(Diagnostic.Kind.NOTE,
                    String.format("%s registered as provider for %s", handler.getClass().getName(), target));
        });
    }

    private void executeHandlers(Map<KendalHandler, Set<Node>> handlersMap, Set<Node> forest) {
        messager.printMessage(Diagnostic.Kind.NOTE, "### Handlers' execution ###");
        handlersMap.forEach((handler, annotationNodes) -> {
            try {
                long startTime = System.currentTimeMillis();
                if (handler.getHandledAnnotationType() != null) handler.handle(annotationNodes, astHelper);
                else handler.handle(forest, astHelper);
                messager.printElapsedTime("Handler" + handler.getClass().getName(), startTime);
            } catch (KendalException | KendalRuntimeException e) {
                messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage());
            }
        });
    }
}