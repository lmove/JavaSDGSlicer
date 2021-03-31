package es.upv.mist.slicing.utils;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl;
import es.upv.mist.slicing.nodes.GraphNode;

import java.util.*;

/** JavaParser-related utility functions. */
public class ASTUtils {
    private ASTUtils() {
        throw new UnsupportedOperationException("This is a static, utility class");
    }

    public static boolean isContained(Node upper, Node contained) {
        Optional<Node> parent = contained.getParentNode();
        if (parent.isEmpty())
            return false;
        return equalsWithRangeInCU(upper, parent.get()) || isContained(upper, parent.get());
    }

    public static boolean switchHasDefaultCase(SwitchStmt stmt) {
        return switchGetDefaultCase(stmt) != null;
    }

    public static SwitchEntry switchGetDefaultCase(SwitchStmt stmt) {
        for (SwitchEntry entry : stmt.getEntries())
            if (entry.getLabels().isEmpty())
                return entry;
        return null;
    }

    public static boolean equalsWithRange(Node n1, Node n2) {
        return Objects.equals(n1.getRange(), n2.getRange()) && Objects.equals(n1, n2);
    }

    public static boolean equalsWithRangeInCU(Node n1, Node n2) {
        return n1.findCompilationUnit().equals(n2.findCompilationUnit())
                && equalsWithRange(n1, n2);
    }

    public static boolean resolvableIsVoid(Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        var resolved = call.resolve();
        if (resolved instanceof ResolvedMethodDeclaration)
            return ((ResolvedMethodDeclaration) resolved).getReturnType().isVoid();
        if (resolved instanceof ResolvedConstructorDeclaration)
            return false;
        throw new IllegalArgumentException("Call didn't resolve to either method or constructor!");
    }

    public static int getMatchingParameterIndex(CallableDeclaration<?> declaration, ResolvedParameterDeclaration param) {
        var parameters = declaration.getParameters();
        for (int i = 0; i < parameters.size(); i++)
            if (resolvedParameterEquals(param, parameters.get(i).resolve()))
                return i;
        throw new IllegalArgumentException("Expression resolved to a parameter, but could not be found!");
    }

    public static int getMatchingParameterIndex(ResolvedMethodLikeDeclaration declaration, ResolvedParameterDeclaration param) {
        for (int i = 0; i < declaration.getNumberOfParams(); i++)
            if (resolvedParameterEquals(declaration.getParam(i), param))
                return i;
        throw new IllegalArgumentException("Expression resolved to a parameter, but could not be found!");
    }

    protected static boolean resolvedParameterEquals(ResolvedParameterDeclaration p1, ResolvedParameterDeclaration p2) {
        return p2.getType().getClass().getName().equals(p1.getType().getClass().getName()) && p2.getName().equals(p1.getName());
    }

    public static List<Expression> getResolvableArgs(Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        if (call instanceof MethodCallExpr)
            return ((MethodCallExpr) call).getArguments();
        if (call instanceof ObjectCreationExpr)
            return ((ObjectCreationExpr) call).getArguments();
        if (call instanceof ExplicitConstructorInvocationStmt)
            return ((ExplicitConstructorInvocationStmt) call).getArguments();
        throw new IllegalArgumentException("Call wasn't of a compatible type!");
    }

    public static Optional<BlockStmt> getCallableBody(CallableDeclaration<?> callableDeclaration) {
        if (callableDeclaration instanceof MethodDeclaration)
            return ((MethodDeclaration) callableDeclaration).getBody();
        if (callableDeclaration instanceof ConstructorDeclaration)
            return Optional.of(((ConstructorDeclaration) callableDeclaration).getBody());
        return Optional.empty();
    }

    public static Optional<Expression> getResolvableScope(Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        if (call instanceof MethodCallExpr)
            return ((MethodCallExpr) call).getScope();
        if (call instanceof ObjectCreationExpr)
            return ((ObjectCreationExpr) call).getScope();
        if (call instanceof ExplicitConstructorInvocationStmt)
            return Optional.empty();
        throw new IllegalArgumentException("Call wasn't of a compatible type!");
    }

    public static Optional<? extends CallableDeclaration<?>> getResolvedAST(ResolvedMethodLikeDeclaration resolvedDeclaration) {
        if (resolvedDeclaration instanceof ResolvedMethodDeclaration)
            return ((ResolvedMethodDeclaration) resolvedDeclaration).toAst();
        if (resolvedDeclaration instanceof ResolvedConstructorDeclaration)
            return ((ResolvedConstructorDeclaration) resolvedDeclaration).toAst();
        throw new IllegalStateException("AST node of invalid type");
    }

    public static boolean shouldVisitArgumentsForMethodCalls(Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        return getResolvedAST(call.resolve()).isEmpty();
    }

    public static boolean shouldVisitArgumentsForMethodCalls(Resolvable<? extends ResolvedMethodLikeDeclaration> call, GraphNode<?> graphNode) {
        return shouldVisitArgumentsForMethodCalls(call) || graphNode == null;
    }

    /**
     * Creates a new set that is suitable for JavaParser nodes. This
     * set behaves by comparing by identity (==) instead of equality (equals()).
     * Thus, multiple objects representing the same node will not be identified as
     * equal, and duplicates will be inserted. For this use-case, you may use
     * {@link NodeHashSet}.
     */
    public static <T> Set<T> newIdentityHashSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * Creates a new map that is suitable for JavaParser nodes as keys. This
     * map behaves by comparing by identity (==) instead of equality (equals()).
     * Thus, multiple objects representing the same node will not be identified as
     * equal, and duplicates will be inserted.
     */
    public static <K, V> Map<K, V> newIdentityHashMap() {
        return new IdentityHashMap<>();
    }

    /** Converts a type declaration into just a type. */
    public static ResolvedType resolvedTypeDeclarationToResolvedType(ResolvedReferenceTypeDeclaration decl) {
        return new ReferenceTypeImpl(decl, StaticTypeSolver.getTypeSolver());
    }

    /**
     * Whether a cast of reference type is a downcast; which means
     * that the type of the cast is strictly more specific than the expression's static type.
     * This method should only be called with cast expressions of reference type (no primitives).
     * Otherwise it will fail.
     * <br/>
     * Examples:
     * <ul>
     *     <li>{@code (Object) new String()}: false.</li>
     *     <li>{@code (String) new String()}: false</li>
     *     <li>{@code (String) object}: true.</li>
     * </ul>
     */
    public static boolean isDownCast(CastExpr castExpr) {
        ResolvedType castType = castExpr.getType().resolve();
        ResolvedType exprType = castExpr.getExpression().calculateResolvedType();
        if (castType.isReferenceType() && exprType.isReferenceType()) {
            if (castType.equals(exprType))
                return false;
            return castType.asReferenceType().getAllAncestors().contains(exprType.asReferenceType());
        }
        throw new IllegalArgumentException("This operation is only valid for reference type cast operations.");
    }

}
