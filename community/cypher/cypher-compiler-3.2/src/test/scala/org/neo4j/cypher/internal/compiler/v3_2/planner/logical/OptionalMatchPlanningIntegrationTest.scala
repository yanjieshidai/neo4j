/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_2.planner.logical

import org.neo4j.cypher.internal.compiler.v3_2.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.plans.rewriter.unnestOptional
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.plans.{Limit, _}
import org.neo4j.cypher.internal.frontend.v3_2.SemanticDirection
import org.neo4j.cypher.internal.frontend.v3_2.ast._
import org.neo4j.cypher.internal.frontend.v3_2.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.ir.v3_2.{IdName, SimplePatternLength}

class OptionalMatchPlanningIntegrationTest extends CypherFunSuite with LogicalPlanningTestSupport2 {

  test("should build plans containing joins") {
    (new given {
      cost = {
        case (_: AllNodesScan, _) => 2000000.0
        case (_: NodeByLabelScan, _) => 20.0
        case (_: Expand, _) => 10.0
        case (_: OuterHashJoin, _) => 20.0
        case (_: SingleRow, _) => 1.0
        case _ => Double.MaxValue
      }
    } getLogicalPlanFor "MATCH (a:X)-[r1]->(b) OPTIONAL MATCH (b)-[r2]->(c:Y) RETURN b")._2 should equal(
        OuterHashJoin(Set("b"),
          Expand(NodeByLabelScan("a", lblName("X"), Set.empty)(solved), "a", SemanticDirection.OUTGOING, Seq(), "b", "r1")(solved),
          Expand(NodeByLabelScan("c", lblName("Y"), Set.empty)(solved), "c", SemanticDirection.INCOMING, Seq(), "b", "r2")(solved)
        )(solved)
    )
  }

  test("should build simple optional match plans") { // This should be built using plan rewriting
    planFor("OPTIONAL MATCH (a) RETURN a")._2 should equal(
      Optional(AllNodesScan("a", Set.empty)(solved))(solved))
  }

  test("should build simple optional expand") {
    planFor("MATCH (n) OPTIONAL MATCH (n)-[:NOT_EXIST]->(x) RETURN n")._2.endoRewrite(unnestOptional) match {
      case OptionalExpand(
      AllNodesScan(IdName("n"), _),
      IdName("n"),
      SemanticDirection.OUTGOING,
      _,
      IdName("x"),
      _,
      _,
      _
      ) => ()
    }
  }

  test("should build optional ProjectEndpoints") {
    planFor("MATCH (a1)-[r]->(b1) WITH r, a1 LIMIT 1 OPTIONAL MATCH (a1)<-[r]-(b2) RETURN a1, r, b2")._2 match {
      case
        Apply(
        Limit(
        Expand(
        AllNodesScan(IdName("b1"), _), _, _, _, _, _, _), _, _),
        Optional(
        ProjectEndpoints(
        Argument(args), IdName("r"), IdName("b2"), false, IdName("a1"), true, None, true, SimplePatternLength
        ), _
        )
        ) =>
        args should equal(Set(IdName("r"), IdName("a1")))
    }
  }

  test("should build optional ProjectEndpoints with extra predicates") {
    planFor("MATCH (a1)-[r]->(b1) WITH r, a1 LIMIT 1 OPTIONAL MATCH (a2)<-[r]-(b2) WHERE a1 = a2 RETURN a1, r, b2")._2 match {
      case Apply(
      Limit(Expand(AllNodesScan(IdName("b1"), _), _, _, _, _, _, _), _, _),
      Optional(
      Selection(
      predicates,
      ProjectEndpoints(
      Argument(args),
      IdName("r"), IdName("b2"), false, IdName("a2"), false, None, true, SimplePatternLength
      )
      ), _
      )
      ) =>
        args should equal(Set(IdName("r"), IdName("a1")))
        val predicate: Expression = Equals(Variable("a1")_, Variable("a2")_)_
        predicates should equal(Seq(predicate))
    }
  }

  test("should build optional ProjectEndpoints with extra predicates 2") {
    planFor("MATCH (a1)-[r]->(b1) WITH r LIMIT 1 OPTIONAL MATCH (a2)-[r]->(b2) RETURN a2, r, b2")._2  match {
      case Apply(
      Limit(Expand(AllNodesScan(IdName("b1"), _), _, _, _, _, _, _), _, _),
      Optional(
      ProjectEndpoints(
      Argument(args),
      IdName("r"), IdName("a2"), false, IdName("b2"), false, None, true, SimplePatternLength
      ), _
      )
      ) =>
        args should equal(Set(IdName("r")))
    }
  }

  test("should solve multiple optional matches") {
    val plan = planFor("MATCH (a) OPTIONAL MATCH (a)-[:R1]->(x1) OPTIONAL MATCH (a)-[:R2]->(x2) RETURN a, x1, x2")._2.endoRewrite(unnestOptional)
    plan should equal(
      OptionalExpand(
        OptionalExpand(
          AllNodesScan(IdName("a"), Set.empty)(solved),
          IdName("a"), SemanticDirection.OUTGOING, List(RelTypeName("R1") _), IdName("x1"), IdName("  UNNAMED29"), ExpandAll, Seq.empty)(solved),
        IdName("a"), SemanticDirection.OUTGOING, List(RelTypeName("R2") _), IdName("x2"), IdName("  UNNAMED60"), ExpandAll, Seq.empty)(solved)
    )
  }

  test("should solve optional matches with arguments and predicates") {
    val plan = planFor(
      """MATCH (n:X)
        |OPTIONAL MATCH (n)-[r]-(m:Y)
        |WHERE m.prop = 42
        |RETURN m""".stripMargin)._2.endoRewrite(unnestOptional)
    val s = solved
    val allNodesN: LogicalPlan = NodeByLabelScan(IdName("n"), LabelName("X") _, Set.empty)(solved)
    val propEquality: Expression =
      In(Property(varFor("m"), PropertyKeyName("prop") _) _, ListLiteral(List(SignedDecimalIntegerLiteral("42") _)) _) _

    val labelCheck: Expression =
      HasLabels(varFor("m"), List(LabelName("Y") _)) _

    plan should equal(
      OptionalExpand(allNodesN, IdName("n"), SemanticDirection.BOTH, Seq.empty, IdName("m"), IdName("r"), ExpandAll,
        Seq(propEquality, labelCheck))(s)
    )
  }
}
