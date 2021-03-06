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
package org.squeryl.internals

import org.squeryl.annotations.Column
import java.lang.annotation.Annotation
import java.lang.reflect.{Field, Method}
import java.sql.ResultSet
import java.math.BigDecimal


class FieldMetaData(
        val parentMetaData: PosoMetaData[_],
        val nameOfProperty:String,
        val fieldType: Class[_], // if isOption, this fieldType is the type param of Option, i.e. the T in Option[T]
        val wrappedFieldType: Class[_], //in primitive type mode fieldType == wrappedFieldType, in custom type mode wrappedFieldType is the 'real'
        // type, i.e. the (primitive) type that jdbc understands
        val customTypeFactory: Option[AnyRef=>Product1[Any]],
        val isOption: Boolean,
        getter: Option[Method],
        setter: Option[Method],
        field:  Option[Field],
        columnAnnotation: Option[Column],
        val isOptimisticCounter: Boolean) {

  def isCustomType = customTypeFactory != None

  /**
   * @return the length defined in org.squeryl.annotations.Column.length
   * if it is defined, or the default length for Java primitive types.
   * The unit of the length is dependent on the type, the convention is
   * that numeric types have a length in byte, boolean is bits
   * date has -1, and for string the lenght is in chars.  
   * double,long -> 8, float,int -> 4, byte -> 1, boolean -> 1
   * java.util.Date -> -1.
   *
   * The use of this field is to help custom schema generators select
   * the most appropriate column type  
   */
  def length: Int =
    if(columnAnnotation == None || columnAnnotation.get.length == -1)
      FieldMetaData.defaultFieldLength(wrappedFieldType)
    else
      columnAnnotation.get.length

  def scale: Int =
    columnAnnotation flatMap { ca =>
      if(ca.scale == -1) None
      else Some(ca.scale)
    } getOrElse FieldMetaData.defaultFieldScale(wrappedFieldType)

  /**
   * The name of the database column
   */
  def columnName =
    if(columnAnnotation == None)
      parentMetaData.schema.columnNameFromPropertyName(nameOfProperty)
    else {
      val ca = columnAnnotation.get
      var res = ca.name

      if(res == "")
        res = ca.value

      if(res == "")
        parentMetaData.schema.columnNameFromPropertyName(nameOfProperty)
      else
        res
    }
  
  val resultSetHandler =
    FieldMetaData.resultSetHandlerFor(wrappedFieldType)

  if(!isCustomType)
    assert(fieldType == wrappedFieldType,
      "expected fieldType == wrappedFieldType in primitive type mode, got "+
      fieldType.getName + " != " + wrappedFieldType.getName)

  override def toString =
    parentMetaData.clasz.getSimpleName + "." + columnName + ":" + displayType

  def isStringType =
    wrappedFieldType.isAssignableFrom(classOf[String])  

  def displayType =
     (if(isOption)
        "Option[" + fieldType.getName + "]"
      else
        fieldType.getName)  

  lazy val isPrimaryKey =
    parentMetaData.primaryKey != None &&
    this == parentMetaData.primaryKey.get

  var isAutoIncremented = false

  /**
   * gets the value of the field from the object.
   * Note that it will unwrap Option[] and return null instead of None, i.e.
   * if converts None and Some to null and some.get respectively 
   * @arg o the object that owns the field
   */
  def get(o:AnyRef): AnyRef =
    try {
      val res =
        if(getter != None)
          _getFromGetter(o)
        else
          _getFromField(o)

      if(isOption) {
        if(res == None)
          null
        else
          res.asInstanceOf[Option[_]].get.asInstanceOf[AnyRef]
      }
      else
        res
    }
    catch {
      case e: IllegalArgumentException => error(wrappedFieldType.getName + " used on " + o.getClass.getName)
    }

  def setFromResultSet(target: AnyRef, rs: ResultSet, index: Int) = {
    val v = resultSetHandler(rs, index)    
    set(target, v)
  }
  
  /**
   * Sets the value 'v' to the object, the value will be converted to Some or None
   * if the field is an Option[], (if isOption).   
   */
  def set(target: AnyRef, v: AnyRef) = {
    try {
      val v0:AnyRef =
        if(customTypeFactory == None)
          v
        else {
          val f = customTypeFactory.get
          f(v)
        }

      val actualValue =
        if(!isOption)
          v0
        else if(v0 == null)
          None
        else
          Some(v0)

      val res =
        if(setter != None)
          _setWithSetter(target, actualValue)
        else
          _setWithField(target, actualValue)
    }
    catch {
      case e: IllegalArgumentException => {
        val typeOfV = if(v == null) "null" else v.getClass.getName
        error(
          this + " was invoked with value '" + v + "' of type " + typeOfV + " on object of type " + target.getClass.getName + " \n" + e)
      }
    }

  }

  private def _getFromGetter(o:AnyRef) =
    getter.get.invoke(o)

  private def _setWithSetter(target: AnyRef, v: AnyRef) =
    setter.get.invoke(target, v)

  private def _getFromField(o:AnyRef) =
    field.get.get(o)

  private def _setWithField(target: AnyRef, v: AnyRef) =
    field.get.set(target, v)
}

