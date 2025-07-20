import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.CommonTokenStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The core translation engine, implemented as an ANTLR listener.
 * This class walks the parse tree generated from C code and builds the equivalent Python code.
 *
 * Architecture:
 * - It uses a ParseTreeProperty<String> to associate translated Python code with each parse tree node.
 * - Translation is compositional and bottom-up: the translation for a node is constructed in its
 *   'exit' method by retrieving the already-translated code of its children.
 * - State, such as indentation level, is managed via member variables.
 * - It handles specific C idioms like printf and sizeof by inspecting the parse tree structure.
 * - Comments are preserved by accessing ANTLR's hidden token channel.
 */
public class CToPythonListener extends CBaseListener {
    private final CParser parser;
    private CParser.CompilationUnitContext root;
    private final ParseTreeProperty<String> pythonCode = new ParseTreeProperty<String>();
    private int indentationLevel = 0;
    private static final String INDENT = "    ";

    /**
     * Constructor. Requires the parser to access the token stream for comment handling.
     * @param parser The CParser instance used to parse the code.
     */
    public CToPythonListener(CParser parser) {
        this.parser = parser;
    }

    /**
     * Retrieves the final translated code after the walk is complete.
     * @return The complete Python code as a String.
     */
    public String getPythonCode() {
        return pythonCode.get(root); // output the code contained in the root context
    }

    // Helper methods for code management
    private String getCode(ParseTree node) {
        return pythonCode.get(node);
    }

    private void setCode(ParseTree node, String code) {
        pythonCode.put(node, code);
    }

