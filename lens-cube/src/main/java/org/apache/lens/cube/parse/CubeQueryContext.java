/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.lens.cube.parse;

import static org.apache.hadoop.hive.ql.parse.HiveParser.Identifier;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_TABLE_OR_COL;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_TMP_FILE;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import org.apache.lens.cube.metadata.*;
import org.apache.lens.cube.parse.CandidateTablePruneCause.CandidateTablePruneCode;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.*;

import org.codehaus.jackson.map.ObjectMapper;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import lombok.AllArgsConstructor;

import lombok.Data;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

public class CubeQueryContext implements TrackQueriedColumns {
  public static final String TIME_RANGE_FUNC = "time_range_in";
  public static final String NOW = "now";
  public static final String DEFAULT_TABLE = "_default_";
  public static final Log LOG = LogFactory.getLog(CubeQueryContext.class.getName());
  private final ASTNode ast;
  @Getter
  private final QB qb;
  private String clauseName = null;
  @Getter
  private final Configuration conf;

  @Getter
  private final List<TimeRange> timeRanges;

  // metadata
  @Getter
  private CubeInterface cube;
  // Dimensions accessed in the query, contains dimensions that are joinchain destinations
  // of the joinchains used.
  @Getter
  protected Set<Dimension> dimensions = new HashSet<Dimension>();
  // The dimensions accessed by name in the query directly, via tablename.columname
  @Getter
  protected Set<Dimension> nonChainedDimensions = new HashSet<Dimension>();
  // Joinchains accessed in the query
  @Getter
  protected Map<String, JoinChain> joinchains = new HashMap<String, JoinChain>();
  private final Set<String> queriedDimAttrs = new HashSet<String>();

  @Getter
  private final Set<String> queriedMsrs = new HashSet<String>();

  @Getter
  private final Set<String> queriedExprs = new HashSet<String>();

  private final Set<String> queriedTimeDimCols = new LinkedHashSet<String>();

  @Getter
  private final Set<String> queriedExprsWithMeasures = new HashSet<String>();

  @Getter
  // Mapping of a qualified column name to its table alias
  private final Map<String, String> colToTableAlias = new HashMap<String, String>();

  @Getter()
  private final Set<Set<CandidateFact>> candidateFactSets = new HashSet<Set<CandidateFact>>();

  // would be added through join chains and de-normalized resolver
  protected Map<Dimension, OptionalDimCtx> optionalDimensions = new HashMap<Dimension, OptionalDimCtx>();

  // Alias to table object mapping of tables accessed in this query
  @Getter
  private final Map<String, AbstractCubeTable> cubeTbls = new HashMap<String, AbstractCubeTable>();
  // Alias name to fields queried
  @Getter
  private final Map<String, Set<String>> tblAliasToColumns = new HashMap<String, Set<String>>();
  // Mapping of an expression to its column alias in the query
  @Getter
  private final Map<String, String> exprToAlias = new HashMap<String, String>();
  @Getter
  private final List<String> selectAliases = new ArrayList<String>();
  @Getter
  private final List<String> selectFinalAliases = new ArrayList<String>();
  // All aggregate expressions in the query
  @Getter
  private final Set<String> aggregateExprs = new HashSet<String>();
  // Join conditions used in all join expressions
  @Getter
  private final Map<QBJoinTree, String> joinConds = new HashMap<QBJoinTree, String>();

  // storage specific
  @Getter
  protected final Set<CandidateFact> candidateFacts = new HashSet<CandidateFact>();
  @Getter
  protected final Map<Dimension, Set<CandidateDim>> candidateDims = new HashMap<Dimension, Set<CandidateDim>>();

  // query trees
  @Getter
  private ASTNode havingAST;
  @Getter
  private ASTNode selectAST;

  // Will be set after the Fact is picked and time ranges replaced
  @Getter
  @Setter
  private ASTNode whereAST;

  @Getter
  private ASTNode orderByAST;
  // Setter is used in promoting the select when promotion is on.
  @Getter
  @Setter
  private ASTNode groupByAST;
  @Getter
  private CubeMetastoreClient metastoreClient;
  @Getter
  @Setter
  private JoinResolver.AutoJoinContext autoJoinCtx;
  @Getter
  @Setter
  private ExpressionResolver.ExpressionResolverContext exprCtx;
  @Getter
  @Setter
  private DenormalizationResolver.DenormalizationContext deNormCtx;
  @Getter
  private PruneCauses<CubeFactTable> factPruningMsgs =
    new PruneCauses<CubeFactTable>();
  @Getter
  private Map<Dimension, PruneCauses<CubeDimensionTable>> dimPruningMsgs =
    new HashMap<Dimension, PruneCauses<CubeDimensionTable>>();

  public CubeQueryContext(ASTNode ast, QB qb, Configuration queryConf, HiveConf metastoreConf)
    throws SemanticException {
    this.ast = ast;
    this.qb = qb;
    this.conf = queryConf;
    this.clauseName = getClause();
    this.timeRanges = new ArrayList<TimeRange>();
    try {
      metastoreClient = CubeMetastoreClient.getInstance(metastoreConf);
    } catch (HiveException e) {
      throw new SemanticException(e);
    }
    if (qb.getParseInfo().getWhrForClause(clauseName) != null) {
      this.whereAST = qb.getParseInfo().getWhrForClause(clauseName);
    }
    if (qb.getParseInfo().getHavingForClause(clauseName) != null) {
      this.havingAST = qb.getParseInfo().getHavingForClause(clauseName);
    }
    if (qb.getParseInfo().getOrderByForClause(clauseName) != null) {
      this.orderByAST = qb.getParseInfo().getOrderByForClause(clauseName);
    }
    if (qb.getParseInfo().getGroupByForClause(clauseName) != null) {
      this.groupByAST = qb.getParseInfo().getGroupByForClause(clauseName);
    }
    if (qb.getParseInfo().getSelForClause(clauseName) != null) {
      this.selectAST = qb.getParseInfo().getSelForClause(clauseName);
    }

    for (ASTNode aggrTree : qb.getParseInfo().getAggregationExprsForClause(clauseName).values()) {
      String aggr = HQLParser.getString(aggrTree);
      aggregateExprs.add(aggr);
    }

    extractMetaTables();
  }

