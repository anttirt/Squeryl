package org.squeryl.dsl

import ast._
import org.squeryl.internals.{StatementWriter, OutMapper}
import java.sql.ResultSet
import java.util.Date


trait DslFactory
  extends ExpressionDsl
    with SqlFunctions {

  protected def createLeafNodeOfScalarIntType(i: IntType): ScalarInt
  protected def createLeafNodeOfScalarIntOptionType(i: Option[IntType]): ScalarIntOption

  protected def createLeafNodeOfScalarDoubleType(d: DoubleType): ScalarDouble
  protected def createLeafNodeOfScalarDoubleOptionType(d: Option[DoubleType]): ScalarDoubleOption

  protected def createLeafNodeOfScalarFloatType(d: FloatType): ScalarFloat
  protected def createLeafNodeOfScalarFloatOptionType(d: Option[FloatType]): ScalarFloatOption

  protected def createLeafNodeOfScalarStringType(s: StringType): ScalarString
  protected def createLeafNodeOfScalarStringOptionType(s: Option[StringType]): ScalarStringOption

  protected def createLeafNodeOfScalarLongType(s: LongType): ScalarLong
  protected def createLeafNodeOfScalarLongOptionType(s: Option[LongType]): ScalarLongOption

  protected def createLeafNodeOfScalarBooleanType(s: BooleanType): ScalarBoolean
  protected def createLeafNodeOfScalarBooleanOptionType(s: Option[BooleanType]): ScalarBooleanOption

  protected def createLeafNodeOfScalarDateType(d: DateType): ScalarDate
  protected def createLeafNodeOfScalarDateOptionType(d: Option[DateType]): ScalarDateOption


//  protected def createLeafNodeOfAgregateIntType(i: IntType): AgregateIntOption

//  protected def createLeafNodeOfAgregateDoubleType(d: DoubleType): AgregateDoubleOption

//  protected def createLeafNodeOfAgregateFloatType(d: FloatType): AgregateFloatOption

//  protected def createLeafNodeOfAgregateStringType(s: StringType): AgregateStringOption

//  protected def createLeafNodeOfAgregateLongType(s: LongType): AgregateLongOption

//  protected def createLeafNodeOfAgregateBooleanType(s: BooleanType): AgregateBooleanOption

//  protected def createLeafNodeOfAgregateDateType(d: DateType): AgregateDateOption

  // expose Factory Methods implicit :
  // ScalarNode Types :
  implicit def int2ScalarInt(i: IntType) = createLeafNodeOfScalarIntType(i)

  implicit def double2ScalarDouble(d: DoubleType) = createLeafNodeOfScalarDoubleType(d)

  implicit def float2ScalarFloat(d: FloatType) = createLeafNodeOfScalarFloatType(d)

  implicit def string2ScalarString(s: StringType) = createLeafNodeOfScalarStringType(s)

  implicit def long2ScalarLong(l: LongType) = createLeafNodeOfScalarLongType(l)

  implicit def bool2ScalarBoolean(b: BooleanType) = createLeafNodeOfScalarBooleanType(b)

  implicit def date2ScalarDate(b: DateType) = createLeafNodeOfScalarDateType(b)

  implicit def optionInt2ScalarInt(i: Option[IntType]) = createLeafNodeOfScalarIntOptionType(i)

  implicit def optionLong2ScalarLong(i: Option[LongType]) = createLeafNodeOfScalarLongOptionType(i)

  implicit def optionString2ScalarString(i: Option[StringType]) = createLeafNodeOfScalarStringOptionType(i)

  implicit def optionDouble2ScalarDouble(i: Option[DoubleType]) = createLeafNodeOfScalarDoubleOptionType(i)

  implicit def optionFloat2ScalarFloat(i: Option[FloatType]) = createLeafNodeOfScalarFloatOptionType(i)

  implicit def optionBoolean2ScalarBoolean(i: Option[BooleanType]) = createLeafNodeOfScalarBooleanOptionType(i)

  implicit def optionDate2ScalarDate(i: Option[DateType]) = createLeafNodeOfScalarDateOptionType(i)

  
  // List Conversion implicits don't vary with the choice of
  // column/field types, so they don't need to be overridable factory methods :
  implicit def listOfInt2ListInt(l: List[IntType]) =
    new ConstantExpressionNodeList[IntType](l) with ListInt

  //TODO: relax type equivalence using Numerical, since databases allow things like 1 in (0.4,0.4, ...)
  implicit def listOfDouble2ListDouble(l: List[DoubleType]) =
    new ConstantExpressionNodeList[DoubleType](l) with ListDouble

  implicit def listOfFloat2ListFloat(l: List[FloatType]) =
    new ConstantExpressionNodeList[FloatType](l) with ListFloat

  implicit def listOfLong2ListLong(l: List[LongType]) =
    new ConstantExpressionNodeList[LongType](l) with ListLong

  implicit def listOfString2ListString(l: List[StringType]) =
    new ConstantExpressionNodeList[StringType](l) with ListString

  implicit def listOfDate2ListDate(l: List[DateType]) =
    new ConstantExpressionNodeList[DateType](l) with ListDate

  trait AgregateArg {
    def expression: ExpressionNode
    def mapper: OutMapper[_]
  }
  
  class GroupArg[T](val expression: TypedExpressionNode[Scalar,T], val mapper: OutMapper[T]) extends AgregateArg

  //TODO: replace Scalar with Agregate when Scalac can handle disambiguation on context
  class ComputeArg[T](val expression: TypedExpressionNode[Agregate,T], val mapper: OutMapper[T]) extends AgregateArg
    

  class OrderByArg(e: NonLogicalBoolean[Scalar,_]) extends ExpressionNode {

    override def inhibited = e.inhibited

    def doWrite(sw: StatementWriter) = {
      e.write(sw)
      if(isAscending)
        sw.write(" Asc")
      else
        sw.write(" Desc")
    }

    override def children = List(e)
    
    private var _ascending = true

    private [squeryl] def isAscending = _ascending

    def Asc = {
      _ascending = true
      this
    }

    def Desc = {
      _ascending = false
      this
    }
  }

  implicit def nonBoolean2OrderByArg(e: NonLogicalBoolean[Scalar,_]) = new OrderByArg(e)

  implicit def string2OrderByArg(s: StringType) =
    new OrderByArg(createLeafNodeOfScalarStringType(s))

  implicit def int2OrderByArg(i: IntType) =
    new OrderByArg(createLeafNodeOfScalarIntType(i))

  implicit def double2OrderByArg(i: DoubleType) =
    new OrderByArg(createLeafNodeOfScalarDoubleType(i))

  implicit def float2OrderByArg(i: FloatType) =
    new OrderByArg(createLeafNodeOfScalarFloatType(i))

  implicit def long2OrderByArg(l: LongType) =
    new OrderByArg(createLeafNodeOfScalarLongType(l))

  implicit def date2OrderByArg(l: DateType) =
    new OrderByArg(createLeafNodeOfScalarDateType(l))

//  implicit def bool2OrderByArg(b: BooleanType) =
//    new OrderByArg(createLeafNodeOfScalarBooleanType(b))

  protected def mapInt2IntType(i: Int): IntType
  protected def mapString2StringType(s: String): StringType
  protected def mapDouble2DoubleType(d: Double): DoubleType
  protected def mapFloat2FloatType(d: Float): FloatType
  protected def mapLong2LongType(l: Long): LongType
  protected def mapBoolean2BooleanType(b: Boolean): BooleanType
  protected def mapDate2DateType(b: Date): DateType
  
  //TODO: make these methods protected when COMPILER won't crash !!!
  def createOutMapperIntType: OutMapper[IntType] = new OutMapper[IntType] {
    def doMap(rs: ResultSet) = mapInt2IntType(rs.getInt(index))
    def sample = sampleInt
  }
  
  def createOutMapperStringType: OutMapper[StringType] = new OutMapper[StringType] {
    def doMap(rs: ResultSet) = mapString2StringType(rs.getString(index))
    def sample = sampleString
  }

  def createOutMapperDoubleType: OutMapper[DoubleType] = new OutMapper[DoubleType] {
    def doMap(rs: ResultSet) = mapDouble2DoubleType(rs.getDouble(index))
    def sample = sampleDouble
  }

  def createOutMapperFloatType: OutMapper[FloatType] = new OutMapper[FloatType] {
    def doMap(rs: ResultSet) = mapFloat2FloatType(rs.getFloat(index))
    def sample = sampleFloat
  }

  def createOutMapperLongType: OutMapper[LongType] = new OutMapper[LongType] {
    def doMap(rs: ResultSet) = mapLong2LongType(rs.getLong(index))
    def sample = sampleLong
  }

  def createOutMapperBooleanType: OutMapper[BooleanType] = new OutMapper[BooleanType] {
    def doMap(rs: ResultSet) = mapBoolean2BooleanType(rs.getBoolean(index))
    def sample = sampleBoolean
  }

  def createOutMapperDateType: OutMapper[DateType] = new OutMapper[DateType] {
    def doMap(rs: ResultSet) = mapDate2DateType(rs.getDate(index))
    def sample = sampleDate
  }

  def createOutMapperIntTypeOption: OutMapper[Option[IntType]] = new OutMapper[Option[IntType]] {
    def doMap(rs: ResultSet) = {
      val v = mapInt2IntType(rs.getInt(index))
      if(rs.wasNull)
        None
      else
        Some(v)
    }
    def sample = Some(sampleInt)
  }

  def createOutMapperDoubleTypeOption: OutMapper[Option[DoubleType]] = new OutMapper[Option[DoubleType]] {
    def doMap(rs: ResultSet) = {
      val v = mapDouble2DoubleType(rs.getDouble(index))
      if(rs.wasNull)
        None
      else
        Some(v)
    }
    def sample = Some(sampleDouble)
  }

  def createOutMapperFloatTypeOption: OutMapper[Option[FloatType]] = new OutMapper[Option[FloatType]] {
    def doMap(rs: ResultSet) = {
      val v = mapFloat2FloatType(rs.getFloat(index))
      if(rs.wasNull)
        None
      else
        Some(v)
    }
    def sample = Some(sampleFloat)
  }

  def createOutMapperStringTypeOption: OutMapper[Option[StringType]] = new OutMapper[Option[StringType]] {
    def doMap(rs: ResultSet) = {
      val v = mapString2StringType(rs.getString(index))
      if(rs.wasNull)
        None
      else
        Some(v)
    }
    def sample = Some(sampleString)
  }

  def createOutMapperLongTypeOption: OutMapper[Option[LongType]] = new OutMapper[Option[LongType]] {
    def doMap(rs: ResultSet) = {
      val v = mapLong2LongType(rs.getLong(index))
      if(rs.wasNull)
        None
      else
        Some(v)
    }
    def sample = Some(sampleLong)
  }

  def createOutMapperBooleanTypeOption: OutMapper[Option[BooleanType]] = new OutMapper[Option[BooleanType]] {
    def doMap(rs: ResultSet) = {
      val v = mapBoolean2BooleanType(rs.getBoolean(index))
      if(rs.wasNull)
        None
      else
        Some(v)
    }
    def sample = Some(sampleBoolean)
  }

  def createOutMapperDateTypeOption: OutMapper[Option[DateType]] = new OutMapper[Option[DateType]] {
    def doMap(rs: ResultSet) = {
      val v = mapDate2DateType(rs.getDate(index))
      if(rs.wasNull)
        None
      else
        Some(v)
    }
    def sample = Some(sampleDate)
  }

  // implicits for convering GroupArgs from fields:
  implicit def string2GroupArg(s: StringType) =
    new GroupArg[StringType](createLeafNodeOfScalarStringType(s), createOutMapperStringType)

  implicit def int2GroupArg(i: IntType) =
    new GroupArg[IntType](createLeafNodeOfScalarIntType(i), createOutMapperIntType)

  implicit def double2GroupArg(i: DoubleType) =
    new GroupArg[DoubleType](createLeafNodeOfScalarDoubleType(i), createOutMapperDoubleType)

  implicit def float2GroupArg(i: FloatType) =
    new GroupArg[FloatType](createLeafNodeOfScalarFloatType(i), createOutMapperFloatType)

  implicit def long2GroupArg(i: LongType) =
    new GroupArg[LongType](createLeafNodeOfScalarLongType(i), createOutMapperLongType)

  implicit def boolean2GroupArg(i: BooleanType) =
    new GroupArg[BooleanType](createLeafNodeOfScalarBooleanType(i), createOutMapperBooleanType)
  
  implicit def date2GroupArg(i: DateType) =
    new GroupArg[DateType](createLeafNodeOfScalarDateType(i), createOutMapperDateType)

  // GroupArgs from expressions

  implicit def expr2SclarIntGroupArg(e: TypedExpressionNode[Scalar,IntType]) =
    new GroupArg[IntType](e, createOutMapperIntType)
  implicit def expr2SclarIntOptionGroupArg(e: TypedExpressionNode[Scalar,Option[IntType]]) =
    new GroupArg[Option[IntType]](e, createOutMapperIntTypeOption)

  implicit def expr2SclarStringGroupArg(e: TypedExpressionNode[Scalar,StringType]) =
    new GroupArg[StringType](e, createOutMapperStringType)
  implicit def expr2SclarStringOptionGroupArg(e: TypedExpressionNode[Scalar,Option[StringType]]) =
    new GroupArg[Option[StringType]](e, createOutMapperStringTypeOption)


  implicit def expr2SclarDoubleGroupArg(e: TypedExpressionNode[Scalar,DoubleType]) =
    new GroupArg[DoubleType](e, createOutMapperDoubleType)
  implicit def expr2SclarDoubleOptionGroupArg(e: TypedExpressionNode[Scalar,Option[DoubleType]]) =
    new GroupArg[Option[DoubleType]](e, createOutMapperDoubleTypeOption)

  implicit def expr2SclarFloatGroupArg(e: TypedExpressionNode[Scalar,FloatType]) =
    new GroupArg[FloatType](e, createOutMapperFloatType)

  implicit def expr2SclarFloatOptionGroupArg(e: TypedExpressionNode[Scalar,Option[FloatType]]) =
    new GroupArg[Option[FloatType]](e, createOutMapperFloatTypeOption)

  implicit def expr2SclarLongGroupArg(e: TypedExpressionNode[Scalar,LongType]) =
    new GroupArg[LongType](e, createOutMapperLongType)
  implicit def expr2SclarLongOptionGroupArg(e: TypedExpressionNode[Scalar,Option[LongType]]) =
    new GroupArg[Option[LongType]](e, createOutMapperLongTypeOption)

  implicit def expr2SclarBooleanGroupArg(e: TypedExpressionNode[Scalar,BooleanType]) =
    new GroupArg[BooleanType](e, createOutMapperBooleanType)
  implicit def expr2SclarBooleanOptionGroupArg(e: TypedExpressionNode[Scalar,Option[BooleanType]]) =
    new GroupArg[Option[BooleanType]](e, createOutMapperBooleanTypeOption)

  implicit def expr2SclarDateGroupArg(e: TypedExpressionNode[Scalar,DateType]) =
    new GroupArg[DateType](e, createOutMapperDateType)
  implicit def expr2SclarDateOptionGroupArg(e: TypedExpressionNode[Scalar,Option[DateType]]) =
    new GroupArg[Option[DateType]](e, createOutMapperDateTypeOption)

  // ComputeArgs from agregate expressions:
  
  implicit def agreateInt2ComputeArg(e: TypedExpressionNode[Agregate,IntType]) =
    new ComputeArg[IntType](e, createOutMapperIntType)

  implicit def agreateOptionInt2ComputeArg(e: TypedExpressionNode[Agregate,Option[IntType]]) =
    new ComputeArg[Option[IntType]](e, createOutMapperIntTypeOption)
  
  implicit def agreateString2ComputeArg(e: TypedExpressionNode[Agregate,Option[StringType]]) =
    new ComputeArg[Option[StringType]](e, createOutMapperStringTypeOption)

  implicit def agreateDoubleOption2ComputeArg(e: TypedExpressionNode[Agregate,Option[DoubleType]]) =
    new ComputeArg[Option[DoubleType]](e, createOutMapperDoubleTypeOption)

  implicit def agreateDouble2ComputeArg(e: TypedExpressionNode[Agregate,DoubleType]) =
    new ComputeArg[DoubleType](e, createOutMapperDoubleType)
  
  implicit def agreateFloat2ComputeArg(e: TypedExpressionNode[Agregate,Option[FloatType]]) =
    new ComputeArg[Option[FloatType]](e, createOutMapperFloatTypeOption)

  implicit def agreateLongOption2ComputeArg(e: TypedExpressionNode[Agregate,Option[LongType]]) =
    new ComputeArg[Option[LongType]](e, createOutMapperLongTypeOption)

  implicit def agreateLong2ComputeArg(e: TypedExpressionNode[Agregate,LongType]) =
    new ComputeArg[LongType](e, createOutMapperLongType)
  
  implicit def agreateBoolean2ComputeArg(e: TypedExpressionNode[Agregate,Option[BooleanType]]) =
    new ComputeArg[Option[BooleanType]](e, createOutMapperBooleanTypeOption)

  implicit def agreateDate2ComputeArg(e: TypedExpressionNode[Agregate,Option[DateType]]) =
    new ComputeArg[Option[DateType]](e, createOutMapperDateTypeOption)

  protected def sampleInt: IntType
  protected def sampleString: StringType
  protected def sampleDouble: DoubleType
  protected def sampleFloat: FloatType
  protected def sampleLong: LongType
  protected def sampleBoolean: BooleanType
  protected def sampleDate: DateType
}