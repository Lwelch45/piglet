/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dbis.pig.parser

import dbis.pig._
import dbis.pig.op._
import dbis.pig.plan.DataflowPlan
import dbis.pig.schema._

import scala.util.parsing.combinator.JavaTokenParsers
import scala.util.parsing.input.CharSequenceReader
import scala.language.implicitConversions
import scala.language.postfixOps

/**
 * An enumeration type representing the various language sets
 * supported by the Pig compiler.
 */
object LanguageFeature extends Enumeration {
  type LanguageFeature = Value
  val PlainPig, // standard Pig conforming to Apache Pig
  SparqlPig,    // Pig + SPARQL extensions (TUPLIFY, BGP filter)
  StreamingPig  // Pig for data stream processing
    = Value
}

import dbis.pig.parser.LanguageFeature._

/**
 * A parser for the (extended) Pig language.
 */
class PigParser extends JavaTokenParsers {
  override protected val whiteSpace = """(\s|--.*)+""".r

  /**
   * A helper class for supporting case-insensitive keywords.
   *
   * @param str the keyword string to be handled.
   */
  class CaseInsensitiveString(str: String) {
    def ignoreCase: Parser[String] = ("""(?i)\Q""" + str + """\E""").r
  }

  implicit def pimpString(str: String): CaseInsensitiveString = new CaseInsensitiveString(str)

  def pigStringLiteral: Parser[String] =
    ("'"+"""([^'\p{Cntrl}\\]|\\[\\"bfnrt]|\\u[a-fA-F0-9]{4})*"""+"'").r

  def unquote(s: String): String = s.substring(1, s.length - 1)

  def num: Parser[Int] = wholeNumber ^^ (_.toInt)

  def bag: Parser[String] = ident
  def fileName: Parser[String] = pigStringLiteral ^^ { str => unquote(str) }

  def className: Parser[String] = repsep(ident, ".") ^^ { identList => identList.mkString(".")}

  /*
   * A reference can be a named field, a positional field (e.g $0, $1, ...) or a literal.
   */
  def posField: Parser[Ref] = """\$[0-9]*""".r ^^ { p => PositionalField(p.substring(1, p.length).toInt) }
  def namedField: Parser[Ref] = ident ^^ { i => NamedField(i) }
  def literalField: Parser[Ref] = (floatingPointNumber ^^ { n => Value(n) } | stringLiteral ^^ { s => Value(s) })
  def fieldSpec: Parser[Ref] = (posField | namedField | literalField)
  /*
   * It can be also a dereference operator for tuples, bags or maps.
   */
  def derefBagOrTuple: Parser[Ref] = (posField | namedField) ~ "." ~ (posField | namedField) ^^ { case r1 ~ _ ~ r2 => DerefTuple(r1, r2) }
  def derefMap: Parser[Ref] = (posField | namedField) ~ "#" ~ stringLiteral ^^ { case m ~ _ ~ k => DerefMap(m, k) }

  def ref: Parser[Ref] = (derefMap |  derefBagOrTuple | fieldSpec) // ( fieldSpec | derefBagOrTuple | derefMap)

  def arithmExpr: Parser[ArithmeticExpr] = term ~ rep("+" ~ term | "-" ~ term) ^^ {
    case l ~ list => list.foldLeft(l) {
      case (x, "+" ~ i) => Add(x,i)
      case (x, "-" ~ i) => Minus(x,i)
    }
  }

  def term: Parser[ArithmeticExpr] = factor ~ rep("*" ~ factor | "/" ~ factor) ^^ {
    case l ~ list => list.foldLeft(l) {
      case (x, "*" ~ i) => Mult(x,i)
      case (x, "/" ~ i) => Div(x,i)
    }
  }

  // def typeName: Parser[String] = ( "int" | "float" | "double" | "chararray"| " bytearray") ^^ { s => s }