  public boolean hasCubeInQuery() {
    return cube != null;
  }

  public boolean hasDimensionInQuery() {
    return dimensions != null && !dimensions.isEmpty();
  }

  private void extractMetaTables() throws SemanticException {
    List<String> tabAliases = new ArrayList<String>(qb.getTabAliases());
    Set<String> missing = new HashSet<String>();
    for (String alias : tabAliases) {
      boolean added = addQueriedTable(alias);
      if (!added) {
        missing.add(alias);
      }
    }
    for (String alias : missing) {
      // try adding them as joinchains
      boolean added = addJoinChain(alias, false);
      if (!added) {
        LOG.info("Queried tables do not exist. Missing table:" + alias);
        throw new SemanticException(ErrorMsg.NEITHER_CUBE_NOR_DIMENSION);
      }
    }
  }

  private boolean addJoinChain(String alias, boolean isOptional) throws SemanticException {
    boolean retVal = false;
    String aliasLowerCaseStr = alias.toLowerCase();
    JoinChain joinchain = null;

    if (getCube() != null) {
      JoinChain chainByName = getCube().getChainByName(aliasLowerCaseStr);
      if (chainByName != null) {
        joinchain = chainByName;
        retVal = true;
      }
    }

    if (!retVal) {
      for (Dimension table : dimensions) {
        JoinChain chainByName = table.getChainByName(aliasLowerCaseStr);
        if (chainByName != null) {
          joinchain = chainByName;
          retVal = true;
          break;
        }
      }
    }

    if (retVal) {
      joinchains.put(aliasLowerCaseStr, new JoinChain(joinchain));
      String destTable = joinchain.getDestTable();
      boolean added = addQueriedTable(alias, destTable, isOptional, true);
      if (!added) {
        LOG.info("Queried tables do not exist. Missing tables:" + destTable);
        throw new SemanticException(ErrorMsg.NEITHER_CUBE_NOR_DIMENSION);
      }
      LOG.info("Added join chain for " + destTable);
      return true;
    }

    return retVal;
  }

  public boolean addQueriedTable(String alias) throws SemanticException {
    return addQueriedTable(alias, false);
  }

  private boolean addQueriedTable(String alias, boolean isOptional) throws SemanticException {
    String tblName = qb.getTabNameForAlias(alias);
    if (tblName == null) {
      tblName = alias;
    }
    boolean added = addQueriedTable(alias, tblName, isOptional, false);
    if (!added) {
      // try adding as joinchain
      added = addJoinChain(alias, isOptional);
    }
    return added;
  }

  /**
   * destination table  : a table whose columns are getting queried intermediate table : a table which is only used as a
   * link between cube and destination table
   *
   * @param alias
   * @param tblName
   * @param isOptional         pass false when it's a destination table pass true when it's an intermediate table when
   *                           join chain destination is being added, this will be false.
   * @param isChainedDimension pass true when you're adding the dimension as a joinchain destination, pass false when
   *                           this table is mentioned by name in the user query
   * @return true if added
   * @throws SemanticException
   */
  private boolean addQueriedTable(String alias, String tblName, boolean isOptional, boolean isChainedDimension)
    throws SemanticException {
    alias = alias.toLowerCase();
    if (cubeTbls.containsKey(alias)) {
      return true;
    }
    try {
      if (metastoreClient.isCube(tblName)) {
        if (cube != null) {
          if (!cube.getName().equalsIgnoreCase(tblName)) {
            throw new SemanticException(ErrorMsg.MORE_THAN_ONE_CUBE, cube.getName(), tblName);
          }
        }
        cube = metastoreClient.getCube(tblName);
        cubeTbls.put(alias, (AbstractCubeTable) cube);
      } else if (metastoreClient.isDimension(tblName)) {
        Dimension dim = metastoreClient.getDimension(tblName);
        if (!isOptional) {
          dimensions.add(dim);
        }
        if (!isChainedDimension) {
          nonChainedDimensions.add(dim);
        }
        cubeTbls.put(alias, dim);
      } else {
        return false;
      }
    } catch (HiveException e) {
      return false;
    }
    return true;
  }

  public boolean isAutoJoinResolved() {
    return autoJoinCtx != null && autoJoinCtx.isJoinsResolved();
  }

  public Cube getBaseCube() {
    if (cube instanceof Cube) {
      return (Cube) cube;
    }
    return ((DerivedCube) cube).getParent();
  }

  public Set<String> getPartitionColumnsQueried() {
    Set<String> partsQueried = Sets.newHashSet();
    for (TimeRange range : getTimeRanges()) {
      partsQueried.add(range.getPartitionColumn());
    }
    return partsQueried;
  }

  // map of ref column in query to set of Dimension that have the column - which are added as optional dims
  @Getter
  private Map<String, Set<Dimension>>  refColToDim = Maps.newHashMap();

  public void updateRefColDim(String col, Dimension dim) {
    Set<Dimension> refDims = refColToDim.get(col.toLowerCase());
    if (refDims == null) {
      refDims = Sets.newHashSet();
      refColToDim.put(col.toLowerCase(), refDims);
    }
    refDims.add(dim);
  }

