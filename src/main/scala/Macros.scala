package metaconfig.generic
import metaconfig.*

import scala.quoted.*
import scala.deriving.*
import java.nio.file.Paths
import java.io.File
import scala.annotation.StaticAnnotation
import metaconfig.annotation.TabCompleteAsPath

inline def deriveEncoder[T]: metaconfig.ConfEncoder[T] =
  ${ deriveEncoderImpl[T] }

inline def deriveDecoder[T](inline default: T): metaconfig.ConfDecoder[T] =
  ${ deriveConfDecoderImpl[T]('default) }

inline def deriveSurface[T]: Surface[T] =
  ${ deriveSurfaceImpl[T] }

private[generic] def deriveEncoderImpl[T](using tp: Type[T])(using q: Quotes) =
  import q.reflect.*
  assumeCaseClass[T]
  val str                    = Expr("Show: " + Type.show[T])
  val encoders               = params[T]
  val fields                 = paramNames[T]
  val ev: Expr[Mirror.Of[T]] = Expr.summon[Mirror.Of[T]].get
  ev match
    case '{
          $m: Mirror.ProductOf[T] { type MirroredElemTypes = elementTypes }
        } =>
      '{
        new metaconfig.ConfEncoder[T]:
          override def write(value: T): metaconfig.Conf =
            val prod = value.asInstanceOf[Product]
            new metaconfig.Conf.Obj(
              $fields.zip($encoders).zipWithIndex.map {
                case ((name, enc), idx) =>
                  name -> enc
                    .asInstanceOf[ConfEncoder[Any]]
                    .write(prod.productElement(idx))
              }
            )
      }
  end match
end deriveEncoderImpl

def deriveConfDecoderImpl[T: Type](default: Expr[T])(using q: Quotes) =
  import q.reflect.*
  assumeCaseClass[T]

  val cls    = Expr(Type.show[T])
  val clsTpt = TypeRepr.of[T]
  val settings =
    Expr.summon[Settings[T]] match
      case None =>
        report.error(s"Missing Implicit for Settings[${Type.show[T]}]"); ???
      case Some(v) => v

  val paramss = TypeRepr.of[T].classSymbol.get.primaryConstructor.paramSymss

  def next(p: ValDef): Expr[Conf => Configured[Any]] =
    p.tpt.tpe.asType match
      case '[t] =>
        val name     = Expr(p.name)
        val getter   = clsTpt.classSymbol.get.declaredField(p.name)
        val fallback = Select(default.asTerm, getter).asExprOf[t]
        val dec = Expr
          .summon[ConfDecoder[t]]
          .getOrElse {
            report.error(
              "Could not find an implicit decoder for type" +
                s"'${TypeTree.of[t].show}' for field ${p.name} of class ${clsTpt.show}"
            )
            ???
          }

        '{ conf =>
          conf.getSettingOrElse[t](
            $settings.unsafeGet($name),
            $fallback
          )(using $dec)
        }

  if paramss.head.isEmpty then '{ ConfDecoder.constant($default) }
  else
    val (head :: params) :: Nil = paramss
    val vds = paramss.head.map(_.tree).collect { case vd: ValDef =>
      next(vd)
    }

    // def construct: Expr[List[Configured[Any]] => T]
    val mir = Expr.summon[Mirror.ProductOf[T]].get

    val parameters = Expr.ofList(vds)
    val merged = '{ (conf: Conf) =>
      $parameters
        .map { f =>
          f(conf).map(Array.apply(_))
        }
        .reduceLeft((acc, nx) =>
          acc.product(nx).map { x =>
            x._1 ++ x._2
          }
        )
        .map(Tuple.fromArray)
        .map($mir.fromProduct)
    }

    '{
      new ConfDecoder[T]:
        def read(conf: Conf): Configured[T] = $merged(conf)
    }
  end if
end deriveConfDecoderImpl

