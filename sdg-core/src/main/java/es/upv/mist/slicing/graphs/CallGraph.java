package es.upv.mist.slicing.graphs;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import es.upv.mist.slicing.graphs.cfg.CFG;
import es.upv.mist.slicing.nodes.GraphNode;
import es.upv.mist.slicing.utils.ASTUtils;
import es.upv.mist.slicing.utils.NodeNotFoundException;
import es.upv.mist.slicing.utils.Utils;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.nio.dot.DOTExporter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A directed graph which displays the available method declarations as nodes and their
 * invocations as edges (caller to callee).
 * <br/>
 * Method declarations include both {@link ConstructorDeclaration constructors}
 * and {@link MethodDeclaration method declarations}.
 * In the future, {@link InitializerDeclaration static initializer blocks} and field initializers will be included.
 * <br/>
 * Method calls include only direct method calls, from {@link MethodCallExpr normal call},
 * to {@link ObjectCreationExpr object creation} and {@link ExplicitConstructorInvocationStmt
 * explicit constructor invokation} ({@code this()}, {@code super()}).
 */
public class CallGraph extends DirectedPseudograph<CallGraph.Vertex, CallGraph.Edge<?>> implements Buildable<NodeList<CompilationUnit>> {
    private final Map<CallableDeclaration<?>, CFG> cfgMap;
    private final Map<CallableDeclaration<?>, Vertex> vertexDeclarationMap = ASTUtils.newIdentityHashMap();
    private final ClassGraph classGraph;

    private boolean built = false;

    public CallGraph(Map<CallableDeclaration<?>, CFG> cfgMap, ClassGraph classGraph) {
        super(null, null, false);
        this.cfgMap = cfgMap;
        this.classGraph = classGraph;
    }

    /** Resolve a call to all its possible declarations, by using the call AST nodes stored on the edges. */
    public Stream<CallableDeclaration<?>> getCallTargets(Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        return edgeSet().stream()
                .filter(e -> e.getCall() == call)
                .map(this::getEdgeTarget)
                .map(Vertex::getDeclaration)
                .map(decl -> (CallableDeclaration<?>) decl);
    }

    @Override
    public void build(NodeList<CompilationUnit> arg) {
        if (isBuilt())
            return;
        buildVertices(arg);
        buildEdges(arg);
        built = true;
    }

    @Override
    public boolean isBuilt() {
        return built;
    }