  @Data
  @AllArgsConstructor
  static class QueriedExprColumn {
    private String exprCol;
    private String alias;
  }
  // map of expression column in query to set of Dimension that are accessed in the expression column - which are added
  // as optional dims
  @Getter
  private Map<QueriedExprColumn, Set<Dimension>>  exprColToDim = Maps.newHashMap();

  public void updateExprColDim(String tblAlias, String col, Dimension dim) {

    QueriedExprColumn qexpr = new QueriedExprColumn(col, tblAlias);
    Set<Dimension> exprDims = exprColToDim.get(qexpr);
    if (exprDims == null) {
      exprDims = Sets.newHashSet();
      exprColToDim.put(qexpr, exprDims);
    }
    exprDims.add(dim);
  }

  // Holds the context of optional dimension
  // A dimension is optional if it is not queried directly by the user, but is
  // required by a candidate table to get a denormalized field from reference
  // or required in a join chain
  @ToString
  static class OptionalDimCtx {
    OptionalDimCtx() {
    }

    Set<String> colQueried = new HashSet<String>();
    Set<CandidateTable> requiredForCandidates = new HashSet<CandidateTable>();
    boolean isRequiredInJoinChain = false;
  }

  public void addOptionalJoinDimTable(String alias, boolean isRequired) throws SemanticException {
    addOptionalDimTable(alias, null, isRequired, null, false, (String[])null);
  }

  public void addOptionalExprDimTable(String dimAlias, String queriedExpr, String srcTableAlias,
    CandidateTable candidate, String... cols) throws SemanticException {
    addOptionalDimTable(dimAlias, candidate, false, queriedExpr, false, srcTableAlias, cols);
  }

  public void addOptionalDimTable(String alias, CandidateTable candidate, boolean isRequiredInJoin, String cubeCol,
    boolean isRef, String... cols) throws SemanticException {
    addOptionalDimTable(alias, candidate, isRequiredInJoin, cubeCol, true, null, cols);
  }

  private void addOptionalDimTable(String alias, CandidateTable candidate, boolean isRequiredInJoin, String cubeCol,
    boolean isRef, String tableAlias, String... cols) throws SemanticException {
    alias = alias.toLowerCase();
    try {
      if (!addQueriedTable(alias, true)) {
        throw new SemanticException("Could not add queried table or chain:" + alias);
      }
      Dimension dim = (Dimension) cubeTbls.get(alias);
      OptionalDimCtx optDim = optionalDimensions.get(dim);
      if (optDim == null) {
        optDim = new OptionalDimCtx();
        optionalDimensions.put(dim, optDim);
      }
      if (cols != null && candidate != null) {
        for (String col : cols) {
          optDim.colQueried.add(col);
        }
        optDim.requiredForCandidates.add(candidate);
      }
      if (cubeCol != null) {
        if (isRef) {
          updateRefColDim(cubeCol, dim);
        } else {
          updateExprColDim(tableAlias, cubeCol, dim);
        }
      }
      if (!optDim.isRequiredInJoinChain) {
        optDim.isRequiredInJoinChain = isRequiredInJoin;
      }
      LOG.info("Adding optional dimension:" + dim + " optDim:" + optDim
        + (cubeCol == null ? "" : " for column:" + cubeCol + " isRef:" + isRef));
    } catch (HiveException e) {
      throw new SemanticException(e);
    }
  }

  public AbstractCubeTable getCubeTableForAlias(String alias) {
    return cubeTbls.get(alias);
  }

  private String getClause() {
    if (clauseName == null) {
      TreeSet<String> ks = new TreeSet<String>(qb.getParseInfo().getClauseNames());
      clauseName = ks.first();
    }
    return clauseName;
  }

  public Map<Dimension, Set<CandidateDim>> getCandidateDimTables() {
    return candidateDims;
  }

  public void addFactPruningMsgs(CubeFactTable fact, CandidateTablePruneCause factPruningMsg) {
    factPruningMsgs.addPruningMsg(fact, factPruningMsg);
  }

  public void addDimPruningMsgs(Dimension dim, CubeDimensionTable dimtable, CandidateTablePruneCause msg) {
    PruneCauses<CubeDimensionTable> dimMsgs = dimPruningMsgs.get(dim);
    if (dimMsgs == null) {
      dimMsgs = new PruneCauses<CubeDimensionTable>();
      dimPruningMsgs.put(dim, dimMsgs);
    }
    dimMsgs.addPruningMsg(dimtable, msg);
  }

  public String getAliasForTableName(Named named) {
    return getAliasForTableName(named.getName());
  }

  public String getAliasForTableName(String tableName) {
    for (String alias : qb.getTabAliases()) {
      String table = qb.getTabNameForAlias(alias);
      if (table != null && table.equalsIgnoreCase(tableName)) {
        return alias;
      }
    }
    // get alias from cubeTbls
    for (Map.Entry<String, AbstractCubeTable> cubeTblEntry : cubeTbls.entrySet()) {
      if (cubeTblEntry.getValue().getName().equalsIgnoreCase(tableName)) {
        return cubeTblEntry.getKey();
      }
    }
    return tableName.toLowerCase();
  }

