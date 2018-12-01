package kendal.api.impl;

import static kendal.utils.Utils.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCCatch;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCThrow;
import com.sun.tools.javac.tree.JCTree.JCTry;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCTypeUnion;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;

import kendal.api.AstNodeBuilder;
import kendal.api.AstUtils;
import kendal.api.AstValidator;
import kendal.api.Modifier;
import kendal.api.exceptions.ImproperNodeTypeException;
import kendal.model.Node;

public class AstNodeBuilderImpl implements AstNodeBuilder {
    private static final JCExpression NO_VALUE = null;

    private final TreeMaker treeMaker;
    private AstValidator astValidator;
    private AstUtils astUtils;

    AstNodeBuilderImpl(Context context, AstUtils astUtils, AstValidator astValidator) {
        this.treeMaker = TreeMaker.instance(context);
        this.astValidator = astValidator;
        this.astUtils = astUtils;
    }

    @Override
    public <T extends JCExpression> Node<JCVariableDecl> buildVariableDecl(Node<T> type, String name) {
        return buildVariableDecl(type, astUtils.nameFromString(name));
    }

    @Override
    public <T extends JCExpression> Node<JCVariableDecl> buildVariableDecl(Node<T> type, Name name) {
        return buildVariableDecl(new LinkedList<>(), type.getObject(), name, null);
    }

    @Override
    public <T extends JCExpression> Node<JCVariableDecl> buildVariableDecl(List<Modifier> modifiers, T type, Name name,
            Node<JCAnnotation> source) {
        JCModifiers jcModifiers = treeMaker.Modifiers(map(modifiers, (List<Modifier>m) -> {
            long result = 0;
            for (Modifier mod : m) {
                result |= mod.getFlag();
            }
            return result;
        }));
        if (source != null) treeMaker.at(source.getObject().pos);
        JCVariableDecl jcVariableDecl = treeMaker.VarDef(jcModifiers, name, type, NO_VALUE);
        return new Node<>(jcVariableDecl, true);
    }

    @Override
    public Node<JCMethodDecl> buildMethodDecl(JCModifiers modifiers, Name name, JCExpression resType,
            com.sun.tools.javac.util.List<JCVariableDecl> params, Node<JCBlock> body) {
        return buildMethodDecl(modifiers, name, resType, params, body.getObject());
    }

    @Override
    public Node<JCMethodDecl> buildMethodDecl(JCModifiers jcModifiers, Name name, JCExpression resType,
            com.sun.tools.javac.util.List<JCVariableDecl> params, JCBlock body) {
        // todo: add support for typarams and thrown
        com.sun.tools.javac.util.List<JCTypeParameter> typarams = astUtils.toJCList(new ArrayList<>());
        com.sun.tools.javac.util.List<JCExpression> thrown = astUtils.toJCList(new ArrayList<>());
        JCMethodDecl jcMethodDecl = treeMaker.MethodDef(jcModifiers, name, resType, typarams, params, thrown,
                body, null);
        return new Node<>(jcMethodDecl, true);
    }

    @Override
    public <T extends JCExpression> Node<JCMethodInvocation> buildMethodInvocation(Node<T> method) {
        return buildMethodInvocation(method, com.sun.tools.javac.util.List.<JCExpression>nil());
    }

    @Override
    public <T extends JCExpression, P extends JCExpression> Node<JCMethodInvocation>
    buildMethodInvocation(Node<T> method, List<Node<P>> parameters) {
        return buildMethodInvocation(method, astUtils.mapNodesToJCListOfObjects(parameters));
    }

    @Override
    public <T extends JCExpression, P extends JCExpression> Node<JCMethodInvocation> buildMethodInvocation(Node<T> method,
            com.sun.tools.javac.util.List<P> parameters) {
        try {
            JCMethodInvocation jcMethodInvocation = treeMaker.App(method.getObject(),
                    (com.sun.tools.javac.util.List<JCExpression>) parameters);
            return new Node<>(jcMethodInvocation);
        } catch (RuntimeException ex) {
            ex.printStackTrace();
            throw ex;
        }
    }