  def castTypeSpec: Parser[PigType] = (
    "int" ^^ { _ => Types.IntType }
      | "long" ^^ { _ => Types.LongType }
      | "float" ^^ { _ => Types.FloatType }
      | "double" ^^ { _ => Types.DoubleType }
      | "boolean" ^^ { _ => Types.BooleanType }
      | "chararray" ^^ { _ => Types.CharArrayType }
      | "bytearray" ^^{ _ => Types.ByteArrayType }
      | "tuple" ~ "(" ~ repsep(castTypeSpec, ",") ~ ")" ^^{
            case _ ~ _ ~ typeList ~ _ => TupleType(typeList.map(t => Field("", t)).toArray)
        }
      /*
       * bag schema: bag{tuple(<list of types>)}
       */
     // | "bag" ~ "{" ~ "tuple" ~ "(" ~ ")" ~ repsep(castTypeSpec, ",") ~ "}" ^^{ case _ ~ "{" ~ tup ~ "}" => BagType("", tup) }
      /*
       * map schema: map[<list of types>]
       */
      | "map" ~ "[" ~ "]" ^^{ case _ ~ _ ~ _ => MapType(Types.ByteArrayType) }
    )

  def factor: Parser[ArithmeticExpr] =  (
     "(" ~ castTypeSpec ~ ")" ~ refExpr ^^ { case _ ~ t ~ _ ~ e => CastExpr(t, e) }
      | "(" ~ arithmExpr ~ ")" ^^ { case _ ~ e ~ _ => PExpr(e) }
       | "flatten" ~ "(" ~ arithmExpr ~ ")" ^^ { case _ ~ _ ~ e ~ _  => FlattenExpr(e) }
       | typeConstructor
       | func
      | refExpr
    )

  def func: Parser[ArithmeticExpr] = className ~ "(" ~ repsep(arithmExpr, ",") ~ ")" ^^ { case f ~ _ ~ p ~ _ => Func(f, p) }
  def refExpr: Parser[ArithmeticExpr] = ref ^^ { r => RefExpr(r) }

  /*
   * And it can be also a type constrctor for tuple, bag or map.
  */
  def tupleConstructor: Parser[ArithmeticExpr] = "(" ~ repsep(arithmExpr, ",") ~ ")" ^^ { case _ ~ l ~ _ => ConstructTupleExpr(l) }
  def bagConstructor: Parser[ArithmeticExpr] = "{" ~ repsep(arithmExpr, ",") ~ "}"  ^^ { case _ ~ l ~ _ => ConstructBagExpr(l) }
  def mapConstructor: Parser[ArithmeticExpr] = "[" ~ repsep(arithmExpr, ",") ~ "]"  ^^ { case _ ~ l ~ _ => ConstructMapExpr(l) }
  def typeConstructor: Parser[ArithmeticExpr] = (tupleConstructor | bagConstructor | mapConstructor)

  def comparisonExpr: Parser[Predicate] = arithmExpr ~ ("!=" | "<=" | ">=" | "==" | "<" | ">") ~ arithmExpr ^^ {
    case a ~ op ~ b => op match {
      case "==" => Eq(a, b)
      case "!=" => Neq(a, b)
      case "<" => Lt(a, b)
      case "<=" => Leq(a, b)
      case ">" => Gt(a, b)
      case ">=" => Geq(a, b)
    }
  }

  def logicalTerm: Parser[Predicate] = (
    comparisonExpr ^^ { e => e }
     | "(" ~ logicalExpr ~ ")" ^^ { case _ ~ e ~ _ => PPredicate(e) }
    )

  def logicalExpr: Parser[Predicate] = (
      logicalTerm ~ (andKeyword | orKeyword) ~ logicalTerm ^^ {
        case a ~ op ~ b => op match {
          case "AND" => And(a, b)
          case "OR" => Or(a, b)
        }
      }
      | notKeyword ~ logicalTerm ^^ { case _ ~ e => Not(e) }
      | logicalTerm ^^ { e => e }
     )

