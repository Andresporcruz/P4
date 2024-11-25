// Andres Portillo
// COP4020
// Last Modified Nov 10th

package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * To examine and type-check the abstract syntax tree (AST), the Analyzer class uses a visitor pattern.
 * By generating runtime errors for any * inconsistencies or type violations found, this makes sure the code complies with expected types and structures.
 */
public final class Analyzer implements Ast.Visitor<Void> {

    Scope scope;
    private Environment.Type returnType;

    /**
     * Constructor initializes the analyzer with a parent scope.
     * Defines built-in functions for the language, such as the "print" function.
     */
    public Analyzer(Scope parent) {
        this.scope = new Scope(parent);

        // Define built-in print function, which expects a parameter of any type and returns NIL.
        this.scope.defineFunction(
                "print",
                "System.out.println",
                Arrays.asList(Environment.Type.ANY),
                Environment.Type.NIL,
                args -> Environment.NIL
        );
    }

    /**
     * Visit the root of the AST, analyzing fields and methods, and ensuring there is a main method.
     */
    @Override
    public Void visit(Ast.Source ast) {
        // Verify presence of "main" method
        boolean hasMain = ast.getMethods().stream().anyMatch(method ->
                method.getName().equals("main") && method.getParameters().isEmpty()
        );
        if (!hasMain) {
            throw new RuntimeException("No main method found.");
        }

        // Ensure the main method returns an Integer type
        for (Ast.Method method : ast.getMethods()) {
            if (method.getName().equals("main") && !method.getReturnTypeName().orElse("Integer").equals("Integer")) {
                throw new RuntimeException("Main method must return Integer.");
            }
        }

        // Visit each field and method in the source
        for (Ast.Field field : ast.getFields()) {
            visit(field);
        }
        for (Ast.Method method : ast.getMethods()) {
            visit(method);
        }
        return null;
    }

    /**
     * Analyze a field, ensuring type validity and initializing its value if provided.
     */
    @Override
    public Void visit(Ast.Field ast) {
        Environment.Type fieldType = Environment.getType(ast.getTypeName());
        ast.setVariable(new Environment.Variable(ast.getName(), ast.getName(), fieldType, Environment.NIL));

        // If the field has a value, check type compatibility
        if (ast.getValue().isPresent()) {
            visit(ast.getValue().get());
            Environment.Type valueType = ast.getValue().get().getType();
            requireAssignable(fieldType, valueType);
        }

        // Define the variable in the current scope
        scope.defineVariable(ast.getName(), ast.getName(), fieldType, Environment.NIL);
        return null;
    }

    /**
     * Analyze a method, including parameter types, return types, and its body.
     */
    @Override
    public Void visit(Ast.Method ast) {
        // Collect parameter types
        List<Environment.Type> paramTypes = ast.getParameterTypeNames().stream()
                .map(Environment::getType)
                .collect(Collectors.toList());

        // Determine return type, defaulting to "Nil" if unspecified
        Environment.Type returnType = Environment.getType(ast.getReturnTypeName().orElse("Nil"));
        ast.setFunction(new Environment.Function(
                ast.getName(), ast.getName(), paramTypes, returnType, args -> Environment.NIL
        ));

        // Define the function within the scope
        scope.defineFunction(ast.getName(), ast.getName(), paramTypes, returnType, args -> Environment.NIL);

        // Create a new scope for the method body
        Scope previousScope = this.scope;
        this.scope = new Scope(scope);
        this.returnType = returnType;

        // Define parameters in the method's scope
        for (int i = 0; i < ast.getParameters().size(); i++) {
            String paramName = ast.getParameters().get(i);
            this.scope.defineVariable(paramName, paramName, paramTypes.get(i), Environment.NIL);
        }

        // Analyze each statement in the method
        for (Ast.Stmt stmt : ast.getStatements()) {
            visit(stmt);
        }

        // Restore the previous scope after method analysis
        this.scope = previousScope;
        return null;
    }

    /**
     * Analyzes an expression statement, ensuring it is a valid function call.
     */
    @Override
    public Void visit(Ast.Stmt.Expression ast) {
        if (!(ast.getExpression() instanceof Ast.Expr.Function)) {
            throw new RuntimeException("Expression must be a function call.");
        }
        visit(ast.getExpression());
        return null;
    }

    /**
     * Analyzes a variable declaration, checking type compatibility if an initializer is provided.
     */
    @Override
    public Void visit(Ast.Stmt.Declaration ast) {
        Environment.Type type;
        if (ast.getTypeName().isPresent()) {
            type = Environment.getType(ast.getTypeName().get());
        } else {
            if (ast.getValue().isPresent()) {
                visit(ast.getValue().get());
                type = ast.getValue().get().getType();
            } else {
                throw new RuntimeException("Declaration must have a type or an initializer.");
            }
        }

        ast.setVariable(new Environment.Variable(ast.getName(), ast.getName(), type, Environment.NIL));

        // Validate initializer's compatibility with declared type
        if (ast.getValue().isPresent()) {
            visit(ast.getValue().get());
            Environment.Type valueType = ast.getValue().get().getType();
            requireAssignable(type, valueType);
        }

        // Define the variable in the current scope
        scope.defineVariable(ast.getName(), ast.getName(), type, Environment.NIL);
        return null;
    }