    private String getIndentation() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indentationLevel; i++) {
            sb.append(INDENT);
        }
        return sb.toString();
    }

    private void indent() {
        indentationLevel++;
    }

    private void dedent() {
        if (indentationLevel > 0) {
            indentationLevel--;
        }
    }

    // Help get to the base identifier of a direct declarator
    private CParser.DirectDeclaratorContext getBaseDirectDeclarator(CParser.DirectDeclaratorContext ddc) {
        if (ddc.Identifier()!= null) return ddc;
        if (ddc.directDeclarator()!= null) return getBaseDirectDeclarator(ddc.directDeclarator());
        return null;
    }

    // Listener method overrides for translation logic

    @Override
    public void exitCompilationUnit(CParser.CompilationUnitContext ctx) {
        StringBuilder finalCode = new StringBuilder();
        finalCode.append(getCode(ctx.translationUnit()));
        
        // Add the standard Python main entry point guard
        finalCode.append("\n\nif __name__ == \"__main__\":\n");
        finalCode.append(INDENT).append("main()\n");
        // Print the main function call to the console
        //System.out.println("\n\nif __name__ == \"__main__\":\n" + INDENT + "main()\n");
        setCode(ctx, finalCode.toString());
        root = ctx; // Store the root context for final retrieval
    }

    @Override
    public void exitTranslationUnit(CParser.TranslationUnitContext ctx) {
        // Collect all external declarations and compose the final code
        String code = ctx.externalDeclaration().stream()
               .map(this::getCode)
               .collect(Collectors.joining("\n"));
        setCode(ctx, code);
    }

    @Override
    public void exitExternalDeclaration(CParser.ExternalDeclarationContext ctx) {
        if (ctx.functionDefinition()!= null) {
            setCode(ctx, getCode(ctx.functionDefinition()));
        } else if (ctx.declaration()!= null) {
            setCode(ctx, getCode(ctx.declaration()));
        }
    }

    @Override
    public void enterFunctionDefinition(CParser.FunctionDefinitionContext ctx) {
        // Set the indentation for the function body
        indent();
    }

    @Override
    public void exitFunctionDefinition(CParser.FunctionDefinitionContext ctx) {
        // We print directly in enter/exit for functions and blocks to manage indentation correctly.
        dedent();
        
        // Handle function signature: def function_name(param1, param2):
        CParser.DirectDeclaratorContext dd = ctx.declarator().directDeclarator();
        dd = getBaseDirectDeclarator(dd);
        String funcName = dd.Identifier().getText();
        String params = "";
        //CParser.ParameterTypeListContext paramsListCtx = ctx.declarator().directDeclarator().parameterTypeList();
        CParser.ParameterTypeListContext paramsListCtx = ctx.declarator().directDeclarator().parameterTypeList();
        if (paramsListCtx!= null) {
            params = getCode(paramsListCtx);
        }
        StringBuilder sb = new StringBuilder();
        String body = getCode(ctx.compoundStatement());
        sb.append("def ").append(funcName).append("(").append(params).append("):\n").append(body);

        // We need to set code on the node for the parent to retrieve.
        setCode(ctx, sb.toString());
        handleTrailingComment(ctx);
    }

    @Override
    public void exitParameterTypeList(CParser.ParameterTypeListContext ctx) {
        String paramTypeList = getCode(ctx.parameterList());
        setCode(ctx, paramTypeList);
    }

    @Override
    public void exitParameterList(CParser.ParameterListContext ctx) {
        String params = ctx.parameterDeclaration().stream()
               .map(this::getCode)
               .collect(Collectors.joining(", "));
        setCode(ctx, params);
    }

    @Override
    public void exitParameterDeclaration(CParser.ParameterDeclarationContext ctx) {
        // In Python, we don't need type declarations for parameters
        // Just retrieve the identifier name
        CParser.DirectDeclaratorContext dd = ctx.declarator().directDeclarator();
        dd = getBaseDirectDeclarator(dd);
        setCode(ctx, dd.Identifier().getText());
    }

    @Override
    public void enterCompoundStatement(CParser.CompoundStatementContext ctx) {
        // This represents a '{...}' block. In Python, this is just an indented block.
        // Indentation is handled by the parent construct (e.g., function, if, for).
    }

    @Override
    public void exitCompoundStatement(CParser.CompoundStatementContext ctx) {
        if (ctx.blockItemList()!= null) {
            setCode(ctx, getCode(ctx.blockItemList()));
        } else {
            // Handle empty block, e.g. {}
            //System.out.println(getIndentation() + "pass");
            setCode(ctx, getIndentation() + "pass\n");
        }
    }

    @Override
    public void exitBlockItemList(CParser.BlockItemListContext ctx) {
        String code = ctx.blockItem().stream()
               .map(this::getCode)
               .collect(Collectors.joining(""));
        setCode(ctx, code);
    }
    
    @Override
    public void exitBlockItem(CParser.BlockItemContext ctx) {
        String code = "";
        if (ctx.statement()!= null) {
            code = getCode(ctx.statement());
        } else if (ctx.declaration()!= null) {
            code = getCode(ctx.declaration());
        }
        
        // Check for and handle trailing line comments
        code += getTrailingComment(ctx);

        setCode(ctx, code);
    }

    @Override
    public void exitStatement(CParser.StatementContext ctx) {
        String code = "";
        if (ctx.selectionStatement()!= null) {
            code = getCode(ctx.selectionStatement()) + "\n";
        } else if (ctx.iterationStatement()!= null) {
            code = getCode(ctx.iterationStatement()) + "\n";
        } else if (ctx.jumpStatement()!= null) {
            code = getIndentation() + getCode(ctx.jumpStatement()) + getTrailingComment(ctx) + "\n";
        } else if (ctx.expressionStatement()!= null) {
            code = getIndentation() + getCode(ctx.expressionStatement()) + getTrailingComment(ctx) + "\n";
        } else if (ctx.compoundStatement()!= null) {
            code = getCode(ctx.compoundStatement());
        }
        
        // Print the final statement code
        //System.out.print(code);
        setCode(ctx, code);
    }

    @Override
    public void exitDeclaration(CParser.DeclarationContext ctx) {
        // Handles variable declarations like 'int numbers = {1, 2, 3, 4, 5, 6};'
        if (ctx.initDeclaratorList() == null) {
            setCode(ctx, "");
        }
        String code = getIndentation() + getCode(ctx.initDeclaratorList()) + getTrailingComment(ctx) + "\n";
        //System.out.print(code);
        setCode(ctx, code);
    }

    @Override
    public void exitInitDeclaratorList(CParser.InitDeclaratorListContext ctx) {
        String code = ctx.initDeclarator().stream()
               .map(this::getCode)
               .collect(Collectors.joining("\n" + getIndentation()));
        setCode(ctx, code);
    }

    @Override
    public void exitInitDeclarator(CParser.InitDeclaratorContext ctx) {
        String varName = getCode(ctx.declarator());
        String initializer = "";
        if (ctx.initializer()!= null) {
            initializer = " = " + getCode(ctx.initializer());
        }
        setCode(ctx, varName + initializer);
    }

    @Override
    public void exitDeclarator(CParser.DeclaratorContext ctx) {
        setCode(ctx, getCode(ctx.directDeclarator()));
    }

    @Override
    public void exitDirectDeclarator(CParser.DirectDeclaratorContext ctx) {
        CParser.DirectDeclaratorContext dd = getBaseDirectDeclarator(ctx);
        if (dd.Identifier()!= null) {
            setCode(ctx, dd.Identifier().getText());
        } else {
            // Handle other declarator forms if needed
            setCode(ctx, ctx.getText());
        }
        
        /* 
        else if (ctx.directDeclarator()!= null && ctx.LeftBracket()!= null) {
            // This handles array declarations like 'numbers'
            setCode(ctx, getCode(ctx.directDeclarator()));
        } else {
            // Handle other declarator forms if needed
            setCode(ctx, ctx.getText());
        }
        */
    }

    @Override
    public void exitInitializer(CParser.InitializerContext ctx) {
        if (ctx.assignmentExpression()!= null) {
            setCode(ctx, getCode(ctx.assignmentExpression()));
        } else if (ctx.initializerList()!= null) {
            // Handles array initializers like '{1, 2, 3}'
            String list = "[" + getCode(ctx.initializerList()) + "]";
            setCode(ctx, list);
        }
    }

    @Override
    public void exitInitializerList(CParser.InitializerListContext ctx) {
        String list = ctx.initializer().stream()
               .map(this::getCode)
               .collect(Collectors.joining(", "));
        setCode(ctx, list);
    }

    @Override
    public void enterSelectionStatement(CParser.SelectionStatementContext ctx) {
        indent();
    }

    @Override
    public void exitSelectionStatement(CParser.SelectionStatementContext ctx) {
        dedent();
        // Handles 'if' statements
        StringBuilder selectionBuilder = new StringBuilder();
        String condition = getCode(ctx.expression());
        selectionBuilder.append(getIndentation()).append("if ").append(condition).append(":\n");
        //System.out.println(getIndentation() + "if " + condition + ":");
        //indent();
        String ifBody = getCode(ctx.statement(0));
        if (ifBody==null) {
            ifBody = "pass\n"; // If the body is empty, we still need to print a pass statement
        }
        selectionBuilder.append(ifBody);

        if (ctx.Else()!= null) {
            // Handle else part if it exists
            selectionBuilder.append(getIndentation()).append("else:\n");
            //System.out.println(getIndentation() + "else:");
            indent();
            String elseBody = getCode(ctx.statement(1));
            if (elseBody==null) {
                elseBody = "pass\n"; // If the else body is empty, we still need to print a pass statement
            }
            selectionBuilder.append(elseBody);
            dedent();
        }
        // System.out.println(selectionBuilder.toString());
        setCode(ctx, selectionBuilder.toString());
    }
    
    @Override
    public void enterIterationStatement(CParser.IterationStatementContext ctx) {
        // Indent for the loop body
        indent();
    }

    @Override
    public void exitIterationStatement(CParser.IterationStatementContext ctx) {
        dedent(); // Dedent after the loop body
        // Deconstructs a C 'for' loop into a Python 'while' loop
        StringBuilder loopBuilder = new StringBuilder();
        CParser.ForConditionContext forCtx = ctx.forCondition();
        if (forCtx.forExpression(1)!= null) {
            
            // 1. Initialization
            String init = "";
            if (forCtx.forDeclaration()!= null) {
                // For 'for (int i = 0;...)'
                CParser.InitDeclaratorContext initDeclarator = forCtx.forDeclaration().initDeclaratorList().initDeclarator(0);
                String varName = getCode(initDeclarator.declarator());
                String value = getCode(initDeclarator.initializer());
                init = varName + '=' + value;
            } else if (forCtx.expression()!= null) {
                // For 'for (i = 0;...)'
                init = getCode(forCtx.expression());
            }

            // 2. Condition (becomes the while loop condition)
            String condition = "True"; // for loops with no condition
            if (forCtx.forExpression(0)!= null) {
                condition = getCode(forCtx.forExpression(0));
            }
            //System.out.println(getIndentation() + "while " + condition + ":");
            // 3. Update/Increment expression


            String body = getCode(ctx.statement());
            // The body already contains its own indented statements. We just need to add the update.

            if (!init.isEmpty()) {
                loopBuilder.append(getIndentation()).append(init).append("\n");
                //System.out.println(getIndentation() + init);
            }
            loopBuilder.append(getIndentation()).append("while ").append(condition).append(":\n");
            if (!body.isEmpty()) {
                loopBuilder.append(body);
                //System.out.print(body);
            }


            indent();
            String update = getCode(forCtx.forExpression(1));
            //System.out.println("Update expression: " + update);
            // C '++' becomes Python '+= 1'
            if (update.endsWith("++")) {
                update = update.substring(0, update.length() - 2) + " += 1";
            } else if (update.endsWith("--")) {
                update = update.substring(0, update.length() - 2) + " -= 1";
            }
            if (!update.isEmpty()) {
                loopBuilder.append(getIndentation()).append(update).append("\n");
                //System.out.println(getIndentation() + update);
            }
            dedent();
        }
        // Set the final loop code
        setCode(ctx, loopBuilder.toString());
    }

    @Override
    public void exitForExpression(CParser.ForExpressionContext ctx) {
        // Handle only the first expression for simplicity
        String code = ctx.assignmentExpression().stream()
            .map(this::getCode)
            .collect(Collectors.joining(", "));
        setCode(ctx, code);
    }
    
    @Override
    public void exitJumpStatement(CParser.JumpStatementContext ctx) {
        if (ctx.Return()!= null) {
            String retVal = (ctx.expression()!= null)? getCode(ctx.expression()) : "";
            setCode(ctx, "return " + retVal);
        }
    }

    @Override
    public void exitExpressionStatement(CParser.ExpressionStatementContext ctx) {
        if (ctx.expression()!= null) {
            setCode(ctx, getCode(ctx.expression()));
        } else {
            setCode(ctx, "");
        }
    }

    @Override
    public void exitExpression(CParser.ExpressionContext ctx) {
        // For simplicity, handle only the first assignment expression
        String code = ctx.assignmentExpression().stream()
            .map(this::getCode)
            .collect(Collectors.joining(", "));
        setCode(ctx, code);
    }

    @Override
    public void exitAssignmentExpression(CParser.AssignmentExpressionContext ctx) {
        if (ctx.assignmentOperator()!=null) {
            String lvalue = getCode(ctx.unaryExpression());
            String op = ctx.assignmentOperator().getText();
            String rvalue = getCode(ctx.assignmentExpression());
            setCode(ctx, lvalue + " " + op + " " + rvalue);
        } else if (ctx.conditionalExpression()!=null){
            setCode(ctx, getCode(ctx.conditionalExpression()));
        } else {
            setCode(ctx, ctx.DigitSequence().getText());
        }
    }

    @Override
    public void exitConditionalExpression(CParser.ConditionalExpressionContext ctx) {
        setCode(ctx, getCode(ctx.logicalOrExpression()));
    }

    @Override
    public void exitLogicalOrExpression(CParser.LogicalOrExpressionContext ctx) {
        if (ctx.logicalAndExpression().size() > 1) {
            String code = ctx.logicalAndExpression().stream()
                   .map(this::getCode)
                   .collect(Collectors.joining(" or "));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.logicalAndExpression(0)));
        }
    }

    @Override
    public void exitLogicalAndExpression(CParser.LogicalAndExpressionContext ctx) {
        if (ctx.inclusiveOrExpression().size() > 1) {
            String code = ctx.inclusiveOrExpression().stream()
                   .map(this::getCode)
                   .collect(Collectors.joining(" and "));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.inclusiveOrExpression(0)));
        }
    }

    @Override
    public void exitInclusiveOrExpression(CParser.InclusiveOrExpressionContext ctx) {
        // Python has no direct equivalent for bitwise '|', so we translate as is for now
        if (ctx.exclusiveOrExpression().size() > 1) {
            String code = ctx.exclusiveOrExpression().stream()
                   .map(this::getCode)
                   .collect(Collectors.joining(" | "));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.exclusiveOrExpression(0)));
        }
    }
    
    @Override
    public void exitExclusiveOrExpression(CParser.ExclusiveOrExpressionContext ctx) {
        if (ctx.andExpression().size() > 1) {
            String code = ctx.andExpression().stream()
                   .map(this::getCode)
                   .collect(Collectors.joining(" ^ "));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.andExpression(0)));
        }
    }

    @Override
    public void exitAndExpression(CParser.AndExpressionContext ctx) {
        if (ctx.equalityExpression().size() > 1) {
            String code = ctx.equalityExpression().stream()
                   .map(this::getCode)
                   .collect(Collectors.joining(" & "));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.equalityExpression(0)));
        }
    }

    @Override
    public void exitEqualityExpression(CParser.EqualityExpressionContext ctx) {
        if (ctx.relationalExpression().size() > 1) {
            String op = ctx.getChild(1).getText(); // '==' or '!='
            String code = getCode(ctx.relationalExpression(0)) + " " + op + " " + getCode(ctx.relationalExpression(1));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.relationalExpression(0)));
        }
    }

    @Override
    public void exitRelationalExpression(CParser.RelationalExpressionContext ctx) {
        if (ctx.shiftExpression().size() > 1) {
            String op = ctx.getChild(1).getText(); // '<', '>', etc.
            String code = getCode(ctx.shiftExpression(0)) + " " + op + " " + getCode(ctx.shiftExpression(1));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.shiftExpression(0)));
        }
    }

    @Override
    public void exitShiftExpression(CParser.ShiftExpressionContext ctx) {
        if (ctx.additiveExpression().size() > 1) {
            String op = ctx.getChild(1).getText(); // '<<' or '>>'
            String code = getCode(ctx.additiveExpression(0)) + " " + op + " " + getCode(ctx.additiveExpression(1));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.additiveExpression(0)));
        }
    }

    @Override
    public void exitAdditiveExpression(CParser.AdditiveExpressionContext ctx) {
        if (ctx.multiplicativeExpression().size() > 1) {
            String op = ctx.getChild(1).getText(); // '+' or '-'
            String code = getCode(ctx.multiplicativeExpression(0)) + " " + op + " " + getCode(ctx.multiplicativeExpression(1));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.multiplicativeExpression(0)));
        }
    }

    @Override
    public void exitMultiplicativeExpression(CParser.MultiplicativeExpressionContext ctx) {
        // Special handling for sizeof(arr)/sizeof(arr) idiom
        if (!ctx.Div().isEmpty() && isSizeofIdiom(ctx)) {
            String arrayName = ctx.castExpression(0).unaryExpression().postfixExpression().primaryExpression().Identifier().getText();
            setCode(ctx, "len(" + arrayName + ")");
        } else if (ctx.castExpression().size() > 1) {
            String op = ctx.getChild(1).getText(); // '*', '/', '%'
            String code = getCode(ctx.castExpression(0)) + " " + op + " " + getCode(ctx.castExpression(1));
            setCode(ctx, code);
        } else {
            setCode(ctx, getCode(ctx.castExpression(0)));
        }
    }

    private boolean isSizeofIdiom(CParser.MultiplicativeExpressionContext ctx) {
        try {
            // Check left side: sizeof(array)
            CParser.CastExpressionContext left = ctx.castExpression(0);
            if (!left.unaryExpression().Sizeof(0).getText().equals("sizeof")) return false;
            String leftArg = left.unaryExpression().postfixExpression().primaryExpression().Identifier().getText();

            // Check right side: sizeof(array)
            CParser.CastExpressionContext right = ctx.castExpression(1);
            if (!right.unaryExpression().Sizeof(0).getText().equals("sizeof")) return false;
            CParser.PostfixExpressionContext rightPostfix = right.unaryExpression().postfixExpression();
            String rightArg = rightPostfix.primaryExpression().Identifier().getText();
            
            // Check if array names match and it's an array access of index 0
            return leftArg.equals(rightArg) && rightPostfix.expression().get(0).getText().equals("0");
        } catch (Exception e) {
            return false; // Structure doesn't match, not the idiom
        }
    }

    @Override
    public void exitCastExpression(CParser.CastExpressionContext ctx) {
        if (ctx.unaryExpression()!= null) {
            setCode(ctx, getCode(ctx.unaryExpression()));
        }
    }

    @Override
    public void exitUnaryExpression(CParser.UnaryExpressionContext ctx) {
        if (ctx.postfixExpression()!= null) {
            setCode(ctx, getCode(ctx.postfixExpression()));
        } else if (ctx.unaryOperator()!= null) {
            String op = ctx.unaryOperator().getText();
            String expr = getCode(ctx.castExpression());
            setCode(ctx, op + expr);
        }
    }

    @Override
    public void exitPostfixExpression(CParser.PostfixExpressionContext ctx) {
        // Handle function calls like printf
        if (ctx.Identifier().isEmpty() && !ctx.argumentExpressionList().isEmpty()) {
            String funcName = getCode(ctx.primaryExpression());
            if ("printf".equals(funcName)) {
                handlePrintf(ctx);
            } else {
                // Generic function call
                String args = getCode(ctx.argumentExpressionList().get(0));
                setCode(ctx, funcName + "(" + args + ")");
            }
        } else if (!ctx.LeftBracket().isEmpty()) {
            // Array access like numbers[i]
            String arrayName = getCode(ctx.primaryExpression());
            String index = getCode(ctx.expression().get(0));
            setCode(ctx, arrayName + "[" + index + "]");
        } else if (!ctx.PlusPlus().isEmpty() || !ctx.MinusMinus().isEmpty()) {
            setCode(ctx, getCode(ctx.primaryExpression()) + ctx.getChild(1).getText());
        } else if (ctx.primaryExpression()!= null) {
            setCode(ctx, getCode(ctx.primaryExpression()));
        }
    }

    private void handlePrintf(CParser.PostfixExpressionContext ctx) {
        List<CParser.AssignmentExpressionContext> args = ctx.argumentExpressionList(0).assignmentExpression();
        if (args.isEmpty()) {
            setCode(ctx, "print()");
            return;
        }

        String formatString = getCode(args.get(0));
        // Remove quotes and trailing \n
        formatString = formatString.substring(1, formatString.length() - 1).replaceAll("\\\\n$", "");

        List<String> printfArgs = args.subList(1, args.size()).stream()
               .map(this::getCode)
               .collect(Collectors.toList());

        // Simple replacement for %d, %s, etc. with {} for f-string
        String pythonFormatString = formatString.replaceAll("%[dfsc]", "{}");
        
        String printArgs = String.join(", ", printfArgs);
        String pythonCode = "";
        if (printArgs.isEmpty()) {
            pythonCode = "print(\"" + pythonFormatString + "\")";
        } else {
            pythonCode = "print(\"" + pythonFormatString + "\".format(" + printArgs + "))";
        }
        
        setCode(ctx, pythonCode);
    }
    
    @Override
    public void exitArgumentExpressionList(CParser.ArgumentExpressionListContext ctx) {
        String args = ctx.assignmentExpression().stream()
               .map(this::getCode)
               .collect(Collectors.joining(", "));
        setCode(ctx, args);
    }

    @Override
    public void exitPrimaryExpression(CParser.PrimaryExpressionContext ctx) {
        if (ctx.Identifier()!= null) {
            setCode(ctx, ctx.Identifier().getText());
        } else if (ctx.Constant()!= null) {
            setCode(ctx, ctx.Constant().getText());
        } else if (!ctx.StringLiteral().isEmpty()) {
            setCode(ctx, ctx.StringLiteral(0).getText());
        } else if (ctx.expression()!= null) {
            setCode(ctx, "(" + getCode(ctx.expression()) + ")");
        }
    }
    
    // Comment Handling
    private String getTrailingComment(ParserRuleContext ctx) {
        CommonTokenStream tokenStream = (CommonTokenStream) parser.getTokenStream();
        List<Token> comments = tokenStream.getHiddenTokensToRight(
                ctx.getStop().getTokenIndex(), CLexer.HIDDEN
        );
        if (comments!= null &&!comments.isEmpty()) {
            // Assuming one line comment per statement for simplicity
            Token commentToken = comments.get(0);
            if (commentToken.getType() == CLexer.LineComment) {
                String commentText = commentToken.getText().substring(2).trim();
                return "  # " + commentText;
            }
        }
        return "";
    }

    private void handleTrailingComment(ParserRuleContext ctx) {
        String comment = getTrailingComment(ctx);
        if (!comment.isEmpty()) {
            System.out.print(comment);
        }
    }
}