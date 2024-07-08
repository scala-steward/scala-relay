package relay.generated

import _root_.scala.scalajs.js
import _root_.scala.scalajs.js.|
import _root_.scala.scalajs.js.annotation.JSImport

/*
query TestQuery($input: ClientTypeInput!) {
    clientType(input: $input) {
        required
        optional
        requiredListRequiredElements
        requiredListOptionalElements
        optionalListRequiredElements
        optionalListOptionalElements
    }
}
*/

trait TestQueryInput extends js.Object {
  val required: String[Required]
  val optional: String[Optional] | Null
  val requiredListRequiredElements: js.Array[String[RequiredListRequiredElements]]
  val optionalListRequiredElements: js.Array[String[OptionalListRequiredElements]] | Null
  val requiredListOptionalElements: js.Array[String[RequiredListOptionalElements] | Null]
  val optionalListOptionalElements: js.Array[String[OptionalListOptionalElements] | Null] | Null
}

@js.native
trait TestQuery extends js.Object {
  val clientType: TestQuery.ClientType | Null
}

object TestQuery extends _root_.relay.gql.QueryTaggedNode[TestQueryInput, TestQuery] {
  type Ctor[T] = T

  @js.native
  trait ClientType extends js.Object {
    val required: String[Required]
    val optional: String[Optional] | Null
    val requiredListRequiredElements: js.Array[String[RequiredListRequiredElements]]
    val requiredListOptionalElements: js.Array[String[RequiredListOptionalElements] | Null]
    val optionalListRequiredElements: js.Array[String[OptionalListRequiredElements]] | Null
    val optionalListOptionalElements: js.Array[String[OptionalListOptionalElements] | Null] | Null
  }

  def newInput(
    required: String[Required],
    optional: String[Optional] | Null = null,
    requiredListRequiredElements: js.Array[String[RequiredListRequiredElements]],
    optionalListRequiredElements: js.Array[String[OptionalListRequiredElements]] | Null = null,
    requiredListOptionalElements: js.Array[String[RequiredListOptionalElements] | Null],
    optionalListOptionalElements: js.Array[String[OptionalListOptionalElements] | Null] | Null = null
  ): TestQueryInput =
    js.Dynamic.literal(
      "required" -> required.asInstanceOf[js.Any],
      "optional" -> optional.asInstanceOf[js.Any],
      "requiredListRequiredElements" -> requiredListRequiredElements.asInstanceOf[js.Any],
      "optionalListRequiredElements" -> optionalListRequiredElements.asInstanceOf[js.Any],
      "requiredListOptionalElements" -> requiredListOptionalElements.asInstanceOf[js.Any],
      "optionalListOptionalElements" -> optionalListOptionalElements.asInstanceOf[js.Any]
    ).asInstanceOf[TestQueryInput]

  type Query = _root_.relay.gql.ConcreteRequest

  @js.native
  @JSImport("__generated__/TestQuery.graphql", JSImport.Default)
  private object node extends js.Object

  lazy val query: Query = node.asInstanceOf[Query]

  lazy val sourceHash: String = node.asInstanceOf[js.Dynamic].hash.asInstanceOf[String]
}