  /*
   * The list of case-insensitive keywords we want to accept.
   */
  lazy val loadKeyword = "load".ignoreCase
  lazy val dumpKeyword = "dump".ignoreCase
  lazy val storeKeyword = "store".ignoreCase
  lazy val intoKeyword = "into".ignoreCase
  lazy val filterKeyword = "filter".ignoreCase
  lazy val byKeyword = "by".ignoreCase
  lazy val groupKeyword = "group".ignoreCase
  lazy val allKeyword = "all".ignoreCase
  lazy val joinKeyword = "join".ignoreCase
  lazy val distinctKeyword = "distinct".ignoreCase
  lazy val describeKeyword = "describe".ignoreCase
  lazy val limitKeyword = "limit".ignoreCase
  lazy val usingKeyword = "using".ignoreCase
  lazy val foreachKeyword = "foreach".ignoreCase
  lazy val generateKeyword = "generate".ignoreCase
  lazy val asKeyword = "as".ignoreCase
  lazy val unionKeyword = "union".ignoreCase
  lazy val registerKeyword = "register".ignoreCase
  lazy val streamKeyword = "stream".ignoreCase
  lazy val throughKeyword = "through".ignoreCase
  lazy val sampleKeyword = "sample".ignoreCase
  lazy val orderKeyword = "order".ignoreCase
  lazy val ascKeyword = "asc".ignoreCase
  lazy val descKeyword = "desc".ignoreCase
  lazy val andKeyword = "and".ignoreCase
  lazy val orKeyword = "or".ignoreCase
  lazy val notKeyword = "not".ignoreCase
  lazy val toKeyword = "to".ignoreCase
  lazy val socketReadKeyword = "socket_read".ignoreCase
  lazy val socketWriteKeyword = "socket_write".ignoreCase
  lazy val modeKeyword = "mode".ignoreCase
  lazy val zmqKeyword = "zmq".ignoreCase
  lazy val windowKeyword = "window".ignoreCase
  lazy val rowsKeyword = "rows".ignoreCase
  lazy val rangeKeyword = "range".ignoreCase
  lazy val slideKeyword = "slide".ignoreCase
  lazy val splitKeyword = "split".ignoreCase
  lazy val ifKeyword = "if".ignoreCase
  lazy val materializeKeyword = "materialize".ignoreCase

  /*
   * tuple schema: tuple(<list of fields>) or (<list of fields>)
   */
  def tupleTypeSpec: Parser[TupleType] =
    ("tuple"?) ~ "(" ~repsep(fieldSchema, ",") ~ ")" ^^{ case _ ~ _ ~ fieldList ~ _ => TupleType(fieldList.toArray) }

  def typeSpec: Parser[PigType] = (
    "int" ^^ { _ => Types.IntType }
    | "long" ^^ { _ => Types.LongType }
    | "float" ^^ { _ => Types.FloatType }
    | "double" ^^ { _ => Types.DoubleType }
    | "boolean" ^^ { _ => Types.BooleanType }
    | "chararray" ^^ { _ => Types.CharArrayType }
    | "bytearray" ^^{ _ => Types.ByteArrayType }
    | tupleTypeSpec
      /*
       * bag schema: bag{<tuple>} or {<tuple>}
       */
    | ("bag"?) ~ "{" ~ ident ~ ":" ~ tupleTypeSpec ~ "}" ^^{ case _ ~ _ ~ id ~ _ ~ tup ~ _ => tup.name = id; BagType(tup) }
      /*
       * map schema: map[<list of fields>] or [<list of fields>]
       */
    | ("map"?) ~ "[" ~(typeSpec?) ~ "]" ^^{ case _ ~ _ ~ ty ~ _ => ty match {
        case Some(t) => MapType(t)
        case None => MapType(Types.ByteArrayType)
    }}
    )

