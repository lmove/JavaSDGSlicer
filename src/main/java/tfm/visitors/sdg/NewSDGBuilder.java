package tfm.visitors.sdg;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import tfm.graphbuilding.Graphs;
import tfm.graphs.PDGGraph;
import tfm.graphs.SDGGraph;
import tfm.utils.Context;

import java.util.ArrayList;
import java.util.List;

public class NewSDGBuilder extends VoidVisitorAdapter<Context> {

    SDGGraph sdgGraph;
    List<PDGGraph> pdgGraphs;

    public NewSDGBuilder(SDGGraph sdgGraph) {
        this.sdgGraph = sdgGraph;
        this.pdgGraphs = new ArrayList<>();
    }

    @Override
    public void visit(MethodDeclaration methodDeclaration, Context context) {
        if (!methodDeclaration.getBody().isPresent()) {
            return;
        }

        context.setCurrentMethod(methodDeclaration);

        // 1. Build PDG

        PDGGraph pdgGraph = Graphs.PDG.fromASTNode(methodDeclaration);


        // 2. Expand method call nodes (build input and output variable nodes)
        // 2.1 Visit called methods with this visitor
        MethodCallReplacer methodCallReplacer = new MethodCallReplacer(pdgGraph);
        methodDeclaration.accept(methodCallReplacer, null);


        // 3. Build summary arcs
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration classOrInterfaceDeclaration, Context context) {
//        if (sdgGraph.getRootNode() != null) {
//            throw new IllegalStateException("¡Solo podemos procesar una clase por el momento!");
//        }

        if (classOrInterfaceDeclaration.isInterface()) {
            throw new IllegalArgumentException("¡Las interfaces no estan permitidas!");
        }

        context.setCurrentClass(classOrInterfaceDeclaration);

        classOrInterfaceDeclaration.accept(this, context);
    }

    @Override
    public void visit(CompilationUnit compilationUnit, Context context) {
        context.setCurrentCU(compilationUnit);

        super.visit(compilationUnit, context);
    }
}