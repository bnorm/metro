import kotlin.test.*

interface ContributedInterface
interface AnotherInterface

@ContributesBinding(
  AppScope::class,
  binding<ContributedInterface>()
)
@Inject
class Impl : ContributedInterface, AnotherInterface

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
