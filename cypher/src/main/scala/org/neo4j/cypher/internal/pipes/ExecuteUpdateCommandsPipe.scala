/**
 * Copyright (c) 2002-2012 "Neo Technology,"
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
package org.neo4j.cypher.internal.pipes

import collection.mutable.{HashSet => MutableHashSet}
import org.neo4j.cypher.internal.mutation.{DeleteEntityAction, UpdateAction}
import org.neo4j.graphdb.{Relationship, Node, GraphDatabaseService, NotInTransactionException}
import org.neo4j.cypher.{ParameterWrongTypeException, InternalException}

class ExecuteUpdateCommandsPipe(source: Pipe, db: GraphDatabaseService, commands: Seq[UpdateAction]) extends PipeWithSource(source) {


  def createResults(state: QueryState) = {
    val deletedNodes = MutableHashSet[Long]()
    val deletedRelationships = MutableHashSet[Long]()

    if (commands.size == 1) {
      source.createResults(state).flatMap {
        case ctx => executeMutationCommands(ctx, state, deletedNodes, deletedRelationships).flatten
      }
    } else {
      source.createResults(state).flatMap {
        case ctx => {
          val commands = executeMutationCommands(ctx, state, deletedNodes, deletedRelationships)
          commands.foreach(x => if (x.size > 1) throw new ParameterWrongTypeException("If you create multiple elements, you can only create one of each."))
          commands.last
        }
      }
    }
  }

  // TODO: Make it better
  private def executeMutationCommands(ctx: ExecutionContext, state: QueryState, deletedNodes: MutableHashSet[Long], deletedRelationships: MutableHashSet[Long]): Traversable[Traversable[ExecutionContext]] =
    try {
      commands.map {
        case cmd@DeleteEntityAction(expression) => {
          expression(ctx) match {
            case n: Node => {
              if (!deletedNodes.contains(n.getId)) {
                deletedNodes.add(n.getId)
                cmd.exec(ctx, state)
              } else Stream()
            }
            case r: Relationship => {
              if (!deletedRelationships.contains(r.getId)) {
                deletedRelationships.add(r.getId)
                cmd.exec(ctx, state)
              } else Stream()
            }
            case _ => cmd.exec(ctx, state)
          }
        }
        case cmd => cmd.exec(ctx, state)
      }
    } catch {
      case e: NotInTransactionException => throw new InternalException("Expected to be in a transaction at this point", e)
    }

  def executionPlan() = source.executionPlan() + "\nUpdateGraph(" + commands.mkString + ")"

  def symbols = commands.foldLeft(source.symbols)((symbol, cmd) => cmd.influenceSymbolTable(symbol))

  def dependencies = commands.flatMap(_.dependencies)
}