  /*
   * schema of a field: <identifier> : type or simply <identifier>
   */
  def fieldType: Parser[PigType] = ":" ~ typeSpec ^^ { case _ ~ t => t }
  def fieldSchema: Parser[Field] = ident ~ (fieldType?) ^^ {
    case n ~ t => t match {
      case Some(tp) => Field(n, tp)
      case None => Field(n, Types.ByteArrayType)
    }
  }
  /*
   * <A> = LOAD <B> "<FileName>" USING <StorageFunc> (<OptParameters>) [ AS (<Schema>) ]
   */
  def loadSchemaClause: Parser[Schema] = asKeyword ~ "(" ~ repsep(fieldSchema, ",") ~ ")" ^^{
    case _ ~ _ ~ fieldList ~ _ => Schema(BagType(TupleType(fieldList.toArray)))
  }

  def usingClause: Parser[(String, List[String])] = usingKeyword ~ ident ~ "(" ~ repsep(pigStringLiteral, ",") ~ ")" ^^ {
    case _ ~ loader ~ _ ~ params ~ _ => (loader, params)
  }

  def loadStmt: Parser[PigOperator] = bag ~ "=" ~ loadKeyword ~ fileName ~ (usingClause?) ~ (loadSchemaClause?) ^^ {
    case b ~ _ ~ _ ~ f ~ u ~ s => u match {
        case Some(p) => new Load(Pipe(b), f, s, p._1, if (p._2.isEmpty) null else p._2)
        case None => new Load(Pipe(b), f, s)
      }
  }

  /*
   * DUMP <A>
   */
  def dumpStmt: Parser[PigOperator] = dumpKeyword ~ bag ^^ { case _ ~ b => new Dump(Pipe(b)) }

  /*
   * STORE <A> INTO "<FileName>"
   */
  def storeStmt: Parser[PigOperator] = storeKeyword ~ bag ~ intoKeyword ~ fileName ^^ { case _ ~ b ~  _ ~ f => new Store(Pipe(b), f) }

  /*
   * GENERATE expr1, expr2, ...
   */
  def generateStmt: Parser[PigOperator] = plainForeachGenerator ^^ { case g => new Generate(g.asInstanceOf[GeneratorList].exprs) }

  /*
   * B = A.C;
   */
  def constructBagStmt: Parser[PigOperator] = bag ~ "=" ~ derefBagOrTuple ^^ { case res ~ _ ~ ref => new ConstructBag(Pipe(res), ref) }

  /*
   * Currently, Pig allows only DISTINCT, LIMIT, FILTER, ORDER BY + GENERATE and assignment inside FOREACH
   */
  def nestedStmt: Parser[PigOperator] = (distinctStmt | limitStmt | filterStmt | orderByStmt |
    generateStmt | constructBagStmt) ~ ";" ^^ { case op ~ _  => op }
  def nestedScript: Parser[List[PigOperator]] = rep(nestedStmt)

  /*
   * <A> = FOREACH <B> GENERATE <Expr> [ AS <Schema> ]
   * <A> = FOREACH <B> { <SubPlan> }
   */
  def exprSchema: Parser[Field] = asKeyword ~ fieldSchema ^^ { case _ ~ t => t }
  def genExpr: Parser[GeneratorExpr] = arithmExpr ~ (exprSchema?) ^^ { case e ~ s => GeneratorExpr(e, s) }
  def generatorList: Parser[List[GeneratorExpr]] = repsep(genExpr, ",")
  def plainForeachGenerator: Parser[ForeachGenerator] = generateKeyword ~ generatorList ^^ {
    case _ ~ exList => GeneratorList(exList)
  }
  def nestedForeachGenerator: Parser[ForeachGenerator] = "{" ~ nestedScript ~ "}" ^^ {
    case _ ~ opList ~ _ => GeneratorPlan(opList)
  }
  def foreachStmt: Parser[PigOperator] = bag ~ "=" ~ foreachKeyword ~ bag ~
    (plainForeachGenerator | nestedForeachGenerator) ^^ {
      case out ~ _ ~ _ ~ in ~ ex => new Foreach(Pipe(out), Pipe(in), ex)
    }