  public void print() {
    StringBuilder builder = new StringBuilder();
    builder.append("ASTNode:" + ast.dump() + "\n");
    builder.append("QB:");
    builder.append("\n numJoins:" + qb.getNumJoins());
    builder.append("\n numGbys:" + qb.getNumGbys());
    builder.append("\n numSels:" + qb.getNumSels());
    builder.append("\n numSelDis:" + qb.getNumSelDi());
    builder.append("\n aliasToTabs:");
    Set<String> tabAliases = qb.getTabAliases();
    for (String alias : tabAliases) {
      builder.append("\n\t" + alias + ":" + qb.getTabNameForAlias(alias));
    }
    builder.append("\n aliases:");
    for (String alias : qb.getAliases()) {
      builder.append(alias);
      builder.append(", ");
    }
    builder.append("id:" + qb.getId());
    builder.append("isQuery:" + qb.getIsQuery());
    builder.append("\n QBParseInfo");
    QBParseInfo parseInfo = qb.getParseInfo();
    builder.append("\n isSubQ: " + parseInfo.getIsSubQ());
    builder.append("\n alias: " + parseInfo.getAlias());
    if (parseInfo.getJoinExpr() != null) {
      builder.append("\n joinExpr: " + parseInfo.getJoinExpr().dump());
    }
    builder.append("\n hints: " + parseInfo.getHints());
    builder.append("\n aliasToSrc: ");
    for (String alias : tabAliases) {
      builder.append("\n\t" + alias + ": " + parseInfo.getSrcForAlias(alias).dump());
    }
    TreeSet<String> clauses = new TreeSet<String>(parseInfo.getClauseNames());
    for (String clause : clauses) {
      builder.append("\n\t" + clause + ": " + parseInfo.getClauseNamesForDest());
    }
    String clause = clauses.first();
    if (parseInfo.getWhrForClause(clause) != null) {
      builder.append("\n whereexpr: " + parseInfo.getWhrForClause(clause).dump());
    }
    if (parseInfo.getGroupByForClause(clause) != null) {
      builder.append("\n groupby expr: " + parseInfo.getGroupByForClause(clause).dump());
    }
    if (parseInfo.getSelForClause(clause) != null) {
      builder.append("\n sel expr: " + parseInfo.getSelForClause(clause).dump());
    }
    if (parseInfo.getHavingForClause(clause) != null) {
      builder.append("\n having expr: " + parseInfo.getHavingForClause(clause).dump());
    }
    if (parseInfo.getDestLimit(clause) != null) {
      builder.append("\n limit: " + parseInfo.getDestLimit(clause));
    }
    if (parseInfo.getAllExprToColumnAlias() != null && !parseInfo.getAllExprToColumnAlias().isEmpty()) {
      builder.append("\n exprToColumnAlias:");
      for (Map.Entry<ASTNode, String> entry : parseInfo.getAllExprToColumnAlias().entrySet()) {
        builder.append("\n\t expr: " + entry.getKey().dump() + " ColumnAlias: " + entry.getValue());
      }
    }
    if (parseInfo.getAggregationExprsForClause(clause) != null) {
      builder.append("\n aggregateexprs:");
      for (Map.Entry<String, ASTNode> entry : parseInfo.getAggregationExprsForClause(clause).entrySet()) {
        builder.append("\n\t key: " + entry.getKey() + " expr: " + entry.getValue().dump());
      }
    }
    if (parseInfo.getDistinctFuncExprsForClause(clause) != null) {
      builder.append("\n distinctFuncExprs:");
      for (ASTNode entry : parseInfo.getDistinctFuncExprsForClause(clause)) {
        builder.append("\n\t expr: " + entry.dump());
      }
    }

    if (qb.getQbJoinTree() != null) {
      builder.append("\n\n JoinTree");
      QBJoinTree joinTree = qb.getQbJoinTree();
      printJoinTree(joinTree, builder);
    }

    if (qb.getParseInfo().getDestForClause(clause) != null) {
      builder.append("\n Destination:");
      builder.append("\n\t dest expr:" + qb.getParseInfo().getDestForClause(clause).dump());
    }
    LOG.info(builder.toString());
  }

  void printJoinTree(QBJoinTree joinTree, StringBuilder builder) {
    builder.append("leftAlias:" + joinTree.getLeftAlias());
    if (joinTree.getLeftAliases() != null) {
      builder.append("\n leftAliases:");
      for (String alias : joinTree.getLeftAliases()) {
        builder.append("\n\t " + alias);
      }
    }
    if (joinTree.getRightAliases() != null) {
      builder.append("\n rightAliases:");
      for (String alias : joinTree.getRightAliases()) {
        builder.append("\n\t " + alias);
      }
    }
    if (joinTree.getJoinSrc() != null) {
      builder.append("\n JoinSrc: {");
      printJoinTree(joinTree.getJoinSrc(), builder);
      builder.append("\n }");
    }
    if (joinTree.getBaseSrc() != null) {
      builder.append("\n baseSrcs:");
      for (String src : joinTree.getBaseSrc()) {
        builder.append("\n\t " + src);
      }
    }
    builder.append("\n noOuterJoin: " + joinTree.getNoOuterJoin());
    builder.append("\n noSemiJoin: " + joinTree.getNoSemiJoin());
    builder.append("\n mapSideJoin: " + joinTree.isMapSideJoin());
    if (joinTree.getJoinCond() != null) {
      builder.append("\n joinConds:");
      for (JoinCond cond : joinTree.getJoinCond()) {
        builder.append("\n\t left: " + cond.getLeft() + " right: " + cond.getRight() + " type:" + cond.getJoinType()
          + " preserved:" + cond.getPreserved());
      }
    }
  }

  public String getSelectTree() {
    return HQLParser.getString(selectAST);
  }

  public String getWhereTree() {
    if (whereAST != null) {
      return HQLParser.getString(whereAST);
    }
    return null;
  }

  public String getGroupByTree() {
    if (groupByAST != null) {
      return HQLParser.getString(groupByAST);
    }
    return null;
  }

  public String getHavingTree() {
    if (havingAST != null) {
      return HQLParser.getString(havingAST);
    }
    return null;
  }

  public ASTNode getJoinTree() {
    return qb.getParseInfo().getJoinExpr();
  }

