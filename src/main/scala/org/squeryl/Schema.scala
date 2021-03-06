/*******************************************************************************
 * Copyright 2010 Maxime Lévesque
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.squeryl


import dsl.ast.FieldSelectElement
import dsl.boilerplate.Query1
import dsl.fsm.QueryElements
import dsl.{CompositeKey, ManyToManyRelation, OneToManyRelation}
import internals._
import reflect.{Manifest}
import java.sql.SQLException
import collection.mutable.{HashSet, ArrayBuffer};


trait Schema {

  protected implicit def thisSchema = this 

  private val _tables = new ArrayBuffer[Table[_]] 

  private val _oneToManyRelations = new ArrayBuffer[OneToManyRelation[_,_]]

  private val _manyToManyRelations = new ArrayBuffer[ManyToManyRelation[_,_,_]]

  private [squeryl] val _namingScope = new HashSet[String] 

  private [squeryl] def _addRelation(r: OneToManyRelation[_,_]) =
    _oneToManyRelations.append(r)

  private [squeryl] def _addRelation(r: ManyToManyRelation[_,_,_]) =
    _manyToManyRelations.append(r)

  private def _dbAdapter = Session.currentSession.databaseAdapter

  /**
   * @returns a tuple of (Table[_], Table[_], ForeingKeyDeclaration) where
   *  ._1 is the foreing key table,
   *  ._2 is the primary key table
   *  ._3 is the ForeingKeyDeclaration between _1 and _2
   */
  private def _activeForeingKeySpecs = {
    val res = new ArrayBuffer[(Table[_], Table[_], ForeingKeyDeclaration)]

    for( r <- _oneToManyRelations if r.foreingKeyDeclaration._isActive)
      res.append((r.rightTable, r.leftTable, r.foreingKeyDeclaration))

    for(r <- _manyToManyRelations) {
      if(r.leftForeingKeyDeclaration._isActive)
        res.append((r.thisTable, r.leftTable , r.leftForeingKeyDeclaration))
      if(r.rightForeingKeyDeclaration._isActive)
        res.append((r.thisTable, r.rightTable, r.rightForeingKeyDeclaration))
    }

    res
  }


  def findTableFor[A](a: A): Option[Table[A]] = {
    val c = a.asInstanceOf[AnyRef].getClass
    _tables.find(_.posoMetaData.clasz == c).asInstanceOf[Option[Table[A]]]
  }

  object NamingConventionTransforms {
    
    def camelCase2underScore(name: String) =
      name.toList.map(c => if(c.isUpper) "_" + c else c).mkString
  }

  def columnNameFromPropertyName(propertyName: String) = propertyName

  def tableNameFromClassName(tableName: String) = tableName

  def printDml = {

    for(t <- _tables) {
      val sw = new StatementWriter(true, _dbAdapter)
      _dbAdapter.writeCreateTable(t, sw, this)
    }
  }

  /**
   * This will drop all tables and related sequences in the schema... it's a
   * dangerous operation, typically this is only usefull for devellopment
   * database instances, the method is protected in order to make it a little
   * less 'accessible'  
   */
  protected def drop: Unit = {

    if(_dbAdapter.supportsForeignKeyConstraints)
      _dropForeignKeyConstraints

    val s = Session.currentSession.connection.createStatement
    val con = Session.currentSession.connection

    for(t <- _tables) {
      _dbAdapter.dropTable(t)
      _dbAdapter.postDropTable(t)
    }
  }  

  def create = {
    _createTables
    if(_dbAdapter.supportsForeignKeyConstraints)
      _declareForeingKeyConstraints

    _createUniqueConstraints
  }

  private def _dropForeignKeyConstraints = {

    val cs = Session.currentSession
    val dba = cs.databaseAdapter

    for(fk <- _activeForeingKeySpecs) {
      val s = cs.connection.createStatement
      dba.dropForeignKeyStatement(fk._1, dba.foreingKeyConstraintName(fk._1, fk._3.idWithinSchema), cs)
    }
  }

  private def _declareForeingKeyConstraints =
    for(fk <- _activeForeingKeySpecs) {
      val fkDecl = fk._3

      val fkStatement = _dbAdapter.writeForeingKeyDeclaration(
         fk._1, fkDecl.foreingKeyColumnName,
         fk._2, fkDecl.referencedPrimaryKey,
         fkDecl._referentialAction1,
         fkDecl._referentialAction2,
         fkDecl.idWithinSchema
      )
            
      val cs = Session.currentSession
      val s = cs.connection.createStatement
      try {
        s.execute(fkStatement)
      }
      catch {
        case e:SQLException => throw new RuntimeException("error executing " + fkStatement + "\n" + e, e)
      }
      finally {
        s.close
      }
    }


  private def _createTables =
    for(t <- _tables) {
      var sw:StatementWriter = null
      try {
        sw = new StatementWriter(_dbAdapter)
        _dbAdapter.writeCreateTable(t, sw, this)
        val cs = Session.currentSession
        val s = cs.connection.createStatement
        val createS = sw.statement
        if(cs.isLoggingEnabled)
          cs.log(createS)
        s.execute(createS)
      }
      catch {
        case e:SQLException => throw new RuntimeException("error creating table :\n" + sw.statement ,e)
      }

      _dbAdapter.postCreateTable(Session.currentSession, t)
    }    

  private def _createUniqueConstraints = {

    val cs = Session.currentSession

    for(cpk <- _allCompositePrimaryKeys) {

      val createConstraintStmt = _dbAdapter.writeUniquenessConstraint(cpk._1, cpk._2)
      val s = cs.connection.createStatement
      try{
        if(cs.isLoggingEnabled)
          cs.log(createConstraintStmt)
        s.execute(createConstraintStmt)
      }
      finally {
        s.close
      }
    }
  }

  /**
   * returns an Iterable of (Table[_],Iterable[FieldMetaData]), the list of
   * all tables whose PK is a composite, with the columns that are part of the PK : Iterable[FieldMetaData] 
   */
  private def _allCompositePrimaryKeys = {
    
    val res = new ArrayBuffer[(Table[_],Iterable[FieldMetaData])]
    
    for(t <- _tables
        if classOf[KeyedEntity[_]].isAssignableFrom(t.posoMetaData.clasz)) {

      Utils.mapSampleObject(
        t.asInstanceOf[Table[KeyedEntity[_]]],
        (ke:KeyedEntity[_]) => {
          val id = ke.id
          if(id.isInstanceOf[CompositeKey]) {
            val compositeCols = id.asInstanceOf[CompositeKey]._fields
            res.append((t, compositeCols))
          }
        }
      )
    }

    res
  }

  protected def columnTypeFor(fieldMetaData: FieldMetaData, databaseAdapter: DatabaseAdapter): String =
    databaseAdapter.databaseTypeFor(fieldMetaData)

  private [squeryl] def _columnTypeFor(fmd: FieldMetaData, dba: DatabaseAdapter): String =
    this.columnTypeFor(fmd, dba)
  
  def tableNameFromClass(c: Class[_]):String =     
    c.getSimpleName

  protected def table[T]()(implicit manifestT: Manifest[T]): Table[T] =
    table(tableNameFromClass(manifestT.erasure))(manifestT)
  
  protected def table[T](name: String)(implicit manifestT: Manifest[T]): Table[T] = {
    val t = new Table[T](name, manifestT.erasure.asInstanceOf[Class[T]], this)
    _addTable(t)
    t
  }

  private [squeryl] def _addTable(t:Table[_]) =
    _tables.append(t)
  
  protected def view[T]()(implicit manifestT: Manifest[T]): View[T] =
    view(tableNameFromClass(manifestT.erasure))(manifestT)

  protected def view[T](name: String)(implicit manifestT: Manifest[T]): View[T] =
    new View[T](name)(manifestT)

  
  class ReferentialEvent(val eventName: String) {
    def restrict = new ReferentialActionImpl("restrict", this)
    def cascade = new ReferentialActionImpl("cascade", this)
    def noAction = new ReferentialActionImpl("no action", this)
  }

  class ReferentialActionImpl(token: String, ev: ReferentialEvent) extends ReferentialAction {
    def event = ev.eventName
    def action = token
  }
  
  protected def onUpdate = new ReferentialEvent("update")

  protected def onDelete = new ReferentialEvent("delete")

  private var _fkIdGen = 1 

  private [squeryl] def _createForeingKeyDeclaration(fkColName: String, pkColName: String) = {
    val fkd = new ForeingKeyDeclaration(_fkIdGen, fkColName, pkColName)
    _fkIdGen += 1
    applyDefaultForeingKeyPolicy(fkd)
    fkd
  }

  def applyDefaultForeingKeyPolicy(foreingKeyDeclaration: ForeingKeyDeclaration) =
    foreingKeyDeclaration.constrainReference
}