  /*
   * <A> = FILTER <B> BY <Predicate>
   */
  def filterStmt: Parser[PigOperator] = bag ~ "=" ~ filterKeyword ~ bag ~ byKeyword ~ logicalExpr ^^ {
    case out ~ _ ~ _ ~ in ~ _ ~ pred => new Filter(Pipe(out), Pipe(in), pred)
  }

  /*
   * DESCRIBE <A>
   */
  def describeStmt: Parser[PigOperator] = describeKeyword ~ bag ^^ { case _ ~ b => new Describe(Pipe(b)) }

  /*
   * <A> = GROUP <B> ALL
   * <A> = GROUP <B> BY <Ref>
   * <A> = GROUP <B> B> ( <ListOfRefs> )
   */
  def refList: Parser[List[Ref]] = (ref ^^ { r => List(r) } | "(" ~ repsep(ref, ",") ~ ")" ^^ { case _ ~ rlist ~ _ => rlist})
  def groupingClause: Parser[GroupingExpression] = allKeyword ^^ { s => GroupingExpression(List())} |
    (byKeyword ~ refList ^^ { case _ ~ rlist => GroupingExpression(rlist)})
  def groupingStmt: Parser[PigOperator] = bag ~ "=" ~ groupKeyword ~ bag ~ groupingClause ^^ {
    case out ~ _ ~ _ ~ in ~ grouping => new Grouping(Pipe(out), Pipe(in), grouping) }

  /*
   * <A> = DISTINCT <B>
   */
  def distinctStmt: Parser[PigOperator] = bag ~ "=" ~ distinctKeyword ~ bag ^^ { case out ~ _ ~ _ ~ in => new Distinct(Pipe(out), Pipe(in)) }

  /*
   * <A> = LIMIT <B> <Num>
   */
  def limitStmt: Parser[PigOperator] = bag ~ "=" ~ limitKeyword ~ bag ~ num ^^ { case out ~ _ ~ _ ~ in ~ num => new Limit(Pipe(out), Pipe(in), num) }

  /*
   * <A> = WINDOW <B> ROWS  <Num> SLIDE ROWS <Num>
   * <A> = WINDOW <B> ROWS  <Num> SLIDE RANGE <Num> <Unit>
   * <A> = WINDOW <B> RANGE <Num> <Unit> SLIDE ROWS <Num>
   * <A> = WINDOW <B> RANGE <Num> <Unit> SLIDE RANGE <Num> <Unit>
   */
  def timeUnit: Parser[String] = ("seconds".ignoreCase | "minutes".ignoreCase) ^^ { _.toUpperCase }
  def rangeParam: Parser[Tuple2[Int,String]] = rangeKeyword ~ num ~ timeUnit ^^ {case _ ~ n ~ u => (n,u)}
  def rowsParam: Parser[Tuple2[Int,String]] = rowsKeyword ~ num ^^ {case _ ~ n => (n, "")}
  def windowParam: Parser[Tuple2[Int,String]] = (rangeParam | rowsParam)
  def windowStmt: Parser[PigOperator] = bag ~ "=" ~ windowKeyword ~ bag ~ windowParam ~ slideKeyword ~ windowParam ^^ {
    case out ~ _ ~ _ ~ in ~ on ~ _ ~ slide => Window(Pipe(out), Pipe(in), on, slide)
  }

  /*
   * <A> = JOIN <B> BY <Ref>, <C> BY <Ref>, ...
   * <A> = JOIN <B> BY ( <ListOfRefs> ), <C> BY ( <ListOfRefs>), ...
   */
  def joinExpr: Parser[(String, List[Ref])] = bag ~ byKeyword ~ refList ^^ { case b ~ _ ~ rlist => (b, rlist) }
  def joinExprList: Parser[List[(String, List[Ref])]] = repsep(joinExpr, ",") ^^ { case jlist => jlist }
  def extractJoinRelation(jList: List[(String, List[Ref])]): List[Pipe] = { jList.map{ case (alias, refs) => Pipe(alias) } }
  def extractJoinFields(jList: List[(String, List[Ref])]): List[List[Ref]] = { jList.map{ case (alias, refs) => refs } }
  def joinStmt: Parser[PigOperator] = bag ~ "=" ~ joinKeyword ~ joinExprList ^^ {
    case out ~ _ ~ _ ~ jlist => new Join(Pipe(out), extractJoinRelation(jlist), extractJoinFields(jlist)) }