  public QBJoinTree getQBJoinTree() {
    return qb.getQbJoinTree();
  }

  public String getOrderByTree() {
    if (orderByAST != null) {
      return HQLParser.getString(orderByAST);
    }
    return null;
  }

  public Integer getLimitValue() {
    return qb.getParseInfo().getDestLimit(getClause());
  }

  private String getStorageStringWithAlias(CandidateFact fact, Map<Dimension, CandidateDim> dimsToQuery, String alias) {
    if (cubeTbls.get(alias) instanceof CubeInterface) {
      return fact.getStorageString(alias);
    } else {
      return dimsToQuery.get(cubeTbls.get(alias)).getStorageString(alias);
    }
  }

  private String getWhereClauseWithAlias(Map<Dimension, CandidateDim> dimsToQuery, String alias) {
    return StorageUtil.getWhereClause(dimsToQuery.get(cubeTbls.get(alias)), alias);
  }

  String getQBFromString(CandidateFact fact, Map<Dimension, CandidateDim> dimsToQuery) throws SemanticException {
    String fromString = null;
    if (getJoinTree() == null) {
      if (cube != null) {
        fromString = fact.getStorageString(getAliasForTableName(cube.getName()));
      } else {
        if (dimensions.size() != 1) {
          throw new SemanticException(ErrorMsg.NO_JOIN_CONDITION_AVAIABLE);
        }
        Dimension dim = dimensions.iterator().next();
        fromString = dimsToQuery.get(dim).getStorageString(getAliasForTableName(dim.getName()));
      }
    } else {
      StringBuilder builder = new StringBuilder();
      getQLString(qb.getQbJoinTree(), builder, fact, dimsToQuery);
      fromString = builder.toString();
    }
    return fromString;
  }

  private void getQLString(QBJoinTree joinTree, StringBuilder builder, CandidateFact fact,
    Map<Dimension, CandidateDim> dimsToQuery) throws SemanticException {
    String joiningTable = null;
    if (joinTree.getBaseSrc()[0] == null) {
      if (joinTree.getJoinSrc() != null) {
        getQLString(joinTree.getJoinSrc(), builder, fact, dimsToQuery);
      }
    } else { // (joinTree.getBaseSrc()[0] != null){
      String alias = joinTree.getBaseSrc()[0].toLowerCase();
      builder.append(getStorageStringWithAlias(fact, dimsToQuery, alias));
      if (joinTree.getJoinCond()[0].getJoinType().equals(JoinType.RIGHTOUTER)) {
        joiningTable = alias;
      }
    }
    if (joinTree.getJoinCond() != null) {
      builder.append(JoinResolver.getJoinTypeStr(joinTree.getJoinCond()[0].getJoinType()));
      builder.append(" JOIN ");
    }
    if (joinTree.getBaseSrc()[1] == null) {
      if (joinTree.getJoinSrc() != null) {
        getQLString(joinTree.getJoinSrc(), builder, fact, dimsToQuery);
      }
    } else { // (joinTree.getBaseSrc()[1] != null){
      String alias = joinTree.getBaseSrc()[1].toLowerCase();
      builder.append(getStorageStringWithAlias(fact, dimsToQuery, alias));
      if (joinTree.getJoinCond()[0].getJoinType().equals(JoinType.LEFTOUTER)) {
        joiningTable = alias;
      }
    }

    String joinCond = joinConds.get(joinTree);
    if (joinCond != null) {
      builder.append(" ON ");
      builder.append(joinCond);
      if (joiningTable != null) {
        // assuming the joining table to be dimension table
        DimOnlyHQLContext.appendWhereClause(builder, getWhereClauseWithAlias(dimsToQuery, joiningTable), true);
        dimsToQuery.get(cubeTbls.get(joiningTable)).setWhereClauseAdded();
      }
    } else {
      throw new SemanticException(ErrorMsg.NO_JOIN_CONDITION_AVAIABLE);
    }
  }

  void setNonexistingParts(Map<String, Set<String>> nonExistingParts) throws SemanticException {
    if (!nonExistingParts.isEmpty()) {
      ByteArrayOutputStream out = null;
      String partsStr;
      try {
        ObjectMapper mapper = new ObjectMapper();
        out = new ByteArrayOutputStream();
        mapper.writeValue(out, nonExistingParts);
        partsStr = out.toString("UTF-8");
      } catch (Exception e) {
        throw new SemanticException("Error writing non existing parts", e);
      } finally {
        if (out != null) {
          try {
            out.close();
          } catch (IOException e) {
            throw new SemanticException(e);
          }
        }
      }
      conf.set(CubeQueryConfUtil.NON_EXISTING_PARTITIONS, partsStr);
    } else {
      conf.unset(CubeQueryConfUtil.NON_EXISTING_PARTITIONS);
    }
  }

  public String getNonExistingParts() {
    return conf.get(CubeQueryConfUtil.NON_EXISTING_PARTITIONS);
  }

  private Map<Dimension, CandidateDim> pickCandidateDimsToQuery(Set<Dimension> dimensions) throws SemanticException {
    Map<Dimension, CandidateDim> dimsToQuery = new HashMap<Dimension, CandidateDim>();
    if (!dimensions.isEmpty()) {
      for (Dimension dim : dimensions) {
        if (candidateDims.get(dim) != null && candidateDims.get(dim).size() > 0) {
          CandidateDim cdim = candidateDims.get(dim).iterator().next();
          LOG.info("Available candidate dims are:" + candidateDims.get(dim) + ", picking up " + cdim.dimtable
            + " for querying");
          dimsToQuery.put(dim, cdim);
        } else {
          String reason = "";
          if (dimPruningMsgs.get(dim) != null && !dimPruningMsgs.get(dim).isEmpty()) {
            ByteArrayOutputStream out = null;
            try {
              ObjectMapper mapper = new ObjectMapper();
              out = new ByteArrayOutputStream();
              mapper.writeValue(out, dimPruningMsgs.get(dim).getJsonObject());
              reason = out.toString("UTF-8");
            } catch (Exception e) {
              throw new SemanticException("Error writing dim pruning messages", e);
            } finally {
              if (out != null) {
                try {
                  out.close();
                } catch (IOException e) {
                  throw new SemanticException(e);
                }
              }
            }
          }
          throw new SemanticException(ErrorMsg.NO_CANDIDATE_DIM_AVAILABLE, dim.getName(), reason);
        }
      }
    }

    return dimsToQuery;
  }