def deriveSurfaceImpl[T: Type](using q: Quotes) =
  import q.reflect.*
  assumeCaseClass[T]

  val cls   = TypeRepr.of[T].classSymbol.get
  val bases = TypeTree.of[T].tpe.baseClasses
  val argss = cls.primaryConstructor.paramSymss.map { params =>
    val fields = params.map { param =>
      param.tree match
        case vd: ValDef =>
          val baseAnnots = param.annotations.collect {
            case annot
                if annot.tpe.derivesFrom(
                  TypeRepr.of[StaticAnnotation].typeSymbol
                ) =>
              annot.asExprOf[StaticAnnotation]
          }
          val isConf =
            vd.tpt.tpe.derivesFrom(TypeRepr.of[metaconfig.Conf].typeSymbol)
          val isMap = vd.tpt.tpe.derivesFrom(TypeRepr.of[Map[?, ?]].typeSymbol)
          val isIterable =
            vd.tpt.tpe.derivesFrom(TypeRepr.of[Iterable[?]].typeSymbol)

          val repeated =
            if isIterable then List('{ new metaconfig.annotation.Repeated })
            else Nil

          val dynamic =
            if isMap || isConf then List('{ new metaconfig.annotation.Dynamic })
            else Nil

          val flag =
            if vd.tpt.tpe.derivesFrom(TypeRepr.of[Boolean].typeSymbol) then
              List('{ new metaconfig.annotation.Flag })
            else Nil

          val tabCompletePath: List[Expr[StaticAnnotation]] =
            if vd.tpt.tpe.derivesFrom(
                TypeRepr.of[Paths].typeSymbol
              ) || vd.tpt.tpe.derivesFrom(TypeRepr.of[File].typeSymbol)
            then List('{ new metaconfig.annotation.TabCompleteAsPath })
            else Nil

          val finalAnnots =
            Expr.ofList(
              repeated ::: dynamic ::: flag ::: tabCompletePath ::: baseAnnots
            )

          val fieldType = vd.tpt.tpe

          val underlying: Expr[List[List[Field]]] = vd.tpt.tpe.asType match
            case '[t] =>
              Expr.summon[Surface[t]] match
                case None    => '{ Nil }
                case Some(e) => '{ $e.fields }

          val fieldName = Expr(vd.name)
          val tpeString = Expr(fieldType.show(using Printer.TypeReprCode))

          val fieldExpr = '{
            new Field($fieldName, $tpeString, $finalAnnots, $underlying)
          }
          fieldExpr
    }

    Expr.ofList(fields)
  }

  val args = Expr.ofList(argss)

  '{ new Surface[T]($args, Nil) }
end deriveSurfaceImpl

def getParams(m: Symbol)(using q: Quotes) =
  import q.reflect.*

def assumeCaseClass[T: Type](using q: Quotes) =
  import q.reflect.*
  val sym         = TypeTree.of[T].symbol
  val isCaseClass = sym.isClassDef && sym.flags.is(Flags.Case)
  if !isCaseClass then report.error(s"${Type.show[T]} must be a case class")

def params[T: Type](using q: Quotes) =
  import q.reflect.*
  val fields  = TypeTree.of[T].symbol.caseFields
  val encoder = TypeRepr.of[ConfEncoder]
  Expr.ofList {
    fields.map { f =>
      val tp = TypeRepr.of[T].memberType(f)
      val nm = f.name

      Implicits.search(encoder.appliedTo(tp)) match
        case iss: ImplicitSearchSuccess =>
          iss.tree.asExpr
        case isf: ImplicitSearchFailure =>
          report.error(
            s"can't find conversion for ${tp.show(using Printer.TypeReprCode)}"
          )
          '{ ??? }
    }
  }

end params

def paramNames[T: Type](using q: Quotes) =
  import q.reflect.*
  val fields = TypeTree.of[T].symbol.caseFields.map(_.name)

  Expr(fields)