  /*
   * <A> = UNION <B>, <C>, <D>, ...
   */
  def unionStmt: Parser[PigOperator] = bag ~ "=" ~ unionKeyword ~ repsep(bag, ",") ^^ {
    case out ~ _ ~ _ ~ rlist => new Union(Pipe(out), rlist.map(r => Pipe(r)))
  }

  /*
   * REGISTER <JarFile>
   */
  def registerStmt: Parser[PigOperator] = registerKeyword ~ stringLiteral ^^{ case _ ~ uri => new Register(uri) }

  /*
   * <A> = STREAM <B> TROUGH <Operator> [(ParamList)] [AS (<Schema>) ]
   */
  def paramList: Parser[List[Ref]] = "(" ~ repsep(ref, ",") ~ ")" ^^ { case _ ~ rlist ~ _ => rlist}

  def streamStmt: Parser[PigOperator] = bag ~ "=" ~ streamKeyword ~ bag ~ throughKeyword ~ className ~ (paramList?) ~ (loadSchemaClause?) ^^{
    case out ~ _ ~_ ~ in ~ _ ~ opname ~ params ~ schema => new StreamOp(Pipe(out), Pipe(in), opname, params, schema)
  }

  /*
   * <A> = SAMPLE <B> <num>
   * <A> = SAMPLE <B> <Expr>
   */
  def sampleStmt: Parser[PigOperator] = bag ~ "=" ~ sampleKeyword ~ bag ~ arithmExpr ^^ {
    case out ~ _ ~ _ ~ in ~ expr => new Sample(Pipe(out), Pipe(in), expr)
  }

  /*
   * * [ASC | DESC]
   * field [ASC | DESC], field [ASC | DESC]
   */
  import OrderByDirection._

  def sortOrder: Parser[OrderByDirection] = ascKeyword ^^ { _ => OrderByDirection.AscendingOrder } |
    descKeyword ^^ { _ => OrderByDirection.DescendingOrder }

  def allOrderSpec: Parser[OrderBySpec] = "*" ~ (sortOrder?) ^^ { case s ~ o => o match {
     case Some(dir) => OrderBySpec(Value("*"), dir)
      case None => OrderBySpec(Value("*"), OrderByDirection.AscendingOrder)
    }
  }

  def fieldOrderSpec:Parser[OrderBySpec] =  (posField | namedField) ~ (sortOrder?) ^^ {
    case r ~ o => o match {
      case Some(dir) => OrderBySpec(r, dir)
      case None => OrderBySpec(r, OrderByDirection.AscendingOrder)
    }
  }

  def orderSpec: Parser[List[OrderBySpec]] = ( allOrderSpec ^^ {s => List(s)} | repsep(fieldOrderSpec, ",") )

  /*
   * <A> = ORDER <B> BY <OrderSpec>
   */
  def orderByStmt: Parser[PigOperator] = bag ~ "=" ~ orderKeyword ~ bag ~ byKeyword ~ orderSpec ^^ {
    case out ~ _ ~ _ ~ in ~ _ ~ spec => new OrderBy(Pipe(out), Pipe(in), spec)
  }

  /*
   * SPLIT <A> INTO <B> IF <Cond>, <C> IF <Cond> ...
   */
  def splitBranch: Parser[SplitBranch] = bag ~ ifKeyword ~ logicalExpr ^^ { case out ~ _ ~ expr => new SplitBranch(Pipe(out), expr)}

  def splitStmt: Parser[PigOperator] = splitKeyword ~ bag ~ intoKeyword ~ repsep(splitBranch, ",") ^^ {
    case _ ~ in ~ _ ~ splitList => new SplitInto(Pipe(in), splitList)
  }

