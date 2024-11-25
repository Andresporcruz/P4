package plc.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    /** Parses the {@code source} rule. */
    public Ast.Source parseSource() {
        List<Ast.Field> fields = new ArrayList<>();
        List<Ast.Method> methods = new ArrayList<>();

        while (match("LET")) {
            fields.add(parseField());
        }

        while (match("DEF")) {
            methods.add(parseMethod());
        }

        return new Ast.Source(fields, methods);
    }

    /** Parses the {@code field} rule. */
    public Ast.Field parseField() {
        String name = require(Token.Type.IDENTIFIER, "Expected identifier after LET.").getLiteral();
        Optional<Ast.Expr> value = Optional.empty();

        if (match("=")) {
            value = Optional.of(parseExpression());
        }

        require(";", "Expected ';' after field declaration.");
        return new Ast.Field(name, value);
    }

    /** Parses the {@code method} rule. */
    public Ast.Method parseMethod() {
        String name = require(Token.Type.IDENTIFIER, "Expected identifier after DEF.").getLiteral();
        require("(", "Expected '(' after method name.");
        List<String> parameters = new ArrayList<>();

        if (!peek(")")) {
            do {
                parameters.add(require(Token.Type.IDENTIFIER, "Expected parameter name.").getLiteral());
            } while (match(","));
        }

        require(")", "Expected ')' after parameters.");
        require("DO", "Expected 'DO' before method body.");
        List<Ast.Stmt> statements = new ArrayList<>();

        while (!match("END")) {
            statements.add(parseStatement());
        }

        return new Ast.Method(name, parameters, statements);
    }

    /** Parses the {@code statement} rule. */
    public Ast.Stmt parseStatement() {
        if (match("LET")) {
            return parseDeclaration();
        } else if (match("IF")) {
            return parseIf();
        } else if (match("WHILE")) {
            return parseWhile();
        } else if (match("RETURN")) {
            return parseReturn();
        } else {
            Ast.Expr expression = parseExpression();
            if (match("=")) {
                Ast.Expr value = parseExpression();
                require(";", "Expected ';' after assignment.");
                return new Ast.Stmt.Assignment(expression, value);
            }
            require(";", "Expected ';' after expression.");
            return new Ast.Stmt.Expression(expression);
        }
    }

    /** Parses a declaration statement from the {@code statement} rule. */
    private Ast.Stmt parseDeclaration() {
        String name = require(Token.Type.IDENTIFIER, "Expected identifier after LET.").getLiteral();
        Optional<Ast.Expr> value = Optional.empty();
        if (match("=")) {
            value = Optional.of(parseExpression());
        }
        require(";", "Expected ';' after declaration.");
        return new Ast.Stmt.Declaration(name, value);
    }

    /** Parses the {@code if} statement rule. */
    private Ast.Stmt parseIf() {
        Ast.Expr condition = parseExpression();
        require("DO", "Expected 'DO' after if condition.");
        List<Ast.Stmt> thenStatements = new ArrayList<>();

        while (!peek("ELSE") && !peek("END")) {
            thenStatements.add(parseStatement());
        }

        List<Ast.Stmt> elseStatements = new ArrayList<>();
        if (match("ELSE")) {
            while (!peek("END")) {
                elseStatements.add(parseStatement());
            }
        }

        require("END", "Expected 'END' after if statement.");
        return new Ast.Stmt.If(condition, thenStatements, elseStatements);
    }

    /** Parses the {@code while} statement rule. */
    private Ast.Stmt parseWhile() {
        Ast.Expr condition = parseExpression();
        require("DO", "Expected 'DO' after while condition.");
        List<Ast.Stmt> statements = new ArrayList<>();

        while (!match("END")) {
            statements.add(parseStatement());
        }

        return new Ast.Stmt.While(condition, statements);
    }

    /** Parses the {@code return} statement rule. */
    private Ast.Stmt parseReturn() {
        Ast.Expr value = parseExpression();
        require(";", "Expected ';' after return value.");
        return new Ast.Stmt.Return(value);
    }

    /** Parses an {@code expression}. */
    public Ast.Expr parseExpression() {
        return parseLogicalExpression();
    }

    private Ast.Expr parseLogicalExpression() {
        Ast.Expr left = parseComparisonExpression();
        while (match("AND") || match("OR")) {
            String operator = tokens.get(-1).getLiteral();
            Ast.Expr right = parseComparisonExpression();
            left = new Ast.Expr.Binary(operator, left, right);
        }
        return left;
    }

    private Ast.Expr parseComparisonExpression() {
        Ast.Expr left = parseAdditiveExpression();
        while (match("<") || match("<=") || match(">") || match(">=") || match("==") || match("!=")) {
            String operator = tokens.get(-1).getLiteral();
            Ast.Expr right = parseAdditiveExpression();
            left = new Ast.Expr.Binary(operator, left, right);
        }
        return left;
    }

    private Ast.Expr parseAdditiveExpression() {
        Ast.Expr left = parseMultiplicativeExpression();
        while (match("+") || match("-")) {
            String operator = tokens.get(-1).getLiteral();
            Ast.Expr right = parseMultiplicativeExpression();
            left = new Ast.Expr.Binary(operator, left, right);
        }
        return left;
    }

    private Ast.Expr parseMultiplicativeExpression() {
        Ast.Expr left = parseSecondaryExpression();
        while (match("*") || match("/")) {
            String operator = tokens.get(-1).getLiteral();
            Ast.Expr right = parseSecondaryExpression();
            left = new Ast.Expr.Binary(operator, left, right);
        }
        return left;
    }

    private Ast.Expr parseSecondaryExpression() {
        Ast.Expr primary = parsePrimaryExpression();
        while (match(".")) {
            String name = require(Token.Type.IDENTIFIER, "Expected field or method name after '.'.").getLiteral();
            if (match("(")) {
                List<Ast.Expr> arguments = new ArrayList<>();
                if (!peek(")")) {
                    do {
                        arguments.add(parseExpression());
                    } while (match(","));
                }
                require(")", "Expected ')' after arguments.");
                primary = new Ast.Expr.Function(Optional.of(primary), name, arguments);
            } else {
                primary = new Ast.Expr.Access(Optional.of(primary), name);
            }
        }
        return primary;
    }

    private Ast.Expr parsePrimaryExpression() {
        if (match("NIL")) {
            return new Ast.Expr.Literal(null);
        } else if (match("TRUE")) {
            return new Ast.Expr.Literal(Boolean.TRUE);
        } else if (match("FALSE")) {
            return new Ast.Expr.Literal(Boolean.FALSE);
        } else if (match(Token.Type.INTEGER)) {
            return new Ast.Expr.Literal(new java.math.BigInteger(tokens.get(-1).getLiteral()));
        } else if (match(Token.Type.DECIMAL)) {
            return new Ast.Expr.Literal(new java.math.BigDecimal(tokens.get(-1).getLiteral()));
        } else if (match(Token.Type.CHARACTER)) {
            return new Ast.Expr.Literal(tokens.get(-1).getLiteral().charAt(1)); // Removing surrounding single quotes
        } else if (match(Token.Type.STRING)) {
            String literal = tokens.get(-1).getLiteral();
            return new Ast.Expr.Literal(literal.substring(1, literal.length() - 1).replace("\\n", "\n")
                    .replace("\\t", "\t").replace("\\r", "\r").replace("\\b", "\b"));
        } else if (match("(")) {
            Ast.Expr expression = parseExpression();
            require(")", "Expected ')' after expression.");
            return new Ast.Expr.Group(expression);
        } else if (match(Token.Type.IDENTIFIER)) {
            String name = tokens.get(-1).getLiteral();
            if (match("(")) {
                List<Ast.Expr> arguments = new ArrayList<>();
                if (!peek(")")) {
                    do {
                        arguments.add(parseExpression());
                    } while (match(","));
                }
                require(")", "Expected ')' after arguments.");
                return new Ast.Expr.Function(Optional.empty(), name, arguments);
            }
            return new Ast.Expr.Access(Optional.empty(), name);
        }
        throw new RuntimeException(new ParseException("Expected an expression.",
                tokens.has(0) ? tokens.get(0).getIndex()
                        : tokens.get(-1).getIndex() + tokens.get(-1).getLiteral().length()));
    }

    // Helper Methods for Parsing

    private Token require(Object expected, String message) {
        if (!match(expected)) {
            throw new RuntimeException(new ParseException(message,
                    tokens.has(0) ? tokens.get(0).getIndex()
                            : tokens.get(-1).getIndex() + tokens.get(-1).getLiteral().length()));
        }
        return tokens.get(-1);
    }

    private boolean match(Object... patterns) {
        if (peek(patterns)) {
            tokens.advance();
            return true;
        }
        return false;
    }

    private boolean peek(Object... patterns) {
        for (int i = 0; i < patterns.length; i++) {
            if (!tokens.has(i) || !patterns[i].equals(tokens.get(i).getLiteral()) && patterns[i] != tokens.get(i).getType()) {
                return false;
            }
        }
        return true;
    }

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        public TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        public boolean has(int offset) {
            return index + offset >= 0 && index + offset < tokens.size();
        }

        public Token get(int offset) {
            return tokens.get(index + offset);
        }

        public void advance() {
            index++;
        }

    }

}