  private Set<CandidateFact> pickCandidateFactToQuery() throws SemanticException {
    Set<CandidateFact> facts = null;
    if (hasCubeInQuery()) {
      if (candidateFactSets.size() > 0) {
        facts = candidateFactSets.iterator().next();
        LOG.info("Available candidate facts:" + candidateFactSets + ", picking up " + facts + " for querying");
      } else {
        String reason = "";
        if (!factPruningMsgs.isEmpty()) {
          ByteArrayOutputStream out = null;
          try {
            ObjectMapper mapper = new ObjectMapper();
            out = new ByteArrayOutputStream();
            mapper.writeValue(out, factPruningMsgs.getJsonObject());
            reason = out.toString("UTF-8");
          } catch (Exception e) {
            throw new SemanticException("Error writing fact pruning messages", e);
          } finally {
            if (out != null) {
              try {
                out.close();
              } catch (IOException e) {
                throw new SemanticException(e);
              }
            }
          }
        }
        throw new SemanticException(ErrorMsg.NO_CANDIDATE_FACT_AVAILABLE, reason);
      }
    }
    return facts;
  }

  private HQLContextInterface hqlContext;
  @Getter private Collection<CandidateFact> pickedFacts;
  @Getter private Collection<CandidateDim> pickedDimTables;

  public String toHQL() throws SemanticException {
    Set<CandidateFact> cfacts = pickCandidateFactToQuery();
    Map<Dimension, CandidateDim> dimsToQuery = pickCandidateDimsToQuery(dimensions);
    if (autoJoinCtx != null) {
      // prune join paths for picked fact and dimensions
      autoJoinCtx.pruneAllPaths(cube, cfacts, dimsToQuery);
    }

    Map<CandidateFact, Set<Dimension>> factDimMap = new HashMap<CandidateFact, Set<Dimension>>();
    if (cfacts != null) {
      if (cfacts.size() > 1) {
        // copy ASTs for each fact
        for (CandidateFact cfact : cfacts) {
          cfact.copyASTs(this);
          cfact.updateTimeranges(this);
          factDimMap.put(cfact, new HashSet<Dimension>(dimsToQuery.keySet()));
        }
      } else {
        SingleFactHQLContext.addRangeClauses(this, cfacts.iterator().next());
      }
    }

    // pick dimension tables required during expression expansion for the picked fact and dimensions
    Set<Dimension> exprDimensions = new HashSet<Dimension>();
    if (cfacts != null) {
      for (CandidateFact cfact : cfacts) {
        Set<Dimension> factExprDimTables = exprCtx.rewriteExprCtx(cfact, dimsToQuery, cfacts.size() > 1);
        exprDimensions.addAll(factExprDimTables);
        if (cfacts.size() > 1) {
          factDimMap.get(cfact).addAll(factExprDimTables);
        }
      }
    } else {
      // dim only query
      exprDimensions.addAll(exprCtx.rewriteExprCtx(null, dimsToQuery, false));
    }
    dimsToQuery.putAll(pickCandidateDimsToQuery(exprDimensions));

    // pick denorm tables for the picked fact and dimensions
    Set<Dimension> denormTables = new HashSet<Dimension>();
    if (cfacts != null) {
      for (CandidateFact cfact : cfacts) {
        Set<Dimension> factDenormTables = deNormCtx.rewriteDenormctx(cfact, dimsToQuery, cfacts.size() > 1);
        denormTables.addAll(factDenormTables);
        if (cfacts.size() > 1) {
          factDimMap.get(cfact).addAll(factDenormTables);
        }
      }
    } else {
      denormTables.addAll(deNormCtx.rewriteDenormctx(null, dimsToQuery, false));
    }
    dimsToQuery.putAll(pickCandidateDimsToQuery(denormTables));
    // Prune join paths once denorm tables are picked
    if (autoJoinCtx != null) {
      // prune join paths for picked fact and dimensions
      autoJoinCtx.pruneAllPaths(cube, cfacts, dimsToQuery);
    }
    if (autoJoinCtx != null) {
      // add optional dims from Join resolver
      Set<Dimension> joiningTables = new HashSet<Dimension>();
      if (cfacts != null && cfacts.size() > 1) {
        for (CandidateFact cfact : cfacts) {
          Set<Dimension> factJoiningTables = autoJoinCtx.pickOptionalTables(cfact, factDimMap.get(cfact), this);
          factDimMap.get(cfact).addAll(factJoiningTables);
          joiningTables.addAll(factJoiningTables);
        }
      } else {
        joiningTables.addAll(autoJoinCtx.pickOptionalTables(null, dimsToQuery.keySet(), this));
      }
      dimsToQuery.putAll(pickCandidateDimsToQuery(joiningTables));
    }
    LOG.info("Picked Fact:" + cfacts + " dimsToQuery:" + dimsToQuery);
    pickedDimTables = dimsToQuery.values();
    pickedFacts = cfacts;
    if (cfacts != null) {
      if (cfacts.size() > 1) {
        // Update ASTs for each fact
        for (CandidateFact cfact : cfacts) {
          cfact.updateASTs(this);
        }
      }
    }
    hqlContext = createHQLContext(cfacts, dimsToQuery, factDimMap, this);
    return hqlContext.toHQL();
  }

