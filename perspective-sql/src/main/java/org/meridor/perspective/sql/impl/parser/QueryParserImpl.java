package org.meridor.perspective.sql.impl.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.meridor.perspective.beans.BooleanRelation;
import org.meridor.perspective.sql.SQLLexer;
import org.meridor.perspective.sql.SQLParser;
import org.meridor.perspective.sql.SQLParserBaseListener;
import org.meridor.perspective.sql.impl.CaseInsensitiveInputStream;
import org.meridor.perspective.sql.impl.expression.*;
import org.meridor.perspective.sql.impl.function.FunctionName;
import org.meridor.perspective.sql.impl.table.Column;
import org.meridor.perspective.sql.impl.table.DataType;
import org.meridor.perspective.sql.impl.table.TableName;
import org.meridor.perspective.sql.impl.table.TablesAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.sql.SQLSyntaxErrorException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.meridor.perspective.beans.BooleanRelation.*;
import static org.meridor.perspective.sql.impl.parser.AliasExpressionPair.emptyPair;
import static org.meridor.perspective.sql.impl.parser.AliasExpressionPair.pair;
import static org.meridor.perspective.sql.impl.table.Column.ANY;

@Component
@Lazy
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class QueryParserImpl extends SQLParserBaseListener implements QueryParser, SelectQueryAware {

    private class InternalErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
            errors.add(String.format("Parse error: \"%s\" near \"%s\" at %d:%d", msg, String.valueOf(offendingSymbol), line, charPositionInLine));
        }
    }
    
    @Autowired
    private TablesAware tablesAware;

    private Optional<SQLParser.Select_clauseContext> selectClauseContext = Optional.empty();
    private Optional<SQLParser.From_clauseContext> fromClauseContext = Optional.empty();
    private Optional<SQLParser.Where_clauseContext> whereClauseContext = Optional.empty();
    private Optional<SQLParser.Having_clauseContext> havingClauseContext = Optional.empty();
    private Optional<SQLParser.Group_clauseContext> groupByClauseContext = Optional.empty();
    private Optional<SQLParser.Order_clauseContext> orderByClauseContext = Optional.empty();
    
    private QueryType queryType = QueryType.UNKNOWN;
    private Set<String> errors = new HashSet<>();
    private Map<String, Object> selectionMap = new LinkedHashMap<>();
    private Map<String, String> tableAliases = new HashMap<>();
    private Optional<DataSource> dataSource = Optional.empty();
    //Column name -> aliases map of columns available after all joins
    private Map<String, List<String>> availableColumns = new HashMap<>();
    private Optional<Object> whereExpression = Optional.empty();
    private final List<Object> groupByExpressions = new ArrayList<>();
    private final List<OrderExpression> orderByExpressions = new ArrayList<>();
    private Optional<Object> havingExpression = Optional.empty();
    private Optional<Integer> limitCount = Optional.empty();
    private Optional<Integer> limitOffset = Optional.empty();

    @Override
    public void parse(String sql) throws SQLSyntaxErrorException {
        CharStream input = new CaseInsensitiveInputStream(sql);
        ANTLRErrorListener errorListener = new InternalErrorListener();
        SQLLexer sqlLexer = new SQLLexer(input);
        sqlLexer.removeErrorListeners();
        sqlLexer.addErrorListener(errorListener);
        CommonTokenStream commonTokenStream = new CommonTokenStream(sqlLexer);
        SQLParser sqlParser = new SQLParser(commonTokenStream);
        sqlParser.removeErrorListeners();
        sqlParser.addErrorListener(errorListener);
        ParseTree parseTree = sqlParser.query();
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(this, parseTree);
        if (!errors.isEmpty()) {
            String message = errors.stream().collect(Collectors.joining("; "));
            throw new SQLSyntaxErrorException(message);
        }
    }

    @Override
    public SelectQueryAware getSelectQueryAware() {
        return this;
    }

    @Override
    public QueryType getQueryType() {
        return queryType;
    }

    //Show tables query
    @Override
    public void exitShow_tables_query(SQLParser.Show_tables_queryContext ctx) {
        this.queryType = QueryType.SHOW_TABLES;
    }

    //Select query
    @Override
    public void exitSelect_clause(SQLParser.Select_clauseContext ctx) {
        this.queryType = QueryType.SELECT;
        selectClauseContext = Optional.of(ctx);
    }

    @Override
    public void exitFrom_clause(SQLParser.From_clauseContext ctx) {
        fromClauseContext = Optional.of(ctx);
    }

    @Override
    public void exitWhere_clause(SQLParser.Where_clauseContext ctx) {
        whereClauseContext = Optional.of(ctx);
    }

    @Override
    public void exitQuery(SQLParser.QueryContext ctx) {
        processFromClause();
        processSelectClause();
        processWhereClause();
        processGroupByClause();
        processHavingClause();
        processOrderByClause();
    }

    private void processWhereClause() {
        if (whereClauseContext.isPresent()) {
            this.whereExpression = Optional.ofNullable(processComplexBooleanExpression(whereClauseContext.get().complex_boolean_expression()));
        }
    }

    private void processGroupByClause() {
        if (groupByClauseContext.isPresent()) {
            foreachExpression(groupByClauseContext.get().expressions().expression(), p -> this.groupByExpressions.add(p.getExpression()));
        }
    }

    private void processOrderByClause() {
        if (orderByClauseContext.isPresent()) {
            orderByClauseContext.get().order_expressions().order_expression().forEach(oe -> {
                Object expression = processExpression(oe.expression()).getExpression();
                OrderDirection orderDirection = (oe.DESC() != null) ? OrderDirection.DESC : OrderDirection.ASC;
                this.orderByExpressions.add(new OrderExpression(expression, orderDirection));
            });
        }
    }
    
    private void processHavingClause() {
        if (havingClauseContext.isPresent()) {
            this.havingExpression = Optional.ofNullable(processComplexBooleanExpression(havingClauseContext.get().complex_boolean_expression()));
        }
    }
    
    private Object processComplexBooleanExpression(SQLParser.Complex_boolean_expressionContext complexBooleanExpression) {
        if (complexBooleanExpression.unary_boolean_operator() != null && complexBooleanExpression.complex_boolean_expression(0) != null) {
            Object expression = processComplexBooleanExpression(complexBooleanExpression.complex_boolean_expression(0));
            UnaryBooleanOperator unaryBooleanOperator = processUnaryBooleanOperator(complexBooleanExpression.unary_boolean_operator());
            return new UnaryBooleanExpression(expression, unaryBooleanOperator);
        } else if (
                complexBooleanExpression.binary_boolean_operator(0) != null &&
                complexBooleanExpression.simple_boolean_expression(0) != null &&
                complexBooleanExpression.simple_boolean_expression(1) != null
        ) {
            Object left = processSimpleBooleanExpression(complexBooleanExpression.simple_boolean_expression(0));
            Object right = processSimpleBooleanExpression(complexBooleanExpression.simple_boolean_expression(1));
            BinaryBooleanOperator binaryBooleanOperator = processBinaryBooleanOperator(complexBooleanExpression.binary_boolean_operator(0));
            return new BinaryBooleanExpression(left, binaryBooleanOperator, right);
        } else if (complexBooleanExpression.simple_boolean_expression(0) != null) {
            return processSimpleBooleanExpression(complexBooleanExpression.simple_boolean_expression(0));
        } else if (complexBooleanExpression.binary_boolean_operator(0) != null && complexBooleanExpression.complex_boolean_expression(1) != null) {
            return processComplexBooleanExpressionsList(
                    Optional.empty(),
                    complexBooleanExpression.complex_boolean_expression(),
                    complexBooleanExpression.binary_boolean_operator()
            );
        }
        throw new UnsupportedOperationException("Unknown boolean expression type");
    }
    
    private BinaryBooleanExpression processComplexBooleanExpressionsList(
            Optional<BinaryBooleanExpression> previousExpression,
            List<SQLParser.Complex_boolean_expressionContext> remainingExpressions,
            List<SQLParser.Binary_boolean_operatorContext> remainingOperators
    ) {
        if (remainingExpressions.isEmpty() || remainingOperators.isEmpty()) {
            return previousExpression.get();
        }
        BinaryBooleanOperator binaryBooleanOperator = processBinaryBooleanOperator(remainingOperators.remove(0));
        Object left = previousExpression.isPresent() ?
                previousExpression.get() :
                processComplexBooleanExpression(remainingExpressions.remove(0));
        Object right = processComplexBooleanExpression(remainingExpressions.remove(0)); //First remove call shifts indexes to the left
        BinaryBooleanExpression binaryBooleanExpression = new BinaryBooleanExpression(left, binaryBooleanOperator, right);
        return processComplexBooleanExpressionsList(Optional.of(binaryBooleanExpression), remainingExpressions, remainingOperators);
    }
    
    private UnaryBooleanOperator processUnaryBooleanOperator(SQLParser.Unary_boolean_operatorContext unaryBooleanOperator) {
        if (unaryBooleanOperator.NOT() != null) {
            return UnaryBooleanOperator.NOT;
        }
        throw new UnsupportedOperationException("Unknown unary boolean operator");
    }
    
    private BinaryBooleanOperator processBinaryBooleanOperator(SQLParser.Binary_boolean_operatorContext binaryBooleanOperator) {
        if (binaryBooleanOperator.AND() != null) {
            return BinaryBooleanOperator.AND;
        } else if (binaryBooleanOperator.OR() != null) {
            return BinaryBooleanOperator.OR;
        } else if (binaryBooleanOperator.XOR() != null) {
            return BinaryBooleanOperator.XOR;
        }
        throw new UnsupportedOperationException("Unknown binary boolean operator");
    }
    
    private Object processSimpleBooleanExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression) {
        if (simpleBooleanExpression.relational_operator() != null) {
            return processRelationalBooleanExpression(simpleBooleanExpression);
        } else if (simpleBooleanExpression.BETWEEN() != null) {
            return processBetweenExpression(simpleBooleanExpression);
        } else if (simpleBooleanExpression.IS() != null && simpleBooleanExpression.NULL() != null) {
            return processIsExpression(simpleBooleanExpression);
        } else if (simpleBooleanExpression.LIKE() != null) {
            return processLikeExpression(simpleBooleanExpression);
        } else if (simpleBooleanExpression.REGEXP() != null) {
            return processRegexpExpression(simpleBooleanExpression);
        } else if (simpleBooleanExpression.IN() != null && simpleBooleanExpression.expression().size() >= 2) {
            return processInExpression(simpleBooleanExpression);
        }
        throw new UnsupportedOperationException("Unsupported simple boolean expression type");
    }

    private Object getExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression, int pos) {
        return processExpression(simpleBooleanExpression.expression(pos)).getExpression();
    }
    
    private SimpleBooleanExpression processRelationalBooleanExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression) {
        Object left = getExpression(simpleBooleanExpression, 0);
        Object right = getExpression(simpleBooleanExpression, 1);
        BooleanRelation booleanRelation = processRelationalOperator(simpleBooleanExpression.relational_operator());
        return new SimpleBooleanExpression(left, booleanRelation, right);
    }
    
    private Object processBetweenExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression) {
        Object value = getExpression(simpleBooleanExpression, 0);
        Object left = getExpression(simpleBooleanExpression, 1);
        Object right = getExpression(simpleBooleanExpression, 2);
        BinaryBooleanExpression betweenExpression = new BinaryBooleanExpression(
                new SimpleBooleanExpression(value, GREATER_THAN_EQUAL, left),
                BinaryBooleanOperator.AND,
                new SimpleBooleanExpression(value, LESS_THAN_EQUAL, right)
        );
        return (simpleBooleanExpression.NOT() != null) ?
                new UnaryBooleanExpression(betweenExpression, UnaryBooleanOperator.NOT) :
                betweenExpression;
    }
    
    private Object processIsExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression) {
        Object value = getExpression(simpleBooleanExpression, 0);
        FunctionExpression isNullExpression = new FunctionExpression(FunctionName.TYPEOF.name(), Arrays.asList(value, DataType.NULL));
        return (simpleBooleanExpression.NOT() != null) ?
                new UnaryBooleanExpression(isNullExpression, UnaryBooleanOperator.NOT) :
                isNullExpression;
    }
    
    private Object processLikeExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression) {
        Object value = getExpression(simpleBooleanExpression, 0);
        Object pattern = getExpression(simpleBooleanExpression, 1);
        SimpleBooleanExpression likeExpression = new SimpleBooleanExpression(value, LIKE, pattern);
        return (simpleBooleanExpression.NOT() != null) ?
                new UnaryBooleanExpression(likeExpression, UnaryBooleanOperator.NOT) :
                likeExpression;
    }
    
    private Object processRegexpExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression) {
        Object value = getExpression(simpleBooleanExpression, 0);
        Object pattern = getExpression(simpleBooleanExpression, 1);
        SimpleBooleanExpression regexpExpression = new SimpleBooleanExpression(value, REGEXP, pattern);
        return (simpleBooleanExpression.NOT() != null) ?
                new UnaryBooleanExpression(regexpExpression, UnaryBooleanOperator.NOT) :
                regexpExpression;
    }
    
    private Object processInExpression(SQLParser.Simple_boolean_expressionContext simpleBooleanExpression) {
        List<Object> processedExpressions = simpleBooleanExpression.expression().stream()
                .map(e -> processExpression(e).getExpression())
                .collect(Collectors.toList());
        Object value = processedExpressions.remove(0);
        InExpression inExpression = new InExpression(value, processedExpressions);
        return (simpleBooleanExpression.NOT() != null) ?
                new UnaryBooleanExpression(inExpression, UnaryBooleanOperator.NOT) :
                inExpression;
    }
    
    private BooleanRelation processRelationalOperator(SQLParser.Relational_operatorContext relationalOperator) {
        if (relationalOperator.EQ() != null) {
            return EQUAL;
        } else if (relationalOperator.LT() != null) {
            return LESS_THAN;
        } else if (relationalOperator.GT() != null) {
            return GREATER_THAN;
        } else if (relationalOperator.LTE() != null) {
            return LESS_THAN_EQUAL;
        } else if (relationalOperator.GTE() != null) {
            return GREATER_THAN_EQUAL;
        } else if (relationalOperator.NOT_EQ() != null) {
            return NOT_EQUAL;
        }
        throw new UnsupportedOperationException("Unknown relational operator");
    }

    private void processFromClause() {
        if (fromClauseContext.isPresent()) {
            SQLParser.Table_referencesContext tableReferencesContext = fromClauseContext.get().table_references();
            Optional<DataSource> dataSourceCandidate = processTableReferences(tableReferencesContext);
            //Clearing initially available columns used in from clause checks 
            // and preparing more precise avilable columns map
            getAvailableColumns().clear();
            Map<String, List<String>> availableColumns = getAvailableColumns(dataSourceCandidate, Collections.emptyMap());
            getAvailableColumns().putAll(availableColumns);
            this.dataSource = dataSourceCandidate;
        }
    }

    private Optional<DataSource> processTableReferences(SQLParser.Table_referencesContext tableReferencesContext) {
        List<DataSource> dataSources = tableReferencesContext.table_reference().stream()
                .map(this::processTableReference)
                .collect(Collectors.toList());
        return dataSources.size() > 1 ?
                chainDataSources(Optional.empty(), dataSources) :
                Optional.of(dataSources.get(0));
    }
    
    private Optional<DataSource> chainDataSources(Optional<DataSource> previousDataSourceCandidate, List<DataSource> remainingDataSources) {
        if (remainingDataSources.isEmpty()) {
            return previousDataSourceCandidate;
        }
        DataSource currentDataSource = remainingDataSources.remove(remainingDataSources.size() - 1); //Removing from the tail
        DataSource currentCompoundDataSource = new DataSource(currentDataSource);
        if (previousDataSourceCandidate.isPresent()) {
            DataSource previousDataSource = previousDataSourceCandidate.get();
            DataSource previousCompoundDataSource = new DataSource(previousDataSource);
            previousCompoundDataSource.setJoinType(JoinType.INNER);
            currentCompoundDataSource.setNextDatasource(previousDataSource);
        }
        return chainDataSources(Optional.of(currentCompoundDataSource), remainingDataSources);
    }
    
    private Map<String, List<String>> getAvailableColumns(Optional<DataSource> currentDataSourceCandidate, Map<String, List<String>> availableColumns) {
        if (!currentDataSourceCandidate.isPresent()) {
            return availableColumns;
        }
        DataSource currentDataSource = currentDataSourceCandidate.get();
        Optional<String> tableAliasCandidate = currentDataSource.getTableAlias();
        Optional<DataSource> dataSourceCandidate = currentDataSource.getDataSource();
        final Map<String, List<String>> currentlyAvailableColumns = new HashMap<>();
        if (tableAliasCandidate.isPresent()) {
            String tableAlias = tableAliasCandidate.get();
            String tableName = getTableAliases().get(tableAlias);
            List<String> columnNames = tablesAware
                    .getColumns(TableName.fromString(tableName)).stream()
                    .map(Column::getName)
                    .collect(Collectors.toList());
            currentlyAvailableColumns.putAll(createAvailableColumns(tableAlias, columnNames));
        } else if (dataSourceCandidate.isPresent()) {
            currentlyAvailableColumns.putAll(getAvailableColumns(dataSourceCandidate, Collections.emptyMap()));
        }
        if (currentDataSource.getJoinType().isPresent()) {
            Map<String, List<String>> previouslyAvailableColumns = new HashMap<>(availableColumns);
            return getAvailableColumns(
                    currentDataSource.getNextDataSource(),
                    mergeAvailableColumns(
                            previouslyAvailableColumns,
                            currentlyAvailableColumns
                    )
            );
        } else {
            return getAvailableColumns(currentDataSource.getNextDataSource(), currentlyAvailableColumns);
        }
    }
    
    private Map<String, List<String>> createAvailableColumns(String tableAlias, List<String> columnNames) {
        return columnNames.stream().collect(Collectors.toMap(
                Function.identity(),
                cn -> new ArrayList<String>(){
                    {
                        add(tableAlias);
                    }
                }
        ));
    }

    private Map<String, List<String>> mergeAvailableColumns(Map<String, List<String>> first, Map<String, List<String>> second) {
        Map<String, List<String>> mergedMaps = first.keySet().stream().collect(Collectors.toMap(
                Function.identity(),
                k -> second.merge(k, first.get(k), (f, s) -> new ArrayList<>(new HashSet<String>() {
                    {
                        addAll(f);
                        addAll(s);

                    }
                }))
        ));
        second.keySet().stream()
                .filter(k -> !first.containsKey(k))
                .forEach(k -> mergedMaps.put(k, second.get(k)));
        return mergedMaps;
    }
    
    private DataSource processTableReference(SQLParser.Table_referenceContext tableReferenceContext) {
        if (tableReferenceContext.table_atom() != null) {
            return processTableAtom(tableReferenceContext.table_atom());
        } else if (tableReferenceContext.table_join() != null) {
            return processTableJoin(tableReferenceContext.table_join());
        }
        throw new UnsupportedOperationException("Unsupported table reference type");
    }

    private DataSource processTableJoin(SQLParser.Table_joinContext tableJoinContext) {
        DataSource firstDataSource = processTableAtom(tableJoinContext.table_atom());
        List<SQLParser.Join_clauseContext> joinClauseContexts = tableJoinContext.join_clause();
        return appendJoinClauses(firstDataSource, Optional.empty(), joinClauseContexts);
    }
    
    private DataSource appendJoinClauses(DataSource firstDataSource, Optional<DataSource> previousDataSourceCandidate, List<SQLParser.Join_clauseContext> remainingJoinClauseContexts) {
        if (remainingJoinClauseContexts.isEmpty()) {
            firstDataSource.setNextDatasource(previousDataSourceCandidate.get());
            return firstDataSource;
        }
        SQLParser.Join_clauseContext currentJoinClauseContext = remainingJoinClauseContexts.remove(remainingJoinClauseContexts.size() - 1);
        DataSource currentDataSource = processJoinClause(currentJoinClauseContext);
        if (previousDataSourceCandidate.isPresent()) {
            currentDataSource.setNextDatasource(previousDataSourceCandidate.get());
        }
        return appendJoinClauses(firstDataSource, Optional.of(currentDataSource), remainingJoinClauseContexts);
    }

    private DataSource processJoinClause(SQLParser.Join_clauseContext joinClauseContext) {
        if (joinClauseContext.inner_join_clause() != null) {
            return processInnerJoinClause(joinClauseContext.inner_join_clause());
        } else if (joinClauseContext.outer_join_clause() != null) {
            return processOuterJoinClause(joinClauseContext.outer_join_clause());
        } else if (joinClauseContext.natural_join_clause() != null) {
            return processNaturalJoinClause(joinClauseContext.natural_join_clause());
        }
        throw new UnsupportedOperationException("Unknown join clause type");
    }

    private DataSource processInnerJoinClause(SQLParser.Inner_join_clauseContext innerJoinClauseContext) {
        return joinClauseToDataSource(innerJoinClauseContext.table_atom(), JoinType.INNER, Optional.ofNullable(innerJoinClauseContext.join_condition()));
    }

    private DataSource processOuterJoinClause(SQLParser.Outer_join_clauseContext outerJoinClauseContext) {
        JoinType joinType = outerJoinClauseContext.LEFT() != null ?
                JoinType.LEFT :
                JoinType.RIGHT;
        return joinClauseToDataSource(outerJoinClauseContext.table_atom(), joinType, Optional.of(outerJoinClauseContext.join_condition()));
    }

    private DataSource processNaturalJoinClause(SQLParser.Natural_join_clauseContext naturalJoinClauseContext) {
        JoinType joinType = JoinType.INNER;
        if (naturalJoinClauseContext.LEFT() != null) {
            joinType = JoinType.LEFT;
        } else if (naturalJoinClauseContext.RIGHT() != null) {
            joinType = JoinType.RIGHT;
        }
        DataSource dataSource = joinClauseToDataSource(naturalJoinClauseContext.table_atom(), joinType, Optional.empty());
        dataSource.setNaturalJoin(true);
        return dataSource;
    }

    private DataSource joinClauseToDataSource(SQLParser.Table_atomContext tableAtom, JoinType joinType, Optional<SQLParser.Join_conditionContext> joinConditionContextCandidate) {
        DataSource dataSource = processTableAtom(tableAtom);
        dataSource.setJoinType(joinType);
        if (joinConditionContextCandidate.isPresent()) {
            processJoinCondition(dataSource, joinConditionContextCandidate.get());
        }
        return dataSource;
    }

    private void processJoinCondition(DataSource dataSource, SQLParser.Join_conditionContext joinConditionContext) {
        if (joinConditionContext.ON() != null) {
            Object joinCondition = processComplexBooleanExpression(joinConditionContext.complex_boolean_expression());
            dataSource.setJoinCondition(joinCondition);
        } else if (joinConditionContext.USING() != null) {
            List<String> joinColumns = joinConditionContext.columns_list().column_name().stream()
                    .map(cn -> processColumnName(cn, false, true).getExpression())
                    .filter(e -> e instanceof ColumnExpression)
                    .map(e -> ((ColumnExpression) e).getColumnName())
                    .collect(Collectors.toList());
            dataSource.getJoinColumns().addAll(joinColumns);
        } else {
            throw new UnsupportedOperationException("Unsupported join condition type");
        }
    }

    private DataSource processTableAtom(SQLParser.Table_atomContext tableAtom) {
        if (tableAtom.table_name() != null) {
            return processTable(tableAtom.table_name(), Optional.ofNullable(tableAtom.alias_clause()));
        } else if (tableAtom.LPAREN() != null) {
            return processTableReferences(tableAtom.table_references()).get();
        }
        throw new UnsupportedOperationException("Unsupported table atom type");
    }

    private DataSource processTable(SQLParser.Table_nameContext tableNameContext, Optional<SQLParser.Alias_clauseContext> aliasClauseContextCandidate) {
        String tableName = tableNameContext.ID().getText();
        String alias = aliasClauseContextCandidate.isPresent() ?
                aliasClauseContextCandidate.get().alias().ID().getText() :
                tableName;
        if (!getTableAliases().containsKey(alias)) {
            getTableAliases().put(alias, tableName);
        } else {
            errors.add(String.format("Duplicate alias \"%s\"", alias));
        }
        
        //Preparing available columns for the first time
        DataSource dataSource = new DataSource(alias);
        Map<String, List<String>> availableColumns = mergeAvailableColumns(getAvailableColumns(), getAvailableColumns(Optional.of(dataSource), Collections.emptyMap()));
        getAvailableColumns().clear();
        getAvailableColumns().putAll(availableColumns);
        return new DataSource(alias);
    }

    private void processSelectClause() {
        if (this.selectClauseContext.isPresent()) {
            this.selectClauseContext.get().select_expression().aliased_expression().stream().forEach(ae -> {
                AliasExpressionPair pair = processAliasedExpression(ae);
                selectionMap.put(pair.getAlias(), pair.getExpression());
            });
        }
    }

    private AliasExpressionPair processAliasedExpression(SQLParser.Aliased_expressionContext ctx) {
        Optional<SQLParser.Alias_clauseContext> aliasCtxCandidate = Optional.ofNullable(ctx.alias_clause());
        SQLParser.ExpressionContext expressionCtx = ctx.expression();
        AliasExpressionPair defaultAliasExpressionPair = processExpression(expressionCtx, true);
        String alias = getAliasOr(aliasCtxCandidate, defaultAliasExpressionPair.getAlias());
        return pair(alias, defaultAliasExpressionPair.getExpression());
    }

    private String getAliasOr(Optional<SQLParser.Alias_clauseContext> ctxCandidate, String value) {
        return ctxCandidate.isPresent() ? ctxCandidate.get().alias().getText() : value;
    }

    private AliasExpressionPair processExpression(SQLParser.ExpressionContext expression) {
        return processExpression(expression, false);
    }
    
    private AliasExpressionPair processExpression(SQLParser.ExpressionContext expression, boolean allowMultipleColumns) {
        if (expression.literal() != null) {
            return processLiteral(expression.literal());
        } else if (expression.column_name() != null) {
            return processColumnName(expression.column_name(), allowMultipleColumns, false);
        } else if (expression.function_call() != null) {
            return processFunctionCall(expression.function_call());
        } else if (expression.unary_arithmetic_operator() != null) {
            return processUnaryArithmeticExpression(expression.unary_arithmetic_operator(), expression.expression(0));
        } else if (expression.binary_arithmetic_operator() != null) {
            return processBinaryArithmeticExpression(expression.expression(0), expression.binary_arithmetic_operator(), expression.expression(1));
        }
        throw new UnsupportedOperationException("Unsupported expression type");
    }

    private AliasExpressionPair processLiteral(SQLParser.LiteralContext literal) {
        if (literal.STRING() != null) {
            String stringValue = stripApostrophes(literal.STRING().getText());
            return pair(stringValue, stringValue);
        } else if (literal.INT() != null) {
            Integer intValue = Integer.valueOf(literal.INT().getText());
            return pair(String.valueOf(intValue), intValue);
        } else if (literal.FLOAT() != null) {
            Float floatValue = Float.valueOf(literal.INT().getText());
            return pair(String.valueOf(floatValue), floatValue);
        } else if (literal.NULL() != null) {
            return pair("NULL", new Null());
        } else if (literal.TRUE() != null) {
            return pair("TRUE", true);
        } else if (literal.FALSE() != null) {
            return pair("FALSE", false);
        }
        throw new IllegalArgumentException("Unsupported literal type");
    }

    private static String stripApostrophes(String str) {
        final char APOSTROPHE = '\'';
        if (str.length() < 2) {
            return str;
        }
        String ret = str;
        if (ret.charAt(0) == APOSTROPHE) {
            ret = ret.substring(1);
        }
        if (ret.charAt(ret.length() - 1) == APOSTROPHE) {
            ret = ret.substring(0, ret.length() - 1);
        }
        return ret;
    }

    private AliasExpressionPair processColumnName(SQLParser.Column_nameContext columnNameContext, boolean allowMultipleColumns, boolean isUsingClause) {
        //Column name can be alias.* for concrete table or just * for all tables
        Optional<String> tableAliasCandidate = getTableAlias(columnNameContext);
        boolean selectAllColumns = columnNameContext.MULTIPLY() != null;
        String columnName = (columnNameContext.ID() != null) ? columnNameContext.ID().getText() : null;
        if (tableAliasCandidate.isPresent()) {
            String tableAlias = tableAliasCandidate.get();
            return processAliasedColumnName(tableAlias, columnName, selectAllColumns, allowMultipleColumns);
        } else {
            return processStandaloneColumnName(columnName, selectAllColumns, allowMultipleColumns, isUsingClause);
        }
    }
    
    private AliasExpressionPair processAliasedColumnName(String tableAlias, String columnName, boolean selectAllColumns, boolean allowMultipleColumns) {
        if (!getTableAliases().containsKey(tableAlias)) {
            errors.add(String.format("Table or alias \"%s\" does not exist", tableAlias));
            return emptyPair();
        }
        if (selectAllColumns) {
            if (!allowMultipleColumns) {
                errors.add(String.format("Selecting %s.* is not allowed in this context", tableAlias));
                return emptyPair();
            }
            return new AliasExpressionPair(String.format("%s.*", tableAlias), new ColumnExpression(ANY, tableAlias));
        }
        if (!getAvailableColumns().containsKey(columnName) || !getAvailableColumns().get(columnName).contains(tableAlias)) {
            errors.add(String.format("Column \"%s.%s\" is not available for selection", tableAlias, columnName));
            return emptyPair();
        }
        return new AliasExpressionPair(String.format("%s.%s", tableAlias, columnName), new ColumnExpression(columnName, tableAlias));
    }
    
    private AliasExpressionPair processStandaloneColumnName(String columnName, boolean selectAllColumns, boolean allowMultipleColumns, boolean isUsingClause) {
        if (selectAllColumns) {
            if (!allowMultipleColumns) {
                errors.add("Selecting * is not allowed in this context");
                return emptyPair();
            }
            return new AliasExpressionPair("*", new ColumnExpression());
        }
        if (!getAvailableColumns().containsKey(columnName)) {
            errors.add(String.format("Column \"%s\" is not available for selection", columnName));
            return emptyPair();
        }
        if (getAvailableColumns().get(columnName).size() > 1 && !isUsingClause) {
            errors.add(String.format("Ambiguous column name \"%s\": use aliases to specify destination table", columnName));
            return emptyPair();
        }
//        String tableAlias = getAvailableColumns().get(columnName).get(0);
        return new AliasExpressionPair(columnName, new ColumnExpression(columnName));
    }
    
    private Optional<String> getTableAlias(SQLParser.Column_nameContext columnName) {
        if (columnName.alias() != null) {
            return Optional.of(columnName.alias().ID().getText());
        } else if (columnName.table_name() != null) {
            return Optional.of(columnName.table_name().ID().getText());
        }
        return Optional.empty();
    }

    private void foreachExpression(List<SQLParser.ExpressionContext> expressions, Consumer<AliasExpressionPair> action) {
        expressions.stream().map(this::processExpression).forEach(action::accept);
    }
    
    private AliasExpressionPair processFunctionCall(SQLParser.Function_callContext functionCall) {
        String functionName = functionCall.ID().getText();
        List<Object> argExpressions = new ArrayList<>();
        List<String> argDefaultAliases = new ArrayList<>();
        if (functionCall.expressions() != null) {
            foreachExpression(functionCall.expressions().expression(), p -> {
                argDefaultAliases.add(p.getAlias());
                argExpressions.add(p.getExpression());
            });
        }
        String joinedArgs = argDefaultAliases.stream().collect(Collectors.joining(", "));
        String defaultAlias = String.format("%s(%s)", functionName, joinedArgs);
        return pair(defaultAlias, new FunctionExpression(functionName, argExpressions));
    }

    private AliasExpressionPair processUnaryArithmeticExpression(
            SQLParser.Unary_arithmetic_operatorContext unaryArithmeticOperatorContext,
            SQLParser.ExpressionContext expressionContext
    ) {
        UnaryArithmeticOperator unaryArithmeticOperator = determineUnaryArithmeticOperator(unaryArithmeticOperatorContext);
        AliasExpressionPair expression = processExpression(expressionContext);
        return new AliasExpressionPair(
                unaryArithmeticOperator.getText() + expression.getAlias(),
                new UnaryArithmeticExpression(expression.getExpression(), unaryArithmeticOperator)
        );
    }

    private UnaryArithmeticOperator determineUnaryArithmeticOperator(SQLParser.Unary_arithmetic_operatorContext unaryArithmeticOperator) {
        if (unaryArithmeticOperator.BIT_NOT() != null) {
            return UnaryArithmeticOperator.BIT_NOT;
        } else if (unaryArithmeticOperator.PLUS() != null) {
            return UnaryArithmeticOperator.PLUS;
        } else if (unaryArithmeticOperator.MINUS() != null) {
            return UnaryArithmeticOperator.MINUS;
        }
        throw new UnsupportedOperationException("Unknown unary arithmetic operator");
    }

    private AliasExpressionPair processBinaryArithmeticExpression(
            SQLParser.ExpressionContext leftExpressionContext,
            SQLParser.Binary_arithmetic_operatorContext binaryArithmeticOperatorContext,
            SQLParser.ExpressionContext rightExpressionContext
    ) {
        BinaryArithmeticOperator binaryArithmeticOperator = determineBinaryArithmeticOperator(binaryArithmeticOperatorContext);
        AliasExpressionPair leftExpression = processExpression(leftExpressionContext);
        AliasExpressionPair rightExpression = processExpression(rightExpressionContext);
        return new AliasExpressionPair(
                String.format("%s %s %s", leftExpression.getAlias(), binaryArithmeticOperator.getText(), rightExpression.getAlias()),
                new BinaryArithmeticExpression(leftExpression.getExpression(), binaryArithmeticOperator, rightExpression.getExpression())
        );
    }

    private BinaryArithmeticOperator determineBinaryArithmeticOperator(SQLParser.Binary_arithmetic_operatorContext binaryArithmeticOperator) {
        if (binaryArithmeticOperator.PLUS() != null) {
            return BinaryArithmeticOperator.PLUS;
        } else if (binaryArithmeticOperator.MINUS() != null) {
            return BinaryArithmeticOperator.MINUS;
        } else if (binaryArithmeticOperator.MULTIPLY() != null) {
            return BinaryArithmeticOperator.MULTIPLY;
        } else if (binaryArithmeticOperator.DIVIDE() != null) {
            return BinaryArithmeticOperator.DIVIDE;
        } else if (binaryArithmeticOperator.MOD() != null) {
            return BinaryArithmeticOperator.MOD;
        } else if (binaryArithmeticOperator.BIT_AND() != null) {
            return BinaryArithmeticOperator.BIT_AND;
        } else if (binaryArithmeticOperator.BIT_OR() != null) {
            return BinaryArithmeticOperator.BIT_OR;
        } else if (binaryArithmeticOperator.BIT_XOR() != null) {
            return BinaryArithmeticOperator.BIT_XOR;
        } else if (binaryArithmeticOperator.SHIFT_LEFT() != null) {
            return BinaryArithmeticOperator.SHIFT_LEFT;
        } else if (binaryArithmeticOperator.SHIFT_RIGHT() != null) {
            return BinaryArithmeticOperator.SHIFT_RIGHT;
        }
        throw new UnsupportedOperationException("Unknown binary arithmetic operator");
    }

    @Override
    public void exitHaving_clause(SQLParser.Having_clauseContext ctx) {
        havingClauseContext = Optional.of(ctx);
    }

    @Override
    public void exitGroup_clause(SQLParser.Group_clauseContext ctx) {
        groupByClauseContext = Optional.of(ctx);
    }

    @Override
    public void exitOrder_clause(SQLParser.Order_clauseContext ctx) {
        orderByClauseContext = Optional.of(ctx);
    }

    @Override
    public void exitLimit_clause(SQLParser.Limit_clauseContext ctx) {
        if (ctx.offset() != null) {
            Integer limitOffset = Integer.valueOf(ctx.offset().INT().getText());
            if (limitOffset < 0) {
                errors.add(String.format("Limit offset count should be less than or equal to zero but %d was given", limitOffset));
            } else {
                this.limitOffset = Optional.of(limitOffset);
            }
        }
        Integer limitCount = Integer.valueOf(ctx.row_count().INT().getText());
        if (limitCount < 0) {
            errors.add(String.format("Limit count should be less than or equal to zero but %d was given", limitCount));
        } else {
            this.limitCount = Optional.of(limitCount);
        }
    }

    @Override
    public Map<String, Object> getSelectionMap() {
        return selectionMap;
    }

    @Override
    public Optional<DataSource> getDataSource() {
        return dataSource;
    }

    @Override
    public Map<String, String> getTableAliases() {
        return tableAliases;
    }

    @Override
    public Map<String, List<String>> getAvailableColumns() {
        return availableColumns;
    }

    @Override
    public Optional<Object> getWhereExpression() {
        return whereExpression;
    }

    @Override
    public List<Object> getGroupByExpressions() {
        return groupByExpressions;
    }

    @Override
    public List<OrderExpression> getOrderByExpressions() {
        return orderByExpressions;
    }

    @Override
    public Optional<Object> getHavingExpression() {
        return havingExpression;
    }

    @Override
    public Optional<Integer> getLimitCount() {
        return limitCount;
    }

    @Override
    public Optional<Integer> getLimitOffset() {
        return limitOffset;
    }
}