  def materializeStmt: Parser[PigOperator] = materializeKeyword ~ bag ^^ { case _ ~ b => Materialize(Pipe(b))}
  
  /*
   * A statement can be one of the above delimited by a semicolon.
   */
  def stmt: Parser[PigOperator] = (loadStmt | dumpStmt | describeStmt | foreachStmt | filterStmt | groupingStmt |
    distinctStmt | joinStmt | storeStmt | limitStmt | unionStmt | registerStmt | streamStmt | sampleStmt | orderByStmt |
    splitStmt | materializeStmt) ~ ";" ^^ {
    case op ~ _  => op }

  /*
   * A plain Pig script is a list of statements.
   */
  def plainPigScript: Parser[List[PigOperator]] = rep(stmt)

  /* ---------------------------------------------------------------------------------------------------------------- */
  /*
   * Pig extensions for processing RDF data and supporting SPARQL BGPs.
   */
  lazy val tuplifyKeyword = "tuplify".ignoreCase
  lazy val onKeyword = "on".ignoreCase
  lazy val bgpFilterKeyword = "bgp_filter".ignoreCase

  def tuplifyStmt: Parser[PigOperator] = bag ~ "=" ~ tuplifyKeyword ~ bag ~ onKeyword ~ ref ^^ {
    case out ~ _ ~ _ ~ in ~ _ ~ r => new Tuplify(Pipe(out), Pipe(in), r) }

  def bgPattern: Parser[TriplePattern] = ref ~ ref ~ ref ^^ { case r1 ~ r2 ~ r3 => TriplePattern(r1, r2, r3)}

  def bgpFilterStmt: Parser[PigOperator] = bag ~ "=" ~ bgpFilterKeyword ~ bag ~
    byKeyword ~ "{" ~ repsep(bgPattern, ".") ~ "}" ^^ {
    case out ~ _ ~ _ ~ in ~ _ ~ _ ~ pattern ~ _ => new BGPFilter(Pipe(out), Pipe(in), pattern)
  }

  def sparqlStmt: Parser[PigOperator] = (loadStmt | dumpStmt | describeStmt | foreachStmt | filterStmt | groupingStmt |
    distinctStmt | joinStmt | storeStmt | limitStmt | unionStmt | registerStmt | streamStmt | sampleStmt | orderByStmt |
    splitStmt | tuplifyStmt | bgpFilterStmt) ~ ";" ^^ {
    case op ~ _  => op }


  def sparqlPigScript: Parser[List[PigOperator]] = rep(sparqlStmt)

  /* ---------------------------------------------------------------------------------------------------------------- */
  /*
   * Pig extensions for processing streaming data.
   */

  /*
   * Socket Definitions
   */
  def ipMember: Parser[String] = "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)".r
  def ipv4: Parser[String] = ipMember ~ "." ~ ipMember ~ "." ~ ipMember ~ "." ~ ipMember ^^{
    case i1 ~ _ ~ i2 ~ _ ~ i3 ~ _ ~i4 => i1 + "." + i2 + "." + i3 + "." + i4
  }
  def portNum: Parser[String] = "([0-9]{1,5})".r
  def port: Parser[String] = (portNum | "*")
  def bindAddress: Parser[String] = (ipv4 | "*" | ident)