trait FieldMetaDataFactory {

  def build(parentMetaData: PosoMetaData[_], name: String, property: (Option[Field], Option[Method], Option[Method], Set[Annotation]), sampleInstance4OptionTypeDeduction: AnyRef, isOptimisticCounter: Boolean): FieldMetaData
}

object FieldMetaData {

  private val _EMPTY_ARRAY = new Array[Object](0)

  var factory = new FieldMetaDataFactory {
    def build(parentMetaData: PosoMetaData[_], name: String, property: (Option[Field], Option[Method], Option[Method], Set[Annotation]), sampleInstance4OptionTypeDeduction: AnyRef, isOptimisticCounter: Boolean) = {

      val field  = property._1
      val getter = property._2
      val setter = property._3
      val annotations = property._4

      val colAnnotation = annotations.find(a => a.isInstanceOf[Column]).map(a => a.asInstanceOf[Column])

      var typeOfField =
        if(setter != None)
          setter.get.getParameterTypes.apply(0)
        else if(getter != None)
          getter.get.getReturnType
        else if(field != None)
          field.get.getType
        else
          error("invalid field group")

      var v =
         if(sampleInstance4OptionTypeDeduction != null) {
           if(field != None)
             field.get.get(sampleInstance4OptionTypeDeduction)
           else if(getter != None)
             getter.get.invoke(sampleInstance4OptionTypeDeduction, _EMPTY_ARRAY :_*)
           else
             createDefaultValue(parentMetaData.clasz, typeOfField, colAnnotation)
         }
         else null

      if(v != null && v == None) // can't deduce the type from None in this case the Annotation
        v = null         //needs to tell us the type, if it doesn't it will a few lines bellow

      var customTypeFactory: Option[AnyRef=>Product1[Any]] = None

      if(classOf[Product1[Any]].isAssignableFrom(typeOfField))
        customTypeFactory = _createCustomTypeFactory(parentMetaData.clasz, typeOfField)

      if(customTypeFactory != None) {
        val f = customTypeFactory.get
        v = f(null) // this creates a dummy (sample) field
      }

      if(v == null)
        v = try {
          createDefaultValue(parentMetaData.clasz, typeOfField, colAnnotation)
        }
        catch {
          case e:Exception => null
        }

      if(v == null)
        error("Could not deduce Option[] type of field '" + name + "' of class " + parentMetaData.clasz.getName)

      val isOption = v.isInstanceOf[Some[_]]

      val typeOfFieldOrTypeOfOption =
        if(!isOption)
          v.getClass
        else
          v.asInstanceOf[Option[AnyRef]].get.getClass

      val primitiveFieldType =
        if(v.isInstanceOf[Product1[_]])
          v.asInstanceOf[Product1[Any]]._1.asInstanceOf[AnyRef].getClass
        else if(isOption && v.asInstanceOf[Option[AnyRef]].get.isInstanceOf[Product1[_]]) {
          //if we get here, customTypeFactory has not had a chance to get created
          customTypeFactory = _createCustomTypeFactory(parentMetaData.clasz, typeOfFieldOrTypeOfOption)
          v.asInstanceOf[Option[AnyRef]].get.asInstanceOf[Product1[Any]]._1.asInstanceOf[AnyRef].getClass
        }
        else
          typeOfFieldOrTypeOfOption

      new FieldMetaData(
        parentMetaData,
        name,
        typeOfFieldOrTypeOfOption,
        primitiveFieldType,
        customTypeFactory,
        isOption,
        getter,
        setter,
        field,
        colAnnotation,
        isOptimisticCounter)
    }
  }

  /**
   * creates a closure that takes a java.lang. primitive wrapper (ex.: java.lang.Integer) and
   * that creates an instance of a custom type with it, the factory accepts null to create
   * default values for non nullable primitive types (int, long, etc...)
   */
  private def _createCustomTypeFactory(ownerClass: Class[_], typeOfField: Class[_]): Option[AnyRef=>Product1[Any]] = {
    for(c <- typeOfField.getConstructors if c.getParameterTypes.length == 1) {
      val pTypes = c.getParameterTypes
      val dv = createDefaultValue(ownerClass, pTypes(0), None)
      if(dv != null)
        return  Some(
          (i:AnyRef)=> {
            if(i != null)
              c.newInstance(i).asInstanceOf[Product1[Any]]
            else
              c.newInstance(dv).asInstanceOf[Product1[Any]]
          }
        )
    }

    None
  }

  def defaultFieldLength(fieldType: Class[_]) =
    _defaultFieldLengthAssigner.handleType(fieldType, None)

  private val _defaultFieldLengthAssigner = new FieldTypeHandler[Int] {

    def handleIntType = 4
    def handleStringType  = 255
    def handleStringType(l:Int) = error("Trying to get default length for string whose length was specified.")
    def handleBooleanType = 1
    def handleDoubleType = 8
    def handleDateType = -1
    def handleLongType = 8
    def handleFloatType = 4
    def handleBigDecimalType = 32
    def handleBigDecimalType(p:Int,s:Int) = error("Trying to get default length for bigdecimal whose length was specified.")
    def handleTimestampType = -1
    def handleUnknownType(c: Class[_]) = error("Cannot assign field length for " + c.getName)
  }

