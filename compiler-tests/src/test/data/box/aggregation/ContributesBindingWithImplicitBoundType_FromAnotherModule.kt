// MODULE: lib

interface ContributedInterface

@ContributesBinding(AppScope::class)
@Inject
class Impl : ContributedInterface

// MODULE: main(lib)

import kotlin.test.*

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val contributedInterface = createGraph<ExampleGraph>().contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.simpleName, "Impl")
  return "OK"
}
