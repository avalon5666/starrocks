// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/SetStmtTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.mysql.privilege.MockedAuth;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.analyzer.SetStmtAnalyzer;
import com.starrocks.sql.ast.SetListItem;
import com.starrocks.sql.ast.SetNamesVar;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.SetType;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.UserVariable;
import com.starrocks.sql.common.QueryDebugOptions;
import com.starrocks.sql.parser.NodePosition;
import mockit.Mocked;
import org.apache.commons.lang3.EnumUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.List;

public class SetStmtTest {

    @Mocked
    private ConnectContext ctx;

    @Before
    public void setUp() {
        MockedAuth.mockedConnectContext(ctx, "root", "192.168.1.1");
    }

    @Test
    public void testNormal() throws StarRocksException {
        List<SetListItem> vars = Lists.newArrayList(new UserVariable("times", new IntLiteral(100L),
                        NodePosition.ZERO),
                new SetNamesVar("utf8"));
        SetStmt stmt = new SetStmt(vars);
        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);

        Assert.assertEquals("times", ((UserVariable) stmt.getSetListItems().get(0)).getVariable());
        Assert.assertEquals("100", ((UserVariable) stmt.getSetListItems().get(0)).getEvaluatedExpression().toSqlImpl());
        Assert.assertTrue(stmt.getSetListItems().get(1) instanceof SetNamesVar);
        Assert.assertEquals("utf8", ((SetNamesVar) stmt.getSetListItems().get(1)).getCharset());
    }

    @Test(expected = SemanticException.class)
    public void testNoVariable() {
        SystemVariable var = new SystemVariable(SetType.SESSION, "", new StringLiteral("utf-8"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(var)), ctx);
        Assert.fail("No exception throws.");
    }

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @Test
    public void testNonConstantExpr() throws StarRocksException {
        SlotDescriptor descriptor = new SlotDescriptor(new SlotId(1), "x",
                Type.INT, false);
        Expr lhsExpr = new SlotRef(descriptor);
        Expr rhsExpr = new IntLiteral(100L);
        ArithmeticExpr addExpr = new ArithmeticExpr(
                ArithmeticExpr.Operator.ADD, lhsExpr, rhsExpr);
        SystemVariable var = new SystemVariable(SetType.SESSION, SessionVariable.SQL_SELECT_LIMIT, addExpr);
        expectedEx.expect(SemanticException.class);
        expectedEx.expectMessage("Set statement only support constant expr.");
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(var)), ctx);
    }

    @Test
    public void setResourceGroup() {
        SystemVariable setEmpty = new SystemVariable(SetType.SESSION, SessionVariable.RESOURCE_GROUP, new StringLiteral(""));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setEmpty)), ctx);

        SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.RESOURCE_GROUP, new StringLiteral("not_exists"));
        try {
            SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
            Assert.fail("should fail");
        } catch (SemanticException e) {
            Assert.assertEquals("Getting analyzing error. Detail message: resource group not exists: not_exists.",
                    e.getMessage());
        }
    }

    @Test
    public void testSetNonNegativeLongVariable() throws StarRocksException {
        List<String> fields = Lists.newArrayList(
                SessionVariable.LOAD_MEM_LIMIT,
                SessionVariable.QUERY_MEM_LIMIT,
                SessionVariable.SQL_SELECT_LIMIT);

        for (String field : fields) {
            Assert.assertThrows("is not a number", SemanticException.class, () -> {
                SystemVariable setVar = new SystemVariable(SetType.SESSION, field, new StringLiteral("non_number"));
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
            });

            Assert.assertThrows("must be equal or greater than 0", SemanticException.class, () -> {
                SystemVariable setVar = new SystemVariable(SetType.SESSION, field, new StringLiteral("-1"));
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
            });

            SystemVariable var = new SystemVariable(SetType.SESSION, field, new StringLiteral("0"));
            SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(var)), ctx);
            Assert.assertEquals(field, var.getVariable());
            Assert.assertEquals("0", var.getResolvedExpression().getStringValue());

            var = new SystemVariable(SetType.SESSION, field, new StringLiteral("10"));
            SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(var)), ctx);
            Assert.assertEquals(field, var.getVariable());
            Assert.assertEquals("10", var.getResolvedExpression().getStringValue());

            var = new SystemVariable(SetType.SESSION, field, new IntLiteral(0));
            SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(var)), ctx);
            Assert.assertEquals(field, var.getVariable());
            Assert.assertEquals("0", var.getResolvedExpression().getStringValue());

            var = new SystemVariable(SetType.SESSION, field, new IntLiteral(10));
            SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(var)), ctx);
            Assert.assertEquals(field, var.getVariable());
            Assert.assertEquals("10", var.getResolvedExpression().getStringValue());
        }
    }

    @Test
    public void testMaterializedViewRewriteMode() throws AnalysisException {
        // normal
        {
            for (SessionVariable.MaterializedViewRewriteMode mode :
                    EnumUtils.getEnumList(SessionVariable.MaterializedViewRewriteMode.class)) {
                try {
                    SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.MATERIALIZED_VIEW_REWRITE_MODE,
                            new StringLiteral(mode.toString()));
                    SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                } catch (Exception e) {
                    Assert.fail();;
                }
            }

        }

        // empty
        {
            SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.MATERIALIZED_VIEW_REWRITE_MODE,
                    new StringLiteral(""));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                Assert.fail();
            } catch (Exception e) {
                e.printStackTrace();
                Assert.assertEquals("Getting analyzing error. Detail message: Unsupported materialized view " +
                        "rewrite mode: , supported list is DISABLE,DEFAULT,DEFAULT_OR_ERROR,FORCE,FORCE_OR_ERROR.",
                        e.getMessage());
            }
        }

        // bad case
        {
            SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.MATERIALIZED_VIEW_REWRITE_MODE,
                    new StringLiteral("bad_case"));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                Assert.fail("should fail");
            } catch (SemanticException e) {
                Assert.assertEquals("Getting analyzing error. Detail message: Unsupported " +
                        "materialized view rewrite mode: bad_case, " +
                        "supported list is DISABLE,DEFAULT,DEFAULT_OR_ERROR,FORCE,FORCE_OR_ERROR.", e.getMessage());;
            }
        }
    }

    @Test
    public void testFollowerQueryForwardMode() throws AnalysisException {
        // normal
        {
            for (SessionVariable.FollowerQueryForwardMode mode :
                    EnumUtils.getEnumList(SessionVariable.FollowerQueryForwardMode.class)) {
                try {
                    SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.FOLLOWER_QUERY_FORWARD_MODE,
                            new StringLiteral(mode.toString()));
                    SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                } catch (Exception e) {
                    Assert.fail();;
                }
            }

        }

        // empty
        {
            SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.FOLLOWER_QUERY_FORWARD_MODE,
                    new StringLiteral(""));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                Assert.fail();
            } catch (Exception e) {
                e.printStackTrace();
                Assert.assertEquals("Getting analyzing error. Detail message: Unsupported follower " +
                                "query forward mode: , supported list is DEFAULT,FOLLOWER,LEADER.",
                        e.getMessage());
            }
        }

        // bad case
        {
            SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.FOLLOWER_QUERY_FORWARD_MODE,
                    new StringLiteral("bad_case"));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                Assert.fail("should fail");
            } catch (SemanticException e) {
                Assert.assertEquals("Getting analyzing error. Detail message: " +
                        "Unsupported follower query forward mode: bad_case, " +
                        "supported list is DEFAULT,FOLLOWER,LEADER.", e.getMessage());;
            }
        }
    }

    @Test
    public void testQueryDebuOptions() throws AnalysisException {
        // normal
        {
            String[] jsons = {
                    "",
                    "{'enableNormalizePredicateAfterMVRewrite':'true'}",
                    "{'maxRefreshMaterializedViewRetryNum':2}",
                    "{'enableNormalizePredicateAfterMVRewrite':'true', 'maxRefreshMaterializedViewRetryNum':2}",
            };
            for (String json : jsons) {
                try {
                    SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.QUERY_DEBUG_OPTIONS,
                            new StringLiteral(json));
                    SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                } catch (Exception e) {
                    Assert.fail();;
                }
            }
        }
        // bad
        {
            String[] jsons = {
                    "abc",
                    "{abc",
            };
            for (String json : jsons) {
                try {
                    SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.QUERY_DEBUG_OPTIONS,
                            new StringLiteral(json));
                    SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                    Assert.fail();;
                } catch (Exception e) {
                    Assert.assertTrue(e.getMessage().contains("Unsupported query_debug_option"));
                    e.printStackTrace();
                }
            }
        }
        // non normal
        {
            String[] jsons = {
                    "{'enableNormalizePredicateAfterMVRewrite2':'true'}",
                    "{'maxRefreshMaterializedViewRetryNum2':'2'}"
            };
            for (String json : jsons) {
                try {
                    SystemVariable setVar = new SystemVariable(SetType.SESSION, SessionVariable.QUERY_DEBUG_OPTIONS,
                            new StringLiteral(json));
                    SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                    QueryDebugOptions debugOptions = QueryDebugOptions.read(json);
                    Assert.assertEquals(debugOptions.getMaxRefreshMaterializedViewRetryNum(), 1);
                    Assert.assertEquals(debugOptions.isEnableNormalizePredicateAfterMVRewrite(), false);
                } catch (Exception e) {
                    Assert.fail();;
                }
            }
        }
    }

    @Test
    public void testCBOMaterializedViewRewriteLimit() {
        // good
        {
            List<Pair<String, String>> goodCases = ImmutableList.of(
                    Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_CANDIDATE_LIMIT, "1"),
                    Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RELATED_MVS_LIMIT, "1"),
                    Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RULE_OUTPUT_LIMIT, "1")
            );
            for (Pair<String, String> goodCase : goodCases) {
                try {
                    SystemVariable setVar = new SystemVariable(SetType.SESSION, goodCase.first,
                            new StringLiteral(goodCase.second));
                    SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                } catch (Exception e) {
                    Assert.fail();;
                }
            }
        }
    }

    // bad
    {
        List<Pair<String, String>> goodCases = ImmutableList.of(
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_CANDIDATE_LIMIT, "-1"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_CANDIDATE_LIMIT, "0"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_CANDIDATE_LIMIT, "abc"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RELATED_MVS_LIMIT, "-1"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RELATED_MVS_LIMIT, "0"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RELATED_MVS_LIMIT, "abc"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RULE_OUTPUT_LIMIT, "-1"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RULE_OUTPUT_LIMIT, "0"),
                Pair.create(SessionVariable.CBO_MATERIALIZED_VIEW_REWRITE_RULE_OUTPUT_LIMIT, "abc")
        );
        for (Pair<String, String> goodCase : goodCases) {
            try {
                SystemVariable setVar = new SystemVariable(SetType.SESSION, goodCase.first,
                        new StringLiteral(goodCase.second));
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
                Assert.fail();;
            } catch (Exception e) {
                Assert.assertTrue(e instanceof SemanticException);
            }
        }
    }

    @Test
    public void testSetCatalog() {
        // good
        try {
            SystemVariable setVar = new SystemVariable(SetType.SESSION, "catalog",
                    new StringLiteral("default_catalog"));
            SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
        } catch (Exception e) {
            Assert.fail();
        }

        // bad
        try {
            SystemVariable setVar = new SystemVariable(SetType.SESSION, "catalog",
                    new StringLiteral("non_existent_catalog"));
            SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), ctx);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertEquals("Getting analyzing error. Detail message: Unknown catalog non_existent_catalog.", e.getMessage());;
        }
    }
}
