// MODULE: lib

interface ContributedInterface<T>

@ContributesBinding(
  AppScope::class,
  binding<@Named("named") ContributedInterface<String>>()
)
@Inject
class Impl : ContributedInterface<String>

// MODULE: main(lib)

import kotlin.test.*

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("named") val contributedInterface: ContributedInterface<String>
}

fun box(): String {
  val contributedInterface = createGraph<ExampleGraph>().contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.simpleName, "Impl")
  return "OK"
}