  def defaultFieldScale(fieldType: Class[_]) =
      _defaultFieldScaleAssigner.handleType(fieldType, None)

  private val _defaultFieldScaleAssigner = new FieldTypeHandler[Int] {
    def handleIntType = -1
    def handleStringType  = -1
    def handleStringType(l:Int) = -1
    def handleBooleanType = -1
    def handleDoubleType = -1
    def handleDateType = -1
    def handleLongType = -1
    def handleFloatType = -1
    def handleBigDecimalType = 16
    def handleBigDecimalType(p:Int,s:Int) = error("Trying to get default scale for bigdecimal whose scale was specified.")
    def handleTimestampType = -1
    def handleUnknownType(c: Class[_]) = error("Cannot assign field scale for " + c.getName)
  }

  private val _defaultValueFactory = new FieldTypeHandler[AnyRef] {

    def handleIntType = new java.lang.Integer(0)
    def handleStringType  = ""
    def handleStringType(l:Int)  = ""
    def handleBooleanType = new java.lang.Boolean(false)
    def handleDoubleType = new java.lang.Double(0.0)
    def handleDateType = new java.util.Date()
    def handleLongType = new java.lang.Long(0)
    def handleFloatType = new java.lang.Float(0)
    def handleBigDecimalType = new scala.math.BigDecimal(java.math.BigDecimal.ZERO)
    def handleBigDecimalType(p:Int,s:Int) = new scala.math.BigDecimal(java.math.BigDecimal.ZERO)
    def handleTimestampType = new java.sql.Timestamp(0)
    def handleUnknownType(c: Class[_]) = null
  }

  private val _mapper = new FieldTypeHandler[(ResultSet,Int)=>AnyRef] {

    private def _handleNull(rs: ResultSet, v: Any) =
      if(rs.wasNull)
        null
      else
        v.asInstanceOf[AnyRef]

    val _intM =     (rs:ResultSet,i:Int) => _handleNull(rs, rs.getInt(i))
    val _stringM =  (rs:ResultSet,i:Int) => _handleNull(rs, rs.getString(i))
    val _doubleM =  (rs:ResultSet,i:Int) => _handleNull(rs, rs.getDouble(i))
    val _booleanM = (rs:ResultSet,i:Int) => _handleNull(rs, rs.getBoolean(i))
    //(rs:ResultSet,i:Int) => Session.currentSession.databaseAdapter.convertToBooleanForJdbc(rs, i)
    val _dateM =    (rs:ResultSet,i:Int) => _handleNull(rs, rs.getDate(i))
    val _longM =    (rs:ResultSet,i:Int) => _handleNull(rs, rs.getLong(i))
    val _floatM =   (rs:ResultSet,i:Int) => _handleNull(rs, rs.getFloat(i))
    val _bigDecM =  (rs:ResultSet,i:Int) => _handleNull(rs, new scala.math.BigDecimal(rs.getBigDecimal(i)))
    val _timestampM =    (rs:ResultSet,i:Int) => _handleNull(rs, rs.getTimestamp(i))

    def handleIntType = _intM
    def handleStringType  = _stringM
    def handleStringType(l:Int)  = _stringM
    def handleBooleanType = _booleanM
    def handleDoubleType = _doubleM
    def handleDateType = _dateM
    def handleFloatType = _floatM
    def handleLongType = _longM
    def handleBigDecimalType = _bigDecM
    def handleBigDecimalType(p:Int,s:Int) = _bigDecM
    def handleTimestampType = _timestampM

    def handleUnknownType(c: Class[_]) =
      error("field type " + c.getName + " is not supported")
  }

  def resultSetHandlerFor(c: Class[_]) =
    _mapper.handleType(c, None)

//  def createDefaultValue(ownerCLass: Class[_], p: Class[_], optionFieldsInfo: Array[Annotation]): Object =
//    createDefaultValue(ownerCLass, p, optionFieldsInfo.find(a => a.isInstanceOf[Column]).map(a => a.asInstanceOf[Column]))

  def createDefaultValue(ownerCLass: Class[_], p: Class[_], optionFieldsInfo: Option[Column]): Object = {

    if(p.isAssignableFrom(classOf[Option[Any]])) {

//      if(optionFieldsInfo == None)
//        error("Option[Option[]] fields in "+ownerCLass.getName+ " are not supported")
//
//      if(optionFieldsInfo.size == 0)
//        return null
//      val oc0 = optionFieldsInfo.find(a => a.isInstanceOf[Column])
//      if(oc0 == None)
//        return null
//
//      val oc = oc0.get.asInstanceOf[Column]

      if(optionFieldsInfo == None)
        return null

      if(classOf[Object].isAssignableFrom(optionFieldsInfo.get.optionType))
        error("cannot deduce type of Option[] in " + ownerCLass.getName)

      Some(createDefaultValue(ownerCLass, optionFieldsInfo.get.optionType, optionFieldsInfo))
    }
    else
      _defaultValueFactory.handleType(p, None)
  }
  
}