  def inetAddress: Parser[SocketAddress] = "'" ~ (ipv4 | ident) ~ ":" ~ portNum ~ "'" ^^ { case _ ~ ip ~ _ ~ p ~ _ => SocketAddress("",ip,p)}
  def tcpSocket: Parser[SocketAddress] = "tcp://" ~ bindAddress ~ ":" ~ port ^^ { case trans ~ addr ~ _ ~ p => SocketAddress(trans, addr, p)}
  def ipcSocket: Parser[SocketAddress] = "ipc://" ~ (fileName | "*") ^^ { case trans ~  path => SocketAddress(trans,path,"")}
  def inprocSocket: Parser[SocketAddress] = "inproc://" ~ ident ^^ { case trans ~ name => SocketAddress(trans,name,"")}
  def pgmSocket: Parser[SocketAddress] = ("pgm://" | "epgm://") ~ (ipv4 | ident) ~ ";" ~ ipv4 ~ ":" ~ portNum ^^ {
    case trans ~ interface ~ _ ~ ip ~ _ ~ p => SocketAddress(trans, interface + ";" + ip, p)
  }
  def zmqAddress: Parser[SocketAddress] = "'" ~ (tcpSocket | ipcSocket | inprocSocket | pgmSocket) ~ "'" ^^ { case _ ~ addr ~ _ => addr}

  /*
   * <A> = SOCKET_READ '<address>' [ MODE ZMQ ] USING <StreamFunc> [ AS <schema> ]
   *
   * Maybe other modes later
   */
  def socketReadStmt: Parser[PigOperator] =
    bag ~ "=" ~ socketReadKeyword ~ inetAddress ~ (usingClause?) ~ (loadSchemaClause?) ^^ {
      case out ~ _ ~ _ ~ addr ~ u ~ schema => u match {
        case Some(p) => SocketRead(Pipe(out), addr, "", schema, p._1, if (p._2.isEmpty) null else p._2)
        case None =>  SocketRead(Pipe(out), addr, "", schema)
      }
    } |
      bag ~ "=" ~ socketReadKeyword ~ zmqAddress ~ modeKeyword ~ zmqKeyword ~ (usingClause?) ~ (loadSchemaClause?) ^^ {
        case out ~ _ ~ _ ~ addr ~ _ ~ mode ~ u ~ schema => u match {
          case Some(p) => SocketRead(Pipe(out), addr, mode, schema, p._1, if (p._2.isEmpty) null else p._2)
          case None => SocketRead(Pipe(out), addr, mode, schema)
        }
      }

  /*
   * SOCKET_WRITE <A> TO '<address>' [ MODE ZMQ ]
   */
  def socketWriteStmt: Parser[PigOperator] =
    socketWriteKeyword ~ bag ~ toKeyword ~ inetAddress ^^ {
      case _ ~ b ~ _ ~ addr => SocketWrite(Pipe(b), addr, "")
    } |
      socketWriteKeyword ~ bag ~ toKeyword ~ zmqAddress ~ modeKeyword ~ zmqKeyword ^^ {
        case _ ~ b ~ _ ~ addr ~ _ ~ mode => SocketWrite(Pipe(b), addr, mode)
      }

  def streamingStmt: Parser[PigOperator] = (loadStmt | dumpStmt | describeStmt | foreachStmt | filterStmt | groupingStmt |
    distinctStmt | joinStmt | storeStmt | limitStmt | unionStmt | registerStmt | streamStmt | sampleStmt | orderByStmt |
    splitStmt | socketReadStmt | socketWriteStmt | windowStmt ) ~ ";" ^^ {
    case op ~ _  => op }

  def streamingPigScript: Parser[List[PigOperator]] = rep(streamingStmt)

  /* ---------------------------------------------------------------------------------------------------------------- */

  def parseScript(s: CharSequence, feature: LanguageFeature = PlainPig): List[PigOperator] = {
    parseScript(new CharSequenceReader(s), feature)
  }

  def parseScript(input: CharSequenceReader, feature: LanguageFeature): List[PigOperator] = {
    parsePhrase(input, feature) match {
      case Success(t, _) => t
      case NoSuccess(msg, next) => 
        throw new IllegalArgumentException(s"Could not parse input string:\n${next.pos.longString} => $msg")
    }
  }

  def parsePhrase(input: CharSequenceReader, feature: LanguageFeature): ParseResult[List[PigOperator]] =
    feature match {
      case PlainPig => phrase(plainPigScript)(input)
      case SparqlPig => phrase(sparqlPigScript)(input)
      case StreamingPig => phrase(streamingPigScript)(input)
    }

}