  private HQLContextInterface createHQLContext(Set<CandidateFact> facts, Map<Dimension, CandidateDim> dimsToQuery,
    Map<CandidateFact, Set<Dimension>> factDimMap, CubeQueryContext query) throws SemanticException {
    if (facts == null || facts.size() == 0) {
      return new DimOnlyHQLContext(dimsToQuery, query);
    } else if (facts.size() == 1 && facts.iterator().next().getStorageTables().size() > 1) {
      //create single fact with multiple storage context
      return new SingleFactMultiStorageHQLContext(facts.iterator().next(), dimsToQuery, query);
    } else if (facts.size() == 1 && facts.iterator().next().getStorageTables().size() == 1) {
      // create single fact context
      return new SingleFactHQLContext(facts.iterator().next(), dimsToQuery, query);
    } else {
      return new MultiFactHQLContext(facts, dimsToQuery, factDimMap, query);
    }
  }

  public ASTNode toAST(Context ctx) throws SemanticException {
    String hql = toHQL();
    ParseDriver pd = new ParseDriver();
    ASTNode tree;
    try {
      LOG.info("HQL:" + hql);
      System.out.println("Rewritten HQL:" + hql);
      tree = pd.parse(hql, ctx);
    } catch (ParseException e) {
      throw new SemanticException(e);
    }
    return ParseUtils.findRootNonNullToken(tree);
  }

  public Set<String> getColumnsQueried(String tblName) {
    return tblAliasToColumns.get(getAliasForTableName(tblName));
  }

  public void addColumnsQueried(AbstractCubeTable table, String column) {
    addColumnsQueried(getAliasForTableName(table.getName()), column);
  }

  public void addColumnsQueriedWithTimeDimCheck(String alias, String timeDimColumn) {

    if (!shouldReplaceTimeDimWithPart()) {
      addColumnsQueried(alias, timeDimColumn);
    }
  }

  public void addColumnsQueried(String alias, String column) {

    Set<String> cols = tblAliasToColumns.get(alias.toLowerCase());
    if (cols == null) {
      cols = new LinkedHashSet<String>();
      tblAliasToColumns.put(alias.toLowerCase(), cols);
    }
    cols.add(column);
  }

  public boolean isCubeMeasure(String col) {
    if (col == null) {
      return false;
    }

    col = col.trim();
    // Take care of brackets added around col names in HQLParsrer.getString
    if (col.startsWith("(") && col.endsWith(")") && col.length() > 2) {
      col = col.substring(1, col.length() - 1);
    }

    String[] split = StringUtils.split(col, ".");
    if (split.length <= 1) {
      col = col.trim().toLowerCase();
      if (queriedExprs.contains(col)) {
        return exprCtx.getExpressionContext(col, getAliasForTableName(cube.getName())).hasMeasures();
      } else {
        return cube.getMeasureNames().contains(col);
      }
    } else {
      String cubeName = split[0].trim().toLowerCase();
      String colName = split[1].trim().toLowerCase();
      if (cubeName.equalsIgnoreCase(cube.getName()) || cubeName.equals(getAliasForTableName(cube.getName()))) {
        if (queriedExprs.contains(colName)) {
          return exprCtx.getExpressionContext(colName, cubeName).hasMeasures();
        } else {
          return cube.getMeasureNames().contains(colName.toLowerCase());
        }
      } else {
        return false;
      }
    }
  }

  boolean isCubeMeasure(ASTNode node) {
    String tabname = null;
    String colname;
    int nodeType = node.getToken().getType();
    if (!(nodeType == HiveParser.TOK_TABLE_OR_COL || nodeType == HiveParser.DOT)) {
      return false;
    }

    if (nodeType == HiveParser.TOK_TABLE_OR_COL) {
      colname = ((ASTNode) node.getChild(0)).getText();
    } else {
      // node in 'alias.column' format
      ASTNode tabident = HQLParser.findNodeByPath(node, TOK_TABLE_OR_COL, Identifier);
      ASTNode colIdent = (ASTNode) node.getChild(1);

      colname = colIdent.getText();
      tabname = tabident.getText();
    }

    String msrname = StringUtils.isBlank(tabname) ? colname : tabname + "." + colname;

    return isCubeMeasure(msrname);
  }

  public boolean isAggregateExpr(String expr) {
    return aggregateExprs.contains(expr == null ? null : expr.toLowerCase());
  }

  public boolean hasAggregates() {
    return !aggregateExprs.isEmpty() || getExprCtx().hasAggregates();
  }

  public String getAlias(String expr) {
    return exprToAlias.get(expr);
  }

  public String getSelectAlias(int index) {
    return selectAliases.get(index);
  }

  public String getSelectFinalAlias(int index) {
    return selectFinalAliases.get(index);
  }

  public Map<String, String> getExprToAliasMap() {
    return exprToAlias;
  }

  public void addAggregateExpr(String expr) {
    aggregateExprs.add(expr);
  }

  public void setJoinCond(QBJoinTree qb, String cond) {
    joinConds.put(qb, cond);
  }

  public AbstractCubeTable getQueriedTable(String alias) {
    if (cube != null && cube.getName().equalsIgnoreCase(qb.getTabNameForAlias((alias)))) {
      return (AbstractCubeTable) cube;
    }
    for (Dimension dim : dimensions) {
      if (dim.getName().equalsIgnoreCase(qb.getTabNameForAlias(alias))) {
        return dim;
      }
    }
    return null;
  }

