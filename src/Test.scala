object Foo {

  val gql = graphql"""
  fragment DictionaryComponent_word on Word {
    id
    definition {
      ...DictionaryComponent_definition
    }
  }
  """
}