    @Override
    public Node<JCFieldAccess> buildFieldAccess(Node<JCIdent> objectRef, Name fieldName) {
        JCFieldAccess jcFieldAccess = treeMaker.Select(objectRef.getObject(), fieldName);
        return new Node<>(jcFieldAccess, true);
    }

    @Override
    public <T extends JCExpression> Node<JCReturn> buildReturnStatement(Node<T> expression) {
        JCReturn jcReturn = treeMaker.Return(expression.getObject());
        return new Node<>(jcReturn);
    }

    @Override
    public <T extends JCStatement> Node<JCBlock> buildBlock(List<Node<T>> statements) {
        return buildBlock(astUtils.mapNodesToJCListOfObjects(statements));
    }

    @Override
    public <T extends JCStatement> Node<JCBlock> buildBlock(Node<T> statement) {
        return buildBlock(Collections.singletonList(statement));
    }

    @Override
    public <T extends JCStatement> Node<JCBlock> buildBlock(com.sun.tools.javac.util.List<T> statements) {
        JCBlock jcBlock = treeMaker.Block(0, (com.sun.tools.javac.util.List<JCStatement>) statements);
        return new Node<>(jcBlock);
    }

    @Override
    public Node<JCIdent> buildIdentifier(String name) {
        return buildIdentifier(astUtils.nameFromString(name));
    }

    @Override
    public Node<JCIdent> buildIdentifier(Name name) {
        JCIdent jcIdentifier = treeMaker.Ident(name);
        return new Node(jcIdentifier);
    }

    @Override
    public Node<JCTry> buildTry(Node<JCBlock> body, Node<JCCatch> catchers) {
        return buildTry(body, Collections.singletonList(catchers));
    }

    @Override
    public Node<JCTry> buildTry(Node<JCBlock> body, List<Node<JCCatch>> catchers) {
        return buildTry(body, astUtils.mapNodesToJCListOfObjects(catchers));
    }

    @Override
    public Node<JCTry> buildTry(Node<JCBlock> body, com.sun.tools.javac.util.List<JCCatch> catchers) {
        JCTry jcTry = treeMaker.Try(body.getObject(), catchers, null);
        return new Node<>(jcTry);
    }

    @Override
    public Node<JCCatch> buildCatch(Node<JCVariableDecl> param, Node<JCBlock> body) {
        JCCatch jcCatch = treeMaker.Catch(param.getObject(), body.getObject());
        return new Node<>(jcCatch);
    }

    @Override
    public <T extends JCExpression> Node<JCThrow> buildThrow(Node<T> expression) {
        JCThrow jcThrow = treeMaker.Throw(expression.getObject());
        return new Node<>(jcThrow);
    }

    @Override
    public <T extends JCExpression> Node<JCNewClass> buildNewClass(Node<JCIdent> clazz, Node<T> arg) {
        return buildNewClass(clazz, Collections.singletonList(arg));
    }

    @Override
    public <T extends JCExpression> Node<JCNewClass> buildNewClass(Node<JCIdent> clazz, List<Node<T>> args) {
        com.sun.tools.javac.util.List jcArgs = astUtils.mapNodesToJCListOfObjects(args);
        JCNewClass jcNewClass = treeMaker.NewClass(null, com.sun.tools.javac.util.List.nil(),
                clazz.getObject(), jcArgs, null);
        return new Node<>(jcNewClass);
    }

    @Override
    public <T extends JCExpression> Node<JCTypeUnion> buildTypeUnion(List<Node<T>> components) {
        JCTypeUnion jcTypeUnion =
                treeMaker.TypeUnion((com.sun.tools.javac.util.List<JCExpression>) astUtils.mapNodesToJCListOfObjects(components));
        return new Node<>(jcTypeUnion);
    }

    @Override
    public <L extends JCExpression, R extends JCExpression> Node<JCExpressionStatement>
    buildAssignmentStatement(Node<L> lhs, Node<R> rhs) throws ImproperNodeTypeException {
        if (!astValidator.isExpression(lhs) || !astValidator.isExpression(rhs)) {
            throw new ImproperNodeTypeException();
        }
        JCExpressionStatement jcExpressionStatement = treeMaker.Exec(treeMaker.Assign(lhs.getObject(), rhs.getObject()));
        return new Node<>(jcExpressionStatement, true);
    }
}