  public String getInsertClause() {
    String insertString = "";
    ASTNode destTree = qb.getParseInfo().getDestForClause(clauseName);
    if (destTree != null && ((ASTNode) (destTree.getChild(0))).getToken().getType() != TOK_TMP_FILE) {
      insertString = "INSERT OVERWRITE" + HQLParser.getString(qb.getParseInfo().getDestForClause(clauseName));
    }
    return insertString;
  }

  public void addExprToAlias(ASTNode expr, ASTNode alias) {
    exprToAlias.put(HQLParser.getString(expr).trim(), alias.getText().toLowerCase());
  }

  public void addSelectAlias(String alias, String spacedAlias) {
    selectAliases.add(alias);
    if (!StringUtils.isBlank(spacedAlias)) {
      selectFinalAliases.add("`" + spacedAlias + "`");
    } else {
      selectFinalAliases.add(alias);
    }
  }

  public Set<Dimension> getOptionalDimensions() {
    return optionalDimensions.keySet();
  }

  public Map<Dimension, OptionalDimCtx> getOptionalDimensionMap() {
    return optionalDimensions;
  }

  /**
   * @return the hqlContext
   */
  public HQLContextInterface getHqlContext() {
    return hqlContext;
  }

  public boolean shouldReplaceTimeDimWithPart() {
    return getConf().getBoolean(CubeQueryConfUtil.REPLACE_TIMEDIM_WITH_PART_COL,
      CubeQueryConfUtil.DEFAULT_REPLACE_TIMEDIM_WITH_PART_COL);
  }

  public String getPartitionColumnOfTimeDim(String timeDimName) {
    return getPartitionColumnOfTimeDim(cube, timeDimName);
  }

  public static String getPartitionColumnOfTimeDim(CubeInterface cube, String timeDimName) {
    if (cube == null) {
      return timeDimName;
    }
    if (cube instanceof DerivedCube) {
      return ((DerivedCube) cube).getParent().getPartitionColumnOfTimeDim(timeDimName);
    } else {
      return ((Cube) cube).getPartitionColumnOfTimeDim(timeDimName);
    }
  }

  public String getTimeDimOfPartitionColumn(String partCol) {
    return getTimeDimOfPartitionColumn(cube, partCol);
  }

  public static String getTimeDimOfPartitionColumn(CubeInterface cube, String partCol) {
    if (cube == null) {
      return partCol;
    }
    if (cube instanceof DerivedCube) {
      return ((DerivedCube) cube).getParent().getTimeDimOfPartitionColumn(partCol);
    } else {
      return ((Cube) cube).getTimeDimOfPartitionColumn(partCol);
    }
  }

  /**
   * @return the queriedDimAttrs
   */
  public Set<String> getQueriedDimAttrs() {
    return queriedDimAttrs;
  }

  public void addQueriedDimAttrs(Set<String> dimAttrs) {
    queriedDimAttrs.addAll(dimAttrs);
  }

  public void addQueriedMsrs(Set<String> msrs) {
    queriedMsrs.addAll(msrs);
  }

  public void addQueriedExprs(Set<String> exprs) {
    queriedExprs.addAll(exprs);
  }

  public void addQueriedExprsWithMeasures(Set<String> exprs) {
    queriedExprsWithMeasures.addAll(exprs);
  }

  /**
   * Prune candidate fact sets with respect to available candidate facts.
   * <p></p>
   * Prune a candidate set, if any of the fact is missing.
   *
   * @param pruneCause
   */
  public void pruneCandidateFactSet(CandidateTablePruneCode pruneCause) {
    // remove candidate fact sets that have missing facts
    for (Iterator<Set<CandidateFact>> i = candidateFactSets.iterator(); i.hasNext();) {
      Set<CandidateFact> cfacts = i.next();
      if (!candidateFacts.containsAll(cfacts)) {
        LOG.info("Not considering fact table set:" + cfacts
          + " as they have non candidate tables and facts missing because of " + pruneCause);
        i.remove();
      }
    }
    // prune candidate facts
    pruneCandidateFactWithCandidateSet(CandidateTablePruneCode.ELEMENT_IN_SET_PRUNED);
  }

  /**
   * Prune candidate fact with respect to available candidate fact sets.
   * <p></p>
   * If candidate fact is not present in any of the candidate fact sets, remove it.
   *
   * @param pruneCause
   */
  public void pruneCandidateFactWithCandidateSet(CandidateTablePruneCode pruneCause) {
    // remove candidate facts that are not part of any covering set
    pruneCandidateFactWithCandidateSet(new CandidateTablePruneCause(pruneCause));
  }

  public void pruneCandidateFactWithCandidateSet(CandidateTablePruneCause pruneCause) {
    // remove candidate facts that are not part of any covering set
    Set<CandidateFact> allCoveringFacts = new HashSet<CandidateFact>();
    for (Set<CandidateFact> set : candidateFactSets) {
      allCoveringFacts.addAll(set);
    }
    for (Iterator<CandidateFact> i = candidateFacts.iterator(); i.hasNext();) {
      CandidateFact cfact = i.next();
      if (!allCoveringFacts.contains(cfact)) {
        LOG.info("Not considering fact table:" + cfact + " as " + pruneCause);
        addFactPruningMsgs(cfact.fact, pruneCause);
        i.remove();
      }
    }
  }

  public void addQueriedTimeDimensionCols(final String timeDimColName) {

    checkArgument(StringUtils.isNotBlank(timeDimColName));
    this.queriedTimeDimCols.add(timeDimColName);
  }

  public ImmutableSet<String> getQueriedTimeDimCols() {
    return ImmutableSet.copyOf(this.queriedTimeDimCols);
  }
}
