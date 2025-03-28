import kotlin.test.*

interface ContributedInterface
interface AnotherInterface

@ContributesBinding(
  AppScope::class,
  binding<ContributedInterface>()
)
@ContributesBinding(
  AppScope::class,
  binding<AnotherInterface>()
)
@Inject
class Impl : ContributedInterface, AnotherInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
  val anotherInterface: AnotherInterface
}

fun box(): String {
  val contributedInterface = createGraph<ExampleGraph>().contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.simpleName, "Impl")
  val anotherInterface = createGraph<ExampleGraph>().anotherInterface
  assertNotNull(anotherInterface)
  assertEquals(anotherInterface::class.simpleName, "Impl")
  return "OK"
}