    /**
     * Analyzes an assignment statement, ensuring the types of receiver and value match.
     */
    @Override
    public Void visit(Ast.Stmt.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expr.Access)) {
            throw new RuntimeException("Receiver must be an access expression.");
        }
        visit(ast.getReceiver());
        Environment.Type receiverType = ast.getReceiver().getType();
        visit(ast.getValue());
        Environment.Type valueType = ast.getValue().getType();
        requireAssignable(receiverType, valueType);
        return null;
    }

    /**
     * Analyzes an "if" statement, ensuring condition is boolean and branches are valid.
     */
    @Override
    public Void visit(Ast.Stmt.If ast) {
        visit(ast.getCondition());
        Environment.Type conditionType = ast.getCondition().getType();
        if (!conditionType.equals(Environment.Type.BOOLEAN)) {
            throw new RuntimeException("Condition must be a boolean expression.");
        }
        if (ast.getThenStatements().isEmpty()) {
            throw new RuntimeException("Then branch cannot be empty.");
        }

        // Analyze "then" and "else" branches within new scopes
        Scope previousScope = this.scope;
        this.scope = new Scope(scope);
        for (Ast.Stmt stmt : ast.getThenStatements()) {
            visit(stmt);
        }
        this.scope = previousScope;

        if (!ast.getElseStatements().isEmpty()) {
            this.scope = new Scope(scope);
            for (Ast.Stmt stmt : ast.getElseStatements()) {
                visit(stmt);
            }
            this.scope = previousScope;
        }
        return null;
    }

    /**
     * Analyzes a "for" loop, checking iterator compatibility and loop body validity.
     */
    @Override
    public Void visit(Ast.Stmt.For ast) {
        visit(ast.getValue());
        Environment.Type valueType = ast.getValue().getType();
        if (!valueType.equals(Environment.Type.INTEGER_ITERABLE)) {
            throw new RuntimeException("Value must be of type IntegerIterable.");
        }
        if (ast.getStatements().isEmpty()) {
            throw new RuntimeException("For loop statements cannot be empty.");
        }

        Scope previousScope = this.scope;
        this.scope = new Scope(scope);
        this.scope.defineVariable(ast.getName(), ast.getName(), Environment.Type.INTEGER, Environment.NIL);

        for (Ast.Stmt stmt : ast.getStatements()) {
            visit(stmt);
        }
        this.scope = previousScope;
        return null;
    }

    /**
     * Analyzes a "while" loop, ensuring the condition is boolean and body is valid.
     */
    @Override
    public Void visit(Ast.Stmt.While ast) {
        visit(ast.getCondition());
        Environment.Type conditionType = ast.getCondition().getType();
        if (!conditionType.equals(Environment.Type.BOOLEAN)) {
            throw new RuntimeException("Condition must be a boolean expression.");
        }

        Scope previousScope = this.scope;
        this.scope = new Scope(scope);
        for (Ast.Stmt stmt : ast.getStatements()) {
            visit(stmt);
        }
        this.scope = previousScope;
        return null;
    }

    /**
     * Analyzes a return statement, ensuring the returned value type matches the expected return type.
     */
    @Override
    public Void visit(Ast.Stmt.Return ast) {
        visit(ast.getValue());
        Environment.Type returnValueType = ast.getValue().getType();
        requireAssignable(this.returnType, returnValueType);
        return null;
    }

    // Expression Visitors

    /**
     * Analyzes a literal expression, assigning an appropriate type based on the literal's value.
     */
    @Override
    public Void visit(Ast.Expr.Literal ast) {
        Object value = ast.getLiteral();
        Environment.Type type;
        if (value instanceof Boolean) {
            type = Environment.Type.BOOLEAN;
        } else if (value instanceof Character) {
            type = Environment.Type.CHARACTER;
        } else if (value instanceof String) {
            type = Environment.Type.STRING;
        } else if (value instanceof BigInteger) {
            BigInteger intValue = (BigInteger) value;
            if (intValue.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0 ||
                    intValue.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new RuntimeException("Integer literal out of bounds.");
            }
            type = Environment.Type.INTEGER;
        } else if (value instanceof BigDecimal) {
            type = Environment.Type.DECIMAL;
        } else if (value == null) {
            type = Environment.Type.NIL;
        } else {
            throw new RuntimeException("Unknown literal type.");
        }
        ast.setType(type);
        return null;
    }

    /**
     * Analyzes a grouped expression, ensuring it is a valid expression type.
     */
    @Override
    public Void visit(Ast.Expr.Group ast) {
        if (!(ast.getExpression() instanceof Ast.Expr.Binary)) {
            throw new RuntimeException("Grouped expression must be a binary expression.");
        }
        visit(ast.getExpression());
        ast.setType(ast.getExpression().getType());
        return null;
    }

    /**
     * Analyzes a binary expression, validating operand compatibility for the given operator.
     */
    @Override
    public Void visit(Ast.Expr.Binary ast) {
        visit(ast.getLeft());
        visit(ast.getRight());
        Environment.Type leftType = ast.getLeft().getType();
        Environment.Type rightType = ast.getRight().getType();

        Environment.Type resultType;
        String operator = ast.getOperator();

        if (operator.equals("AND") || operator.equals("OR")) {
            if (!leftType.equals(Environment.Type.BOOLEAN) || !rightType.equals(Environment.Type.BOOLEAN)) {
                throw new RuntimeException("Both operands of AND/OR must be Boolean.");
            }
            resultType = Environment.Type.BOOLEAN;
        } else if (Arrays.asList("<", "<=", ">", ">=", "==", "!=").contains(operator)) {
            if (!leftType.equals(rightType) || !isComparable(leftType)) {
                throw new RuntimeException("Operands must be of the same Comparable type.");
            }
            resultType = Environment.Type.BOOLEAN;
        } else if (operator.equals("+")) {
            if (leftType.equals(Environment.Type.STRING) || rightType.equals(Environment.Type.STRING)) {
                resultType = Environment.Type.STRING;
            } else if (leftType.equals(Environment.Type.INTEGER) && rightType.equals(Environment.Type.INTEGER)) {
                resultType = Environment.Type.INTEGER;
            } else if (leftType.equals(Environment.Type.DECIMAL) && rightType.equals(Environment.Type.DECIMAL)) {
                resultType = Environment.Type.DECIMAL;
            } else {
                throw new RuntimeException("Invalid operand types for + operator.");
            }
        } else if (Arrays.asList("-", "*", "/").contains(operator)) {
            if (!leftType.equals(rightType) ||
                    (!leftType.equals(Environment.Type.INTEGER) && !leftType.equals(Environment.Type.DECIMAL))) {
                throw new RuntimeException("Operands must be Integer or Decimal and of the same type.");
            }
            resultType = leftType;
        } else {
            throw new RuntimeException("Unsupported binary operator: " + operator);
        }
        ast.setType(resultType);
        return null;
    }

    /**
     * Analyzes an access expression, looking up the variable and assigning its type.
     */
    @Override
    public Void visit(Ast.Expr.Access ast) {
        if (ast.getReceiver().isPresent()) {
            visit(ast.getReceiver().get());
            Environment.Type receiverType = ast.getReceiver().get().getType();
            Environment.Variable variable = receiverType.getField(ast.getName());
            ast.setVariable(variable);
        } else {
            Environment.Variable variable = scope.lookupVariable(ast.getName());
            ast.setVariable(variable);
        }
        return null;
    }

    /**
     * Analyzes a function call expression, ensuring parameter compatibility.
     */
    @Override
    public Void visit(Ast.Expr.Function ast) {
        if (ast.getReceiver().isPresent()) {
            visit(ast.getReceiver().get());
            Environment.Type receiverType = ast.getReceiver().get().getType();
            Environment.Function function = receiverType.getMethod(ast.getName(), ast.getArguments().size());
            ast.setFunction(function);
        } else {
            Environment.Function function = scope.lookupFunction(ast.getName(), ast.getArguments().size());
            ast.setFunction(function);
        }
        for (int i = 0; i < ast.getArguments().size(); i++) {
            visit(ast.getArguments().get(i));
            requireAssignable(ast.getFunction().getParameterTypes().get(i), ast.getArguments().get(i).getType());
        }
        return null;
    }

    // Helper Methods

    /**
     * Requires that the target type can be assigned the provided type, throwing an error if not.
     */
    public static void requireAssignable(Environment.Type target, Environment.Type type) {
        if (!isAssignableTo(target, type)) {
            throw new RuntimeException("Cannot assign " + type.getName() + " to " + target.getName());
        }
    }

    /**
     * Determines if the source type can be assigned to the target type.
     */
    private static boolean isAssignableTo(Environment.Type target, Environment.Type type) {
        if (target.equals(Environment.Type.COMPARABLE)) {
            return Arrays.asList(
                    Environment.Type.INTEGER,
                    Environment.Type.DECIMAL,
                    Environment.Type.CHARACTER,
                    Environment.Type.STRING
            ).contains(type);
        } else if (target.equals(Environment.Type.ANY)) {
            return true;
        } else {
            return target.equals(type);
        }
    }

    /**
     * Helper method to determine if a type is comparable.
     */
    private boolean isComparable(Environment.Type type) {
        return Arrays.asList(
                Environment.Type.INTEGER,
                Environment.Type.DECIMAL,
                Environment.Type.CHARACTER,
                Environment.Type.STRING
        ).contains(type);
    }
}