    /** Find the method and constructor declarations (vertices) in the given list of compilation units. */
    protected void buildVertices(NodeList<CompilationUnit> arg) {
        arg.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration n, Void arg) {
                addDeclaration(n);
                super.visit(n, arg);
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                addDeclaration(n);
                super.visit(n, arg);
            }
        }, null);
    }

    protected void addDeclaration(CallableDeclaration<?> n) {
        Vertex v = new Vertex(n);
        vertexDeclarationMap.put(n, v);
        addVertex(v);
    }

    protected boolean addEdge(CallableDeclaration<?> source, CallableDeclaration<?> target, Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        Edge<?> edge = new Edge<>(call, findGraphNode(call, source));
        return addEdge(vertexDeclarationMap.get(source), vertexDeclarationMap.get(target), edge);
    }

    /** Find the calls to methods and constructors (edges) in the given list of compilation units. */
    protected void buildEdges(NodeList<CompilationUnit> arg) {
        arg.accept(new VoidVisitorAdapter<Void>() {
            private final Deque<ClassOrInterfaceDeclaration> classStack = new LinkedList<>();
            private final Deque<CallableDeclaration<?>> declStack = new LinkedList<>();

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                classStack.push(n);
                super.visit(n, arg);
                classStack.pop();
            }

            // ============ Method declarations ===========
            // There are some locations not considered, which may lead to an error in the stack.
            // 1. Method calls in non-static field initializations are assigned to all constructors of that class
            // 2. Method calls in static field initializations are assigned to the static block of that class

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                declStack.push(n);
                super.visit(n, arg);
                declStack.pop();
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                declStack.push(n);
                super.visit(n, arg);
                declStack.pop();
            }

            // =============== Method calls ===============
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                n.resolve().toAst().ifPresent(decl -> createPolyEdges(decl, n));
                if (ASTUtils.shouldVisitArgumentsForMethodCalls(n))
                    super.visit(n, arg);
            }

            @Override
            public void visit(ObjectCreationExpr n, Void arg) {
                n.resolve().toAst().ifPresent(decl -> createNormalEdge(decl, n));
                if (ASTUtils.shouldVisitArgumentsForMethodCalls(n))
                    super.visit(n, arg);
            }

            @Override
            public void visit(ExplicitConstructorInvocationStmt n, Void arg) {
                n.resolve().toAst().ifPresent(decl -> createNormalEdge(decl, n));
                if (ASTUtils.shouldVisitArgumentsForMethodCalls(n))
                    super.visit(n, arg);
            }

            protected void createPolyEdges(MethodDeclaration decl, MethodCallExpr call) {
                // Static calls have no polymorphism, ignore
                if (decl.isStatic()) {
                    createNormalEdge(decl, call);
                    return;
                }
                Expression scope = call.getScope().orElse(null);
                // Determine the type of the call's scope
                Set<ClassOrInterfaceDeclaration> dynamicTypes;
                // a) no scope/this: the current object or any subclass (we could be here but be a subtype)
                if (scope == null || scope instanceof ThisExpr) {
                    if (scope == null) {
                        // Early exit: it is easier to find the methods that override the
                        // detected call than to account for all cases (implicit inner or outer class)
                        classGraph.overriddenSetOf(decl)
                                .forEach(methodDecl -> createNormalEdge(methodDecl, call));
                        return;
                    } else if (scope.asThisExpr().getTypeName().isEmpty())
                        dynamicTypes = classGraph.subclassesOf(classStack.peek());
                    else
                        dynamicTypes = classGraph.subclassesOf(scope.asThisExpr().resolve().asClass());
                } else { // b) object/call/other: find possible dynamic types
                    dynamicTypes = classGraph.subclassesOf(scope.calculateResolvedType().asReferenceType());
                }
                // Locate the corresponding methods for each possible dynamic type, they must be available to all
                // To locate them, use the method signature and search for it in the class graph
                // Connect to each declaration
                AtomicInteger edgesCreated = new AtomicInteger();
                dynamicTypes.stream()
                        .map(t -> classGraph.findMethodByTypeAndSignature(t, decl.getSignature()))
                        .filter(Optional::isPresent).map(Optional::get)
                        .distinct()
                        .forEach(methodDecl -> {
                            edgesCreated.getAndIncrement();
                            createNormalEdge(methodDecl, call);
                        });
                assert edgesCreated.get() > 0;
            }

            protected void createNormalEdge(CallableDeclaration<?> decl, Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
                addEdge(declStack.peek(), decl, call);
            }
        }, null);
    }

    /** Locates the node in the collection of CFGs that contains the given call. */
    protected GraphNode<?> findGraphNode(Resolvable<? extends ResolvedMethodLikeDeclaration> n, CallableDeclaration<?> declaration) {
        for (GraphNode<?> node : cfgMap.get(declaration).vertexSet())
            if (node.containsCall(n))
                return node;
        throw new NodeNotFoundException("call " + n + " could not be located!");
    }

    /** Creates a graph-appropriate DOT exporter. */
    public DOTExporter<CallableDeclaration<?>, Edge<?>> getDOTExporter() {
        DOTExporter<CallableDeclaration<?>, Edge<?>> dot = new DOTExporter<>();
        dot.setVertexAttributeProvider(decl -> Utils.dotLabel(decl.getDeclarationAsString(false, false, false)));
        dot.setEdgeAttributeProvider(edge -> Utils.dotLabel(edge.getCall().toString()));
        return dot;
    }

    /** A vertex containing the declaration it represents. It only exists because
     *  JGraphT relies heavily on equals comparison, which may not be correct in declarations. */
    public static class Vertex {
        protected final CallableDeclaration<?> declaration;

        public Vertex(CallableDeclaration<?> declaration) {
            assert declaration instanceof ConstructorDeclaration || declaration instanceof MethodDeclaration;
            this.declaration = declaration;
        }

        /** The declaration represented by this node. */
        public CallableDeclaration<?> getDeclaration() {
            return declaration;
        }

        @Override
        public int hashCode() {
            return Objects.hash(declaration, declaration.getRange());
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Vertex && ASTUtils.equalsWithRangeInCU(((Vertex) obj).declaration, declaration);
        }

        @Override
        public String toString() {
            return super.toString();
        }
    }

    /** An edge containing the call it represents, and the graph node that contains it. */
    public static class Edge<T extends Resolvable<? extends ResolvedMethodLikeDeclaration>> extends DefaultEdge {
        protected final T call;
        protected final GraphNode<?> graphNode;

        public Edge(T call, GraphNode<?> graphNode) {
            assert call instanceof MethodCallExpr || call instanceof ObjectCreationExpr || call instanceof ExplicitConstructorInvocationStmt;
            this.call = call;
            this.graphNode = graphNode;
        }

        /** The call represented by this edge. Using the {@link CallGraph} it can be effortlessly resolved. */
        public T getCall() {
            return call;
        }

        /** The graph node that contains the call represented by this edge. */
        public GraphNode<?> getGraphNode() {
            return graphNode;
        }

        @Override
        public String toString() {
            return String.format("%s -%d-> %s",
                    ((CallableDeclaration<?>) getSource()).getDeclarationAsString(false, false, false),
                    graphNode.getId(),
                    ((CallableDeclaration<?>) getTarget()).getDeclarationAsString(false, false, false));
        }
    }
}
